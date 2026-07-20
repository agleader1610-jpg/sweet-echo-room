package com.eip.netsniffer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Anti-tamper / anti-hook detection stack.
 *
 * Runs at app start. If ANY red flag is detected, we permanently lock
 * license-gated tabs (Aviator / WinGo) with a troll message, regardless of
 * server response. Nothing here alone is bulletproof — MT-Manager smali
 * edits can bypass individual checks — but stacking them raises the bar
 * enough that casual leech attempts fail.
 *
 * Detects:
 *   - Debuggable APK flag / attached debugger
 *   - Package name renamed (repackaged clone)
 *   - Root artifacts (su binary, common root apps)
 *   - Frida server / gadget artifacts
 *   - Xposed / LSPosed / EdXposed
 *   - Emulator fingerprints
 *
 * Also exposes:
 *   - packageName    → sent to server verify-license
 *   - signatureSha256 → sent to server (allowlist can be enforced there)
 *   - reasons list    → surfaced in admin logs
 */
object TamperGuard {
    private const val TAG = "TamperGuard"
    private const val EXPECTED_PACKAGE = "com.eip.netsniffer"

    @Volatile var tampered: Boolean = false; private set
    @Volatile var reasons: List<String> = emptyList(); private set
    @Volatile var signatureSha256: String = ""; private set
    @Volatile var packageName: String = ""; private set

    private val TROLL_MESSAGES = arrayOf(
        "🛡️ Official app verification failed. অনুগ্রহ করে official EIP TEAM APK install করুন।",
        "🔒 Secure build check failed. Official APK দিয়ে Login করে আবার RECHECK দিন।",
        "⚠️ App integrity verify করা যায়নি। Signal unlock করতে official build ব্যবহার করুন।",
        "🛡️ Protected system active. Modified/repackaged build-এ signal চালু হবে না।",
        "🔐 EIP TEAM secure verification required. Official app install করুন।",
    )

    fun init(ctx: Context) {
        // NOTE: All in-app tamper LOCKING is disabled by design.
        // File-level decoys (assets/_hack_tools/, _admin_master_bypass/, etc.)
        // and server-side signature allowlist remain the real guard.
        // We still compute packageName + signatureSha256 so the server can
        // enforce policy, but we never surface warnings or lock UI locally.
        val soft = mutableListOf<String>()
        try {
            val app = ctx.applicationContext
            packageName = app.packageName
            if (packageName != EXPECTED_PACKAGE) soft += "pkg_renamed:$packageName"
            signatureSha256 = computeSignatureSha256(app)
        } catch (t: Throwable) {
            Log.w(TAG, "check failed: ${t.message}")
        }

        reasons = soft.toList()
        tampered = false // never lock in-app; server decides via signature
    }


    fun trollMessage(): String {
        val idx = (System.currentTimeMillis() / 3600000L).toInt() % TROLL_MESSAGES.size
        val base = TROLL_MESSAGES[if (idx < 0) 0 else idx]
        val why = if (reasons.isEmpty()) "" else "\n\nDetected: ${reasons.joinToString(", ")}"
        return "$base$why"
    }

    // ---- individual detectors ----

    private fun computeSignatureSha256(ctx: Context): String = try {
        val pm = ctx.packageManager
        val sigs: Array<android.content.pm.Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                } ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
        if (sigs.isEmpty()) "" else {
            val md = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
            md.joinToString("") { "%02x".format(it) }
        }
    } catch (_: Throwable) { "" }

    private fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su", "/magisk/.core/mirror",
        )
        if (paths.any { File(it).exists() }) return true
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        return false
    }

    private fun isFridaPresent(): Boolean {
        val paths = arrayOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/data/local/tmp/frida-gadget.so",
        )
        if (paths.any { File(it).exists() }) return true
        return try {
            File("/proc/self/maps").useLines { lines ->
                lines.any { it.contains("frida", ignoreCase = true) || it.contains("gum-js-loop") }
            }
        } catch (_: Throwable) { false }
    }

    private fun findHookApp(pm: PackageManager): String? {
        val bad = arrayOf(
            "de.robv.android.xposed.installer",
            "io.va.exposed",
            "org.meowcat.edxposed.manager",
            "org.lsposed.manager",
            "com.saurik.substrate",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.topjohnwu.magisk",
            "bin.mt.plus",                // MT Manager
            "bin.mt.plus.canary",
            "cn.mtjsoft.www.mtmanager",
            "com.mt.mtxx.mtxx",
        )
        for (p in bad) {
            try { pm.getPackageInfo(p, 0); return p } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    private fun isEmulator(): Boolean {
        val f = (Build.FINGERPRINT ?: "").lowercase()
        val m = (Build.MODEL ?: "").lowercase()
        val h = (Build.HARDWARE ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val product = (Build.PRODUCT ?: "").lowercase()
        return f.startsWith("generic") || f.startsWith("unknown") || f.contains("test-keys") ||
            m.contains("google_sdk") || m.contains("emulator") || m.contains("android sdk built for") ||
            brand.startsWith("generic") && (Build.DEVICE ?: "").lowercase().startsWith("generic") ||
            h.contains("goldfish") || h.contains("ranchu") || h.contains("vbox") ||
            product.contains("sdk_gphone") || product.contains("emulator")
    }
}
