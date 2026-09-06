package com.example.autocallmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Contacts lookup for the Phone module's "Contacts" tab.
 *
 * Kept independent from MainActivity.NativeBridge.getContactsJson() (same
 * query shape, small deliberate duplication) rather than refactoring the
 * dashboard's existing bridge to share this: Phase 1 of the master prompt
 * scopes this work to "Home/Dashboard UI ONLY" for the existing file, and
 * the Phone module is meant to stay modular/independent (its own
 * permission timing, its own screen, its own back stack) so it can be
 * changed later without touching the dashboard bridge.
 */
object PhoneContactsRepository {

    private const val MAX_ROWS = 500

    fun load(context: Context): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return arr

        val seen = mutableSetOf<String>()
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < MAX_ROWS) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val number = cursor.getString(2).orEmpty()
                    if (number.isBlank()) continue
                    val key = "$id|$number"
                    if (!seen.add(key)) continue
                    val initials = name.trim().split(Regex("\\s+"))
                        .filter(String::isNotBlank).take(2)
                        .joinToString("") { it.first().uppercaseChar().toString() }
                        .ifBlank { "?" }
                    arr.put(JSONObject().apply {
                        put("id", id)
                        put("name", name.ifBlank { number })
                        put("number", number)
                        put("initials", initials)
                    })
                    count++
                }
            }
            arr
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
