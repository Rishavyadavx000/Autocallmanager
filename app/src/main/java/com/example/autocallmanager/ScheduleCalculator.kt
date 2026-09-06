package com.example.autocallmanager

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object ScheduleCalculator {

    fun normaliseSlots(task: CallTask): List<Int> {
        val fromTask = task.timeSlots
            .filter { it in 0..1439 }
            .distinct()
            .sorted()

        if (fromTask.isNotEmpty()) return fromTask

        val zone = runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(ZoneId.systemDefault())
        val z = Instant.ofEpochMilli(task.triggerAt).atZone(zone)
        return listOf(z.hour * 60 + z.minute)
    }

    fun nextRecurring(task: CallTask, now: Long = System.currentTimeMillis()): Long? {
        if (task.recurrence == RecurrenceMode.ONCE) return null

        val zone = runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(ZoneId.systemDefault())
        val slots = normaliseSlots(task)
        if (slots.isEmpty()) return null

        val template = Instant.ofEpochMilli(task.triggerAt).atZone(zone)
        val second = template.second.coerceIn(0, 59)
        val allowedDays = if (task.recurrence == RecurrenceMode.WEEKLY && task.repeatDays.isNotEmpty()) {
            task.repeatDays
        } else {
            setOf(1, 2, 3, 4, 5, 6, 7)
        }

        val base = Instant.ofEpochMilli(now).atZone(zone)
        for (offset in 0..370) {
            val day = base.toLocalDate().plusDays(offset.toLong())
            // task.repeatDays comes from the wizard's day chips using
            // Sunday=1..Saturday=7 (see auto_call_ui.html's data-day attrs,
            // the same values java.util.Calendar used pre-refactor).
            // java.time.DayOfWeek is ISO-8601: Monday=1..Sunday=7. Convert,
            // or every weekly call silently fires one day after the day the
            // user actually picked.
            val dayOfWeek = (day.dayOfWeek.value % 7) + 1
            if (dayOfWeek !in allowedDays) continue

            for (minutes in slots) {
                val candidate = ZonedDateTime.of(
                    day.year, day.monthValue, day.dayOfMonth,
                    minutes / 60, minutes % 60, second, 0, zone
                )
                val millis = candidate.toInstant().toEpochMilli()
                if (millis > now) return millis
            }
        }
        return null
    }
}
