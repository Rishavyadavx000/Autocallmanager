package com.example.autocallmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini REST client for AutoCallManager V1.0.6.
 * Prompt IDs are stable so V1.0.6 can later replace the local prompt catalog
 * with admin-managed prompts without changing the AI request contract.
 */
object AiService {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    data class Result(
        val ok: Boolean,
        val text: String = "",
        val status: String = "ERROR",
        val httpCode: Int = 0,
        val error: String = "",
        val promptId: String = PromptStore.DEFAULT_PROMPT_ID
    )

    fun testModel(context: Context): Result {
        val key = SettingsStore.aiApiKey(context).trim()
        if (key.isBlank()) {
            return Result(false, status = "NOT_CONFIGURED", error = "API key is missing")
        }

        val model = SettingsStore.aiModel(context)
        return try {
            val connection = openConnection(
                "$BASE_URL$model",
                key,
                "GET"
            )
            val code = connection.responseCode
            val body = readResponse(connection, code)
            connection.disconnect()
            if (code in 200..299) {
                Result(true, status = "ONLINE", httpCode = code)
            } else {
                Result(false, status = classifyError(code), httpCode = code, error = parseError(body))
            }
        } catch (e: Exception) {
            Result(false, status = "ERROR", error = e.message ?: "Connection failed")
        }
    }

    fun generate(context: Context, instruction: String): Result =
        generate(context, PromptStore.DEFAULT_PROMPT_ID, instruction)

    fun generate(context: Context, promptId: String, instruction: String): Result {
        val key = SettingsStore.aiApiKey(context).trim()
        if (key.isBlank()) {
            return Result(false, status = "NOT_CONFIGURED", error = "API key is missing", promptId = promptId)
        }

        val cleanInstruction = instruction.trim()
        if (cleanInstruction.isBlank()) {
            return Result(false, status = "ERROR", error = "Prompt is empty", promptId = promptId)
        }

        val prompt = PromptStore.render(
            context,
            promptId,
            mapOf("instruction" to cleanInstruction)
        )
        val model = SettingsStore.aiModel(context)
        val url = "$BASE_URL$model:generateContent"

        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt.template)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", prompt.maxWords.coerceAtLeast(32) * 4)
                })
            }

            val connection = openConnection(url, key, "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val response = readResponse(connection, code)
            connection.disconnect()

            if (code !in 200..299) {
                return Result(
                    false,
                    status = classifyError(code),
                    httpCode = code,
                    error = parseError(response),
                    promptId = prompt.id
                )
            }

            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates") ?: JSONArray()
            if (candidates.length() == 0) {
                return Result(
                    false,
                    status = "ERROR",
                    httpCode = code,
                    error = "No candidates returned",
                    promptId = prompt.id
                )
            }

            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: JSONArray()

            val text = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val piece = part.optString("text", "")
                    if (piece.isNotBlank()) append(piece)
                }
            }.trim()

            if (text.isBlank()) {
                Result(
                    false,
                    status = "ERROR",
                    httpCode = code,
                    error = "Model returned empty text",
                    promptId = prompt.id
                )
            } else {
                Result(
                    true,
                    text = text,
                    status = "ONLINE",
                    httpCode = code,
                    promptId = prompt.id
                )
            }
        } catch (e: Exception) {
            Result(
                false,
                status = "ERROR",
                error = e.message ?: "Network error",
                promptId = promptId
            )
        }
    }

    private fun openConnection(url: String, key: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Accept", "application/json")
        }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }.orEmpty()
    }

    private fun parseError(body: String): String =
        runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").ifBlank { "AI request failed" }

    private fun classifyError(code: Int): String = when (code) {
        401, 403 -> "AUTH_ERROR"
        429 -> "RATE_LIMITED"
        in 500..599 -> "SERVER_ERROR"
        else -> "ERROR"
    }
}
