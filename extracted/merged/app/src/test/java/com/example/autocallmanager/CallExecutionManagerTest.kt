package com.example.autocallmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision/formatting functions CallExecutionManager exposes
 * as `internal`: classifyCallEnd (OFFHOOK-based outcome), describeCallEnd
 * (trace/history detail text), and retryDelaySecondsFor (Smart Retry Engine
 * 2.0 policy).
 *
 * classifyCallEnd no longer takes OFFHOOK duration into account for
 * success/failure (see BRIEF_OFFHOOK_DIAGNOSTIC_MS's KDoc): a fixed duration
 * cutoff could not reliably tell a genuine short call apart from an instant
 * reject, and was misclassifying real, successfully-delivered short calls as
 * failures. Only two outcomes are distinguishable now, not three --
 * CALL_OFFHOOK_TOO_BRIEF no longer exists as a classifyCallEnd result.
 *
 * What this file deliberately does NOT test: attemptCount incrementing
 * (CallReceiver.handleCallAttempt), actual retry scheduling (CallScheduler /
 * AlarmManager), and end-to-end finalizeAttempt/finishFromIdle/
 * executeCallAttempt. Those all require a real or faked Android Context
 * (SharedPreferences, AlarmManager, TelecomManager), which this module's
 * plain-JUnit test setup has no way to provide -- there is no Robolectric or
 * Mockito dependency here (see app/build.gradle.kts: testImplementation is
 * JUnit only). Covering those paths for real would need one of those added,
 * which is a call for the project owner, not something to add silently as
 * part of this fix.
 */
class CallExecutionManagerTest {

    // ---- classifyCallEnd -----------------------------------------------
    // Only two outcomes are distinguishable from this platform's one
    // signal (OFFHOOK observed at all); see the KDoc on classifyCallEnd for
    // why busy/no-answer/unreachable/restricted aren't split out further,
    // and why duration no longer affects the outcome.

    @Test
    fun neverOffhookIsNotAConnection() {
        val outcome = CallExecutionManager.classifyCallEnd(
            sawOffhook = false,
            offhookDurationMs = 0L
        )
        assertEquals("CALL_ENDED_WITHOUT_OFFHOOK", outcome.code)
        assertFalse(outcome.success)
    }

    @Test
    fun neverOffhookIsNotAConnectionRegardlessOfDurationField() {
        // offhookDurationMs is meaningless when sawOffhook is false (there
        // was no session to time), but classifyCallEnd must not
        // accidentally key off it anyway.
        val outcome = CallExecutionManager.classifyCallEnd(
            sawOffhook = false,
            offhookDurationMs = 999_999L
        )
        assertEquals("CALL_ENDED_WITHOUT_OFFHOOK", outcome.code)
        assertFalse(outcome.success)
    }

    @Test
    fun briefOffhookStillCountsAsConnected() {
        // The exact bug this fixed: a short but genuine call (a quick
        // "reminder heard, thanks, bye") must not be misclassified as a
        // failure just because it was brief. See BRIEF_OFFHOOK_DIAGNOSTIC_MS.
        val outcome = CallExecutionManager.classifyCallEnd(
            sawOffhook = true,
            offhookDurationMs = CallExecutionManager.BRIEF_OFFHOOK_DIAGNOSTIC_MS - 1
        )
        assertEquals("CALL_OFFHOOK_ENDED", outcome.code)
        assertTrue(outcome.success)
    }

    @Test
    fun zeroDurationOffhookStillCountsAsConnected() {
        // sawOffhook true with no measurable duration (e.g. offhookAt and
        // the IDLE callback landed in the same millisecond) is still real
        // evidence a call session existed -- duration is not part of the
        // success decision at all, only of the diagnostic "(brief)" hint in
        // describeCallEnd.
        val outcome = CallExecutionManager.classifyCallEnd(
            sawOffhook = true,
            offhookDurationMs = 0L
        )
        assertEquals("CALL_OFFHOOK_ENDED", outcome.code)
        assertTrue(outcome.success)
    }

    @Test
    fun offhookWellPastDiagnosticThresholdCountsAsConnected() {
        // Stands in for both "remote hung up after connecting" and "local
        // hung up after connecting" -- indistinguishable with this signal.
        val outcome = CallExecutionManager.classifyCallEnd(
            sawOffhook = true,
            offhookDurationMs = CallExecutionManager.BRIEF_OFFHOOK_DIAGNOSTIC_MS * 10
        )
        assertEquals("CALL_OFFHOOK_ENDED", outcome.code)
        assertTrue(outcome.success)
    }

    // ---- describeCallEnd -------------------------------------------------

    @Test
    fun describeCallEndWithoutOffhookIsJustTheCode() {
        val outcome = CallExecutionManager.CallEndOutcome("CALL_ENDED_WITHOUT_OFFHOOK", success = false)
        val detail = CallExecutionManager.describeCallEnd(outcome, sawOffhook = false, offhookDurationMs = 0L)
        assertEquals("CALL_ENDED_WITHOUT_OFFHOOK", detail)
    }

    @Test
    fun describeCallEndAddsBriefHintUnderDiagnosticThreshold() {
        val outcome = CallExecutionManager.CallEndOutcome("CALL_OFFHOOK_ENDED", success = true)
        val detail = CallExecutionManager.describeCallEnd(
            outcome, sawOffhook = true,
            offhookDurationMs = CallExecutionManager.BRIEF_OFFHOOK_DIAGNOSTIC_MS - 1
        )
        assertTrue("expected a '(brief)' style hint, got: $detail", detail.contains("brief"))
        assertTrue(detail.contains("disconnect_reason=UNKNOWN"))
    }

    @Test
    fun describeCallEndOmitsBriefHintAtOrAboveDiagnosticThreshold() {
        val outcome = CallExecutionManager.CallEndOutcome("CALL_OFFHOOK_ENDED", success = true)
        val detail = CallExecutionManager.describeCallEnd(
            outcome, sawOffhook = true,
            offhookDurationMs = CallExecutionManager.BRIEF_OFFHOOK_DIAGNOSTIC_MS
        )
        assertFalse("did not expect a 'brief' hint, got: $detail", detail.contains("brief"))
    }

    @Test
    fun describeCallEndNeverGuessesADisconnectReason() {
        // Per acceptance criterion G: REMOTE/LOCAL is never observable on
        // this platform without becoming the default dialer, so it must
        // always read UNKNOWN rather than a guessed value.
        val outcome = CallExecutionManager.CallEndOutcome("CALL_OFFHOOK_ENDED", success = true)
        val detail = CallExecutionManager.describeCallEnd(outcome, sawOffhook = true, offhookDurationMs = 60_000L)
        assertTrue(detail.contains("disconnect_reason=UNKNOWN"))
    }

    // ---- retryDelaySecondsFor -------------------------------------------

    @Test
    fun structuralFailuresAreNotRetried() {
        val permanent = listOf(
            "INVALID_NUMBER",
            "CALL_NOT_PERMITTED",
            "SIM_ACCOUNT_UNAVAILABLE",
            "SIM_ACCOUNT_INVALID",
            "SECURITY_ERROR",
            // AI Voice failures that are structural, not transient -- see
            // AiVoiceCallClient.Code.
            "AI_VOICE_CONFIG_MISSING",
            "AI_VOICE_INVALID_URL",
            "AI_VOICE_AUTH_FAILED",
            "AI_VOICE_REJECTED"
        )
        for (code in permanent) {
            assertEquals(
                "$code should not be retried",
                Long.MAX_VALUE,
                CallExecutionManager.retryDelaySecondsFor(code, 300)
            )
        }
    }

    @Test
    fun knownTransientCodesUseTheConfiguredInterval() {
        val transient = listOf(
            "CALL_NO_ANSWER",
            "CALL_NO_ANSWER_TIMEOUT",
            "DEVICE_CALL_BUSY",
            "CALL_BLOCKED_ACTIVE_CALL",
            "CALL_ENDED_WITHOUT_CONNECT",
            "CALL_ENDED_WITHOUT_OFFHOOK",
            "CALL_RESULT_TIMEOUT",
            "CALL_REQUEST_FAILED",
            "TELECOM_UNAVAILABLE",
            "AI_VOICE_OFFLINE",
            "AI_VOICE_SERVER_ERROR",
            "AI_VOICE_REQUEST_FAILED",
            "CALL_STATE_TRACKING_FAILED",
            "SCHEDULE_FAILED"
        )
        for (code in transient) {
            assertEquals(
                "$code should retry after the configured interval",
                180L,
                CallExecutionManager.retryDelaySecondsFor(code, 180)
            )
        }
    }

    @Test
    fun unrecognizedCodeFallsBackToConfiguredIntervalRatherThanGuessing() {
        assertEquals(
            300L,
            CallExecutionManager.retryDelaySecondsFor("SOME_FUTURE_CODE_NOT_YET_CLASSIFIED", 300)
        )
    }

    @Test
    fun retiredOffhookTooBriefCodeWouldStillBeRetryableIfItEverAppeared() {
        // CALL_OFFHOOK_TOO_BRIEF is no longer produced by classifyCallEnd
        // (brief OFFHOOK is now CALL_OFFHOOK_ENDED/success), but
        // retryDelaySecondsFor is a pure string->delay function that makes
        // no assumption about which codes classifyCallEnd can still emit --
        // an old persisted history/trace row using this code (from before
        // this fix) must still fall through to the configured interval
        // instead of being newly (and wrongly) treated as non-retryable.
        assertEquals(
            300L,
            CallExecutionManager.retryDelaySecondsFor("CALL_OFFHOOK_TOO_BRIEF", 300)
        )
    }

    @Test
    fun customRetryIntervalsAreHonoredExactlyNotScaled() {
        assertEquals(45L, CallExecutionManager.retryDelaySecondsFor("CALL_NO_ANSWER", 45))
        assertEquals(900L, CallExecutionManager.retryDelaySecondsFor("CALL_NO_ANSWER", 900))
    }

    @Test
    fun nonPositiveConfiguredIntervalIsFlooredToOneSecond() {
        assertEquals(1L, CallExecutionManager.retryDelaySecondsFor("CALL_NO_ANSWER", 0))
        assertEquals(1L, CallExecutionManager.retryDelaySecondsFor("CALL_NO_ANSWER", -30))
    }
}
