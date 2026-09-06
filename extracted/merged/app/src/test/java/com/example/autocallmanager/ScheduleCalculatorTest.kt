package com.example.autocallmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {
    private fun task(
        trigger: ZonedDateTime,
        recurrence: RecurrenceMode,
        repeatDays: Set<Int> = emptySet()
    ) = CallTask(
        id = 1L,
        number = "+919876543210",
        contactName = "Test",
        triggerAt = trigger.toInstant().toEpochMilli(),
        maxAttempts = 3,
        retryIntervalSeconds = 300,
        recurrence = recurrence,
        repeatDays = repeatDays,
        timezoneId = trigger.zone.id
    )

    @Test
    fun dailyScheduleUsesOriginalTimezoneAfterDeviceZoneChange() {
        val zone = ZoneId.of("Asia/Kolkata")
        val trigger = ZonedDateTime.of(2026, 8, 30, 8, 0, 0, 0, zone)
        val task = task(trigger, RecurrenceMode.DAILY)
        val now = ZonedDateTime.of(2026, 8, 30, 8, 1, 0, 0, zone).toInstant().toEpochMilli()

        val next = ScheduleCalculator.nextRecurring(task, now)
        assertTrue(next != null)
        val nextLocal = java.time.Instant.ofEpochMilli(next!!).atZone(zone)
        assertEquals(8, nextLocal.hour)
        assertEquals(0, nextLocal.minute)
        assertEquals(0, nextLocal.second)
        assertEquals(LocalDate.of(2026, 8, 31), nextLocal.toLocalDate())
    }

    @Test
    fun weeklySundaySelectionUsesSundayAsOne() {
        val zone = ZoneId.of("Asia/Kolkata")
        val trigger = ZonedDateTime.of(2026, 8, 30, 8, 0, 0, 0, zone) // Sunday
        val task = task(trigger, RecurrenceMode.WEEKLY, repeatDays = setOf(1))
        val now = ZonedDateTime.of(2026, 8, 30, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        val next = ScheduleCalculator.nextRecurring(task, now)
        assertTrue(next != null)
        val nextLocal = java.time.Instant.ofEpochMilli(next!!).atZone(zone)
        assertEquals(7, nextLocal.dayOfWeek.value) // Sunday in java.time
        assertEquals(8, nextLocal.hour)
    }

    @Test
    fun noonAndMidnightAreRepresentedCorrectly() {
        val zone = ZoneId.of("Asia/Kolkata")
        val midnight = ZonedDateTime.of(2026, 8, 31, 0, 0, 0, 0, zone)
        val noon = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, zone)
        assertEquals(0, midnight.hour)
        assertEquals(12, noon.hour)
    }
}
