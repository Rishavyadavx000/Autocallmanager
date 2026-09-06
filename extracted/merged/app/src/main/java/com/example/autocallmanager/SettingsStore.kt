package com.example.autocallmanager

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SettingsStore {
    private const val PREFS = "auto_call_manager_v104"
    private const val DEFAULT_ATTEMPTS = "defaultAttempts"
    private const val DEFAULT_RETRY = "defaultRetrySeconds"
    private const val VOICE_LANGUAGE = "voiceLanguage"
    private const val VOICE_SPEED = "voiceSpeed"
    private const val VOICE_PITCH = "voicePitch"
    private const val AI_ENABLED = "aiEnabled"
    private const val AI_ENDPOINT = "aiEndpoint" // legacy/custom endpoint kept for migration
    private const val AI_API_KEY = "aiApiKey" // legacy plaintext key key name
    private const val AI_API_KEY_ENCRYPTED = "aiApiKeyEncrypted"
    private const val AI_MODEL = "aiModel"
    private const val AI_LAST_OK = "aiLastOk"
    private const val AI_LAST_TEST = "aiLastTest"
    private const val AI_LAST_ERROR = "aiLastError"
    private const val AI_STATUS = "aiStatus"
    private const val AI_USAGE_DATE = "aiUsageDate"
    private const val AI_USAGE_COUNT = "aiUsageCount"
    private const val AI_LOCAL_DAILY_LIMIT = "aiLocalDailyLimit"
    private const val ADMIN_CATALOG_URL = "adminCatalogUrl"
    private const val ADMIN_APP_KEY = "adminCatalogAppKey"
    private const val ADMIN_APP_KEY_ENCRYPTED = "adminCatalogAppKeyEncrypted"
    private const val PROMPTS_LAST_SYNC = "promptsLastSync"
    private const val PROMPTS_SYNC_STATUS = "promptsSyncStatus"
    private const val PROMPTS_SYNC_ERROR = "promptsSyncError"
    // AI Voice Backend has its own status, deliberately kept separate from
    // PROMPTS_SYNC_STATUS above even though both features read the same
    // ADMIN_CATALOG_URL -- see AiVoiceCallClient's class doc for why sharing
    // the URL is intentional but sharing the status would not be.
    private const val AI_VOICE_BACKEND_STATUS = "aiVoiceBackendStatus"
    private const val AI_VOICE_BACKEND_LAST_TEST = "aiVoiceBackendLastTest"
    private const val AI_VOICE_BACKEND_LAST_ERROR = "aiVoiceBackendLastError"
    private const val NOTIF_SOUND = "notificationSound"
    private const val NOTIF_VIBRATE = "notificationVibrate"
    private const val THEME = "theme"
    private const val ACCENT = "accent"
    private const val FONT_SIZE = "fontSize"
    private const val APP_LOCK = "appLock"
    private const val PIN = "pin"
    private const val PAUSED = "pausedAll"
    private const val LAST_BACKUP = "lastBackup"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "AutoCallManager.AIKey.v104"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultAttempts(context: Context) = prefs(context).getInt(DEFAULT_ATTEMPTS, 5)
    fun defaultRetrySeconds(context: Context) = prefs(context).getInt(DEFAULT_RETRY, 300)
    fun voiceLanguage(context: Context) = prefs(context).getString(VOICE_LANGUAGE, "Hinglish") ?: "Hinglish"
    fun voiceSpeed(context: Context) = prefs(context).getFloat(VOICE_SPEED, 1.0f)
    fun voicePitch(context: Context) = prefs(context).getFloat(VOICE_PITCH, 1.0f)
    fun aiEnabled(context: Context) = prefs(context).getBoolean(AI_ENABLED, false)
    fun aiEndpoint(context: Context) = prefs(context).getString(AI_ENDPOINT, "") ?: ""
    fun aiModel(context: Context) = prefs(context).getString(AI_MODEL, AiDefaults.DEFAULT_MODEL) ?: AiDefaults.DEFAULT_MODEL
    fun aiLastOk(context: Context) = prefs(context).getBoolean(AI_LAST_OK, false)
    fun aiLastTest(context: Context) = prefs(context).getLong(AI_LAST_TEST, 0L)
    fun aiLastError(context: Context) = prefs(context).getString(AI_LAST_ERROR, "") ?: ""
    fun aiStoredStatus(context: Context) = prefs(context).getString(AI_STATUS, "") ?: ""
    fun notificationSound(context: Context) = prefs(context).getBoolean(NOTIF_SOUND, true)
    fun notificationVibrate(context: Context) = prefs(context).getBoolean(NOTIF_VIBRATE, true)
    fun theme(context: Context) = prefs(context).getString(THEME, "dark") ?: "dark"
    fun accent(context: Context) = prefs(context).getString(ACCENT, "cyan") ?: "cyan"
    fun fontSize(context: Context) = prefs(context).getString(FONT_SIZE, "M") ?: "M"
    fun appLock(context: Context) = prefs(context).getBoolean(APP_LOCK, false)
    fun hasPin(context: Context) = prefs(context).getString(PIN, "").orEmpty().isNotBlank()
    fun pausedAll(context: Context) = prefs(context).getBoolean(PAUSED, false)
    fun lastBackup(context: Context) = prefs(context).getLong(LAST_BACKUP, 0L)
    fun adminCatalogUrl(context: Context) = prefs(context).getString(ADMIN_CATALOG_URL, "") ?: ""
    fun promptsLastSync(context: Context) = prefs(context).getLong(PROMPTS_LAST_SYNC, 0L)
    fun promptsSyncError(context: Context) = prefs(context).getString(PROMPTS_SYNC_ERROR, "") ?: ""

    fun promptsSyncStatus(context: Context): String {
        if (adminCatalogUrl(context).isBlank()) return "LOCAL_ONLY"
        val stored = prefs(context).getString(PROMPTS_SYNC_STATUS, "").orEmpty().uppercase()
        return stored.ifBlank { "NOT_SYNCED" }
    }

    /** Records the outcome of a PromptStore.refreshFromRemote() attempt. Not user-settable via [set]. */
    fun setPromptsSyncResult(context: Context, ok: Boolean, error: String = "") {
        val editor = prefs(context).edit()
        if (ok) editor.putLong(PROMPTS_LAST_SYNC, System.currentTimeMillis())
        editor.putString(PROMPTS_SYNC_STATUS, if (ok) "SYNCED" else "ERROR")
        editor.putString(PROMPTS_SYNC_ERROR, error.take(300))
        editor.apply()
    }

    /**
     * True only when a backend base URL is set. This is the AI Voice Call
     * feature's own "configured" flag -- independent of promptsSyncStatus(),
     * which answers the same "is adminCatalogUrl blank" question for the
     * unrelated prompt-catalog feature. Both read [adminCatalogUrl] because
     * it is genuinely the same server (see AiVoiceCallClient), but neither
     * feature's status should be inferred from the other's.
     */
    fun aiVoiceBackendConfigured(context: Context): Boolean = adminCatalogUrl(context).isNotBlank()

    fun aiVoiceBackendLastTest(context: Context) = prefs(context).getLong(AI_VOICE_BACKEND_LAST_TEST, 0L)
    fun aiVoiceBackendLastError(context: Context) = prefs(context).getString(AI_VOICE_BACKEND_LAST_ERROR, "") ?: ""

    /** NOT_CONFIGURED (no URL) / NOT_TESTED (URL set, never checked) / the last checkHealth()/create() outcome otherwise. */
    fun aiVoiceBackendStatus(context: Context): String {
        if (adminCatalogUrl(context).isBlank()) return "NOT_CONFIGURED"
        val stored = prefs(context).getString(AI_VOICE_BACKEND_STATUS, "").orEmpty().uppercase()
        return stored.ifBlank { "NOT_TESTED" }
    }

    /** Records the outcome of an AiVoiceCallClient.checkHealth() call. Not user-settable via [set]. */
    fun setAiVoiceBackendTestResult(context: Context, status: String, error: String = "") {
        prefs(context).edit()
            .putLong(AI_VOICE_BACKEND_LAST_TEST, System.currentTimeMillis())
            .putString(AI_VOICE_BACKEND_STATUS, status.uppercase())
            .putString(AI_VOICE_BACKEND_LAST_ERROR, error.take(300))
            .apply()
    }

    fun aiApiKey(context: Context): String {
        val p = prefs(context)
        val encrypted = p.getString(AI_API_KEY_ENCRYPTED, "").orEmpty()
        if (encrypted.isNotBlank()) return decrypt(encrypted).orEmpty()

        // One-time migration from the older plaintext preference.
        val legacy = p.getString(AI_API_KEY, "").orEmpty()
        if (legacy.isNotBlank()) {
            runCatching { setAiApiKey(context, legacy) }
            p.edit().remove(AI_API_KEY).apply()
        }
        return legacy
    }

    fun setAiApiKey(context: Context, value: String): Boolean {
        val trimmed = value.trim()
        return if (trimmed.isBlank()) {
            prefs(context).edit().remove(AI_API_KEY_ENCRYPTED).remove(AI_API_KEY).apply()
            true
        } else {
            val encrypted = runCatching { encrypt(trimmed) }.getOrElse { return false }
            prefs(context).edit()
                .putString(AI_API_KEY_ENCRYPTED, encrypted)
                .remove(AI_API_KEY)
                .putBoolean(AI_LAST_OK, false)
                .putLong(AI_LAST_TEST, 0L)
                .putString(AI_LAST_ERROR, "")
                .putString(AI_STATUS, "NOT_TESTED")
                .apply()
            true
        }
    }

    fun adminCatalogAppKey(context: Context): String {
        val encrypted = prefs(context).getString(ADMIN_APP_KEY_ENCRYPTED, "").orEmpty()
        if (encrypted.isBlank()) return ""
        return decrypt(encrypted).orEmpty()
    }

    fun adminCatalogAppKeyConfigured(context: Context): Boolean =
        prefs(context).getString(ADMIN_APP_KEY_ENCRYPTED, "").orEmpty().isNotBlank()

    fun setAdminCatalogAppKey(context: Context, value: String): Boolean {
        val trimmed = value.trim()
        return if (trimmed.isBlank()) {
            prefs(context).edit().remove(ADMIN_APP_KEY_ENCRYPTED).apply()
            true
        } else {
            val encrypted = runCatching { encrypt(trimmed) }.getOrElse { return false }
            prefs(context).edit().putString(ADMIN_APP_KEY_ENCRYPTED, encrypted).apply()
            true
        }
    }

    fun set(context: Context, key: String, value: String): Boolean {
        if (key == AI_API_KEY) return setAiApiKey(context, value)
        if (key == ADMIN_APP_KEY) return setAdminCatalogAppKey(context, value)
        val editor = prefs(context).edit()
        when (key) {
            DEFAULT_ATTEMPTS -> editor.putInt(DEFAULT_ATTEMPTS, value.toIntOrNull()?.coerceIn(1, 50) ?: return false)
            DEFAULT_RETRY -> editor.putInt(DEFAULT_RETRY, value.toIntOrNull()?.coerceAtLeast(20) ?: return false)
            VOICE_LANGUAGE -> {
                if (value !in setOf("Hindi", "Hinglish", "English")) return false
                editor.putString(VOICE_LANGUAGE, value)
            }
            VOICE_SPEED -> editor.putFloat(VOICE_SPEED, value.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: return false)
            VOICE_PITCH -> editor.putFloat(VOICE_PITCH, value.toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: return false)
            AI_ENABLED -> {
                val enabled = value.toBooleanStrictOrNull() ?: return false
                editor.putBoolean(AI_ENABLED, enabled)
                if (!enabled) {
                    editor.putBoolean(AI_LAST_OK, false)
                    editor.putLong(AI_LAST_TEST, 0L)
                    editor.putString(AI_STATUS, "DISABLED")
                }
            }
            AI_ENDPOINT -> {
                if (value.isNotBlank()) {
                    val parsed = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
                    if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank()) return false
                }
                editor.putString(AI_ENDPOINT, value.trim())
                editor.putBoolean(AI_LAST_OK, false)
                editor.putLong(AI_LAST_TEST, 0L)
                editor.putString(AI_STATUS, "NOT_TESTED")
            }
            AI_MODEL -> {
                if (value !in AiDefaults.SUPPORTED_MODELS) return false
                editor.putString(AI_MODEL, value)
                editor.putBoolean(AI_LAST_OK, false)
                editor.putLong(AI_LAST_TEST, 0L)
                editor.putString(AI_STATUS, "NOT_TESTED")
            }
            AI_LOCAL_DAILY_LIMIT -> editor.putInt(AI_LOCAL_DAILY_LIMIT, value.toIntOrNull()?.coerceIn(0, 100000) ?: return false)
            ADMIN_CATALOG_URL -> {
                if (value.isNotBlank()) {
                    val parsed = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
                    if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank()) return false
                }
                editor.putString(ADMIN_CATALOG_URL, value.trim().trimEnd('/'))
                editor.putLong(PROMPTS_LAST_SYNC, 0L)
                editor.putString(PROMPTS_SYNC_STATUS, if (value.isBlank()) "LOCAL_ONLY" else "NOT_SYNCED")
                editor.putString(PROMPTS_SYNC_ERROR, "")
                // Same shared URL, but AI Voice Backend tracks its own cached
                // status (see aiVoiceBackendStatus) -- it must be invalidated
                // here too, or a stale ONLINE/AUTH_FAILED from the previous
                // URL would keep showing after the user changes it.
                editor.putLong(AI_VOICE_BACKEND_LAST_TEST, 0L)
                editor.putString(AI_VOICE_BACKEND_STATUS, if (value.isBlank()) "NOT_CONFIGURED" else "NOT_TESTED")
                editor.putString(AI_VOICE_BACKEND_LAST_ERROR, "")
            }
            NOTIF_SOUND -> editor.putBoolean(NOTIF_SOUND, value.toBooleanStrictOrNull() ?: return false)
            NOTIF_VIBRATE -> editor.putBoolean(NOTIF_VIBRATE, value.toBooleanStrictOrNull() ?: return false)
            THEME -> {
                if (value !in setOf("dark", "light")) return false
                editor.putString(THEME, value)
            }
            ACCENT -> {
                if (value !in setOf("cyan", "purple", "amber", "green")) return false
                editor.putString(ACCENT, value)
            }
            FONT_SIZE -> {
                if (value !in setOf("S", "M", "L")) return false
                editor.putString(FONT_SIZE, value)
            }
            APP_LOCK -> editor.putBoolean(APP_LOCK, value.toBooleanStrictOrNull() ?: return false)
            PAUSED -> editor.putBoolean(PAUSED, value.toBooleanStrictOrNull() ?: return false)
            else -> return false
        }
        return editor.commit()
    }

    fun setPin(context: Context, pin: String): Boolean {
        if (!pin.matches(Regex("\\d{4,8}"))) return false
        return prefs(context).edit().putString(PIN, hashPin(pin)).commit()
    }

    fun verifyPin(context: Context, pin: String): Boolean =
        hashPin(pin) == prefs(context).getString(PIN, "")

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun setLastBackup(context: Context, whenMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(LAST_BACKUP, whenMillis).apply()
    }

    fun aiStatus(context: Context): String {
        if (!aiEnabled(context)) return "DISABLED"
        if (aiApiKey(context).isBlank()) return "NOT_CONFIGURED"
        val stored = aiStoredStatus(context).uppercase()
        return if (stored.isNotBlank()) stored else if (aiLastTest(context) == 0L) "NOT_TESTED" else if (aiLastOk(context)) "ONLINE" else "ERROR"
    }

    fun setAiTestResult(context: Context, ok: Boolean, error: String = "", status: String = if (ok) "ONLINE" else "ERROR") {
        prefs(context).edit()
            .putBoolean(AI_LAST_OK, ok)
            .putLong(AI_LAST_TEST, System.currentTimeMillis())
            .putString(AI_LAST_ERROR, error.take(500))
            .putString(AI_STATUS, status.uppercase())
            .commit()
    }

    @Synchronized
    fun canConsumeAiRequest(context: Context): Boolean {
        val limit = aiLocalDailyLimit(context)
        return limit <= 0 || aiRequestsToday(context) < limit
    }

    @Synchronized
    fun markAiRequest(context: Context) {
        val today = dayKey()
        val p = prefs(context)
        val storedDay = p.getString(AI_USAGE_DATE, "")
        val count = if (storedDay == today) p.getInt(AI_USAGE_COUNT, 0) else 0
        p.edit()
            .putString(AI_USAGE_DATE, today)
            .putInt(AI_USAGE_COUNT, count + 1)
            .apply()
    }

    fun aiRequestsToday(context: Context): Int {
        val p = prefs(context)
        val today = dayKey()
        if (p.getString(AI_USAGE_DATE, "") != today) return 0
        return p.getInt(AI_USAGE_COUNT, 0).coerceAtLeast(0)
    }

    fun aiLocalDailyLimit(context: Context) = prefs(context).getInt(AI_LOCAL_DAILY_LIMIT, 0).coerceAtLeast(0)

    fun aiLocalRemaining(context: Context): Int {
        val limit = aiLocalDailyLimit(context)
        return if (limit <= 0) -1 else (limit - aiRequestsToday(context)).coerceAtLeast(0)
    }

    private fun dayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            return (ks.getKey(KEY_ALIAS, null) as javax.crypto.SecretKey)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$ciphertext"
    }

    private fun decrypt(value: String): String? {
        return runCatching {
            val parts = value.split(":", limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

object AiDefaults {
    const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
    val SUPPORTED_MODELS = listOf(
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )
}
