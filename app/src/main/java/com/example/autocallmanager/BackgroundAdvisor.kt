package com.example.autocallmanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Honest, best-effort detection of OEM-level background restrictions that
 * sit ON TOP OF standard Android battery/exact-alarm settings and are not
 * visible through any public AOSP API (MIUI/ColorOS/RealmeUI/FunTouch-style
 * "Autostart"/"Startup Manager"/"App Lock" screens).
 *
 * This class does not and cannot force a fix. It can only:
 *  1) name the manufacturer family so the user knows to go looking, and
 *  2) try to jump straight to the likely settings screen, falling back to
 *     the guaranteed-to-exist app-info page when the deep link doesn't
 *     resolve on this ROM/version.
 *
 * The exact component names below are widely reported by other open-source
 * "don't kill my app"-style projects, but OEMs change them across ROM
 * versions without notice, so every one of them is verified with
 * resolveActivity() before use and silently skipped if absent -- a stale
 * entry here degrades to "no deep link found", never a crash.
 */
object BackgroundAdvisor {

    data class Advisory(
        val manufacturer: String,
        val family: String,
        val likelyExtraRestrictions: Boolean,
        val reason: String
    )

    // manufacturer/brand substring -> (family label, candidate settings activities to try in order)
    private val OEM_TABLE: List<Triple<List<String>, String, List<Pair<String, String>>>> = listOf(
        Triple(
            listOf("xiaomi", "redmi", "poco"), "MIUI / HyperOS",
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.optimizecenter.autostart.AutoStartManagementActivity"
            )
        ),
        Triple(
            listOf("realme", "oppo"), "ColorOS / realme UI",
            listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),
        Triple(
            listOf("vivo", "iqoo"), "FuntouchOS / OriginOS",
            listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        ),
        Triple(
            listOf("oneplus"), "OxygenOS",
            listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        ),
        Triple(
            listOf("huawei", "honor"), "EMUI / MagicUI",
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        ),
        Triple(
            listOf("asus"), "ASUS ZenUI",
            listOf(
                "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
            )
        ),
        Triple(
            listOf("lenovo", "zui"), "ZUI",
            listOf(
                "com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity"
            )
        )
    )

    /**
     * Manufacturers whose stock ROM is documented (by widespread developer
     * reports, not by any Google API) to sometimes ignore standard battery
     * unrestricted / exact-alarm grants unless the OEM's own switch is also
     * set. Used only to decide whether to SHOW the advisory -- never to
     * claim the app has verified or fixed anything on that device.
     */
    private val KNOWN_AGGRESSIVE = setOf(
        "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo",
        "oneplus", "huawei", "honor", "asus", "lenovo", "meizu",
        "infinix", "tecno", "itel"
    )

    /**
     * Pure classification: manufacturer/brand strings in, advisory out.
     * No Context, no Intents -- kept separate from resolve()/openSettings()
     * so this logic is plain-JVM unit testable (see BackgroundAdvisorTest).
     */
    internal fun classify(manufacturer: String, brand: String): Advisory {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val entry = OEM_TABLE.firstOrNull { (keys, _, _) -> keys.any { it in m || it in b } }
        val family = entry?.second ?: "Stock / unrecognized Android"
        val flagged = KNOWN_AGGRESSIVE.any { it in m || it in b }
        val reason = if (flagged) {
            "$family devices are widely reported to apply an additional, OEM-specific background/" +
                "autostart restriction on top of standard Android battery settings. Granting " +
                "\"Unrestricted\" battery and exact-alarm access in Android Settings may not be " +
                "enough on this device -- an extra manufacturer toggle (often called Autostart, " +
                "Startup Manager, or App Lock/Battery > No restrictions) may also need to be enabled " +
                "by hand. AutoCallManager cannot detect or set that toggle directly; no public " +
                "Android API exposes it."
        } else {
            "No OEM-specific background restriction is expected beyond the standard Android " +
                "settings already checked above."
        }
        return Advisory(manufacturer, family, flagged, reason)
    }

    fun detect(context: Context): Advisory =
        classify(Build.MANUFACTURER ?: "", Build.BRAND ?: "")

    /**
     * Best-effort jump to the OEM's own autostart/background settings screen.
     * Every candidate is verified resolvable before being launched; if none
     * resolve (wrong ROM version, renamed component, non-matching OEM), this
     * falls back to the standard per-app "App info" screen, which always
     * exists. Returns which screen it actually opened, so the caller can be
     * honest with the user instead of assuming success.
     */
    fun openBestEffortSettings(context: Context): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        val candidates = OEM_TABLE
            .filter { (keys, _, _) -> keys.any { it in manufacturer.lowercase() || it in brand.lowercase() } }
            .flatMap { it.third }

        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(intent)
                    "Opened $pkg background/autostart settings"
                } catch (_: ActivityNotFoundException) {
                    openAppInfoFallback(context)
                } catch (_: SecurityException) {
                    openAppInfoFallback(context)
                }
            }
        }
        return openAppInfoFallback(context)
    }

    private fun openAppInfoFallback(context: Context): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "No OEM-specific screen found on this ROM version -- opened standard App info instead"
        } catch (_: Exception) {
            "Unable to open any settings screen"
        }
    }
}
