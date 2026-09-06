package com.example.autocallmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Local call-state watcher for the Phone module's dial screen.
 *
 * This is a per-instance class, NOT a routed-through-object singleton like
 * [CallStateManager]. Reusing that shared object here was considered and
 * rejected: CallStateManager.start()/stop() mutate top-level `object`
 * fields, so a second caller registering while MainActivity is still
 * mid-transition (Android starts the next Activity before the previous one
 * receives onStop()) would have MainActivity's later onStop() tear down
 * *this* screen's listener instead of its own. Keeping this module's
 * listener fully independent avoids that cross-Activity race entirely, at
 * the cost of a small amount of duplicated TelephonyCallback/
 * PhoneStateListener plumbing -- the same trade-off already made
 * elsewhere in this codebase (see PhoneContactsRepository).
 *
 * This only reports the raw local telephony call state (IDLE/RINGING/
 * OFFHOOK/UNKNOWN), exactly like CallStateManager. It never infers
 * answered/failed/success from OFFHOOK duration -- see the master-prompt
 * note this project already carries in CHANGELOG_V1.0.9.md about
 * CALL_OFFHOOK_TOO_BRIEF: that duration-based classification lives only in
 * the Scheduler's CallExecutionManager and is intentionally not
 * replicated here. The Phone module only ever shows what the platform
 * actually told it.
 */
class PhoneCallStateWatcher(private val context: Context) {

    enum class State { IDLE, RINGING, OFFHOOK, UNKNOWN }

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null

    fun current(): State {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) return State.UNKNOWN
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return State.UNKNOWN
        return try {
            map(tm.callState)
        } catch (_: SecurityException) {
            State.UNKNOWN
        }
    }

    fun start(onChange: (State) -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onChange(State.UNKNOWN)
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm

        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onChange(map(state))
                }
            }
            callback = cb
            try {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } catch (_: SecurityException) {
                onChange(State.UNKNOWN)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Use TelephonyCallback on Android 12+.")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    onChange(map(state))
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        onChange(current())
    }

    fun stop() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            callback?.let {
                try { tm.unregisterTelephonyCallback(it) } catch (_: Exception) {}
            }
        } else {
            legacyListener?.let {
                @Suppress("DEPRECATION")
                tm.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyManager = null
        callback = null
        legacyListener = null
    }

    private fun map(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> State.IDLE
        TelephonyManager.CALL_STATE_RINGING -> State.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> State.OFFHOOK
        else -> State.UNKNOWN
    }
}
