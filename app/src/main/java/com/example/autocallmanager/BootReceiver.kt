package com.example.autocallmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-registers alarms after reboot, app update (MY_PACKAGE_REPLACED), or a
 * change in exact-alarm permission. AlarmManager entries never survive any
 * of these -- TaskStore is the only durable copy of what should be armed, so
 * everything scheduled has to be reconstructed from it here.
 *
 * Three cases per task, by how its nextTrigger compares to "now":
 * - still in the future: re-register exactly as scheduled (unchanged from
 *   before this fix).
 * - passed only within PAST_DUE_GRACE_MS (ordinary boot delay): treated as
 *   on-time, fired almost immediately.
 * - passed by more than that: never silently dropped, and never fired as a
 *   real phone call an unknown number of hours late without being asked to.
 *   Recurring tasks skip forward to their next real future occurrence
 *   (ScheduleCalculator already knows how -- see completeOrScheduleNext in
 *   CallExecutionManager for the same pattern). One-time tasks -- and
 *   diagnostic test tasks, which must never resurrect on their own -- are
 *   surfaced as MISSED_WHILE_OFFLINE instead of vanishing with no record.
 *   Previously this whole category was filtered out and simply never
 *   rescheduled: a task could sit indefinitely with status="Scheduled" and
 *   no alarm actually registered, invisible until the user happened to
 *   notice it never rang.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val PAST_DUE_GRACE_MS = 5 * 60_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                CallExecutionStore.clear(context)
                if (SettingsStore.pausedAll(context)) return

                val now = System.currentTimeMillis()
                val tasks = TaskStore.load(context)
                var changed = false

                tasks.filter { it.status == "Scheduled" }.forEach { task ->
                    when {
                        task.nextTrigger > now -> {
                            if (reArm(context, task, task.nextTrigger, task.attemptCount + 1)) changed = true
                        }

                        task.nextTrigger > now - PAST_DUE_GRACE_MS -> {
                            val fireAt = now + 5_000L
                            task.nextTrigger = fireAt
                            TaskStore.addHistory(context, task, "SYSTEM", "REBOOT_RECOVERED_NEAR_ON_TIME")
                            changed = true
                            if (reArm(context, task, fireAt, task.attemptCount + 1)) changed = true
                        }

                        else -> {
                            val next = if (task.isDiagnosticTest) null else ScheduleCalculator.nextRecurring(task, now)
                            if (next != null) {
                                task.attemptCount = 0
                                task.activeSlotIndex += 1
                                task.nextTrigger = next
                                TaskStore.addHistory(context, task, "SYSTEM", "REBOOT_MISSED_SLOT_SKIPPED_TO_NEXT")
                                changed = true
                                if (reArm(context, task, next, 1)) changed = true
                            } else {
                                task.status = "Stopped"
                                task.nextTrigger = -1L
                                task.liveStatus = "MISSED_WHILE_OFFLINE"
                                task.lastResult = "MISSED_WHILE_OFFLINE"
                                TaskStore.addHistory(context, task, "SYSTEM", "MISSED_WHILE_OFFLINE")
                                changed = true
                            }
                        }
                    }
                }

                if (changed) TaskStore.save(context, tasks)
            }
        }
    }

    /**
     * Registers the OS alarm for [task] and returns whether it mutated
     * [task] (only on failure). Previously a false return from
     * CallScheduler.schedule() (e.g. exact-alarm permission revoked between
     * schedule time and reboot) was silently ignored here, leaving a task
     * marked "Scheduled" with no real alarm behind it -- indistinguishable
     * from a working schedule until it silently never fired.
     */
    private fun reArm(context: Context, task: CallTask, triggerAt: Long, attempt: Int): Boolean {
        if (CallScheduler.schedule(context, task.id, triggerAt, attempt)) {
            CallScheduler.schedulePreNotification(context, task)
            return false
        }
        task.status = "Stopped"
        task.nextTrigger = -1L
        task.liveStatus = "SCHEDULER_ERROR"
        task.lastResult = "SCHEDULE_FAILED"
        TaskStore.addHistory(context, task, "SYSTEM", "SCHEDULE_FAILED")
        return true
    }
}
