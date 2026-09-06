package com.example.autocallmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Covers only the two pure, Context-free functions ExecutionTraceStore
 * exposes: formatTimestamp and formatLine. The actual log()/append()/read
 * path (SharedPreferences-backed) needs a real or faked Android Context,
 * same limitation noted in CallExecutionManagerTest -- not covered here.
 */
class ExecutionTraceStoreTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    @Test
    fun formatsTimestampAsHourMinuteSecondMillis() {
        // 2025-01-01 00:00:00.000 UTC = 2025-01-01 05:30:00.000 IST
        val ts = 1735689600000L
        assertEquals("05:30:00.000", ExecutionTraceStore.formatTimestamp(ts, ist))
    }

    @Test
    fun millisecondComponentIsPreservedAndZeroPadded() {
        val ts = 1735689600000L + 7L // + 7ms
        assertEquals("05:30:00.007", ExecutionTraceStore.formatTimestamp(ts, ist))
    }

    @Test
    fun formatLineIncludesEveryFieldTheUserAskedToTrace() {
        val event = ExecutionTraceStore.TraceEvent(
            ts = 1735689600000L,
            taskId = 42L,
            executionId = "T42-A1-1735689600000",
            attempt = 1,
            stage = ExecutionTraceStore.Stage.PLACE_CALL,
            result = ExecutionTraceStore.PASS,
            detail = "TelecomManager.placeCall() returned normally",
            screen = "OFF",
            lock = "LOCKED",
            battery = "UNRESTRICTED",
            exactAlarm = "GRANTED",
            phoneAccount = "SIM 1"
        )
        val line = ExecutionTraceStore.formatLine(event, ist)

        // timestamp, task/schedule id (via executionId), attempt, screen,
        // lock, battery, exact-alarm, phone-account, result, detail --
        // every field the user's spec asked every stage row to carry.
        assertTrue(line.startsWith("05:30:00.000"))
        assertTrue(line.contains("PLACE_CALL"))
        assertTrue(line.contains("PASS"))
        assertTrue(line.contains("screen=OFF"))
        assertTrue(line.contains("lock=LOCKED"))
        assertTrue(line.contains("battery=UNRESTRICTED"))
        assertTrue(line.contains("exactAlarm=GRANTED"))
        assertTrue(line.contains("attempt=1"))
        assertTrue(line.contains("acct=SIM 1"))
        assertTrue(line.contains("TelecomManager.placeCall() returned normally"))
    }

    @Test
    fun stageColumnIsPaddedSoColumnsLineUpAcrossDifferentStageNameLengths() {
        val short = ExecutionTraceStore.TraceEvent(
            0L, 1L, "", 1, ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.PASS,
            "", "ON", "UNLOCKED", "UNRESTRICTED", "GRANTED", ""
        )
        val long = ExecutionTraceStore.TraceEvent(
            0L, 1L, "", 1, ExecutionTraceStore.Stage.PHONE_ACCOUNT_RESOLVED, ExecutionTraceStore.PASS,
            "", "ON", "UNLOCKED", "UNRESTRICTED", "GRANTED", ""
        )
        val shortLine = ExecutionTraceStore.formatLine(short, ist)
        val longLine = ExecutionTraceStore.formatLine(long, ist)

        // Both lines should place " screen=" at the same column despite
        // PLACE_CALL (10 chars) vs PHONE_ACCOUNT_RESOLVED (22 chars).
        assertEquals(shortLine.indexOf("screen="), longLine.indexOf("screen="))
    }
}
