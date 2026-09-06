package com.example.autocallmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class RecurrenceMode {
    ONCE, DAILY, WEEKLY
}

data class CallTask(
    val id: Long,
    var number: String,
    var contactName: String,
    var triggerAt: Long,
    var maxAttempts: Int,
    var retryIntervalSeconds: Int,
    var attemptCount: Int = 0,
    var status: String = "Stopped",
    var nextTrigger: Long = -1L,
    var liveStatus: String = "Waiting",
    var lastResult: String = "NOT_STARTED",
    var callEnabled: Boolean = true,
    var voiceEnabled: Boolean = false,
    var messageEnabled: Boolean = false,
    var notificationEnabled: Boolean = true,
    var notifyBefore: Boolean = true,
    var notifyAt: Boolean = true,
    var notifyAfter: Boolean = true,
    var messageText: String = "",
    var promptId: String = PromptStore.DEFAULT_PROMPT_ID,
    var promptVersion: Int = 1,
    var voiceLanguage: String = "Hinglish",
    var voiceSpeed: Float = 1.0f,
    var voicePitch: Float = 1.0f,
    var groupName: String = "",
    var recurrence: RecurrenceMode = RecurrenceMode.ONCE,
    var repeatDays: Set<Int> = emptySet(),
    var timeSlots: List<Int> = emptyList(),
    var activeSlotIndex: Int = 0,
    var simSelectionEnabled: Boolean = false,
    var phoneAccountId: String = "",
    var phoneAccountComponent: String = "",
    var phoneAccountLabel: String = "",
    var timezoneId: String = java.util.TimeZone.getDefault().id,
    var executionMode: String = "NORMAL_SIM",
    // Marks a task created by Diagnostics -> "Run Background Test". Kept out of
    // the normal Calls list and excluded from ScheduleCalculator recurrence so a
    // test never lingers as a real recurring schedule. See ExecutionTraceStore.
    var isDiagnosticTest: Boolean = false
)

data class HistoryEvent(
    val time: Long,
    val taskId: Long,
    val number: String,
    val contactName: String,
    val groupName: String,
    val attempt: Int,
    val maxAttempts: Int,
    val result: String,
    val liveStatus: String,
    val eventType: String,
    // Which entry point produced this attempt: MANUAL or SCHEDULED -- see
    // ExecutionTraceStore.TraceEvent.callSource. Defaults to SCHEDULED for
    // history rows written before a manual call-now path existed.
    val source: String = "SCHEDULED"
)

object TaskStore {
    private const val PREFS = "auto_call_manager_102"
    private const val TASKS = "tasks"
    private const val HISTORY = "history"
    private const val MAX_HISTORY = 1500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<CallTask> {
        val arr = JSONArray(prefs(context).getString(TASKS, "[]") ?: "[]")
        val out = mutableListOf<CallTask>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val oldMessage =
                o.optString("messageText",
                    o.optString("voiceMessage", ""))

            val recurrence =
                runCatching {
                    RecurrenceMode.valueOf(
                        o.optString("recurrence", "ONCE")
                    )
                }.getOrDefault(RecurrenceMode.ONCE)

            val repeatDays = o.optString("repeatDays", "")
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet()

            val slots = o.optString("timeSlots", "")
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 0..1439 }
                .distinct()
                .sorted()

