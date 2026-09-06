package com.example.autocallmanager

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Finalizes an outgoing call attempt from real telephony state transitions.
 * Retry is scheduled only after a failed attempt, never immediately after
 * placeCall() succeeds.
 *
 * Every state transition here is also written to ExecutionTraceStore under
 * the executionId that the attempt's entry point generated -- CallReceiver
 * for an alarm-driven attempt, or MainActivity's manual "call now" bridge
 * method for a direct one -- so a call outcome can be traced back to the
 * exact pipeline run that produced it -- see Diagnostics -> Execution Trace.
 *
 * [executeCallAttempt] is the single shared call engine: it is the one place
 * a real call is placed (CallManager.placeCallDetailed) or an AI Voice
 * request is sent (AiVoiceCallClient), and every entry point that can
 * produce a real call goes through it instead of duplicating that sequence.
 * Every function below accepts or reads back a callSource ("MANUAL" or
 * "SCHEDULED") so History and the Execution Trace can always show which
 * entry point produced a given attempt -- see ExecutionTraceStore.TraceEvent
 * and TaskStore.HistoryEvent.
 */
object CallExecutionManager {
    private const val CALL_WATCHDOG_MS = 120_000L

    /**
     * V1.0.9 used this value as a hard success/fail GATE: any OFFHOOK
     * shorter than this was automatically classified
     * CALL_OFFHOOK_TOO_BRIEF/FAILED and auto-retried. That over-corrected.
     * Real execution traces showed genuine, successfully-delivered short
     * calls (offhook_ms=3366, 3983, 4295 -- a quick "reminder heard,
     * thanks, bye") being misclassified as failures and re-dialed
     * automatically, which is worse than the false-success bug it
     * replaced.
     *
     * The deeper problem duration can't fix: Android's legacy
     * TelephonyManager call state (the only signal available here without
     * the app becoming the default dialer / an InCallService -- see the
     * class KDoc on CallStateManager) collapses dialing, ring-out, an
     * active call, and on-hold into one OFFHOOK value, and OFFHOOK
     * duration reflects total session length, not "was answered". An
     * unanswered call that rings out to a network timeout can show MORE
     * offhook time than a real, quickly-finished call. No fixed duration
     * cutoff -- 6 seconds or otherwise -- can reliably separate "briefly
     * connected, real call" from "instant reject" in either direction.
     *
     * So duration is no longer a success/fail gate anywhere in
     * classifyCallEnd. This constant survives only to add a "(brief)"
     * hint to the trace/history text for a human reading it -- see
     * finishFromIdle. It never affects success, failure, or retry.
     */
    internal const val BRIEF_OFFHOOK_DIAGNOSTIC_MS = 6_000L

