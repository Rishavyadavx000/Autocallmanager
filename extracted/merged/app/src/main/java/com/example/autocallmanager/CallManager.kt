package com.example.autocallmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object CallManager {
    data class CallResult(
        val ok: Boolean,
        val code: String,
        val message: String
    )

    private const val BASE_NOTIFICATION_ID = 9100
    private const val CHANNEL_SOUND_VIBRATE = "events_sound_vibrate"
    private const val CHANNEL_SOUND = "events_sound"
    private const val CHANNEL_VIBRATE = "events_vibrate"
    private const val CHANNEL_SILENT = "events_silent"

    data class SimAccountOption(
        val handleId: String,
        val component: String,
        val label: String,
        val slotIndex: Int,
        val subscriptionId: Int
    )

    fun hasCallCapableAccount(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE)
                as? TelecomManager ?: return@runCatching false
            telecom.callCapablePhoneAccounts.isNotEmpty()
        }.getOrDefault(false)
    }

    fun activeSimAccounts(context: Context): List<SimAccountOption> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager ?: return@runCatching emptyList()
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return@runCatching emptyList()
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return@runCatching emptyList()
            val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
            val callAccounts = telecom.callCapablePhoneAccounts
            if (subscriptions.size < 2 || callAccounts.size < 2) return@runCatching emptyList()

            subscriptions.sortedBy { it.simSlotIndex }.mapNotNull { sub ->
                val subId = sub.subscriptionId.toString()
                val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    callAccounts.firstOrNull { runCatching { telephony.getSubscriptionId(it) }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID) == sub.subscriptionId }
                } else {
                    callAccounts.firstOrNull { it.id == subId }
                } ?: return@mapNotNull null
                // Avoid the unnecessary Telecom phone-account metadata lookup on target SDK 31+
                // it requires READ_PHONE_NUMBERS, which is unnecessary for this app.
                // SubscriptionInfo.displayName is sufficient for the user-facing label.
                val provider = sub.displayName?.toString()?.trim().orEmpty()
                val label = listOf("SIM ${sub.simSlotIndex + 1}", provider)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                SimAccountOption(
                    handleId = handle.id,
                    component = handle.componentName.flattenToString(),
                    label = label,
                    slotIndex = sub.simSlotIndex,
                    subscriptionId = sub.subscriptionId
                )
            }.distinctBy { it.handleId }.take(2)
        }.getOrDefault(emptyList())
    }

    // Digits, an optional leading '+', and common separators users paste in
    // from Contacts/WhatsApp. '+' is only permitted at the very start.
    private val PHONE_ALLOWED_CHARS = Regex("^\\+?[0-9()\\-.\\s]+$")

    /**
     * Format check for a task's `number` before it is ever handed to the
     * dialer. This runs at schedule-save time (immediate feedback) and again
     * immediately before [placeCallDetailed] (defense-in-depth): the call in
     * that path fires from an unattended alarm receiver, often while the
     * device is locked/screen-off, so nothing else is watching to catch a
     * corrupted or malformed number before it reaches TelecomManager.
     * Deliberately permissive on punctuation, strict on character set and
     * digit count (6-15 digits, matching the E.164 maximum length).
     */
    fun isValidPhoneNumber(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return false
        if (!PHONE_ALLOWED_CHARS.matches(trimmed)) return false
        val digitCount = trimmed.count { it.isDigit() }
        return digitCount in 6..15
    }

    fun placeCall(
        context: Context,
        number: String,
        phoneAccountComponent: String = "",
        phoneAccountId: String = ""
    ): Boolean = placeCallDetailed(
        context, number, phoneAccountComponent, phoneAccountId
    ).ok

    /**
     * [taskId]/[executionId]/[attempt] are purely for ExecutionTraceStore
     * correlation -- passing "" / 0 (the defaults) is fine for any caller
     * that doesn't care about tracing and only changes logging, never the
     * call-placement behavior itself.
     */
    fun placeCallDetailed(
        context: Context,
        number: String,
        phoneAccountComponent: String = "",
        phoneAccountId: String = "",
        taskId: Long = 0L,
        executionId: String = "",
        attempt: Int = 0,
        callSource: String = "SCHEDULED"
    ): CallResult {
        val normalized = number.trim()
        if (normalized.isBlank()) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                "empty phone number", callSource = callSource
            )
            return CallResult(false, "INVALID_NUMBER", "Phone number is empty")
        }
        if (!isValidPhoneNumber(normalized)) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                "invalid phone number format", callSource = callSource
            )
            return CallResult(false, "INVALID_NUMBER", "Phone number format is invalid")
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                "CALL_PHONE permission not granted", callSource = callSource
            )
            return CallResult(false, "CALL_PERMISSION_MISSING", "Call permission is required")
        }

        when (CallStateManager.current(context)) {
            CallStateManager.State.RINGING, CallStateManager.State.OFFHOOK -> {
                ExecutionTraceStore.log(
                    context, taskId, executionId, attempt,
                    ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                    "device call state busy - another cellular call is already active",
                    callSource = callSource
                )
                return CallResult(false, "DEVICE_CALL_BUSY", "Another cellular call is already active")
            }
            else -> Unit
        }

        var accountLabel = "DEFAULT"
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE)
                as? TelecomManager
                ?: run {
                    ExecutionTraceStore.log(
                        context, taskId, executionId, attempt,
                        ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                        "TelecomManager service unavailable", phoneAccount = accountLabel, callSource = callSource
                    )
                    return CallResult(false, "TELECOM_UNAVAILABLE", "Telecom service is unavailable")
                }

            val extras = android.os.Bundle()
            if (phoneAccountComponent.isNotBlank() && phoneAccountId.isNotBlank()) {
                // getCallCapablePhoneAccounts() and isOutgoingCallPermitted()
                // below both read telecom/SIM state and are conditionally
                // permission-gated the same way activeSimAccounts()/
                // hasCallCapableAccount() already are elsewhere in this
                // object -- this function just never had its own check.
                // Practically this permission is already guaranteed here
                // (a task can only have simSelectionEnabled=true if
                // activeSimAccounts() succeeded when the SIM was chosen),
                // but Android allows revoking a granted permission later,
                // and lint has no way to know that without an explicit
                // check in this exact function.
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ExecutionTraceStore.log(
                        context, taskId, executionId, attempt,
                        ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                        "READ_PHONE_STATE permission not granted for SIM account resolution",
                        phoneAccount = accountLabel, callSource = callSource
                    )
                    return CallResult(false, "SIM_ACCOUNT_UNAVAILABLE", "Reading SIM account state requires phone-state permission")
                }
                val component = ComponentName.unflattenFromString(phoneAccountComponent)
                    ?: run {
                        ExecutionTraceStore.log(
                            context, taskId, executionId, attempt,
                            ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                            "selected SIM account identifier could not be parsed", phoneAccount = accountLabel,
                            callSource = callSource
                        )
                        return CallResult(false, "SIM_ACCOUNT_INVALID", "Selected SIM account is invalid")
                    }
                val handle = PhoneAccountHandle(component, phoneAccountId)

                if (!telecom.callCapablePhoneAccounts.contains(handle)) {
                    ExecutionTraceStore.log(
                        context, taskId, executionId, attempt,
                        ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                        "selected SIM account is no longer in the call-capable account list",
                        phoneAccount = accountLabel, callSource = callSource
                    )
                    return CallResult(false, "SIM_ACCOUNT_UNAVAILABLE", "Selected SIM is no longer available")
                }

                val permitted = runCatching {
                    telecom.isOutgoingCallPermitted(handle)
                }.getOrDefault(true)

                if (!permitted) {
                    ExecutionTraceStore.log(
                        context, taskId, executionId, attempt,
                        ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                        "Telecom.isOutgoingCallPermitted() returned false", phoneAccount = accountLabel,
                        callSource = callSource
                    )
                    return CallResult(false, "CALL_NOT_PERMITTED", "Telecom did not permit this call")
                }

                extras.putParcelable(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    handle
                )
                // Masked: a slot label ("SIM 1 (GSM)"), never the raw account id.
                accountLabel = maskedAccountLabel(context, handle)
            }
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PHONE_ACCOUNT_RESOLVED, ExecutionTraceStore.PASS,
                phoneAccount = accountLabel, callSource = callSource
            )

            telecom.placeCall(
                Uri.fromParts("tel", normalized, null),
                extras
            )

            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.PASS,
                "TelecomManager.placeCall() returned normally", phoneAccount = accountLabel,
                callSource = callSource
            )
            CallResult(true, "CALL_REQUEST_SENT", "Outgoing call request sent")
        } catch (e: SecurityException) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                "SecurityException: ${e.message}", phoneAccount = accountLabel, callSource = callSource
            )
            CallResult(false, "SECURITY_ERROR", "Android denied the call request")
        } catch (e: Exception) {
            ExecutionTraceStore.log(
                context, taskId, executionId, attempt,
                ExecutionTraceStore.Stage.PLACE_CALL, ExecutionTraceStore.FAIL,
                "${e.javaClass.simpleName}: ${e.message}", phoneAccount = accountLabel, callSource = callSource
            )
            CallResult(
                false,
                "CALL_ERROR",
                e.message?.take(180).orEmpty().ifBlank { "Unable to place call" }
            )
        }
    }

    /** Slot-based label only ("SIM 1 (GSM)") -- deliberately never the raw PhoneAccountHandle id. */
    private fun maskedAccountLabel(context: Context, handle: PhoneAccountHandle): String = runCatching {
        val match = activeSimAccounts(context).firstOrNull { it.handleId == handle.id }
        match?.let { "SIM ${it.slotIndex + 1}" } ?: "SIM(unmatched)"
    }.getOrDefault("SIM(unknown)")

    fun openDialer(context: Context, number: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Exception) { false }

    fun openMessageComposer(context: Context, number: String, message: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Exception) { false }

    fun showNotification(context: Context, task: CallTask, message: String) {
        if (!task.notificationEnabled) return
        ensureChannels(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        // areNotificationsEnabled() above is a real runtime check, but lint's
        // MissingPermission detector only recognizes an explicit
        // checkSelfPermission()/SDK-gate immediately guarding the notify()
        // call itself -- it does not trace areNotificationsEnabled() as
        // equivalent, so the check below is required for a clean lintDebug
        // even though it's functionally redundant with the one above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val channelId = when {
            SettingsStore.notificationSound(context) && SettingsStore.notificationVibrate(context) -> CHANNEL_SOUND_VIBRATE
            SettingsStore.notificationSound(context) -> CHANNEL_SOUND
            SettingsStore.notificationVibrate(context) -> CHANNEL_VIBRATE
            else -> CHANNEL_SILENT
        }
        val notificationId = notificationId(task.id)
        val pending = PendingIntent.getActivity(
            context,
            notificationId + 1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(task.number)}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = task.contactName.ifBlank { task.number }
        NotificationManagerCompat.from(context).notify(
            notificationId,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_call)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_notification_call, "Open dialer", pending)
                .build()
        )
    }

    fun showPreNotification(context: Context, task: CallTask) {
        showNotification(context, task, "Upcoming call in 5 minutes • ${task.contactName.ifBlank { task.number }}")
    }


    fun stopVoiceService(context: Context) {
        runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
    }

    fun openAppSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Exception) { false }

    fun showCallPermissionError(context: Context) {
        Toast.makeText(context, "Call permission is required.", Toast.LENGTH_LONG).show()
    }

    fun showCallError(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try { pm.isIgnoringBatteryOptimizations(context.packageName) } catch (_: Exception) { false }
    }

    fun openBatterySettings(context: Context): Boolean = try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryUnrestricted(context)) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else Intent(Settings.ACTION_SETTINGS)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    fun openNotificationSettings(context: Context): Boolean = try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    private fun notificationId(id: Long): Int = BASE_NOTIFICATION_ID + ((id xor (id ushr 32)).toInt() and 0x3FFF)

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager, CHANNEL_SOUND_VIBRATE, "Events • Sound + Vibration", true, true)
        createChannel(manager, CHANNEL_SOUND, "Events • Sound", true, false)
        createChannel(manager, CHANNEL_VIBRATE, "Events • Vibration", false, true)
        createChannel(manager, CHANNEL_SILENT, "Events • Silent", false, false)
    }

    private fun createChannel(manager: NotificationManager, id: String, name: String, sound: Boolean, vibrate: Boolean) {
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(vibrate)
            vibrationPattern = if (vibrate) longArrayOf(0, 250, 150, 250) else null
            if (sound) {
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
            } else setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}
