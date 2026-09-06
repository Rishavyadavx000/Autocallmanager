package com.example.autocallmanager

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Ground-truth log of the REAL scheduled-call pipeline, written from the
 * actual production code path (CallScheduler / CallReceiver /
 * CallExecutionManager / CallManager) -- never from a simulated or
 * best-case run. This is deliberately separate from TaskStore's
 * user-facing History: History answers "what happened to my schedule",
 * this answers "which exact stage did the background pipeline reach, and
 * what did the device look like at that moment".
 *
 * Every log() call is wrapped so it can never throw into the caller: a
 * logging bug must not be able to break the call pipeline it is observing.
 */
object ExecutionTraceStore {

    const val PASS = "PASS"
    const val FAIL = "FAIL"
    const val INFO = "INFO"

    // Stage names, intentionally matching the pipeline as specified:
    // SCHEDULE_CREATED -> ALARM_REGISTERED -> ALARM_DELIVERED ->
    // RECEIVER_STARTED -> WAKELOCK_ACQUIRED -> CALL_EXECUTION_BEGIN ->
    // PHONE_ACCOUNT_RESOLVED -> PLACE_CALL -> CALL_STATE_CALLBACK -> FINAL_OUTCOME
    //
    // AI_VOICE_REQUEST is a branch taken instead of PHONE_ACCOUNT_RESOLVED ->
    // PLACE_CALL -> CALL_STATE_CALLBACK when task.executionMode == "AI_VOICE":
    // there is no local phone account or telephony call state to track, only
    // the one HTTP request to the AI Voice backend (see AiVoiceCallClient).
    // On success this is also the last stage logged for that attempt -- the
    // real call outcome happens server-side and this device has no further
    // visibility into it, so no FINAL_OUTCOME is fabricated for that case.
    object Stage {
        const val SCHEDULE_CREATED = "SCHEDULE_CREATED"
        const val ALARM_REGISTERED = "ALARM_REGISTERED"
        const val ALARM_DELIVERED = "ALARM_DELIVERED"
        const val RECEIVER_STARTED = "RECEIVER_STARTED"
        const val WAKELOCK_ACQUIRED = "WAKELOCK_ACQUIRED"
        const val CALL_EXECUTION_BEGIN = "CALL_EXECUTION_BEGIN"
        const val PHONE_ACCOUNT_RESOLVED = "PHONE_ACCOUNT_RESOLVED"
        const val PLACE_CALL = "PLACE_CALL"
        const val AI_VOICE_REQUEST = "AI_VOICE_REQUEST"
        const val CALL_STATE_CALLBACK = "CALL_STATE_CALLBACK"
        const val FINAL_OUTCOME = "FINAL_OUTCOME"
    }

    data class TraceEvent(
        val ts: Long,
        val taskId: Long,
        val executionId: String,
        val attempt: Int,
        val stage: String,
        val result: String,
        val detail: String,
        val screen: String,
        val lock: String,
        val battery: String,
        val exactAlarm: String,
        val phoneAccount: String,
        // Which entry point produced this attempt: MANUAL (the direct
        // "call now" bridge method) or SCHEDULED (alarm-driven, including
        // retries, next-slot advances, and reboot recovery -- anything that
        // goes through CallScheduler/AlarmManager). Defaults to SCHEDULED
        // both for rows logged before a manual path existed and for stages
        // that are always alarm-based regardless (e.g. SCHEDULE_CREATED /
        // ALARM_REGISTERED, which a manual call never produces at all).
        val callSource: String = "SCHEDULED"
    )

    private const val PREFS = "auto_call_manager_trace"
    private const val KEY = "events"
    private const val MAX_EVENTS = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Deterministically identifies one real firing of the pipeline for one
     * task attempt, so stages logged from different broadcasts (CallReceiver
     * for the alarm, CallStateReceiver for the later phone-state change) can
     * be reassembled into a single execution. Not a secret, just a join key.
     */
    fun newExecutionId(taskId: Long, attempt: Int): String =
        "T${taskId}-A${attempt}-${System.currentTimeMillis()}"

    fun log(
        context: Context,
        taskId: Long,
        executionId: String,
        attempt: Int,
        stage: String,
        result: String,
        detail: String = "",
        phoneAccount: String = "",
        callSource: String = "SCHEDULED"
    ) {
        runCatching {
            val event = TraceEvent(
                ts = System.currentTimeMillis(),
                taskId = taskId,
                executionId = executionId,
                attempt = attempt,
                stage = stage,
                result = result,
                detail = detail.take(200),
                screen = screenState(context),
                lock = lockState(context),
                battery = batteryState(context),
                exactAlarm = exactAlarmState(context),
                phoneAccount = phoneAccount,
                callSource = callSource
            )
            append(context, event)
        }
    }