    /**
     * Runs one real call attempt for [task]: NORMAL_SIM synchronously
     * through the shared CallController (CallManager.placeCallDetailed), or
     * AI_VOICE dispatched to a background thread (AiVoiceCallClient does
     * blocking network I/O, and this may be called from a
     * BroadcastReceiver's onReceive(), which -- goAsync() or not -- runs on
     * the main thread, where a direct network call would throw
     * NetworkOnMainThreadException).
     *
     * This is the ONE place a real call is placed or an AI Voice request is
     * sent from. Every entry point that can produce a real call -- the
     * alarm-driven CallReceiver (callSource=SCHEDULED: real schedules,
     * retries, next-slot advances, reboot recovery, and Diagnostics -> Run
     * Background Test) and the direct manual "call now" bridge method in
     * MainActivity (callSource=MANUAL, entered without touching
     * CallScheduler/AlarmManager at all) -- calls this instead of
     * duplicating the placeCall/AI-voice/bookkeeping sequence, per the
     * single-call-engine requirement.
     *
     * Caller contract: the caller has already confirmed this attempt should
     * actually run right now (pause state; and for an alarm-triggered
     * attempt, that the alarm isn't stale and the task is still
     * status=="Scheduled" -- neither check makes sense for a manual
     * trigger, which is exactly why they stay in CallReceiver rather than
     * living here) and has already updated task.attemptCount/status/
     * liveStatus to reflect "this attempt is starting". This function only
     * decides HOW to actually carry out the call itself.
     *
     * Returns true if the attempt finished synchronously (the caller should
     * treat the attempt as over immediately); returns false only when
     * AI_VOICE work was handed to a background thread, in which case
     * [onAsyncComplete] runs on that thread once the backend request
     * completes, success or failure. A caller with no goAsync()/
     * PendingResult to release (e.g. the manual bridge method, which is not
     * a BroadcastReceiver) can pass the default no-op.
     */
    fun executeCallAttempt(
        context: Context,
        task: CallTask,
        executionId: String,
        attempt: Int,
        callSource: String,
        onAsyncComplete: () -> Unit = {}
    ): Boolean {
        if (!task.callEnabled) {
            completeWithoutCall(context, task, callSource)
            return true
        }

        val active = CallExecutionStore.get(context)
        if (active != null && task.executionMode == "NORMAL_SIM") {
            ExecutionTraceStore.log(
                context, task.id, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                "another execution already active for task ${active.taskId}",
                callSource = callSource
            )
            handleImmediateFailure(context, task, "CALL_BLOCKED_ACTIVE_CALL", executionId, callSource)
            return true
        }

        if (task.executionMode == "AI_VOICE") {
            // AiVoiceCallClient.create() performs blocking network I/O
            // (HttpURLConnection, up to connectTimeout(7s) + readTimeout(12s)
            // ~= 19s worst case). Dispatching it to a background thread keeps
            // this call synchronous and safe from any caller, including a
            // BroadcastReceiver's onReceive() where a direct network call
            // would throw NetworkOnMainThreadException. See
            // acquireExecutionWakeLock in CallReceiver for why its wakelock
            // ceiling has to stay above that 19s worst case.
            ExecutionTraceStore.log(
                context, task.id, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.PASS,
                "dispatching to AI Voice backend", callSource = callSource
            )
            thread(name = "AutoCallManager-AiVoice") {
                try {
                    runAiVoiceAttempt(context, task.id, executionId, attempt, task, callSource)
                } catch (e: Exception) {
                    ExecutionTraceStore.log(
                        context, task.id, executionId, attempt,
                        ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                        "uncaught ${e.javaClass.simpleName} on AI Voice background thread: ${e.message}",
                        callSource = callSource
                    )
                } finally {
                    onAsyncComplete()
                }
            }
            return false
        }

        // Start background state tracking before asking Telecom to place the call.
        // This avoids losing a very fast RINGING/OFFHOOK transition.
        if (!beginAttempt(context, task, executionId, callSource)) {
            ExecutionTraceStore.log(
                context, task.id, executionId, attempt,
                ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.FAIL,
                "beginAttempt() returned false (watchdog scheduling failed or conflicting active call)",
                callSource = callSource
            )
            handleImmediateFailure(context, task, "CALL_STATE_TRACKING_FAILED", executionId, callSource)
            return true
        }
        ExecutionTraceStore.log(
            context, task.id, executionId, attempt,
            ExecutionTraceStore.Stage.CALL_EXECUTION_BEGIN, ExecutionTraceStore.PASS,
            callSource = callSource
        )

        val callResult = CallManager.placeCallDetailed(
            context = context,
            number = task.number,
            phoneAccountComponent = if (task.simSelectionEnabled) task.phoneAccountComponent else "",
            phoneAccountId = if (task.simSelectionEnabled) task.phoneAccountId else "",
            taskId = task.id,
            executionId = executionId,
            attempt = attempt,
            callSource = callSource
        )

        if (!callResult.ok) {
            CallManager.showCallError(context, callResult.message)
            handleImmediateFailure(context, task, callResult.code, executionId, callSource)
            return true
        }

        // Re-load after placeCall(): a very fast OFFHOOK/IDLE broadcast can update
        // the task concurrently. Never overwrite those newer states with the
        // stale task/list captured before the Telecom request.
        val latestTasks = TaskStore.load(context)
        val latestTask = latestTasks.firstOrNull { it.id == task.id } ?: return true
        latestTask.liveStatus = "CALL_REQUEST_SENT"
        latestTask.lastResult = "CALL_REQUEST_SENT"
        TaskStore.addHistory(context, latestTask, "CALL", "CALL_REQUEST_SENT", callSource)
        TaskStore.save(context, latestTasks)

        startVoiceIfEnabled(context, latestTasks, latestTask, callSource)
        if (latestTask.messageEnabled && latestTask.messageText.isNotBlank()) {
            TaskStore.addHistory(context, latestTask, "MESSAGE", "MESSAGE_READY", callSource)
        } else {
            TaskStore.addHistory(context, latestTask, "MESSAGE", "MESSAGE_DISABLED", callSource)
        }
        TaskStore.save(context, latestTasks)
        return true
    }

