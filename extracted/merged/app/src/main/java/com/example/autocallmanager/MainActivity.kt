package com.example.autocallmanager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var requireUnlock = false
    private var backupJsonPending: String? = null
    private var traceExportTextPending: String? = null
    private val io = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshWebView() }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = backupJsonPending ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open backup file")
            SettingsStore.setLastBackup(this)
            toast("Backup exported successfully")
            refreshWebView()
        }.onFailure { toast("Backup failed: ${it.message ?: "unknown error"}") }
        backupJsonPending = null
    }

    private val createTraceExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = traceExportTextPending ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open export file")
            toast("Diagnostic log exported")
        }.onFailure { toast("Export failed: ${it.message ?: "unknown error"}") }
        traceExportTextPending = null
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Unable to read backup")
            val summary = BackupManager.restoreJson(this, json)
            toast("Restored ${summary.tasks} schedules and ${summary.history} history events")
            refreshWebView()
        }.onFailure { toast("Restore failed: ${it.message ?: "invalid backup"}") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.mainWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(this), "NativeBridge")
        webView.loadUrl("file:///android_asset/auto_call_ui.html")

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            refreshWebView()
        }

        requestPermissionsIfNeeded()
        if (SettingsStore.appLock(this) && SettingsStore.hasPin(this)) requireUnlock = true
    }

    override fun onStart() {
        super.onStart()
        CallStateManager.start(this) { refreshWebView() }
    }

    override fun onStop() {
        CallStateManager.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshWebView()
        if (requireUnlock && SettingsStore.appLock(this) && SettingsStore.hasPin(this)) {
            requireUnlock = false
            showUnlockDialog()
        }
    }

    override fun onPause() {
        if (SettingsStore.appLock(this) && SettingsStore.hasPin(this)) requireUnlock = true
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("NativeBridge")
            webView.destroy()
        }
        io.shutdownNow()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.CALL_PHONE)) needed += Manifest.permission.CALL_PHONE
        if (!has(Manifest.permission.READ_CONTACTS)) needed += Manifest.permission.READ_CONTACTS
        if (!has(Manifest.permission.READ_PHONE_STATE)) needed += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshWebView() {
        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                "if(typeof syncFromNative==='function'){syncFromNative();}",
                null
            )
        }
    }

    private fun speakPreview(message: String, language: String, speed: Float, pitch: Float) {
        val safeMessage = message.trim().take(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(100))
        if (safeMessage.isBlank()) return
        val engine = tts ?: return
        val locale = when (language.lowercase(Locale.getDefault())) {
            "hindi" -> Locale("hi", "IN")
            "english" -> Locale.US
            else -> if (safeMessage.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale.US
        }
        runOnUiThread {
            val r = engine.setLanguage(locale)
            if (r < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.US)
            engine.setSpeechRate(speed.coerceIn(0.5f, 2f))
            engine.setPitch(pitch.coerceIn(0.5f, 1.5f))
            engine.speak(safeMessage, TextToSpeech.QUEUE_FLUSH, null, "preview-${System.currentTimeMillis()}")
        }
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun showUnlockDialog() {
        val input = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
            addView(input, ViewGroup.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Auto Call Manager locked")
            .setMessage("Enter your PIN to continue")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("UNLOCK", null)
            .setNegativeButton("CANCEL") { _, _ -> finish() }
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (SettingsStore.verifyPin(this, input.text.toString())) {
                            dialog.dismiss()
                        } else input.error = "Incorrect PIN"
                    }
                }
                dialog.show()
            }
    }

    inner class NativeBridge(private val activity: Activity) {
        @JavascriptInterface
        fun openPhoneModule(): String {
            return try {
                activity.startActivity(Intent(activity, PhoneActivity::class.java))
                result(true, "Opening Phone")
            } catch (e: Exception) {
                result(false, e.message ?: "Unable to open Phone module")
            }
        }

        @JavascriptInterface
        fun getTasksJson(): String = JSONArray().apply { TaskStore.load(activity).forEach { put(taskToJson(it)) } }.toString()

        @JavascriptInterface
        fun getHistoryJson(): String = TaskStore.history(activity).toString()

        @JavascriptInterface
        fun getTaskHistoryJson(idValue: String): String =
            TaskStore.historyForTask(activity, idValue.toLongOrNull() ?: -1L).toString()

        @JavascriptInterface
        fun getContactsJson(): String {
            // Deliberately NOT calling has(): lint's MissingPermission data-flow
            // analysis does not follow custom permission-check wrapper functions,
            // only a checkSelfPermission() call that directly/immediately guards
            // the flagged call below (see the identical rationale already
            // documented in CallManager.showNotification() and
            // VoiceService.updateNotification() for the POST_NOTIFICATIONS case).
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_CONTACTS
                ) != PackageManager.PERMISSION_GRANTED
            ) return "[]"
            val arr = JSONArray()
            val seen = mutableSetOf<String>()
            return try {
                activity.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ), null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
                )?.use { cursor ->
                    var count = 0
                    while (cursor.moveToNext() && count < 500) {
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
                            put("group", "Phone")
                            put("initials", initials)
                        })
                        count++
                    }
                }
                arr.toString()
            } catch (_: Exception) { "[]" }
        }

        @JavascriptInterface
        fun getSimAccountsJson(): String = JSONArray().apply {
            CallManager.activeSimAccounts(activity).forEach { sim ->
                put(JSONObject().apply {
                    put("handleId", sim.handleId)
                    put("handleKey", sim.component + "::" + sim.handleId)
                    put("component", sim.component)
                    put("label", sim.label)
                    put("slotIndex", sim.slotIndex)
                    put("subscriptionId", sim.subscriptionId)
                })
            }
        }.toString()

        @JavascriptInterface
        fun getSettingsJson(): String = JSONObject().apply {
            put("defaultAttempts", SettingsStore.defaultAttempts(activity))
            put("defaultRetrySeconds", SettingsStore.defaultRetrySeconds(activity))
            put("voiceLanguage", SettingsStore.voiceLanguage(activity))
            put("voiceSpeed", SettingsStore.voiceSpeed(activity))
            put("voicePitch", SettingsStore.voicePitch(activity))
            put("aiEnabled", SettingsStore.aiEnabled(activity))
            put("aiProvider", "Gemini")
            put("aiModel", SettingsStore.aiModel(activity))
            put("aiConfigured", SettingsStore.aiApiKey(activity).isNotBlank())
            put("aiStatus", SettingsStore.aiStatus(activity))
            put("aiLastTest", SettingsStore.aiLastTest(activity))
            put("aiRequestsToday", SettingsStore.aiRequestsToday(activity))
            put("aiLocalDailyLimit", SettingsStore.aiLocalDailyLimit(activity))
            put("aiLocalRemaining", SettingsStore.aiLocalRemaining(activity))
            put("aiLastError", SettingsStore.aiLastError(activity))
            put("notificationSound", SettingsStore.notificationSound(activity))
            put("notificationVibrate", SettingsStore.notificationVibrate(activity))
            put("theme", SettingsStore.theme(activity))
            put("accent", SettingsStore.accent(activity))
            put("fontSize", SettingsStore.fontSize(activity))
            put("appLock", SettingsStore.appLock(activity))
            put("hasPin", SettingsStore.hasPin(activity))
            put("pausedAll", SettingsStore.pausedAll(activity))
            put("lastBackup", SettingsStore.lastBackup(activity))
            put("adminCatalogUrl", SettingsStore.adminCatalogUrl(activity))
            put("adminCatalogAppKeyConfigured", SettingsStore.adminCatalogAppKeyConfigured(activity))
            put("promptsSyncStatus", SettingsStore.promptsSyncStatus(activity))
            put("promptsLastSync", SettingsStore.promptsLastSync(activity))
            put("promptsSyncError", SettingsStore.promptsSyncError(activity))
            put("aiVoiceBackendConfigured", SettingsStore.aiVoiceBackendConfigured(activity))
            put("aiVoiceBackendStatus", SettingsStore.aiVoiceBackendStatus(activity))
            put("aiVoiceBackendLastTest", SettingsStore.aiVoiceBackendLastTest(activity))
            put("aiVoiceBackendLastError", SettingsStore.aiVoiceBackendLastError(activity))
        }.toString()

        @JavascriptInterface
        fun setSetting(key: String, value: String): String {
            return if (SettingsStore.set(activity, key, value)) {
                refreshWebView()
                result(true, "Setting updated")
            } else result(false, "Invalid setting value")
        }

        @JavascriptInterface
        fun configureAi(): String {
            activity.runOnUiThread {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 0, 48, 0)
                }

                val key = EditText(activity).apply {
                    hint = "Gemini API key"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(SettingsStore.aiApiKey(activity))
                }

                val model = Spinner(activity)
                val models = AiDefaults.SUPPORTED_MODELS.toList()
                model.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, models)
                val current = models.indexOf(SettingsStore.aiModel(activity)).coerceAtLeast(0)
                model.setSelection(current)

                val limit = EditText(activity).apply {
                    hint = "Local daily guard (0 = off)"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    val existing = SettingsStore.aiLocalDailyLimit(activity)
                    setText(if (existing == 0) "0" else existing.toString())
                }

                container.addView(key, ViewGroup.LayoutParams(-1, -2))
                container.addView(model, ViewGroup.LayoutParams(-1, -2))
                container.addView(limit, ViewGroup.LayoutParams(-1, -2))

                AlertDialog.Builder(activity)
                    .setTitle("Gemini AI Configuration")
                    .setMessage("API key is stored locally using Android Keystore encryption. The optional daily guard is a local safety limit, not Google's provider quota.")
                    .setView(container)
                    .setPositiveButton("SAVE") { _, _ ->
                        val keyValue = key.text.toString().trim()
                        val modelValue = model.selectedItem?.toString().orEmpty()
                        val limitValue = limit.text.toString().trim().ifBlank { "0" }.toIntOrNull()
                        if (limitValue == null || limitValue !in 0..100000) {
                            toast("Daily guard must be 0 to 100000")
                            return@setPositiveButton
                        }
                        if (!SettingsStore.setAiApiKey(activity, keyValue)) {
                            toast("Could not secure the API key")
                            return@setPositiveButton
                        }
                        SettingsStore.set(activity, "aiModel", modelValue)
                        SettingsStore.set(activity, "aiLocalDailyLimit", limitValue.toString())
                        refreshWebView()
                        toast(if (keyValue.isBlank()) "Gemini API key cleared" else "Gemini settings saved. Tap Test Connection when ready.")
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            return result(true, "Gemini configuration opened")
        }

        @JavascriptInterface
        fun getAiPromptsJson(): String = PromptStore.listJson(activity)

        @JavascriptInterface
        fun configurePromptSync(): String {
            activity.runOnUiThread {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 0, 48, 0)
                }

                val urlField = EditText(activity).apply {
                    hint = "https://your-admin-server.example.com"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(SettingsStore.adminCatalogUrl(activity))
                }

                val appKeyField = EditText(activity).apply {
                    hint = "Optional app key"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(SettingsStore.adminCatalogAppKey(activity))
                }

                container.addView(urlField, ViewGroup.LayoutParams(-1, -2))
                container.addView(appKeyField, ViewGroup.LayoutParams(-1, -2))

                AlertDialog.Builder(activity)
                    .setTitle("Admin Prompt Catalog")
                    .setMessage(
                        "Configure the backend URL used both to receive enabled prompts AND to place AI Voice calls " +
                        "(see AI Voice Backend below). Leave the URL blank to use the bundled catalog -- AI Voice calls " +
                        "will not be available until a URL is set here. HTTPS is recommended."
                    )
                    .setView(container)
                    .setPositiveButton("SAVE") { _, _ ->
                        val urlValue = urlField.text.toString().trim()
                        if (!SettingsStore.set(activity, "adminCatalogUrl", urlValue)) {
                            toast("URL must be blank or a valid https:// address")
                            return@setPositiveButton
                        }
                        if (!SettingsStore.setAdminCatalogAppKey(activity, appKeyField.text.toString().trim())) {
                            toast("Could not secure the app key")
                            return@setPositiveButton
                        }
                        refreshWebView()
                        toast(
                            if (urlValue.isBlank())
                                "Using local prompt catalog"
                            else
                                "Admin catalog saved. Sync when ready."
                        )
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            return result(true, "Prompt catalog settings opened")
        }

        @JavascriptInterface
        fun refreshPromptCatalog(): String {
            val baseUrl = SettingsStore.adminCatalogUrl(activity)
            if (baseUrl.isBlank()) return result(false, "Set an Admin Catalog URL first")

            val requestId = "sync-${System.currentTimeMillis()}"

            io.execute {
                val syncResult = PromptStore.refreshFromRemote(activity)
                SettingsStore.setPromptsSyncResult(
                    activity,
                    syncResult.ok,
                    syncResult.error
                )

                val payload = JSONObject().apply {
                    put("requestId", requestId)
                    put("ok", syncResult.ok)
                    put("count", syncResult.count)
                    put("error", syncResult.error)
                    put("syncStatus", SettingsStore.promptsSyncStatus(activity))
                    put("lastSync", SettingsStore.promptsLastSync(activity))
                }.toString()

                activity.runOnUiThread {
                    if (!::webView.isInitialized) return@runOnUiThread
                    webView.evaluateJavascript(
                        "if(typeof onPromptCatalogSynced==='function'){onPromptCatalogSynced(${JSONObject.quote(payload)});}",
                        null
                    )
                }
                refreshWebView()
            }

            return JSONObject()
                .put("ok", true)
                .put("message", "Sync started")
                .put("requestId", requestId)
                .toString()
        }

        @JavascriptInterface
        fun testAiVoiceBackend(): String {
            if (!SettingsStore.aiVoiceBackendConfigured(activity)) {
                return result(false, "Set an Admin/Backend URL first")
            }
            val requestId = "voice-health-${System.currentTimeMillis()}"

            io.execute {
                val health = AiVoiceCallClient.checkHealth(activity)
                SettingsStore.setAiVoiceBackendTestResult(activity, health.status, health.message)

                val payload = JSONObject().apply {
                    put("requestId", requestId)
                    put("ok", health.status == AiVoiceCallClient.Code.ONLINE)
                    put("status", SettingsStore.aiVoiceBackendStatus(activity))
                    put("message", health.message)
                    put("lastTest", SettingsStore.aiVoiceBackendLastTest(activity))
                }.toString()

                activity.runOnUiThread {
                    if (!::webView.isInitialized) return@runOnUiThread
                    webView.evaluateJavascript(
                        "if(typeof onAiVoiceBackendTested==='function'){onAiVoiceBackendTested(${JSONObject.quote(payload)});}",
                        null
                    )
                }
                refreshWebView()
            }

            return JSONObject()
                .put("ok", true)
                .put("message", "Checking AI Voice backend")
                .put("requestId", requestId)
                .toString()
        }

        @JavascriptInterface
        fun openAdminPanel(): String {
            val baseUrl = SettingsStore.adminCatalogUrl(activity).trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                return result(false, "Set Admin Catalog URL first")
            }

            val adminUrl = "$baseUrl/admin"
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(adminUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                result(true, "Opening Admin Panel")
            } catch (_: Exception) {
                result(false, "Could not open Admin Panel")
            }
        }

        @JavascriptInterface
        fun testAiConnection(): String {
            val key = SettingsStore.aiApiKey(activity).trim()
            if (key.isBlank()) return result(false, "Configure Gemini API key first")
            if (!SettingsStore.aiEnabled(activity)) return result(false, "AI is disabled")
            if (!SettingsStore.canConsumeAiRequest(activity)) return result(false, "Local daily AI guard reached")
            SettingsStore.markAiRequest(activity)
            refreshWebView()
            io.execute { testAiConnectionInternal() }
            return result(true, "Gemini connection test started")
        }

        private fun testAiConnectionInternal(): Boolean {
            if (SettingsStore.aiApiKey(activity).isBlank() || !SettingsStore.aiEnabled(activity)) {
                return false
            }
            val response = AiService.testModel(activity)
            SettingsStore.setAiTestResult(activity, response.ok, response.error, response.status)
            refreshWebView()
            return response.ok
        }

        @JavascriptInterface
        fun generateAI(prompt: String): String = generateAIWithPrompt(PromptStore.DEFAULT_PROMPT_ID, prompt)

        @JavascriptInterface
        fun generateAIWithPrompt(promptId: String, prompt: String): String {
            val clean = prompt.trim()
            if (clean.isBlank()) return result(false, "Add an instruction first")
            if (!SettingsStore.aiEnabled(activity)) return result(false, "Enable AI in Settings first")
            if (SettingsStore.aiApiKey(activity).isBlank()) return result(false, "Configure Gemini API key first")
            if (!SettingsStore.canConsumeAiRequest(activity)) {
                return result(false, "Local daily AI guard reached")
            }

            val selectedPrompt = PromptStore.get(activity, promptId)
            val requestId = "ai-${System.currentTimeMillis()}-${clean.hashCode().toUInt()}"
            SettingsStore.markAiRequest(activity)
            refreshWebView()

            io.execute {
                val response = AiService.generate(activity, selectedPrompt.id, clean)
                if (response.ok) {
                    SettingsStore.setAiTestResult(activity, true, "", "ONLINE")
                } else {
                    SettingsStore.setAiTestResult(activity, false, response.error.ifBlank { response.status }, response.status)
                }

                val payload = JSONObject().apply {
                    put("requestId", requestId)
                    put("ok", response.ok)
                    put("text", response.text)
                    put("status", response.status)
                    put("httpCode", response.httpCode)
                    put("error", response.error)
                    put("promptId", response.promptId)
                    put("requestsToday", SettingsStore.aiRequestsToday(activity))
                    put("localDailyLimit", SettingsStore.aiLocalDailyLimit(activity))
                    put("localRemaining", SettingsStore.aiLocalRemaining(activity))
                }.toString()

                activity.runOnUiThread {
                    if (!::webView.isInitialized) return@runOnUiThread
                    webView.evaluateJavascript(
                        "if(typeof onNativeAiResult==='function'){onNativeAiResult(${JSONObject.quote(payload)});}",
                        null
                    )
                }
                refreshWebView()
            }
            return JSONObject()
                .put("ok", true)
                .put("message", "AI request started")
                .put("requestId", requestId)
                .put("promptId", selectedPrompt.id)
                .toString()
        }

        @JavascriptInterface
        fun saveSchedule(payloadJson: String, activateValue: String, existingIdValue: String): String {
            return try {
                val payload = JSONObject(payloadJson)
                val phone = payload.optString("number").trim()
                if (phone.isBlank()) return result(false, "Phone number is required")
                if (!CallManager.isValidPhoneNumber(phone)) return result(false, "Enter a valid phone number (6-15 digits)")
                val now = System.currentTimeMillis()
                val triggerAt = payload.optLong("triggerAt", 0L)
                if (triggerAt <= now) return result(false, "Choose a future date/time")
                val maxAttempts = payload.optInt("maxAttempts", SettingsStore.defaultAttempts(activity)).coerceIn(1, 50)
                val retry = payload.optInt("retryIntervalSeconds", SettingsStore.defaultRetrySeconds(activity)).coerceAtLeast(20)
                val existingId = existingIdValue.toLongOrNull() ?: 0L
                val activateRequested = activateValue.equals("true", true)
                val activate = activateRequested && !SettingsStore.pausedAll(activity)
                val tasks = TaskStore.load(activity)

                if (existingId > 0) {
                    val task = tasks.firstOrNull { it.id == existingId } ?: return result(false, "Schedule not found")
                    CallScheduler.cancel(activity, existingId)
                    applyPayload(task, payload, triggerAt, maxAttempts, retry, activate)
                    TaskStore.save(activity, tasks)
                    if (activate) {
                        if (!CallScheduler.schedule(activity, existingId, task.nextTrigger, task.attemptCount + 1)) {
                            task.status = "Stopped"
                            task.nextTrigger = -1L
                            task.liveStatus = "SCHEDULER_ERROR"
                            task.lastResult = "SCHEDULE_FAILED"
                            TaskStore.save(activity, tasks)
                            return result(false, "Exact alarm access is required for activation")
                        }
                        CallScheduler.schedulePreNotification(activity, task)
                    }
                    refreshWebView()
                    return result(true, if (activate) "Schedule updated and activated" else "Schedule updated")
                }

                val recurrence = runCatching { RecurrenceMode.valueOf(payload.optString("recurrence", "ONCE")) }.getOrDefault(RecurrenceMode.ONCE)
                val id = System.currentTimeMillis()
                val task = CallTask(
                    id = id,
                    number = phone,
                    contactName = payload.optString("contactName").trim(),
                    triggerAt = triggerAt,
                    maxAttempts = maxAttempts,
                    retryIntervalSeconds = retry,
                    status = if (activate) "Scheduled" else "Stopped",
                    nextTrigger = if (activate) triggerAt else -1L,
                    liveStatus = if (activate) "WAITING" else "STORED",
                    lastResult = if (activate) "SCHEDULED" else "SAVED",
                    callEnabled = payload.optBoolean("callEnabled", true),
                    voiceEnabled = payload.optBoolean("voiceEnabled", false),
                    messageEnabled = payload.optBoolean("messageEnabled", false),
                    notificationEnabled = payload.optBoolean("notificationEnabled", true),
                    notifyBefore = payload.optBoolean("notifyBefore", true),
                    notifyAt = payload.optBoolean("notifyAt", true),
                    notifyAfter = payload.optBoolean("notifyAfter", true),
                    messageText = payload.optString("messageText").trim(),
                    promptId = PromptStore.get(activity, payload.optString("promptId", PromptStore.DEFAULT_PROMPT_ID)).id,
                    promptVersion = payload.optInt("promptVersion", 1).coerceAtLeast(1),
                    voiceLanguage = payload.optString("voiceLanguage", SettingsStore.voiceLanguage(activity)).let { if (it in setOf("Hindi", "Hinglish", "English")) it else SettingsStore.voiceLanguage(activity) },
                    voiceSpeed = payload.optDouble("voiceSpeed", SettingsStore.voiceSpeed(activity).toDouble()).toFloat().coerceIn(0.5f, 2.0f),
                    voicePitch = payload.optDouble("voicePitch", SettingsStore.voicePitch(activity).toDouble()).toFloat().coerceIn(0.5f, 1.5f),
                    groupName = payload.optString("groupName", ""),
                    recurrence = recurrence,
                    repeatDays = jsonIntSet(payload.optJSONArray("repeatDays")),
                    timeSlots = jsonIntList(payload.optJSONArray("timeSlots")),
                    simSelectionEnabled = payload.optBoolean("simSelectionEnabled", false),
                    phoneAccountId = payload.optString("phoneAccountId", ""),
                    phoneAccountComponent = payload.optString("phoneAccountComponent", ""),
                    phoneAccountLabel = payload.optString("phoneAccountLabel", ""),
                    timezoneId = payload.optString("timezoneId", java.util.TimeZone.getDefault().id).ifBlank { java.util.TimeZone.getDefault().id },
                    executionMode = payload.optString("executionMode", "NORMAL_SIM").let { if (it == "AI_VOICE") "AI_VOICE" else "NORMAL_SIM" }
                )

                if (task.simSelectionEnabled) {
                    val valid = CallManager.activeSimAccounts(activity).any {
                        it.handleId == task.phoneAccountId && it.component == task.phoneAccountComponent
                    }
                    if (!valid) {
                        return result(false, "Selected SIM is no longer available. Please choose an active SIM.")
                    }
                }

                tasks.add(0, task)
                TaskStore.save(activity, tasks)
                if (activate) {
                    val firstTrigger = task.nextTrigger.takeIf { it > System.currentTimeMillis() } ?: triggerAt
                    task.nextTrigger = firstTrigger
                    TaskStore.save(activity, tasks)
                    if (!CallScheduler.schedule(activity, id, firstTrigger, 1)) {
                        task.status = "Stopped"
                        task.nextTrigger = -1L
                        task.liveStatus = "SCHEDULER_ERROR"
                        task.lastResult = "SCHEDULE_FAILED"
                        TaskStore.save(activity, tasks)
                        return result(false, "Exact alarm access is required for activation")
                    }
                    CallScheduler.schedulePreNotification(activity, task)
                }
                refreshWebView()
                result(true, if (activate) "Schedule saved and activated" else "Schedule saved")
            } catch (e: Exception) {
                result(false, "Save failed: ${e.message ?: "unknown error"}")
            }
        }

        @JavascriptInterface
        fun startTask(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            if (SettingsStore.pausedAll(activity)) return result(false, "All schedules are paused")
            val tasks = TaskStore.load(activity)
            val task = tasks.firstOrNull { it.id == id } ?: return result(false, "Schedule not found")
            val now = System.currentTimeMillis()
            var trigger = if (task.nextTrigger > now) task.nextTrigger else if (task.recurrence != RecurrenceMode.ONCE) ScheduleCalculator.nextRecurring(task, now) ?: task.triggerAt else task.triggerAt
            if (trigger <= now) return result(false, "Choose a future date/time")
            if (!CallScheduler.canScheduleExactly(activity)) {
                openExactAlarmSettings()
                return result(false, "Allow Alarms & reminders for exact scheduling")
            }
            CallScheduler.cancel(activity, id)
            task.attemptCount = 0
            task.activeSlotIndex = 0
            task.status = "Scheduled"
            task.nextTrigger = trigger
            task.liveStatus = "WAITING"
            task.lastResult = "SCHEDULED"
            TaskStore.save(activity, tasks)
            if (!CallScheduler.schedule(activity, id, trigger, 1)) {
                task.status = "Stopped"
                task.nextTrigger = -1L
                task.liveStatus = "SCHEDULER_ERROR"
                task.lastResult = "SCHEDULE_FAILED"
                TaskStore.save(activity, tasks)
                return result(false, "Could not schedule exact alarm")
            }
            CallScheduler.schedulePreNotification(activity, task)
            refreshWebView()
            return result(true, "Schedule started")
        }

        @JavascriptInterface
        fun stopTask(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            val tasks = TaskStore.load(activity)
            val task = tasks.firstOrNull { it.id == id } ?: return result(false, "Schedule not found")
            CallScheduler.cancel(activity, id)
            CallScheduler.cancelWatchdog(activity, id)
            if (CallExecutionStore.get(activity)?.taskId == id) CallExecutionStore.clear(activity)
            CallManager.stopVoiceService(activity)
            task.status = "Stopped"
            task.nextTrigger = -1L
            task.liveStatus = "STOPPED"
            task.lastResult = "STOPPED"
            TaskStore.save(activity, tasks)
            refreshWebView()
            return result(true, "Schedule stopped")
        }

        @JavascriptInterface
        fun deleteTask(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            CallScheduler.cancel(activity, id)
            CallScheduler.cancelWatchdog(activity, id)
            if (CallExecutionStore.get(activity)?.taskId == id) CallExecutionStore.clear(activity)
            CallManager.stopVoiceService(activity)
            val tasks = TaskStore.load(activity).filterNot { it.id == id }
            TaskStore.save(activity, tasks)
            refreshWebView()
            return result(true, "Schedule deleted")
        }

        @JavascriptInterface
        fun openDialer(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            val task = TaskStore.load(activity).firstOrNull { it.id == id } ?: return result(false, "Schedule not found")
            return if (CallManager.openDialer(activity, task.number)) result(true, "Opening dialer") else result(false, "Could not open dialer")
        }

        /**
         * Places one real call attempt for [idValue] immediately, through the
         * exact same shared engine (CallExecutionManager.executeCallAttempt ->
         * CallManager.placeCallDetailed / AiVoiceCallClient) that a fired
         * alarm uses -- see CallReceiver.handleCallAttempt -- but entered
         * directly from the UI instead of via CallScheduler/AlarmManager, so
         * a NORMAL_SIM task needs neither the Scheduler nor the AI backend nor
         * internet to place a real call this way. Tagged callSource=MANUAL
         * throughout diagnostics/history so it is never confused with an
         * alarm-driven attempt on the same schedule.
         *
         * Always treated as a fresh attempt 1 of a new cycle (matching
         * startTask()'s existing "start over" semantics) rather than
         * continuing whatever attemptCount an earlier automatic retry
         * sequence left behind. Runs through the same finalize/retry/
         * next-slot logic as any other attempt afterward, so a manual call on
         * a recurring schedule still correctly advances to its next slot, and
         * one on a one-time schedule still correctly completes it -- there is
         * no separate manual-only outcome path to keep in sync.
         */
        @JavascriptInterface
        fun callNow(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            if (SettingsStore.pausedAll(activity)) return result(false, "All schedules are paused")
            val tasks = TaskStore.load(activity)
            val task = tasks.firstOrNull { it.id == id } ?: return result(false, "Schedule not found")
            if (!has(Manifest.permission.CALL_PHONE)) return result(false, "Grant Call permission first")

            val active = CallExecutionStore.get(activity)
            if (active != null && task.executionMode == "NORMAL_SIM") {
                return result(false, "Another call is already in progress")
            }

            val executionId = ExecutionTraceStore.newExecutionId(task.id, 1)
            ExecutionTraceStore.log(
                activity, task.id, executionId, 1,
                ExecutionTraceStore.Stage.RECEIVER_STARTED, ExecutionTraceStore.PASS,
                "manual call-now", callSource = "MANUAL"
            )

            task.attemptCount = 1
            task.status = "Running"
            task.nextTrigger = -1L
            task.liveStatus = "CALL_STARTING"
            task.lastResult = "CALL_STARTING"
            TaskStore.addHistory(activity, task, "SYSTEM", "MANUAL_CALL_STARTED", "MANUAL")
            TaskStore.save(activity, tasks)
            refreshWebView()

            val finishedSynchronously = CallExecutionManager.executeCallAttempt(
                activity, task, executionId, 1, callSource = "MANUAL"
            ) {
                // AI_VOICE only: this runs later, on the background thread that
                // sent the backend request -- refresh so the eventual outcome
                // shows up, since this method has already returned its
                // immediate result below.
                refreshWebView()
            }
            refreshWebView()

            val latest = TaskStore.load(activity).firstOrNull { it.id == id }
            val outcome = latest?.lastResult ?: ""
            val ok = outcome in setOf("CALL_REQUEST_SENT", "CALL_SKIPPED", "AI_VOICE_REQUESTED")
            return result(
                ok,
                latest?.lastResult
                    ?: if (finishedSynchronously) "Call attempt finished" else "AI Voice call requested"
            )
        }

        @JavascriptInterface
        fun openMessage(idValue: String): String {
            val id = idValue.toLongOrNull() ?: return result(false, "Invalid schedule")
            val task = TaskStore.load(activity).firstOrNull { it.id == id } ?: return result(false, "Schedule not found")
            return if (CallManager.openMessageComposer(activity, task.number, task.messageText)) result(true, "Opening messages") else result(false, "Could not open messages")
        }

        @JavascriptInterface
        fun previewVoice(message: String, language: String, speedValue: String, pitchValue: String): String {
            if (!ttsReady) return result(false, "Text-to-speech is not ready")
            val speed = speedValue.toFloatOrNull() ?: SettingsStore.voiceSpeed(activity)
            val pitch = pitchValue.toFloatOrNull() ?: SettingsStore.voicePitch(activity)
            speakPreview(message, language, speed, pitch)
            return result(true, "Playing voice preview")
        }

        @JavascriptInterface
        fun stopAll(): String {
            val tasks = TaskStore.load(activity)
            CallExecutionStore.clear(activity)
            tasks.forEach { task ->
                CallScheduler.cancel(activity, task.id)
                CallScheduler.cancelWatchdog(activity, task.id)
                CallManager.stopVoiceService(activity)
                if (task.status == "Scheduled" || task.status == "Paused") {
                    task.status = "Stopped"
                    task.nextTrigger = -1L
                    task.liveStatus = "STOPPED_ALL"
                    task.lastResult = "STOPPED_ALL"
                }
            }
            SettingsStore.set(activity, "pausedAll", "false")
            TaskStore.save(activity, tasks)
            refreshWebView()
            return result(true, "All schedules stopped")
        }

        @JavascriptInterface
        fun pauseAll(): String {
            val tasks = TaskStore.load(activity)
            tasks.forEach { task ->
                if (task.status == "Scheduled") {
                    CallScheduler.cancel(activity, task.id)
                    CallManager.stopVoiceService(activity)
                    task.status = "Paused"
                    task.liveStatus = "PAUSED"
                }
            }
            SettingsStore.set(activity, "pausedAll", "true")
            TaskStore.save(activity, tasks)
            refreshWebView()
            return result(true, "All schedules paused")
        }

        @JavascriptInterface
        fun resumeAll(): String {
            SettingsStore.set(activity, "pausedAll", "false")
            val tasks = TaskStore.load(activity)
            var resumed = 0
            tasks.forEach { task ->
                if (task.status == "Paused") {
                    val next = if (task.recurrence != RecurrenceMode.ONCE) {
                        ScheduleCalculator.nextRecurring(task, System.currentTimeMillis())
                    } else task.triggerAt.takeIf { it > System.currentTimeMillis() }
                    if (next != null && CallScheduler.schedule(activity, task.id, next, task.attemptCount + 1)) {
                        task.status = "Scheduled"
                        task.nextTrigger = next
                        task.liveStatus = "WAITING"
                        task.lastResult = "RESUMED"
                        CallScheduler.schedulePreNotification(activity, task)
                        resumed++
                    } else {
                        task.status = "Stopped"
                        task.nextTrigger = -1L
                        task.liveStatus = "RESUME_FAILED"
                        task.lastResult = "RESUME_FAILED"
                    }
                }
            }
            TaskStore.save(activity, tasks)
            refreshWebView()
            return result(true, "Resumed $resumed schedules")
        }

        @JavascriptInterface
        fun clearHistory(): String {
            TaskStore.clearHistory(activity)
            refreshWebView()
            return result(true, "History cleared")
        }

        @JavascriptInterface
        fun openExactAlarmSettings(): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return result(true, "Exact alarms are available")
            return try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(intent)
                result(true, "Opening exact alarm settings")
            } catch (_: Exception) { result(false, "Could not open exact alarm settings") }
        }

        @JavascriptInterface
        fun openBatterySettings(): String =
            if (CallManager.openBatterySettings(activity)) result(true, "Opening battery settings") else result(false, "Could not open battery settings")

        @JavascriptInterface
        fun openNotificationSettings(): String =
            if (CallManager.openNotificationSettings(activity)) result(true, "Opening notification settings") else result(false, "Could not open notification settings")

        @JavascriptInterface
        fun openAppSettings(): String =
            if (CallManager.openAppSettings(activity)) result(true, "Opening app settings") else result(false, "Could not open app settings")

        @JavascriptInterface
        fun requestPermissions(): String {
            activity.runOnUiThread { requestPermissionsIfNeeded() }
            return result(true, "Permission request started")
        }

        @JavascriptInterface
        fun setPinDialog(): String {
            activity.runOnUiThread {
                val first = EditText(activity).apply {
                    hint = "4–8 digit PIN"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                val second = EditText(activity).apply {
                    hint = "Confirm PIN"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 0, 48, 0)
                    addView(first, ViewGroup.LayoutParams(-1, -2))
                    addView(second, ViewGroup.LayoutParams(-1, -2))
                }
                AlertDialog.Builder(activity)
                    .setTitle("Set app PIN")
                    .setView(box)
                    .setPositiveButton("SAVE") { _, _ ->
                        if (first.text.toString() == second.text.toString() && SettingsStore.setPin(activity, first.text.toString())) {
                            Toast.makeText(activity, "PIN saved", Toast.LENGTH_SHORT).show()
                            refreshWebView()
                        } else Toast.makeText(activity, "PIN must match and be 4–8 digits", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            return result(true, "PIN dialog opened")
        }

        @JavascriptInterface
        fun toggleAppLock(enabledValue: String): String {
            val enabled = enabledValue.equals("true", true)
            if (enabled && !SettingsStore.hasPin(activity)) return result(false, "Set a PIN first")
            SettingsStore.set(activity, "appLock", enabled.toString())
            if (enabled) requireUnlock = false
            refreshWebView()
            return result(true, if (enabled) "App lock enabled" else "App lock disabled")
        }

        @JavascriptInterface
        fun backupData(): String {
            backupJsonPending = BackupManager.exportJson(activity)
            activity.runOnUiThread { createBackupLauncher.launch("AutoCallManager-v1.1.0-backup.json") }
            return result(true, "Backup export opened")
        }

        @JavascriptInterface
        fun restoreData(): String {
            activity.runOnUiThread { restoreLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }
            return result(true, "Restore picker opened")
        }

        @JavascriptInterface
        fun getCallState(): String = CallStateManager.describe(CallStateManager.current(activity))

        @JavascriptInterface
        fun getDiagnosticsJson(): String = Diagnostics.snapshot(activity, ttsReady).toString()

        /**
         * Real per-stage trace of the actual background pipeline (see
         * ExecutionTraceStore), NOT a capability snapshot. Pass a task id to
         * see everything recorded for that schedule, or "" for the most
         * recent execution (what Diagnostics shows by default after a test).
         */
        @JavascriptInterface
        fun getExecutionTraceJson(taskIdValue: String): String {
            val taskId = taskIdValue.toLongOrNull()
            val events = if (taskId != null && taskId > 0L) {
                ExecutionTraceStore.forTask(activity, taskId)
            } else {
                ExecutionTraceStore.mostRecent(activity)
            }
            val arr = JSONArray()
            events.forEach { e ->
                arr.put(JSONObject().apply {
                    put("ts", e.ts)
                    put("time", ExecutionTraceStore.formatTimestamp(e.ts))
                    put("taskId", e.taskId)
                    put("executionId", e.executionId)
                    put("attempt", e.attempt)
                    put("stage", e.stage)
                    put("result", e.result)
                    put("detail", e.detail)
                    put("screen", e.screen)
                    put("lock", e.lock)
                    put("battery", e.battery)
                    put("exactAlarm", e.exactAlarm)
                    put("phoneAccount", e.phoneAccount)
                    put("callSource", e.callSource)
                })
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun clearExecutionTrace(): String {
            ExecutionTraceStore.clear(activity)
            return result(true, "Execution trace cleared")
        }

        /**
         * Schedules a REAL call 60 seconds out through the exact same
         * TaskStore/CallScheduler/CallReceiver path as any user schedule, so
         * it exercises the actual disputed pipeline instead of a simulation.
         * Requires an explicit destination number from the user -- it will
         * never reuse an existing contact/schedule, so a "test" can never
         * surprise a real contact with an unexpected call.
         */
        @JavascriptInterface
        fun runBackgroundTest(numberValue: String): String {
            val number = numberValue.trim()
            if (number.isBlank()) return result(false, "Enter a number to call for this test")
            if (!CallManager.isValidPhoneNumber(number)) return result(false, "Enter a valid phone number (6-15 digits)")
            if (!has(Manifest.permission.CALL_PHONE)) return result(false, "Grant Call permission first")
            if (!CallScheduler.canScheduleExactly(activity)) {
                openExactAlarmSettings()
                return result(false, "Allow Alarms & reminders for exact scheduling first")
            }
            val tasks = TaskStore.load(activity)
            val id = System.currentTimeMillis()
            val triggerAt = id + 60_000L
            val task = CallTask(
                id = id,
                number = number,
                contactName = "Diagnostic Test",
                triggerAt = triggerAt,
                maxAttempts = 1,
                retryIntervalSeconds = 60,
                status = "Scheduled",
                nextTrigger = triggerAt,
                liveStatus = "WAITING",
                lastResult = "SCHEDULED",
                callEnabled = true,
                notificationEnabled = true,
                notifyBefore = false,
                notifyAt = true,
                notifyAfter = true,
                groupName = "Diagnostic Test",
                recurrence = RecurrenceMode.ONCE,
                executionMode = "NORMAL_SIM",
                isDiagnosticTest = true
            )
            tasks.add(0, task)
            TaskStore.save(activity, tasks)
            if (!CallScheduler.schedule(activity, id, triggerAt, 1)) {
                task.status = "Stopped"
                task.nextTrigger = -1L
                TaskStore.save(activity, tasks)
                return result(false, "Could not schedule the exact alarm for the test")
            }
            refreshWebView()
            return JSONObject().apply {
                put("ok", true)
                put("taskId", id)
                put("triggerAt", triggerAt)
                put("message", "Real test call scheduled for 60 seconds from now. Lock the screen now.")
            }.toString()
        }

        /** Plain-text export via Storage Access Framework -- no API keys, no full phone numbers. */
        @JavascriptInterface
        fun exportDiagnosticLog(): String {
            val diag = Diagnostics.snapshot(activity, ttsReady)
            val advisory = BackgroundAdvisor.detect(activity)
            val header = buildString {
                appendLine("AutoCallManager v1.1.0 - Diagnostic Log")
                appendLine("Generated: ${java.util.Date()}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
                appendLine("OEM family: ${advisory.family}")
                appendLine("OEM advisory: ${advisory.reason}")
                appendLine("Diagnostics snapshot: $diag")
                appendLine()
            }
            traceExportTextPending = header + ExecutionTraceStore.exportText(activity)
            activity.runOnUiThread {
                createTraceExportLauncher.launch("AutoCallManager-diagnostic-log-${System.currentTimeMillis()}.txt")
            }
            return result(true, "Export dialog opened")
        }

        /** Best-effort deep link to the OEM's own autostart/background screen; always falls back safely. Never claims to fix anything by itself. */
        @JavascriptInterface
        fun openOemBackgroundSettings(): String {
            val message = BackgroundAdvisor.openBestEffortSettings(activity)
            return result(true, message)
        }

        @JavascriptInterface
        fun getDeviceStatusJson(): String {
            val callPermission = has(Manifest.permission.CALL_PHONE)
            val phoneStatePermission = has(Manifest.permission.READ_PHONE_STATE)
            val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallScheduler.canScheduleExactly(activity)
            } else {
                true
            }
            val notificationsPermission =
                Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
            val notificationEnabled =
                androidx.core.app.NotificationManagerCompat.from(activity)
                    .areNotificationsEnabled()
            val callCapableAccount = CallManager.hasCallCapableAccount(activity)
            val callReady = callPermission && exact && (
                !phoneStatePermission || callCapableAccount
            )
            val screenOn = activity.windowManager.defaultDisplay?.let {
                @Suppress("DEPRECATION")
                it.state == android.view.Display.STATE_ON
            } ?: true

            return JSONObject().apply {
                put("callPhone", callPermission)
                put("contacts", has(Manifest.permission.READ_CONTACTS))
                put("phoneState", phoneStatePermission)
                put("simCount", CallManager.activeSimAccounts(activity).size)
                put("callCapableAccount", callCapableAccount)
                put("callEngineReady", callReady)
                put(
                    "callEngineReason",
                    when {
                        !callPermission -> "CALL_PHONE permission required"
                        !exact -> "Exact alarm access required"
                        phoneStatePermission && !callCapableAccount -> "No call-capable phone account found"
                        else -> "Ready for scheduled calls"
                    }
                )
                put("screenInteractive", screenOn)
                put("notifications", notificationsPermission && notificationEnabled)
                put("exactAlarm", exact)
                put("tts", ttsReady)
                put("battery", CallManager.isBatteryUnrestricted(activity))
                put("aiStatus", SettingsStore.aiStatus(activity))
                put("aiLastTest", SettingsStore.aiLastTest(activity))
            }.toString()
        }

        @JavascriptInterface
        fun getAnalyticsJson(): String {
            val tasks = TaskStore.load(activity)
            val history = TaskStore.history(activity)
            var callRequested = 0
            var callConnected = 0
            var callFailed = 0
            var messageReady = 0
            var voiceEvents = 0
            var notifications = 0
            for (i in 0 until history.length()) {
                val o = history.optJSONObject(i) ?: continue
                val result = o.optString("result")
                val eventType = o.optString("eventType")
                if (eventType == "CALL" && result == "CALL_REQUEST_SENT") callRequested++
                if (eventType == "CALL_STATE") {
                    when (result) {
                        "CALL_CONNECTED_ENDED", "CALL_OFFHOOK_ENDED" -> callConnected++
                        "CALL_NO_ANSWER", "CALL_NO_ANSWER_TIMEOUT", "CALL_ENDED_WITHOUT_CONNECT", "CALL_ENDED_WITHOUT_OFFHOOK", "CALL_RESULT_TIMEOUT", "CALL_BLOCKED_ACTIVE_CALL", "DEVICE_CALL_BUSY", "CALL_REQUEST_FAILED", "SECURITY_ERROR", "SIM_ACCOUNT_UNAVAILABLE", "SIM_ACCOUNT_INVALID", "CALL_NOT_PERMITTED", "INVALID_NUMBER", "TELECOM_UNAVAILABLE" -> callFailed++
                    }
                }
                if (result == "MESSAGE_READY") messageReady++
                if (eventType == "VOICE") voiceEvents++
                if (eventType == "NOTIFICATION") notifications++
            }
            return JSONObject().apply {
                put("schedules", tasks.size)
                put("active", tasks.count { it.status == "Scheduled" || it.status == "Running" })
                put("paused", tasks.count { it.status == "Paused" })
                put("callRequested", callRequested)
                put("callConnected", callConnected)
                put("callFailed", callFailed)
                put("messageReady", messageReady)
                put("voiceEvents", voiceEvents)
                put("notifications", notifications)
                put("history", history.length())
            }.toString()
        }

        private fun applyPayload(task: CallTask, payload: JSONObject, triggerAt: Long, maxAttempts: Int, retry: Int, activate: Boolean) {
            task.number = payload.optString("number").trim()
            task.contactName = payload.optString("contactName").trim()
            task.triggerAt = triggerAt
            task.maxAttempts = maxAttempts
            task.retryIntervalSeconds = retry
            task.callEnabled = payload.optBoolean("callEnabled", true)
            task.voiceEnabled = payload.optBoolean("voiceEnabled", false)
            task.messageEnabled = payload.optBoolean("messageEnabled", false)
            task.notificationEnabled = payload.optBoolean("notificationEnabled", true)
            task.notifyBefore = payload.optBoolean("notifyBefore", true)
            task.notifyAt = payload.optBoolean("notifyAt", true)
            task.notifyAfter = payload.optBoolean("notifyAfter", true)
            task.messageText = payload.optString("messageText").trim()
            task.promptId = PromptStore.get(activity, payload.optString("promptId", PromptStore.DEFAULT_PROMPT_ID)).id
            task.promptVersion = payload.optInt("promptVersion", PromptStore.get(activity, task.promptId).version).coerceAtLeast(1)
            task.voiceLanguage = payload.optString("voiceLanguage", SettingsStore.voiceLanguage(activity)).let { if (it in setOf("Hindi", "Hinglish", "English")) it else SettingsStore.voiceLanguage(activity) }
            task.voiceSpeed = payload.optDouble("voiceSpeed", SettingsStore.voiceSpeed(activity).toDouble()).toFloat().coerceIn(0.5f, 2.0f)
            task.voicePitch = payload.optDouble("voicePitch", SettingsStore.voicePitch(activity).toDouble()).toFloat().coerceIn(0.5f, 1.5f)
            task.groupName = payload.optString("groupName", "")
            task.recurrence = runCatching { RecurrenceMode.valueOf(payload.optString("recurrence", "ONCE")) }.getOrDefault(RecurrenceMode.ONCE)
            task.repeatDays = jsonIntSet(payload.optJSONArray("repeatDays"))
            task.timeSlots = jsonIntList(payload.optJSONArray("timeSlots"))
            task.attemptCount = 0
            task.activeSlotIndex = 0
            task.simSelectionEnabled = payload.optBoolean("simSelectionEnabled", false)
            task.phoneAccountId = payload.optString("phoneAccountId", "")
            task.phoneAccountComponent = payload.optString("phoneAccountComponent", "")
            task.phoneAccountLabel = payload.optString("phoneAccountLabel", "")
            // Re-anchor to the device's current zone on every save, same rule as creation
            // (the review screen already shows this as a live value before saving, for both
            // create and edit -- this was previously only wired up for creation).
            task.timezoneId = payload.optString("timezoneId", task.timezoneId).ifBlank { task.timezoneId }
            task.executionMode = payload.optString("executionMode", task.executionMode).let { if (it == "AI_VOICE") "AI_VOICE" else "NORMAL_SIM" }
            if (task.simSelectionEnabled) {
                val valid = CallManager.activeSimAccounts(activity).any {
                    it.handleId == task.phoneAccountId && it.component == task.phoneAccountComponent
                }
                if (!valid) {
                    task.simSelectionEnabled = false
                    task.phoneAccountId = ""
                    task.phoneAccountComponent = ""
                    task.phoneAccountLabel = ""
                }
            }
            task.status = if (activate) "Scheduled" else "Stopped"
            task.nextTrigger = if (activate) {
                if (task.recurrence != RecurrenceMode.ONCE) {
                    ScheduleCalculator.nextRecurring(task, System.currentTimeMillis() - 1L) ?: triggerAt
                } else triggerAt
            } else -1L
            task.liveStatus = if (activate) "WAITING" else "STORED"
            task.lastResult = if (activate) "SCHEDULED" else "SAVED"
        }

        private fun jsonIntList(array: JSONArray?): List<Int> {
            if (array == null) return emptyList()
            val out = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                val v = array.optInt(i, -1)
                if (v in 0..1439) out += v
            }
            return out.distinct()
        }

        private fun jsonIntSet(array: JSONArray?): Set<Int> = jsonIntList(array).filter { it in 1..7 }.toSet()

        private fun taskToJson(task: CallTask): JSONObject = JSONObject().apply {
            put("id", task.id)
            put("number", task.number)
            put("contactName", task.contactName)
            put("triggerAt", task.triggerAt)
            put("maxAttempts", task.maxAttempts)
            put("retryIntervalSeconds", task.retryIntervalSeconds)
            put("attemptCount", task.attemptCount)
            put("status", task.status)
            put("nextTrigger", task.nextTrigger)
            put("liveStatus", task.liveStatus)
            put("lastResult", task.lastResult)
            put("callEnabled", task.callEnabled)
            put("voiceEnabled", task.voiceEnabled)
            put("messageEnabled", task.messageEnabled)
            put("notificationEnabled", task.notificationEnabled)
            put("notifyBefore", task.notifyBefore)
            put("notifyAt", task.notifyAt)
            put("notifyAfter", task.notifyAfter)
            put("messageText", task.messageText)
            put("promptId", task.promptId)
            put("promptVersion", task.promptVersion)
            put("voiceLanguage", task.voiceLanguage)
            put("voiceSpeed", task.voiceSpeed)
            put("voicePitch", task.voicePitch)
            put("groupName", task.groupName)
            put("recurrence", task.recurrence.name)
            put("repeatDays", JSONArray(task.repeatDays.sorted()))
            put("timeSlots", JSONArray(task.timeSlots.sorted()))
            put("activeSlotIndex", task.activeSlotIndex)
            put("simSelectionEnabled", task.simSelectionEnabled)
            put("phoneAccountId", task.phoneAccountId)
            put("phoneAccountComponent", task.phoneAccountComponent)
            put("phoneAccountLabel", task.phoneAccountLabel)
            put("timezoneId", task.timezoneId)
            put("executionMode", task.executionMode)
        }

        private fun result(ok: Boolean, message: String): String =
            JSONObject().put("ok", ok).put("message", message).toString()
    }
}
