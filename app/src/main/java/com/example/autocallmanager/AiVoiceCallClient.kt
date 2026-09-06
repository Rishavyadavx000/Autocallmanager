package com.example.autocallmanager

import org.json.JSONObject
import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/**
 * Starts a server-originated AI Voice Call. The server/telephony provider owns
 * the telephone audio path; Android never attempts to inject TTS into a SIM call.
 *
 * The base URL and app key here are read from [SettingsStore.adminCatalogUrl] /
 * [SettingsStore.adminCatalogAppKey] -- the SAME fields the Admin Prompt Catalog
 * (see PromptStore) uses. This is intentional, not a bug: admin-server/src/server.js
 * hosts both `/v1/prompts` (catalog) and `/v1/voice/calls` (this client) as
 * separate, documented paths on one server. What must NOT happen is treating
 * "catalog is in local-only mode" as the same fact as "AI Voice backend is not
 * configured" -- they are two independent features reading one shared setting,
 * so each gets its own status surfaced separately (see
 * SettingsStore.aiVoiceBackendStatus / Diagnostics / the Settings screen).
 */
object AiVoiceCallClient {

    /**
     * Outcome codes, chosen to match what admin-server can actually produce
     * (see server.js + voiceConsent.js):
     *  - CONFIG_MISSING / INVALID_URL: never leaves the device, no request attempted.
     *  - AUTH_FAILED: HTTP 401 (requirePublicAppKey) -- missing/wrong X-App-Key.
     *  - REJECTED: HTTP 403 (consent/safety-gate block, e.g. DO_NOT_CALL,
     *    CONSENT_NOT_CONSENTED, SCHEDULE_DISABLED) or any other 4xx -- the
     *    server understood the request and declined it; retrying the same
     *    request will not change that.
     *  - SERVER_ERROR: HTTP 5xx (includes the "provider not configured on the
     *    server" 503s and Twilio-side 502s) -- may well succeed later.
     *  - OFFLINE: the backend host could not be reached at all.
     *  - REQUEST_FAILED: fallback for anything not classified above.
     */
    object Code {
        const val OK = "OK"
        const val ONLINE = "ONLINE"
        const val CONFIG_MISSING = "CONFIG_MISSING"
        const val INVALID_URL = "INVALID_URL"
        const val OFFLINE = "OFFLINE"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val REJECTED = "REJECTED"
        const val SERVER_ERROR = "SERVER_ERROR"
        const val REQUEST_FAILED = "REQUEST_FAILED"
    }

    data class Result(
        val ok: Boolean,
        val message: String,
        val executionId: String = "",
        val code: String = Code.REQUEST_FAILED
    )

    data class HealthResult(val status: String, val message: String = "")

    fun create(context: Context, task: CallTask): Result {
        val baseUrl = SettingsStore.adminCatalogUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result(false, "AI Voice backend URL is not configured", code = Code.CONFIG_MISSING)
        }

        val url = try {
            URL("$baseUrl/v1/voice/calls")
        } catch (e: Exception) {
            return Result(false, e.message ?: "AI Voice backend URL is invalid", code = Code.INVALID_URL)
        }

        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                val key = SettingsStore.adminCatalogAppKey(context)
                if (key.isNotBlank()) setRequestProperty("X-App-Key", key)
            }

            val payload = JSONObject().apply {
                put("phoneNumber", task.number)
                put("scheduleEnabled", task.callEnabled)
                put("scheduleId", task.id.toString())
                put("promptId", task.promptId)
                put("promptVersion", task.promptVersion)
                put("instruction", task.messageText)
                put("maxDurationSeconds", 120)
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val httpCode = conn.responseCode
            val body = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = runCatching { JSONObject(body) }.getOrNull()
            if (httpCode !in 200..299) {
                val resultCode = when {
                    httpCode == 401 -> Code.AUTH_FAILED
                    httpCode in 500..599 -> Code.SERVER_ERROR
                    else -> Code.REJECTED // 403 (consent/safety gate) and any other 4xx
                }
                val serverMessage = json?.optString("error").orEmpty()
                return Result(
                    false,
                    serverMessage.ifBlank { "AI Voice backend returned HTTP $httpCode" },
                    code = resultCode
                )
            }
            val executionId = json?.optString("executionId", json.optJSONObject("call")?.optString("executionId", "")) ?: ""
            Result(true, "AI Voice call requested", executionId, code = Code.OK)
        } catch (e: UnknownHostException) {
            Result(false, "AI Voice backend host could not be resolved", code = Code.OFFLINE)
        } catch (e: SocketTimeoutException) {
            Result(false, "AI Voice backend did not respond in time", code = Code.OFFLINE)
        } catch (e: IOException) {
            Result(false, e.message ?: "AI Voice backend is unreachable", code = Code.OFFLINE)
        } catch (e: Exception) {
            Result(false, e.message ?: "AI Voice backend request failed", code = Code.REQUEST_FAILED)
        }
    }

    /**
     * Lightweight, unauthenticated reachability check against the backend's
     * `GET /v1/health` route (see admin-server/src/server.js -- no X-App-Key
     * required for this route). This proves the configured base URL points at
     * a reachable server; it does NOT prove the app key is valid or that a
     * real call would be accepted -- there is no dedicated endpoint for that,
     * so an app-key problem still only surfaces as AUTH_FAILED from [create]
     * the next time a real call is attempted. Invoke from a background thread.
     */
    fun checkHealth(context: Context): HealthResult {
        val baseUrl = SettingsStore.adminCatalogUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) return HealthResult(Code.CONFIG_MISSING, "No backend URL configured")

        val url = try {
            URL("$baseUrl/v1/health")
        } catch (e: Exception) {
            return HealthResult(Code.INVALID_URL, e.message ?: "Backend URL is invalid")
        }

        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }
            val httpCode = conn.responseCode
            val body = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            when {
                httpCode in 200..299 -> {
                    val ok = runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true)
                    if (ok) HealthResult(Code.ONLINE, "Backend reachable")
                    else HealthResult(Code.SERVER_ERROR, "Backend responded but reported an error")
                }
                else -> HealthResult(Code.SERVER_ERROR, "Backend returned HTTP $httpCode")
            }
        } catch (e: UnknownHostException) {
            HealthResult(Code.OFFLINE, "Host could not be resolved")
        } catch (e: SocketTimeoutException) {
            HealthResult(Code.OFFLINE, "Backend did not respond in time")
        } catch (e: IOException) {
            HealthResult(Code.OFFLINE, e.message ?: "Backend is unreachable")
        } catch (e: Exception) {
            HealthResult(Code.SERVER_ERROR, e.message ?: "Health check failed")
        }
    }
}
