package com.example.autocallmanager

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object Diagnostics {
    fun snapshot(context: Context, ttsReady: Boolean): JSONObject {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val callPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val phoneStatePermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val contactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsPermission = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager?.canScheduleExactAlarms() == true }.getOrDefault(false)
        } else true
        val batteryUnrestricted = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { activityManager?.isBackgroundRestricted == true }.getOrDefault(false)
        } else false
        val networkConnected = runCatching {
            connectivity?.activeNetwork != null
        }.getOrDefault(false)
        val simAccounts = if (phoneStatePermission) CallManager.activeSimAccounts(context) else emptyList()
        val callAccountReady = if (phoneStatePermission) CallManager.hasCallCapableAccount(context) else false
        val paused = SettingsStore.pausedAll(context)
        val callState = CallStateManager.describe(CallStateManager.current(context))
        val screenInteractive = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
                power?.isInteractive == true
            } else true
        }.getOrDefault(true)
        val locked = runCatching { keyguard?.isKeyguardLocked == true }.getOrDefault(false)

        return JSONObject().apply {
            put("version", "1.1.0")
            put("screen", if (screenInteractive) "ON" else "OFF")
            put("lock", if (locked) "LOCKED" else "UNLOCKED")
            put("network", if (networkConnected) "CONNECTED" else "OFFLINE")
            put("scheduler", if (paused) "PAUSED" else "READY")
            put("callState", callState)
            put("callPermission", callPermission)
            put("phoneStatePermission", phoneStatePermission)
            put("contactsPermission", contactsPermission)
            put("notificationsPermission", notificationsPermission)
            put("exactAlarm", exactAlarm)
            put("batteryUnrestricted", batteryUnrestricted)
            put("backgroundRestricted", backgroundRestricted)
            put("callAccountReady", callAccountReady)
            put("simCount", simAccounts.size)
            put("ttsReady", ttsReady)
            put("aiStatus", SettingsStore.aiStatus(context))
            put("aiConfigured", SettingsStore.aiApiKey(context).isNotBlank())
            put("adminCatalogConfigured", SettingsStore.adminCatalogUrl(context).isNotBlank())
            // Distinct from adminCatalogConfigured above: that flag is "is the
            // prompt catalog in local or remote mode" (LOCAL_ONLY is a normal,
            // working state for it). This is "can AI Voice calls be placed at
            // all" -- NOT_CONFIGURED here means every AI Voice schedule will
            // fail until a backend URL is set, which is exactly the ambiguity
            // that caused calls to fail with no visible cause in Settings.
            put("aiVoiceBackendConfigured", SettingsStore.aiVoiceBackendConfigured(context))
            put("aiVoiceBackendStatus", SettingsStore.aiVoiceBackendStatus(context))

            // Point-in-time snapshot only -- NOT proof that a real background
            // execution reached any of these states. See getExecutionTraceJson()
            // / Diagnostics -> Execution Trace for the actual observed pipeline.
            val advisory = BackgroundAdvisor.detect(context)
            put("oemManufacturer", advisory.manufacturer)
            put("oemFamily", advisory.family)
            put("oemLikelyExtraRestrictions", advisory.likelyExtraRestrictions)
            put("oemAdvisory", advisory.reason)
        }
    }
}
