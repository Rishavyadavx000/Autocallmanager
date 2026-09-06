package com.example.autocallmanager

import android.content.Context

/**
 * Small persistent tracker for the currently monitored scheduled cellular call.
 * Kept separate from the schedule store because this state is transient and
 * must survive the Activity being stopped while the phone call continues.
 */
object CallExecutionStore {
    data class ActiveCall(
        val taskId: Long,
        val attempt: Int,
        val startedAt: Long,
        val sawOffhook: Boolean,
        val offhookAt: Long,
        // Joins this active call back to the ExecutionTraceStore entries logged
        // by CallReceiver for the same attempt, so the later phone-state
        // broadcast (a separate BroadcastReceiver, delivered at an arbitrary
        // later time) appends to the same execution's trace instead of an
        // orphaned one. Blank only for state persisted before this field existed.
        val executionId: String = "",
        // Which entry point started this attempt: MANUAL or SCHEDULED -- see
        // CallExecutionManager.executeCallAttempt / ExecutionTraceStore. Read
        // back later by onTelephonyState/finishFromIdle/handleWatchdogTimeout,
        // which only have this persisted record to work from (the phone-state
        // broadcast that eventually finishes the attempt carries no source of
        // its own). Defaults to SCHEDULED for state persisted before this
        // field existed, i.e. before a manual call-now path existed at all.
        val callSource: String = "SCHEDULED"
    )

    private const val PREFS = "auto_call_manager_execution"
    private const val TASK_ID = "task_id"
    private const val ATTEMPT = "attempt"
    private const val STARTED_AT = "started_at"
    private const val SAW_OFFHOOK = "saw_offhook"
    private const val OFFHOOK_AT = "offhook_at"
    private const val EXECUTION_ID = "execution_id"
    private const val CALL_SOURCE = "call_source"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context, taskId: Long, attempt: Int, executionId: String = "", callSource: String = "SCHEDULED") {
        prefs(context).edit()
            .putLong(TASK_ID, taskId)
            .putInt(ATTEMPT, attempt)
            .putLong(STARTED_AT, System.currentTimeMillis())
            .putBoolean(SAW_OFFHOOK, false)
            .putLong(OFFHOOK_AT, 0L)
            .putString(EXECUTION_ID, executionId)
            .putString(CALL_SOURCE, callSource)
            .apply()
    }

    fun get(context: Context): ActiveCall? {
        val taskId = prefs(context).getLong(TASK_ID, -1L)
        if (taskId <= 0L) return null
        return ActiveCall(
            taskId = taskId,
            attempt = prefs(context).getInt(ATTEMPT, 0),
            startedAt = prefs(context).getLong(STARTED_AT, 0L),
            sawOffhook = prefs(context).getBoolean(SAW_OFFHOOK, false),
            offhookAt = prefs(context).getLong(OFFHOOK_AT, 0L),
            executionId = prefs(context).getString(EXECUTION_ID, "") ?: "",
            callSource = prefs(context).getString(CALL_SOURCE, "SCHEDULED") ?: "SCHEDULED"
        )
    }

    fun markOffhook(context: Context) {
        val current = get(context) ?: return
        val editor = prefs(context).edit().putBoolean(SAW_OFFHOOK, true)
        // Record only the FIRST offhook timestamp for this attempt, so a
        // string of repeated OFFHOOK broadcasts (state bouncing) doesn't
        // keep resetting the connected-duration clock.
        if (current.offhookAt <= 0L) {
            editor.putLong(OFFHOOK_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
