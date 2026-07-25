package com.eip.netsniffer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Fail-open remote version gate + startup notice popup.
 *
 * On launch we hit the admin-controlled config endpoint. If the server
 * marks this build as too old AND force_update is on, we show a modal
 * that only lets the user download the new APK.
 *
 * We also read `startup_note`: if non-empty AND the user has not yet
 * acknowledged this exact text, we show a popup with an OK button first —
 * once the user taps OK, the app continues normally.
 *
 * Any failure → silently continue.
 */
object VersionChecker {
    private const val CONFIG_URL =
        "https://apiapk.eip.lat/api/public/app-version"

    private const val PREFS = "eip_startup_notice"
    private const val KEY_LAST_HASH = "last_note_hash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun check(activity: Activity) {
        thread(isDaemon = true) {
            try {
                val currentCode = try {
                    @Suppress("DEPRECATION")
                    activity.packageManager
                        .getPackageInfo(activity.packageName, 0).versionCode
                } catch (_: PackageManager.NameNotFoundException) { 1 }

                val res = client.newCall(Request.Builder().url(CONFIG_URL).build()).execute()
                if (!res.isSuccessful) return@thread
                val body = res.body?.string() ?: return@thread
                val j = JSONObject(body)
                if (!j.optBoolean("ok", false)) return@thread

                val minVersion = j.optInt("min_version", 1)
                val force = j.optBoolean("force_update", false)
                val apkUrl = j.optString("apk_url", "")
                val msg = j.optString(
                    "update_message",
                    "নতুন version এসেছে। অনুগ্রহ করে update করুন।"
                )
                val vName = j.optString("latest_version_name", "")
                val startupNote = j.optString("startup_note", "").trim()

                if (force && currentCode < minVersion) {
                    Handler(Looper.getMainLooper()).post {
                        showBlockDialog(activity, msg, vName, apkUrl)
                    }
                    return@thread
                }

                if (startupNote.isNotEmpty()) {
                    val hash = sha1(startupNote)
                    val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val last = prefs.getString(KEY_LAST_HASH, "") ?: ""
                    if (hash != last) {
                        Handler(Looper.getMainLooper()).post {
                            showStartupNotice(activity, startupNote) {
                                prefs.edit().putString(KEY_LAST_HASH, hash).apply()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fail-open — do nothing.
            }
        }
    }

    private fun sha1(s: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(s.toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { s.hashCode().toString() }
    }

    private fun showStartupNotice(activity: Activity, note: String, onAck: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("📢 নোটিশ")
            .setMessage(note)
            .setCancelable(false)
            .setPositiveButton("OK") { d, _ ->
                onAck()
                d.dismiss()
            }
            .create()
            .show()
    }

    private fun showBlockDialog(activity: Activity, msg: String, vName: String, apkUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val title = if (vName.isNotEmpty()) "Update required (v$vName)" else "Update required"
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Download new APK") { _, _ ->
                if (apkUrl.isNotEmpty()) {
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {}
                }
                activity.finishAffinity()
            }
            .setNegativeButton("Exit") { _, _ -> activity.finishAffinity() }
            .create()
        dialog.show()
    }
}
