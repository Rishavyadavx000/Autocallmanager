package com.example.autocallmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object CallStateManager {
    enum class State { IDLE, RINGING, OFFHOOK, UNKNOWN }

    private var manager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private var legacy: PhoneStateListener? = null

    // Inlined at every call site rather than routed through a shared allowed()
    // helper: lint's MissingPermission data-flow analysis only recognizes an
    // explicit checkSelfPermission() call that directly/immediately guards the
    // flagged call in the SAME function, not a boolean returned by a separate
    // wrapper function (same rationale as MainActivity.getContactsJson() and
    // CallManager.showNotification()).
    fun current(context: Context): State {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) return State.UNKNOWN
        val tm =
            context.getSystemService(Context.TELEPHONY_SERVICE)
                as? TelephonyManager ?: return State.UNKNOWN
        return try { map(tm.callState) }
        catch (_: SecurityException) { State.UNKNOWN }
    }

    fun start(
        context: Context,
        onChange: (State) -> Unit
    ) {
        stop()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onChange(State.UNKNOWN)
            return
        }

        val tm =
            context.getSystemService(Context.TELEPHONY_SERVICE)
                as? TelephonyManager ?: return

        manager = tm

        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object :
                TelephonyCallback(),
                TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onChange(map(state))
                }
            }
            callback = cb
            try {
                tm.registerTelephonyCallback(
                    context.mainExecutor,
                    cb
                )
            } catch (_: SecurityException) {
                onChange(State.UNKNOWN)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Use TelephonyCallback on Android 12+.")
                override fun onCallStateChanged(
                    state: Int,
                    phoneNumber: String?
                ) {
                    onChange(map(state))
                }
            }
            legacy = listener
            @Suppress("DEPRECATION")
            tm.listen(
                listener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
        }

        onChange(current(context))
    }

    fun stop() {
        val tm = manager ?: return

        if (Build.VERSION.SDK_INT >= 31) {
            callback?.let {
                try {
                    tm.unregisterTelephonyCallback(it)
                } catch (_: Exception) {}
            }
        } else {
            legacy?.let {
                @Suppress("DEPRECATION")
                tm.listen(
                    it,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        }

        manager = null
        callback = null
        legacy = null
    }

    fun describe(state: State) =
        when (state) {
            State.IDLE -> "IDLE • no local call active"
            State.RINGING -> "RINGING • local device state"
            State.OFFHOOK ->
                "OFFHOOK • dialing/active/on-hold may appear here"
            State.UNKNOWN -> "UNKNOWN • unavailable"
        }

    private fun map(state: Int) =
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> State.IDLE
            TelephonyManager.CALL_STATE_RINGING -> State.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> State.OFFHOOK
            else -> State.UNKNOWN
        }
}
