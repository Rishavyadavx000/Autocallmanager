package com.example.autocallmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact-alarm execution entry point. It never launches an Activity, so the
 * scheduled call can be requested while the device is screen-off or locked.
 *
 * Every real firing of ACTION_CALL_ATTEMPT is logged to ExecutionTraceStore
 * under one executionId shared by every stage of that attempt (including the
 * later phone-state broadcast handled in CallExecutionManager), so a failure
 * can be pinpointed to an exact stage after the fact -- see Diagnostics ->
 * Execution Trace. This is the same code path used by real schedules AND by
 * Diagnostics -> Run Background Test; the test is not a simulation.
 *
 * This receiver only decides WHETHER and WHEN an attempt should run: alarm
 * staleness, pause state, and task.status -- none of which mean anything for
 * a direct manual trigger. The actual call/AI-voice execution is shared with
 * the manual "call now" bridge method in MainActivity (which has no alarm to
 * unpack and calls straight into CallExecutionManager) via
 * CallExecutionManager.executeCallAttempt(), tagged callSource=SCHEDULED
 * here since every attempt this receiver runs was triggered by an alarm.
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(CallScheduler.EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) {
            ExecutionTraceStore.log(
                context, taskId, "", 0,
                ExecutionTraceStore.Stage.RECEIVER_STARTED, ExecutionTraceStore.FAIL,
                "invalid/missing EXTRA_TASK_ID (received $taskId) action=${intent.action}"
            )
            return
        }

        val isCallAttempt = intent.action == CallScheduler.ACTION_CALL_ATTEMPT
        val attempt = if (isCallAttempt) {
            (TaskStore.load(context).firstOrNull { it.id == taskId }?.attemptCount ?: 0) + 1
        } else 0
        val executionId = if (isCallAttempt) ExecutionTraceStore.newExecutionId(taskId, attempt) else ""

        if (isCallAttempt) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.ALARM_DELIVERED, ExecutionTraceStore.PASS,
                "action=${intent.action}"
            )
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.RECEIVER_STARTED, ExecutionTraceStore.PASS
            )
        }

        val pendingResult = goAsync()
        val wakeLock = acquireExecutionWakeLock(context)
        if (isCallAttempt) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.WAKELOCK_ACQUIRED,
                if (wakeLock != null) ExecutionTraceStore.PASS else ExecutionTraceStore.FAIL,
                if (wakeLock == null) "PowerManager.newWakeLock threw" else ""
            )
        }

        // Releases the wakelock and completes the async broadcast exactly
        // once, however this attempt ends: synchronously below for every
        // action except AI_VOICE, or later from a background thread (see
        // CallExecutionManager.executeCallAttempt) once its network request
        // finishes. Safe to call from any thread and safe to call more than
        // once -- only the first call does anything.
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                runCatching { pendingResult.finish() }
            }
        }

        try {
            when (intent.action) {
                CallScheduler.ACTION_PRE_NOTIFICATION -> {
                    handlePreNotification(context, taskId)
                    finishOnce()
                }
                CallScheduler.ACTION_CALL_ATTEMPT -> {
                    val finishedSynchronously =
                        handleCallAttempt(context, taskId, executionId, attempt, ::finishOnce)
                    if (finishedSynchronously) finishOnce()
                }
                CallScheduler.ACTION_CALL_WATCHDOG -> {
                    CallExecutionManager.handleWatchdogTimeout(context, taskId)
                    finishOnce()
                }
                else -> finishOnce()
            }
        } catch (e: Exception) {
            // Never crash the receiver or leak the wakelock/PendingResult over
            // an unexpected exception -- spec requirement: "Do not crash the
            // receiver. Do not let one failed task break future scheduled
            // tasks." This is a safety net; every known failure path above
            // already logs and returns normally instead of throwing.
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                "uncaught ${e.javaClass.simpleName} in CallReceiver.onReceive: ${e.message}"
            )
            finishOnce()
        }
    }

    private fun handlePreNotification(context: Context, taskId: Long) {
        val task = TaskStore.load(context).firstOrNull { it.id == taskId } ?: return
        if (task.status == "Scheduled" && task.notificationEnabled && task.notifyBefore) {
            CallManager.showPreNotification(context, task)
            TaskStore.addHistory(context, task, "NOTIFICATION", "PRE_EVENT_NOTIFICATION")
        }
    }

    /**
     * Alarm-specific guards only -- stale alarm, pause state, task.status --
     * then hands off to CallExecutionManager.executeCallAttempt() for
     * everything the manual call-now path also needs. See that function's
     * KDoc for why these particular guards live here instead of there: none
     * of them mean anything outside an alarm firing.
     *
     * Returns true if the caller should finish the broadcast immediately, or
     * false if async work was handed to a background thread that will call
     * [onAsyncComplete] itself when done (AI_VOICE only).
     */
    private fun handleCallAttempt(
        context: Context,
        taskId: Long,
        executionId: String,
        attempt: Int,
        onAsyncComplete: () -> Unit
    ): Boolean {
        val tasks = TaskStore.load(context)
        val task = tasks.firstOrNull { it.id == taskId } ?: run {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                "no task found for id=$taskId"
            )
            return true
        }

        if (SettingsStore.pausedAll(context) || task.status != "Scheduled") {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                if (SettingsStore.pausedAll(context)) "all schedules paused" else "task.status=${task.status}, expected Scheduled"
            )
            return true
        }

        val now = System.currentTimeMillis()
        if (task.nextTrigger > 0L && task.nextTrigger > now + 60_000L) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                "stale alarm ignored - nextTrigger is more than 60s in the future (task was likely rescheduled)"
            )
            return true
        }

        task.liveStatus = "ALARM_DELIVERED"
        task.lastResult = "ALARM_DELIVERED"
        TaskStore.addHistory(context, task, "SYSTEM", "ALARM_DELIVERED")

        if (task.notifyAt && task.notificationEnabled) {
            CallManager.showNotification(
                context,
                task,
                "Scheduled event started • Attempt ${task.attemptCount + 1}/${task.maxAttempts}"
            )
            TaskStore.addHistory(context, task, "NOTIFICATION", "AT_EVENT_NOTIFICATION")
        }

        task.attemptCount = (task.attemptCount + 1).coerceAtMost(task.maxAttempts)
        task.status = "Running"
        task.nextTrigger = -1L
        task.liveStatus = "CALL_STARTING"
        task.lastResult = "CALL_STARTING"
        TaskStore.save(context, tasks)

        return CallExecutionManager.executeCallAttempt(
            context, task, executionId, attempt,
            callSource = "SCHEDULED",
            onAsyncComplete = onAsyncComplete
        )
    }

    /**
     * Returns null (never throws) so the caller can log an honest
     * WAKELOCK_ACQUIRED FAIL instead of crashing the receiver.
     *
     * The 25s ceiling is a backstop, not a duration this is normally held
     * for: finishOnce() (see onReceive) releases it the moment real work
     * completes, which for NORMAL_SIM calling is a small fraction of this.
     * It must still be sized above the slowest thing this receiver can
     * legitimately wait on, which today is the AI Voice backend request --
     * connectTimeout(7s) + readTimeout(12s) = 19s worst case, see
     * AiVoiceCallClient -- plus margin. A previous 15s value was shorter
     * than that 19s ceiling, so a slow-but-real backend response could
     * outlive the wakelock. If AiVoiceCallClient's timeouts ever change,
     * this value should be reviewed against them.
     */
    private fun acquireExecutionWakeLock(context: Context): PowerManager.WakeLock? = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:AutoCallExecution"
        ).apply {
            setReferenceCounted(false)
            acquire(25_000L)
        }
    }.getOrNull()
}
