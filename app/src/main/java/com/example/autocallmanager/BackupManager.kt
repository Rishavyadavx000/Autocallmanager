package com.example.autocallmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    private const val BACKUP_VERSION = "1.1.0"

    fun exportJson(context: Context): String {
        val root = JSONObject().apply {
            put("app", "AutoCallManager")
            put("version", BACKUP_VERSION)
            put("createdAt", System.currentTimeMillis())
        }

        val settings = JSONObject().apply {
            put("defaultAttempts", SettingsStore.defaultAttempts(context))
            put("defaultRetrySeconds", SettingsStore.defaultRetrySeconds(context))
            put("voiceLanguage", SettingsStore.voiceLanguage(context))
            put("voiceSpeed", SettingsStore.voiceSpeed(context))
            put("voicePitch", SettingsStore.voicePitch(context))
            put("aiEnabled", SettingsStore.aiEnabled(context))
            put("aiModel", SettingsStore.aiModel(context))
            put("aiLocalDailyLimit", SettingsStore.aiLocalDailyLimit(context))
            // API key intentionally excluded from backups.
            put("notificationSound", SettingsStore.notificationSound(context))
            put("notificationVibrate", SettingsStore.notificationVibrate(context))
            put("theme", SettingsStore.theme(context))
            put("accent", SettingsStore.accent(context))
            put("fontSize", SettingsStore.fontSize(context))
            put("appLock", SettingsStore.appLock(context))
            put("pausedAll", SettingsStore.pausedAll(context))
        }
        root.put("settings", settings)

        val tasks = JSONArray()
        TaskStore.load(context).forEach { task ->
            tasks.put(taskToJson(task))
        }
        root.put("tasks", tasks)
        root.put("history", TaskStore.history(context))
        return root.toString(2)
    }

    fun restoreJson(context: Context, json: String): ResultSummary {
        val root = JSONObject(json)
        val version = root.optString("version", "legacy")
        val tasksArray = root.optJSONArray("tasks") ?: JSONArray()
        val restoredTasks = parseTasks(tasksArray)

        if (root.has("settings")) {
            val s = root.optJSONObject("settings") ?: JSONObject()
            setString(context, "defaultAttempts", s.optInt("defaultAttempts", 5).toString())
            setString(context, "defaultRetrySeconds", s.optInt("defaultRetrySeconds", 300).toString())
            setString(context, "voiceLanguage", s.optString("voiceLanguage", "Hinglish"))
            setString(context, "voiceSpeed", s.optDouble("voiceSpeed", 1.0).toString())
            setString(context, "voicePitch", s.optDouble("voicePitch", 1.0).toString())
            setString(context, "aiEnabled", s.optBoolean("aiEnabled", false).toString())
            setString(context, "aiEndpoint", s.optString("aiEndpoint", ""))
            setString(context, "aiModel", s.optString("aiModel", AiDefaults.DEFAULT_MODEL))
            setString(context, "aiLocalDailyLimit", s.optInt("aiLocalDailyLimit", 0).toString())
            // Existing Gemini API key is retained locally and is never restored from a backup.
            setString(context, "notificationSound", s.optBoolean("notificationSound", true).toString())
            setString(context, "notificationVibrate", s.optBoolean("notificationVibrate", true).toString())
            setString(context, "theme", s.optString("theme", "dark"))
            setString(context, "accent", s.optString("accent", "cyan"))
            setString(context, "fontSize", s.optString("fontSize", "M"))
            setString(context, "appLock", "false")
            setString(context, "pausedAll", "false")
        }

        TaskStore.save(context, restoredTasks)
        val history = root.optJSONArray("history")
        if (history != null) TaskStore.replaceHistory(context, history)

        // Rebuild alarms from restored state.
        restoredTasks.forEach { task ->
            CallScheduler.cancel(context, task.id)
            if (task.status == "Scheduled" && task.nextTrigger > System.currentTimeMillis()) {
                CallScheduler.schedule(context, task.id, task.nextTrigger, task.attemptCount + 1)
                CallScheduler.schedulePreNotification(context, task)
            }
        }

        return ResultSummary(version, restoredTasks.size, history?.length() ?: 0)
    }

    private fun setString(context: Context, key: String, value: String) {
        runCatching { SettingsStore.set(context, key, value) }
    }

    private fun parseTasks(arr: JSONArray): MutableList<CallTask> {
        val out = mutableListOf<CallTask>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val recurrence = runCatching {
                RecurrenceMode.valueOf(o.optString("recurrence", "ONCE"))
            }.getOrDefault(RecurrenceMode.ONCE)
            val repeatDays = o.optString("repeatDays", "")
                .split(",")
                .mapNotNull(String::toIntOrNull)
                .filter { it in 1..7 }
                .toSet()
            val slots = o.optString("timeSlots", "")
                .split(",")
                .mapNotNull(String::toIntOrNull)
                .filter { it in 0..1439 }
                .distinct()
                .sorted()
            out += CallTask(
                id = o.optLong("id", System.currentTimeMillis() + i),
                number = o.optString("number"),
                contactName = o.optString("contactName"),
                triggerAt = o.optLong("triggerAt"),
                maxAttempts = o.optInt("maxAttempts", 1).coerceIn(1, 50),
                retryIntervalSeconds = o.optInt("retryIntervalSeconds", 300).coerceAtLeast(20),
                attemptCount = o.optInt("attemptCount", 0).coerceIn(0, 50),
                status = o.optString("status", "Stopped"),
                nextTrigger = o.optLong("nextTrigger", -1L),
                liveStatus = o.optString("liveStatus", "RESTORED"),
                lastResult = o.optString("lastResult", "RESTORED"),
                callEnabled = o.optBoolean("callEnabled", true),
                voiceEnabled = o.optBoolean("voiceEnabled", false),
                messageEnabled = o.optBoolean("messageEnabled", false),
                notificationEnabled = o.optBoolean("notificationEnabled", true),
                notifyBefore = o.optBoolean("notifyBefore", true),
                notifyAt = o.optBoolean("notifyAt", true),
                notifyAfter = o.optBoolean("notifyAfter", true),
                messageText = o.optString("messageText", o.optString("voiceMessage", "")),
                promptId = o.optString("promptId", PromptStore.DEFAULT_PROMPT_ID),
                promptVersion = o.optInt("promptVersion", 1).coerceAtLeast(1),
                voiceLanguage = o.optString("voiceLanguage", "Hinglish").let { if (it in setOf("Hindi", "Hinglish", "English")) it else "Hinglish" },
                voiceSpeed = o.optDouble("voiceSpeed", 1.0).toFloat().coerceIn(0.5f, 2.0f),
                voicePitch = o.optDouble("voicePitch", 1.0).toFloat().coerceIn(0.5f, 1.5f),
                groupName = o.optString("groupName", ""),
                recurrence = recurrence,
                repeatDays = repeatDays,
                timeSlots = slots,
                activeSlotIndex = o.optInt("activeSlotIndex", 0),
                simSelectionEnabled = o.optBoolean("simSelectionEnabled", false),
                phoneAccountId = o.optString("phoneAccountId", ""),
                phoneAccountComponent = o.optString("phoneAccountComponent", ""),
                phoneAccountLabel = o.optString("phoneAccountLabel", ""),
                timezoneId = o.optString("timezoneId", java.util.TimeZone.getDefault().id).ifBlank { java.util.TimeZone.getDefault().id },
                executionMode = o.optString("executionMode", "NORMAL_SIM").let { if (it == "AI_VOICE") "AI_VOICE" else "NORMAL_SIM" }
            )
        }
        return out
    }

    private fun taskToJson(t: CallTask) = JSONObject().apply {
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
        put("repeatDays", t.repeatDays.sorted().joinToString(","))
        put("timeSlots", t.timeSlots.sorted().joinToString(","))
        put("activeSlotIndex", t.activeSlotIndex)
        put("simSelectionEnabled", t.simSelectionEnabled)
        put("phoneAccountId", t.phoneAccountId)
        put("phoneAccountComponent", t.phoneAccountComponent)
        put("phoneAccountLabel", t.phoneAccountLabel)
        put("timezoneId", t.timezoneId)
        put("executionMode", t.executionMode)
    }

    data class ResultSummary(
        val version: String,
        val tasks: Int,
        val history: Int
    )
}