    /**
     * The actual AI Voice backend request plus its follow-up bookkeeping.
     * Always invoked on a background thread (see the AI_VOICE branch in
     * [executeCallAttempt]) -- never on the caller's original thread, since
     * AiVoiceCallClient.create() does blocking network I/O.
     */
    private fun runAiVoiceAttempt(
        context: Context,
        taskId: Long,
        executionId: String,
        attempt: Int,
        task: CallTask,
        callSource: String
    ) {
        val voice = AiVoiceCallClient.create(context, task)
        val latestTasks = TaskStore.load(context)
        val latestTask = latestTasks.firstOrNull { it.id == taskId } ?: return
        if (!voice.ok) {
            // Specific code (e.g. AI_VOICE_CONFIG_MISSING, AI_VOICE_AUTH_FAILED,
            // AI_VOICE_OFFLINE) rather than one flat AI_VOICE_REQUEST_FAILED, so
            // retryDelaySecondsFor() can tell a permanent configuration problem
            // apart from a transient network/server one.
            val failureCode = "AI_VOICE_${voice.code}"
            latestTask.liveStatus = failureCode
            latestTask.lastResult = failureCode
            TaskStore.addHistory(context, latestTask, "AI_VOICE", voice.message, callSource)
            TaskStore.save(context, latestTasks)
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.AI_VOICE_REQUEST, ExecutionTraceStore.FAIL,
                "${voice.code}: ${voice.message}", callSource = callSource
            )
            handleImmediateFailure(context, latestTask, failureCode, executionId, callSource)
            return
        }
        latestTask.liveStatus = "AI_VOICE_REQUESTED"
        latestTask.lastResult = "AI_VOICE_REQUESTED"
        TaskStore.addHistory(
            context, latestTask, "AI_VOICE",
            "AI_VOICE_REQUESTED${if (voice.executionId.isNotBlank()) " · ${voice.executionId}" else ""}",
            callSource
        )
        TaskStore.save(context, latestTasks)
        ExecutionTraceStore.log(
            context, taskId, executionId, attempt,
            ExecutionTraceStore.Stage.AI_VOICE_REQUEST, ExecutionTraceStore.PASS,
            "requested" + if (voice.executionId.isNotBlank()) " execId=${voice.executionId}" else "",
            callSource = callSource
        )
        // No FINAL_OUTCOME here: once the backend has accepted the request,
        // the real call outcome happens server-side, which this device has
        // no further visibility into. Reporting CALL_SUCCESS here would be
        // fabricating a result Android never actually observed.
    }

    private fun startVoiceIfEnabled(context: Context, tasks: MutableList<CallTask>, task: CallTask, callSource: String) {
        if (!task.voiceEnabled || task.messageText.isBlank()) return
        task.liveStatus = "VOICE_STARTING"
        task.lastResult = "VOICE_SCHEDULED"
        TaskStore.addHistory(context, task, "VOICE", "VOICE_SCHEDULED", callSource)
        val serviceIntent = Intent(context, VoiceService::class.java).apply {
            putExtra(VoiceService.EXTRA_MESSAGE, task.messageText)
            putExtra(VoiceService.EXTRA_TASK_ID, task.id)
            putExtra(VoiceService.EXTRA_LANGUAGE, task.voiceLanguage)
            putExtra(VoiceService.EXTRA_SPEED, task.voiceSpeed)
            putExtra(VoiceService.EXTRA_PITCH, task.voicePitch)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            task.liveStatus = "VOICE_START_FAILED"
            task.lastResult = "VOICE_START_FAILED"
            TaskStore.addHistory(context, task, "VOICE", "VOICE_START_FAILED", callSource)
            TaskStore.save(context, tasks)
        }
    }

    fun beginAttempt(context: Context, task: CallTask, executionId: String = "", callSource: String = "SCHEDULED"): Boolean {
        val existing = CallExecutionStore.get(context)
        if (existing != null && existing.taskId != task.id) return false
        CallExecutionStore.start(context, task.id, task.attemptCount, executionId, callSource)
        return CallScheduler.scheduleCallWatchdog(
            context,
            task.id,
            System.currentTimeMillis() + CALL_WATCHDOG_MS
        )
    }

    fun handleImmediateFailure(context: Context, task: CallTask, code: String, executionId: String = "", callSource: String = "SCHEDULED") {
        CallScheduler.cancelWatchdog(context, task.id)
        CallExecutionStore.clear(context)
        val tasks = TaskStore.load(context)
        val current = tasks.firstOrNull { it.id == task.id } ?: return
        current.liveStatus = code
        current.lastResult = code
        TaskStore.addHistory(context, current, "CALL_STATE", code, callSource)
        ExecutionTraceStore.log(
            context, task.id, executionId, task.attemptCount,
            ExecutionTraceStore.Stage.FINAL_OUTCOME, ExecutionTraceStore.FAIL, code, callSource = callSource
        )
        finalizeAttempt(context, tasks, current, success = false, callSource = callSource)
    }


    fun completeWithoutCall(context: Context, task: CallTask, callSource: String = "SCHEDULED") {
        val tasks = TaskStore.load(context)
        val current = tasks.firstOrNull { it.id == task.id } ?: return
        current.liveStatus = "CALL_SKIPPED"
        current.lastResult = "CALL_SKIPPED"
        TaskStore.addHistory(context, current, "CALL", "CALL_SKIPPED", callSource)
        completeOrScheduleNext(context, tasks, current, callSource)
        notifyAfter(context, current, "Schedule processed without a cellular call", callSource)
    }

    fun handleWatchdogTimeout(context: Context, taskId: Long) {
        val active = CallExecutionStore.get(context) ?: return
        if (active.taskId != taskId) return

        // An ongoing off-hook/dialing call can legitimately last longer than
        // the watchdog window. Keep tracking it rather than falsely retrying.
        if (active.sawOffhook) {
            ExecutionTraceStore.log(
                context, taskId, active.executionId, active.attempt,
                ExecutionTraceStore.Stage.CALL_STATE_CALLBACK, ExecutionTraceStore.INFO,
                "watchdog extended - call still off-hook", callSource = active.callSource
            )
            CallScheduler.scheduleCallWatchdog(
                context,
                taskId,
                System.currentTimeMillis() + CALL_WATCHDOG_MS
            )
            updateTaskState(context, taskId, "CALL_OFFHOOK_LONG_RUNNING", active.callSource)
            return
        }

        ExecutionTraceStore.log(
            context, taskId, active.executionId, active.attempt,
            ExecutionTraceStore.Stage.FINAL_OUTCOME, ExecutionTraceStore.FAIL,
            "CALL_RESULT_TIMEOUT - no off-hook observed within ${CALL_WATCHDOG_MS}ms", callSource = active.callSource
        )
        CallExecutionStore.clear(context)
        CallScheduler.cancelWatchdog(context, taskId)

        val tasks = TaskStore.load(context)
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        task.liveStatus = "CALL_RESULT_TIMEOUT"
        task.lastResult = "CALL_RESULT_TIMEOUT"
        TaskStore.addHistory(context, task, "CALL_STATE", task.liveStatus, active.callSource)
        finalizeAttempt(context, tasks, task, success = false, callSource = active.callSource)
    }

    fun onTelephonyState(context: Context, state: CallStateManager.State) {
        val active = CallExecutionStore.get(context) ?: return
        when (state) {
            CallStateManager.State.RINGING ->
                ExecutionTraceStore.log(
                    context, active.taskId, active.executionId, active.attempt,
                    ExecutionTraceStore.Stage.CALL_STATE_CALLBACK, ExecutionTraceStore.INFO,
                    "RINGING (incoming-call state, not used for outgoing tracking)", callSource = active.callSource
                )
            CallStateManager.State.OFFHOOK -> {
                ExecutionTraceStore.log(
                    context, active.taskId, active.executionId, active.attempt,
                    ExecutionTraceStore.Stage.CALL_STATE_CALLBACK, ExecutionTraceStore.PASS,
                    "OFFHOOK observed", callSource = active.callSource
                )
                CallExecutionStore.markOffhook(context)
                updateTaskState(context, active.taskId, "CALL_OFFHOOK", active.callSource)
            }
            CallStateManager.State.IDLE -> {
                // Ignore an immediate transient IDLE generated before Telecom
                // has established the outgoing call state.
                if (active.startedAt > 0L && System.currentTimeMillis() - active.startedAt < 1500L) {
                    ExecutionTraceStore.log(
                        context, active.taskId, active.executionId, active.attempt,
                        ExecutionTraceStore.Stage.CALL_STATE_CALLBACK, ExecutionTraceStore.INFO,
                        "IDLE ignored - within 1500ms startup guard window", callSource = active.callSource
                    )
                    return
                }
                finishFromIdle(context, active)
            }
            CallStateManager.State.UNKNOWN ->
                ExecutionTraceStore.log(
                    context, active.taskId, active.executionId, active.attempt,
                    ExecutionTraceStore.Stage.CALL_STATE_CALLBACK, ExecutionTraceStore.INFO,
                    "UNKNOWN telephony state (READ_PHONE_STATE missing or query failed)", callSource = active.callSource
                )
        }
    }

    private fun finishFromIdle(context: Context, active: CallExecutionStore.ActiveCall) {
        CallExecutionStore.clear(context)
        CallScheduler.cancelWatchdog(context, active.taskId)

        val tasks = TaskStore.load(context)
        val task = tasks.firstOrNull { it.id == active.taskId } ?: return

        val offhookDurationMs = if (active.sawOffhook && active.offhookAt > 0L) {
            (System.currentTimeMillis() - active.offhookAt).coerceAtLeast(0L)
        } else {
            0L
        }
        val outcome = classifyCallEnd(active.sawOffhook, offhookDurationMs)
        val detail = describeCallEnd(outcome, active.sawOffhook, offhookDurationMs)

        task.liveStatus = outcome.code
        task.lastResult = outcome.code
        TaskStore.addHistory(context, task, "CALL_STATE", detail, active.callSource)
        ExecutionTraceStore.log(
            context, active.taskId, active.executionId, active.attempt,
            ExecutionTraceStore.Stage.FINAL_OUTCOME,
            if (outcome.success) ExecutionTraceStore.PASS else ExecutionTraceStore.FAIL,
            detail, callSource = active.callSource
        )
        finalizeAttempt(context, tasks, task, outcome.success, active.callSource)
    }

    /**
     * Human-readable trace/history detail for a finished attempt. The
     * duration and "(brief)" hint are diagnostic-only annotations -- see
     * BRIEF_OFFHOOK_DIAGNOSTIC_MS -- neither affects outcome.success,
     * which classifyCallEnd already decided from sawOffhook alone, per
     * spec: "keep OFFHOOK duration only as a diagnostic signal". The
     * disconnect reason is always reported as UNKNOWN once a call session
     * happened, per acceptance criterion G: this app cannot see Telecom's
     * DisconnectCause without becoming the default dialer / an
     * InCallService, so it does not guess REMOTE, LOCAL, BUSY, or
     * NO_ANSWER. internal (not private) so it's directly unit testable.
     */
    internal fun describeCallEnd(outcome: CallEndOutcome, sawOffhook: Boolean, offhookDurationMs: Long): String {
        if (!sawOffhook) return outcome.code
        val brief = if (offhookDurationMs in 1 until BRIEF_OFFHOOK_DIAGNOSTIC_MS) " brief" else ""
        return "${outcome.code} (offhook_ms=$offhookDurationMs$brief, disconnect_reason=UNKNOWN)"
    }

    /**
     * Outcome of a finished call attempt as a plain value, so classification
     * doesn't need a Context and can be unit tested directly.
     */
    internal data class CallEndOutcome(val code: String, val success: Boolean)

    /**
     * Pure classification of a finished attempt from the only signal this
     * app can see without becoming the default dialer / an InCallService:
     * whether OFFHOOK was ever observed at all (offhookDurationMs is
     * accepted only so callers can still log it as a diagnostic -- see
     * finishFromIdle -- it does not affect the outcome below).
     *
     * Only two outcomes are actually distinguishable with this signal:
     * - never went OFFHOOK at all: Telecom/telephony never entered a call
     *   session for this attempt. That IS solid evidence of failure --
     *   see acceptance criterion F, "a successful Telecom call request
     *   must not immediately be labelled FAIL without valid evidence";
     *   the converse holds too, an attempt with no evidence of success
     *   is fairly called a failure.
     * - OFFHOOK was observed at least once: Telecom/telephony DID
     *   establish a real call session for this attempt, for however
     *   long. That is valid evidence the call was placed and connected
     *   at the telephony layer, regardless of duration (see
     *   BRIEF_OFFHOOK_DIAGNOSTIC_MS) -- so per acceptance criteria D/E/F
     *   this is not automatically a failure and must not automatically
     *   retry. Who ended it and why (REMOTE vs LOCAL, answered vs
     *   rejected) is NOT observable on this platform -- per acceptance
     *   criterion G this is reported as an honest UNKNOWN disconnect
     *   reason (see finishFromIdle's trace/history detail string)
     *   instead of guessing REMOTE or fabricating a busy/no-answer label
     *   the platform can't actually back up.
     */
    internal fun classifyCallEnd(sawOffhook: Boolean, offhookDurationMs: Long): CallEndOutcome =
        if (!sawOffhook) {
            CallEndOutcome("CALL_ENDED_WITHOUT_OFFHOOK", success = false)
        } else {
            CallEndOutcome("CALL_OFFHOOK_ENDED", success = true)
        }

    private fun updateTaskState(context: Context, taskId: Long, state: String, callSource: String) {
        val tasks = TaskStore.load(context)
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        task.status = "Running"
        task.liveStatus = state
        task.lastResult = state
        TaskStore.save(context, tasks)
        TaskStore.addHistory(context, task, "CALL_STATE", state, callSource)
    }

    private fun finalizeAttempt(
        context: Context,
        tasks: MutableList<CallTask>,
        task: CallTask,
        success: Boolean,
        callSource: String
    ) {
        if (success) {
            // A successful call ends this attempt cycle. Move directly to the
            // next recurrence when configured, otherwise complete the task.
            completeOrScheduleNext(context, tasks, task, callSource)
            notifyAfter(context, task, "Call attempt completed after off-hook state", callSource)
            return
        }

        if (task.attemptCount < task.maxAttempts) {
            val retryDelaySeconds = retryDelaySecondsFor(task.lastResult, task.retryIntervalSeconds)
            if (retryDelaySeconds == Long.MAX_VALUE) {
                completeOrScheduleNext(context, tasks, task, callSource)
                notifyAfter(context, task, "Call failed • retry not appropriate", callSource)
                return
            }
            val retryAt = System.currentTimeMillis() + retryDelaySeconds * 1000L
            task.status = "Scheduled"
            task.nextTrigger = retryAt
            task.liveStatus = "WAITING_RETRY"
            task.lastResult = "RETRY_SCHEDULED"
            TaskStore.addHistory(context, task, "SYSTEM", "RETRY_SCHEDULED", callSource)
            TaskStore.save(context, tasks)
            // The retry itself always fires through the alarm -- CallScheduler
            // -> AlarmManager -> CallReceiver -- regardless of whether THIS
            // attempt was manual or scheduled, so it is always callSource=
            // SCHEDULED once it actually runs. callSource here only tags the
            // bookkeeping about the attempt that just failed.
            if (CallScheduler.schedule(context, task.id, retryAt, task.attemptCount + 1)) {
                CallScheduler.schedulePreNotification(context, task)
                notifyAfter(context, task, "Call attempt failed • retry scheduled", callSource)
            } else {
                markSchedulerFailure(context, tasks, task, callSource)
            }
            return
        }

        completeOrScheduleNext(context, tasks, task, callSource)
        notifyAfter(context, task, "Maximum call attempts reached", callSource)
    }

    /**
     * Smart Retry Engine 2.0: outcome-aware retry delay in seconds, keyed off
     * the failure code the attempt just finished with (task.lastResult).
     *
     * Long.MAX_VALUE is a sentinel the caller already checks for (see
     * finalizeAttempt) meaning "do not retry this outcome at all" -- reserved
     * for outcomes that are structural rather than transient, i.e. they would
     * fail identically on every future attempt until the user changes
     * something (wrong number, missing call permission, disabled/invalid SIM
     * account, or a broken AI Voice backend configuration).
     *
     * Every other outcome -- CALL_ENDED_WITHOUT_OFFHOOK (see
     * classifyCallEnd), busy, no-answer, timeout, and other temporary
     * system/telecom/backend errors -- retries after the task's own
     * configured [baseIntervalSeconds], so a user's custom retry delay is
     * always honored exactly rather than being scaled up or down.
     * CALL_OFFHOOK_ENDED never reaches this function: classifyCallEnd only
     * returns it with success=true, and finalizeAttempt only consults
     * retryDelaySecondsFor after a failed attempt. internal (not private)
     * so it's directly unit testable, same as classifyCallEnd.
     */
    internal fun retryDelaySecondsFor(lastResult: String, baseIntervalSeconds: Int): Long {
        val nonRetryable = setOf(
            "INVALID_NUMBER",
            "CALL_NOT_PERMITTED",
            "SIM_ACCOUNT_UNAVAILABLE",
            "SIM_ACCOUNT_INVALID",
            "SECURITY_ERROR",
            // AI Voice failures that are structural, not transient -- see
            // AiVoiceCallClient.Code. Every future attempt would fail
            // identically until the user (or an admin) actually changes the
            // backend URL, app key, or consent state, so retrying only burns
            // through maxAttempts and spams "retry scheduled" notifications
            // for something no amount of waiting will fix.
            "AI_VOICE_CONFIG_MISSING",
            "AI_VOICE_INVALID_URL",
            "AI_VOICE_AUTH_FAILED",
            "AI_VOICE_REJECTED"
        )
        if (lastResult in nonRetryable) return Long.MAX_VALUE

        // Busy (DEVICE_CALL_BUSY, CALL_BLOCKED_ACTIVE_CALL), no-answer
        // (CALL_NO_ANSWER, CALL_NO_ANSWER_TIMEOUT, CALL_ENDED_WITHOUT_CONNECT,
        // CALL_ENDED_WITHOUT_OFFHOOK), and temporary/system errors
        // (CALL_RESULT_TIMEOUT, CALL_REQUEST_FAILED, TELECOM_UNAVAILABLE,
        // AI_VOICE_OFFLINE, AI_VOICE_SERVER_ERROR, AI_VOICE_REQUEST_FAILED,
        // CALL_STATE_TRACKING_FAILED, SCHEDULE_FAILED, and anything
        // unrecognized) all fall through to the task's own configured
        // interval, since these could plausibly succeed on the next attempt.
        return baseIntervalSeconds.toLong().coerceAtLeast(1L)
    }

    private fun completeOrScheduleNext(context: Context, tasks: MutableList<CallTask>, task: CallTask, callSource: String) {
        if (SettingsStore.pausedAll(context)) {
            task.status = "Paused"
            task.nextTrigger = -1L
            task.liveStatus = "PAUSED"
            task.lastResult = "PAUSED"
            TaskStore.addHistory(context, task, "SYSTEM", "PAUSED", callSource)
            TaskStore.save(context, tasks)
            return
        }

        // Diagnostic test tasks are one-shot by construction: they exist only
        // to prove the pipeline fired, and must never turn into a real
        // recurring schedule that keeps calling the test number.
        if (task.isDiagnosticTest) {
            task.status = "Stopped"
            task.nextTrigger = -1L
            TaskStore.save(context, tasks)
            return
        }

        val next = ScheduleCalculator.nextRecurring(task, System.currentTimeMillis())
        if (next != null) {
            task.attemptCount = 0
            task.activeSlotIndex += 1
            task.nextTrigger = next
            task.status = "Scheduled"
            task.liveStatus = "WAITING_NEXT_SLOT"
            task.lastResult = "NEXT_SLOT"
            TaskStore.addHistory(context, task, "SYSTEM", "NEXT_SLOT", callSource)
            TaskStore.save(context, tasks)
            if (CallScheduler.schedule(context, task.id, next, 1)) {
                CallScheduler.schedulePreNotification(context, task)
            } else {
                markSchedulerFailure(context, tasks, task, callSource)
            }
            return
        }

        task.status = "Stopped"
        task.nextTrigger = -1L
        task.liveStatus = if (task.lastResult == "CALL_OFFHOOK_ENDED") "COMPLETED" else "FAILED"
        task.lastResult = if (task.liveStatus == "COMPLETED") "COMPLETED" else "FAILED_MAX_ATTEMPTS"
        TaskStore.addHistory(context, task, "SYSTEM", task.lastResult, callSource)
        TaskStore.save(context, tasks)
    }

    private fun notifyAfter(context: Context, task: CallTask, message: String, callSource: String = "SCHEDULED") {
        if (!task.notificationEnabled || !task.notifyAfter) return
        CallManager.showNotification(context, task, message)
        TaskStore.addHistory(context, task, "NOTIFICATION", "AFTER_ATTEMPT_NOTIFICATION", callSource)
    }

    private fun markSchedulerFailure(context: Context, tasks: MutableList<CallTask>, task: CallTask, callSource: String) {
        task.status = "Stopped"
        task.nextTrigger = -1L
        task.liveStatus = "SCHEDULER_ERROR"
        task.lastResult = "SCHEDULE_FAILED"
        TaskStore.addHistory(context, task, "SYSTEM", "SCHEDULE_FAILED", callSource)
        TaskStore.save(context, tasks)
    }
}
