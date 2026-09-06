package com.example.autocallmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object CallScheduler {
    const val ACTION_CALL_ATTEMPT = "com.example.autocallmanager.ACTION_CALL_ATTEMPT"
    const val ACTION_PRE_NOTIFICATION = "com.example.autocallmanager.ACTION_PRE_NOTIFICATION"
    const val ACTION_CALL_WATCHDOG = "com.example.autocallmanager.ACTION_CALL_WATCHDOG"
    const val EXTRA_TASK_ID = "com.example.autocallmanager.EXTRA_TASK_ID"
    const val EXTRA_ACTION_KIND = "com.example.autocallmanager.EXTRA_ACTION_KIND"

    private const val PRE_NOTIFICATION_OFFSET = 100_000
    private const val CALL_WATCHDOG_OFFSET = 200_000

    fun canScheduleExactly(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * [attempt] is only used for ExecutionTraceStore labeling (which attempt
     * this registration is FOR); it does not affect scheduling behavior.
     * The executionId for these two rows is deliberately blank: an
     * "execution" doesn't exist yet at registration time, only at
     * ALARM_DELIVERED (see CallReceiver). Trace rows are still joinable by
     * taskId in the meantime.
     */
    fun schedule(context: Context, taskId: Long, triggerAt: Long, attempt: Int = 0): Boolean {
        ExecutionTraceStore.log(
            context, taskId, "", attempt,
            ExecutionTraceStore.Stage.SCHEDULE_CREATED, ExecutionTraceStore.PASS,
            "triggerAt=$triggerAt"
        )
        if (triggerAt <= System.currentTimeMillis()) {
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.FAIL,
                "triggerAt already in the past"
            )
            return false
        }
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.FAIL,
                "AlarmManager service unavailable"
            )
            return false
        }
        if (!canScheduleExactly(context)) {
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.FAIL,
                "SCHEDULE_EXACT_ALARM not granted"
            )
            return false
        }
        return try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(context, taskId, ACTION_CALL_ATTEMPT, 0)
            )
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.PASS,
                "triggerAt=$triggerAt"
            )
            true
        } catch (e: SecurityException) {
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.FAIL,
                "SecurityException: ${e.message}"
            )
            false
        } catch (e: Exception) {
            ExecutionTraceStore.log(
                context, taskId, "", attempt,
                ExecutionTraceStore.Stage.ALARM_REGISTERED, ExecutionTraceStore.FAIL,
                "${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    fun schedulePreNotification(context: Context, task: CallTask): Boolean {
        if (!task.notificationEnabled || !task.notifyBefore) return true
        val trigger = task.nextTrigger.takeIf { it > 0L } ?: task.triggerAt
        val at = trigger - 5 * 60_000L
        if (at <= System.currentTimeMillis() || trigger <= System.currentTimeMillis()) return true
        if (!canScheduleExactly(context)) return false
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at,
                pendingIntent(context, task.id, ACTION_PRE_NOTIFICATION, PRE_NOTIFICATION_OFFSET)
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }


    fun scheduleCallWatchdog(context: Context, taskId: Long, at: Long): Boolean {
        if (at <= System.currentTimeMillis()) return false
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        if (!canScheduleExactly(context)) return false
        return try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at,
                pendingIntent(context, taskId, ACTION_CALL_WATCHDOG, CALL_WATCHDOG_OFFSET)
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun cancelWatchdog(context: Context, taskId: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context, taskId, ACTION_CALL_WATCHDOG, CALL_WATCHDOG_OFFSET))
    }

    fun cancel(context: Context, taskId: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context, taskId, ACTION_CALL_ATTEMPT, 0))
        manager.cancel(pendingIntent(context, taskId, ACTION_PRE_NOTIFICATION, PRE_NOTIFICATION_OFFSET))
        manager.cancel(pendingIntent(context, taskId, ACTION_CALL_WATCHDOG, CALL_WATCHDOG_OFFSET))
    }

    private fun pendingIntent(
        context: Context,
        taskId: Long,
        action: String,
        requestOffset: Int
    ): PendingIntent {
        val intent = Intent(context, CallReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("autocall://v107/$taskId/$requestOffset")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ACTION_KIND, action)
        }
        val base = (taskId xor (taskId ushr 32)).toInt()
        val requestCode = base + requestOffset
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
