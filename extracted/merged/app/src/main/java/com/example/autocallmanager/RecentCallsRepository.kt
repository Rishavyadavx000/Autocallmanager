package com.example.autocallmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only access to the system Call Log for the Phone module's "Recent"
 * tab (PHASE 2 / RECENT CALLS in the master prompt).
 *
 * This is intentionally separate from anything the Scheduler/AI Voice path
 * uses -- it never writes to CallLog and never feeds a call outcome back
 * into TaskStore/CallExecutionManager, so it cannot affect scheduled-call
 * classification. It only reads the log so the user can see and re-dial
 * recent calls, exactly like a normal phone app's call list.
 *
 * Deliberately NOT calling a shared has()-style wrapper before the query:
 * same lint data-flow rationale already documented next to
 * MainActivity.getContactsJson() and CallStateManager.current() -- lint's
 * MissingPermission check only recognizes an explicit checkSelfPermission()
 * call directly guarding the call it protects.
 */
object RecentCallsRepository {

    private const val MAX_ROWS = 200

    /** [filter] is "all" or "missed"; anything else behaves like "all". */
    fun load(context: Context, filter: String): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) return arr

        val wantMissedOnly = filter.equals("missed", ignoreCase = true)
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT $MAX_ROWS"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (cursor.moveToNext()) {
                    val typeValue = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                    val direction = directionOf(typeValue)
                    if (wantMissedOnly && direction != "missed" && direction != "rejected") continue

                    val number = if (numberIdx >= 0) cursor.getString(numberIdx).orEmpty() else ""
                    if (number.isBlank() || number == "-1") continue // hidden/unknown caller rows

                    arr.put(JSONObject().apply {
                        put("id", if (idIdx >= 0) cursor.getLong(idIdx) else 0L)
                        put("name", (if (nameIdx >= 0) cursor.getString(nameIdx) else null).orEmpty())
                        put("number", number)
                        put("direction", direction)
                        put("time", if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L)
                        put("durationSeconds", if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L)
                    })
                }
            }
            arr
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun directionOf(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }
}
