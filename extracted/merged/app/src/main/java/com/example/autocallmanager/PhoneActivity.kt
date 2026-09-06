package com.example.autocallmanager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * PHASE 2 of the master prompt: a real, modular Phone/Calls module.
 *
 * Reached from the dashboard header's phone icon
 * (MainActivity.NativeBridge.openPhoneModule()) and closed with the normal
 * system Back gesture/button, which for a plain Activity with no back-stack
 * manipulation already finishes straight back to Home -- no custom
 * onBackPressed override needed here.
 *
 * Follows the exact same WebView + JavascriptInterface shape MainActivity
 * already uses for the dashboard (own asset file, own bridge object), kept
 * in its own Activity/asset/bridge so this module can evolve without
 * touching the Scheduler/AI Voice dashboard code in MainActivity.kt.
 *
 * Manual calling itself is not implemented here: [PhoneNativeBridge.placeCall]
 * delegates to [PhoneCallController], which in turn delegates to the
 * existing [CallManager.placeCallDetailed] -- the same Telecom call path
 * the Scheduler already uses. This activity's job is the UI, permission
 * timing, and reading Call Log/Contacts for display.
 */
class PhoneActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val watcher by lazy { PhoneCallStateWatcher(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshWebView() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)

        webView = findViewById(R.id.phoneWebView)
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
        webView.addJavascriptInterface(PhoneNativeBridge(this), "NativeBridge")
        webView.loadUrl("file:///android_asset/phone_ui.html")

        // Contextual permission request: only asked when the Phone module is
        // actually opened, not at app startup. CALL_PHONE/READ_CONTACTS/
        // READ_PHONE_STATE are normally already granted from MainActivity's
        // first-run request -- this call is a no-op for those and only
        // truly prompts for the new READ_CALL_LOG permission the Recent
        // Calls tab needs.
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        watcher.start { state -> pushCallState(state.name) }
    }

    override fun onStop() {
        watcher.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("NativeBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.READ_CALL_LOG)) needed += Manifest.permission.READ_CALL_LOG
        if (!has(Manifest.permission.READ_CONTACTS)) needed += Manifest.permission.READ_CONTACTS
        if (!has(Manifest.permission.CALL_PHONE)) needed += Manifest.permission.CALL_PHONE
        if (!has(Manifest.permission.READ_PHONE_STATE)) needed += Manifest.permission.READ_PHONE_STATE
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

    private fun pushCallState(state: String) {
        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                "if(typeof syncCallState==='function'){syncCallState('$state');}",
                null
            )
        }
    }

    inner class PhoneNativeBridge(private val activity: Activity) {

        @JavascriptInterface
        fun getAppearanceJson(): String = JSONObject().apply {
            put("theme", SettingsStore.theme(activity))
            put("accent", SettingsStore.accent(activity))
            put("fontSize", SettingsStore.fontSize(activity))
        }.toString()

        @JavascriptInterface
        fun getPermissionStateJson(): String = JSONObject().apply {
            put("callLog", has(Manifest.permission.READ_CALL_LOG))
            put("contacts", has(Manifest.permission.READ_CONTACTS))
            put("callPhone", has(Manifest.permission.CALL_PHONE))
        }.toString()

        @JavascriptInterface
        fun requestPhonePermissions(): String {
            activity.runOnUiThread { requestNeededPermissions() }
            return result(true, "Permission request started")
        }

        @JavascriptInterface
        fun getRecentCallsJson(filter: String): String =
            RecentCallsRepository.load(activity, filter).toString()

        @JavascriptInterface
        fun getContactsJson(): String = PhoneContactsRepository.load(activity).toString()

        @JavascriptInterface
        fun getSimAccountsJson(): String = JSONArray().apply {
            PhoneCallController.simAccounts(activity).forEach { sim ->
                put(JSONObject().apply {
                    put("handleId", sim.handleId)
                    put("component", sim.component)
                    put("label", sim.label)
                    put("slotIndex", sim.slotIndex)
                    put("subscriptionId", sim.subscriptionId)
                })
            }
        }.toString()

        @JavascriptInterface
        fun placeCall(number: String, phoneAccountComponent: String, phoneAccountId: String): String {
            val r = PhoneCallController.call(activity, number, phoneAccountComponent, phoneAccountId)
            return JSONObject().apply {
                put("ok", r.ok)
                put("code", r.code)
                put("message", r.message)
            }.toString()
        }

        @JavascriptInterface
        fun getCallState(): String = watcher.current().name

        @JavascriptInterface
        fun goBack(): String {
            activity.runOnUiThread { activity.finish() }
            return result(true, "Closing Phone module")
        }

        private fun has(permission: String): Boolean =
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

        private fun result(ok: Boolean, message: String): String =
            JSONObject().put("ok", ok).put("message", message).toString()
    }
}
