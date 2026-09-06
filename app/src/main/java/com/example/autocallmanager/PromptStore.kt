package com.example.autocallmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Versioned prompt contract for V1.0.4/V1.0.5.
 * V1.0.5 can replace the source of definitions with admin/backend prompts
 * without changing the AI request API, because prompt IDs and fields remain stable.
 *
 * Source-of-truth precedence in [list]: synced remote catalog (if an admin
 * catalog URL is configured and a successful sync has happened) -> bundled
 * asset -> built-in single-prompt fallback. The remote fetch itself only
 * happens inside [refreshFromRemote], which callers must run off the main
 * thread (see MainActivity's `io.execute { }` pattern) — [list] never makes
 * a network call and always returns instantly from disk/memory.
 */
object PromptStore {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_PROMPT_ID = "message.generate.v1"
    private const val ASSET_NAME = "ai_prompts_v1.json"
    private const val CACHE_FILE_NAME = "prompt_catalog_remote.json"

    data class PromptDefinition(
        val id: String,
        val version: Int,
        val type: String,
        val name: String,
        val enabled: Boolean,
        val outputFormat: String,
        val maxWords: Int,
        val variables: List<String>,
        val template: String
    )

    data class RemoteSyncResult(
        val ok: Boolean,
        val count: Int = 0,
        val error: String = "",
        val syncedAt: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cached: List<PromptDefinition>? = null

    fun list(context: Context): List<PromptDefinition> {
        cached?.let { return it }
        val remoteEnabled = SettingsStore.adminCatalogUrl(context).isNotBlank()
        val fromRemoteCache = if (remoteEnabled) {
            runCatching { readRemoteCache(context) }.getOrNull()
        } else null
        val loaded = fromRemoteCache ?: runCatching {
            val json = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(json)
        }.getOrElse { builtInFallback() }
        cached = loaded
        return loaded
    }

    /**
     * Blocking network call — invoke from a background thread only. Fetches
     * `{baseUrl}/v1/prompts`, validates the response with the same [parse]
     * routine used for the bundled asset, and atomically writes it to a local
     * cache file so future [list] calls (including an app restart) use it
     * without hitting the network again. Never throws.
     */
    fun refreshFromRemote(context: Context): RemoteSyncResult {
        val baseUrl = SettingsStore.adminCatalogUrl(context).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return RemoteSyncResult(false, error = "No admin catalog URL configured")
        }
        return try {
            val connection = (URL("$baseUrl/v1/prompts").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                val appKey = SettingsStore.adminCatalogAppKey(context)
                if (appKey.isNotBlank()) setRequestProperty("X-App-Key", appKey)
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                return RemoteSyncResult(false, error = "Server returned HTTP $code")
            }

            val parsed = parse(body)
            if (parsed.isEmpty()) return RemoteSyncResult(false, error = "Remote catalog was empty")

            val file = cacheFile(context)
            val tmp = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }

            cached = null // force list() to re-read so getAiPromptsJson() reflects this immediately
            RemoteSyncResult(true, count = parsed.size)
        } catch (e: Exception) {
            RemoteSyncResult(false, error = e.message ?: "Network error")
        }
    }

    /** Drops any cached remote catalog and reverts to the bundled asset on next [list] call. */
    fun clearRemoteCache(context: Context) {
        runCatching { cacheFile(context).delete() }
        cached = null
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun readRemoteCache(context: Context): List<PromptDefinition>? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        val json = file.readText(Charsets.UTF_8)
        return parse(json)
    }

    fun get(context: Context, id: String?): PromptDefinition {
        val requested = id?.trim().orEmpty()
        val prompts = list(context)
        return prompts.firstOrNull { it.id == requested && it.enabled }
            ?: prompts.firstOrNull { it.id == DEFAULT_PROMPT_ID && it.enabled }
            ?: builtInFallback().first()
    }

    fun render(
        context: Context,
        id: String?,
        variables: Map<String, String>
    ): PromptDefinition {
        val definition = get(context, id)
        var output = definition.template
        val merged = LinkedHashMap<String, String>()
        merged["maxWords"] = definition.maxWords.toString()
        merged.putAll(variables)
        merged.forEach { (key, value) ->
            output = output.replace("{{$key}}", value)
        }
        return definition.copy(template = output)
    }

    fun listJson(context: Context): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("prompts", JSONArray().apply {
            list(context).forEach { prompt ->
                put(JSONObject().apply {
                    put("id", prompt.id)
                    put("version", prompt.version)
                    put("type", prompt.type)
                    put("name", prompt.name)
                    put("enabled", prompt.enabled)
                    put("outputFormat", prompt.outputFormat)
                    put("maxWords", prompt.maxWords)
                    put("variables", JSONArray(prompt.variables))
                })
            }
        })
    }.toString()

    private fun parse(json: String): List<PromptDefinition> {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
        val array = root.optJSONArray("prompts") ?: JSONArray()
        val out = mutableListOf<PromptDefinition>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val id = o.optString("id").trim()
            val template = o.optString("template").trim()
            if (id.isBlank() || template.isBlank()) continue
            val variablesArray = o.optJSONArray("variables") ?: JSONArray()
            val variables = buildList {
                for (j in 0 until variablesArray.length()) add(variablesArray.optString(j))
            }
            out += PromptDefinition(
                id = id,
                version = o.optInt("version", 1),
                type = o.optString("type", "MESSAGE_GENERATE"),
                name = o.optString("name", id),
                enabled = o.optBoolean("enabled", true),
                outputFormat = o.optString("outputFormat", "plain_text"),
                maxWords = o.optInt("maxWords", 60).coerceIn(10, 300),
                variables = variables,
                template = template
            )
        }
        require(out.isNotEmpty())
        return out
    }

    private fun builtInFallback(): List<PromptDefinition> = listOf(
        PromptDefinition(
            id = DEFAULT_PROMPT_ID,
            version = 1,
            type = "MESSAGE_GENERATE",
            name = "Generate Reminder",
            enabled = true,
            outputFormat = "plain_text",
            maxWords = 60,
            variables = listOf("instruction"),
            template = "You are the reminder-message writer inside AutoCallManager. Create one short, natural phone reminder from the user's instruction. Respect any requested language, tone, and relationship. Return only the final message, with no heading, explanation, quotes, markdown, or bullet points. Keep it suitable for spoken TTS and normally under {{maxWords}} words. User instruction: {{instruction}}"
        )
    )
}
