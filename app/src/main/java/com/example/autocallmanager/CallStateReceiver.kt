package com.example.autocallmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Receives phone-state broadcasts while the Activity is not visible.
 * MainActivity/CallStateManager remains responsible only for foreground UI.
 *
 * Note: TelephonyManager's global PHONE_STATE broadcast is not a per-call
 * connection-state API. RINGING means an incoming call, so it is intentionally
 * ignored for our scheduled outgoing-call execution tracker. OFFHOOK/IDLE are
 * used only to confirm that the device entered and later left an off-hook state.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                CallExecutionManager.onTelephonyState(context, CallStateManager.State.OFFHOOK)
            TelephonyManager.EXTRA_STATE_IDLE ->
                CallExecutionManager.onTelephonyState(context, CallStateManager.State.IDLE)
            // Ignore incoming-call RINGING so it cannot be mistaken for the
            // remote party ringing during an outgoing scheduled call.
            TelephonyManager.EXTRA_STATE_RINGING -> Unit
        }
    }
}