    private fun append(context: Context, event: TraceEvent) {
        val existing = readRaw(context)
        existing.put(toJson(event))
        val start = maxOf(0, existing.length() - MAX_EVENTS)
        val trimmed = JSONArray()
        for (i in start until existing.length()) trimmed.put(existing.optJSONObject(i))
        prefs(context).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun all(context: Context): List<TraceEvent> =
        runCatching {
            val arr = readRaw(context)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())

    fun forTask(context: Context, taskId: Long): List<TraceEvent> =
        all(context).filter { it.taskId == taskId }

    fun forExecution(context: Context, executionId: String): List<TraceEvent> =
        all(context).filter { it.executionId == executionId }

    /** Most recent execution's events -- what the Diagnostics screen shows by default. */
    fun mostRecent(context: Context): List<TraceEvent> {
        val events = all(context)
        if (events.isEmpty()) return events
        val anchor = events.lastOrNull { it.executionId.isNotBlank() } ?: events.last()
        return if (anchor.executionId.isNotBlank()) {
            events.filter { it.executionId == anchor.executionId || (it.executionId.isBlank() && it.taskId == anchor.taskId) }
        } else {
            // Nothing has actually fired yet (no non-blank executionId exists at
            // all) -- fall back to just this task's registration-time rows.
            events.filter { it.taskId == anchor.taskId }
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun readRaw(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private fun toJson(e: TraceEvent) = JSONObject().apply {
        put("ts", e.ts); put("taskId", e.taskId); put("executionId", e.executionId)
        put("attempt", e.attempt); put("stage", e.stage); put("result", e.result)
        put("detail", e.detail); put("screen", e.screen); put("lock", e.lock)
        put("battery", e.battery); put("exactAlarm", e.exactAlarm); put("phoneAccount", e.phoneAccount)
        put("callSource", e.callSource)
    }

    private fun fromJson(o: JSONObject) = TraceEvent(
        ts = o.optLong("ts"), taskId = o.optLong("taskId"), executionId = o.optString("executionId"),
        attempt = o.optInt("attempt"), stage = o.optString("stage"), result = o.optString("result"),
        detail = o.optString("detail"), screen = o.optString("screen"), lock = o.optString("lock"),
        battery = o.optString("battery"), exactAlarm = o.optString("exactAlarm"),
        phoneAccount = o.optString("phoneAccount"),
        callSource = o.optString("callSource", "SCHEDULED").ifBlank { "SCHEDULED" }
    )

    // --- Live device-state snapshot, captured fresh at every log() call ------

    private fun screenState(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == true) "ON" else "OFF"
    }.getOrDefault("UNKNOWN")

    private fun lockState(context: Context): String = runCatching {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) "LOCKED" else "UNLOCKED"
    }.getOrDefault("UNKNOWN")

    private fun batteryState(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) "UNRESTRICTED" else "RESTRICTED"
    }.getOrDefault("UNKNOWN")

    private fun exactAlarmState(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching "GRANTED"
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am?.canScheduleExactAlarms() == true) "GRANTED" else "DENIED"
    }.getOrDefault("UNKNOWN")

    // --- Pure formatting, independent of Android framework so it is plain-JVM testable ---

    /**
     * Formats a millisecond timestamp as HH:mm:ss.SSS in the given time zone.
     * Pure function (no Context, no I/O) -- see ExecutionTraceFormatTest.
     */
    fun formatTimestamp(ts: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        sdf.timeZone = zone
        return sdf.format(java.util.Date(ts))
    }

    /** Renders one event as a single fixed-width line, matching the requested trace format. */
    fun formatLine(e: TraceEvent, zone: TimeZone = TimeZone.getDefault()): String {
        val stagePadded = e.stage.padEnd(22)
        val resultPadded = e.result.padEnd(6)
        val extras = buildString {
            if (e.phoneAccount.isNotBlank()) append(" acct=${e.phoneAccount}")
            if (e.detail.isNotBlank()) append(" ${e.detail}")
        }
        return "${formatTimestamp(e.ts, zone)}  $stagePadded $resultPadded" +
            " screen=${e.screen} lock=${e.lock} battery=${e.battery} exactAlarm=${e.exactAlarm}" +
            " source=${e.callSource} attempt=${e.attempt}$extras"
    }

    /** Full plain-text export for one execution (or everything, if executionId is blank), no secrets included. */
    fun exportText(context: Context, taskId: Long? = null): String {
        val events = when {
            taskId != null -> forTask(context, taskId)
            else -> all(context)
        }
        if (events.isEmpty()) return "No execution trace recorded yet."
        val zone = TimeZone.getDefault()
        return buildString {
            appendLine("AutoCallManager Execution Trace")
            appendLine("Device time zone: ${zone.id}")
            appendLine("Entries: ${events.size}")
            appendLine("-".repeat(60))
            events.forEach { appendLine(formatLine(it, zone)) }
        }
    }
}