            out += CallTask(
                id = o.optLong("id"),
                number = o.optString("number"),
                contactName = o.optString("contactName"),
                triggerAt = o.optLong("triggerAt"),
                maxAttempts =
                    o.optInt("maxAttempts", 1).coerceIn(1, 50),
                retryIntervalSeconds =
                    o.optInt(
                        "retryIntervalSeconds",
                        o.optInt("retryIntervalMinutes", 1) * 60
                    ).coerceAtLeast(20),
                attemptCount =
                    o.optInt("attemptCount", 0).coerceIn(0, 50),
                status = o.optString("status", "Stopped"),
                nextTrigger = o.optLong("nextTrigger", -1L),
                liveStatus = o.optString("liveStatus", "Waiting"),
                lastResult = o.optString("lastResult", "NOT_STARTED"),
                callEnabled = o.optBoolean("callEnabled", true),
                voiceEnabled = o.optBoolean("voiceEnabled", false),
                messageEnabled = o.optBoolean("messageEnabled", false),
                notificationEnabled =
                    o.optBoolean("notificationEnabled", true),
                notifyBefore = o.optBoolean("notifyBefore", true),
                notifyAt = o.optBoolean("notifyAt", true),
                notifyAfter = o.optBoolean("notifyAfter", true),
                messageText = oldMessage,
                promptId = o.optString("promptId", PromptStore.DEFAULT_PROMPT_ID),
                promptVersion = o.optInt("promptVersion", 1).coerceAtLeast(1),
                voiceLanguage = o.optString("voiceLanguage", "Hinglish").let { if (it in setOf("Hindi", "Hinglish", "English")) it else "Hinglish" },
                voiceSpeed = o.optDouble("voiceSpeed", 1.0).toFloat().coerceIn(0.5f, 2.0f),
                voicePitch = o.optDouble("voicePitch", 1.0).toFloat().coerceIn(0.5f, 1.5f),
                groupName = o.optString("groupName", ""),
                recurrence = recurrence,
                repeatDays = repeatDays,
                timeSlots = slots,
                activeSlotIndex =
                    o.optInt("activeSlotIndex", 0),
                simSelectionEnabled = o.optBoolean("simSelectionEnabled", false),
                phoneAccountId = o.optString("phoneAccountId", ""),
                phoneAccountComponent = o.optString("phoneAccountComponent", ""),
                phoneAccountLabel = o.optString("phoneAccountLabel", ""),
                timezoneId = o.optString("timezoneId", java.util.TimeZone.getDefault().id).ifBlank { java.util.TimeZone.getDefault().id },
                executionMode = o.optString("executionMode", "NORMAL_SIM").let { if (it == "AI_VOICE") "AI_VOICE" else "NORMAL_SIM" },
                isDiagnosticTest = o.optBoolean("isDiagnosticTest", false)
            )
        }

        return out
    }

    fun save(context: Context, tasks: List<CallTask>) {
        val arr = JSONArray()

        tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("number", t.number)
                put("contactName", t.contactName)
                put("triggerAt", t.triggerAt)
                put("maxAttempts", t.maxAttempts)
                put("retryIntervalSeconds", t.retryIntervalSeconds)
                put("attemptCount", t.attemptCount)
                put("status", t.status)
                put("nextTrigger", t.nextTrigger)
                put("liveStatus", t.liveStatus)
                put("lastResult", t.lastResult)
                put("callEnabled", t.callEnabled)
                put("voiceEnabled", t.voiceEnabled)
                put("messageEnabled", t.messageEnabled)
                put("notificationEnabled", t.notificationEnabled)
                put("notifyBefore", t.notifyBefore)
                put("notifyAt", t.notifyAt)
                put("notifyAfter", t.notifyAfter)
                put("messageText", t.messageText)
                put("promptId", t.promptId)
                put("promptVersion", t.promptVersion)
                put("voiceLanguage", t.voiceLanguage)
                put("voiceSpeed", t.voiceSpeed)
                put("voicePitch", t.voicePitch)
                put("groupName", t.groupName)
                put("recurrence", t.recurrence.name)
                put(
                    "repeatDays",
                    t.repeatDays.sorted().joinToString(",")
                )
                put(
                    "timeSlots",
                    t.timeSlots.sorted().joinToString(",")
                )
                put("activeSlotIndex", t.activeSlotIndex)
                put("simSelectionEnabled", t.simSelectionEnabled)
                put("phoneAccountId", t.phoneAccountId)
                put("phoneAccountComponent", t.phoneAccountComponent)
                put("phoneAccountLabel", t.phoneAccountLabel)
                put("timezoneId", t.timezoneId)
                put("executionMode", t.executionMode)
                put("isDiagnosticTest", t.isDiagnosticTest)
            })
        }

        prefs(context).edit()
            .putString(TASKS, arr.toString())
            .apply()
    }

    fun addHistory(
        context: Context,
        task: CallTask,
        eventType: String,
        result: String,
        source: String = "SCHEDULED"
    ) {
        val old =
            JSONArray(prefs(context).getString(HISTORY, "[]") ?: "[]")

        val merged = JSONArray()

        merged.put(JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("taskId", task.id)
            put("number", task.number)
            put("contactName", task.contactName)
            put("groupName", task.groupName)
            put("attempt", task.attemptCount)
            put("maxAttempts", task.maxAttempts)
            put("result", result)
            put("liveStatus", task.liveStatus)
            put("eventType", eventType)
            put("source", source)
        })

        val start = maxOf(
            0,
            old.length() - (MAX_HISTORY - 1)
        )

        for (i in start until old.length()) {
            old.optJSONObject(i)?.let(merged::put)
        }

        prefs(context).edit()
            .putString(HISTORY, merged.toString())
            .apply()
    }

    fun history(context: Context): JSONArray =
        JSONArray(
            prefs(context).getString(HISTORY, "[]") ?: "[]"
        )

    fun clearHistory(context: Context) {
        prefs(context).edit()
            .putString(HISTORY, "[]")
            .apply()
    }

    fun historyForTask(context: Context, taskId: Long): JSONArray {
        val all = history(context)
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optLong("taskId", -1L) == taskId) out.put(item)
        }
        return out
    }

    fun replaceHistory(context: Context, historyArray: JSONArray) {
        prefs(context).edit()
            .putString(HISTORY, historyArray.toString())
            .commit()
    }
}
