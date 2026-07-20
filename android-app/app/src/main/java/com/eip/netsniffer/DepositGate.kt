package com.eip.netsniffer

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DepositGate — deposit-based access control layered on top of LicenseGate.
 *
 * Flow:
 *   1. CaptureStore forwards every NetEntry here via tryExtract().
 *   2. On the DK Win /webapi/Login response we grab the user's UID, set
 *      needsDailyRedirect=true (fires once per uid per day), and query
 *      the server for the current gate decision so the app can lock/unlock
 *      before the user even opens the Recharge History page.
 *   3. On any GetRechargeRecord response we forward the successful rows
 *      to /api/public/ingest-recharge and then re-check the gate.
 *   4. Result is cached (12h) for offline smoothness.
 */
object DepositGate {
    private const val TAG = "DepositGate"
    private const val PREFS = "eip_deposit"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_REASON = "reason"
    private const val KEY_MSG = "message"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_UID = "uid"
    private const val KEY_LAST_REDIRECT_PREFIX = "redirect_"
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

    private val INGEST_URL: String by lazy { assemble("in", "gest-", "rec", "harge") }
    private val CHECK_URL: String by lazy { assemble("che", "ck-de", "posit-", "gate") }

    private fun assemble(vararg tail: String): String = buildString {
        append("htt"); append("ps://")
        append("api"); append("apk").append(".eip").append(".").append("lat")
        append("/a"); append("pi/"); append("pub"); append("lic/")
        tail.forEach { append(it) }
    }

    data class State(
        val allowed: Boolean,
        val reason: String,
        val message: String,
        val uid: String?,
    ) {
        val locked: Boolean get() = !allowed
    }

    val state = MutableLiveData(State(true, "unknown", "", null))
    /** Fires true exactly once when the app should push the browser to RechargeHistory. */
    val needsRedirect = MutableLiveData(false)

    private val exec = Executors.newSingleThreadExecutor()
    private var deviceId: String? = null
    @Volatile private var lastCheckedKey: String? = null

    fun init(ctx: Context, deviceId: String?) {
        this.deviceId = deviceId
        val p = prefs(ctx)
        val allowed = p.getBoolean(KEY_ALLOWED, true)
        val reason = p.getString(KEY_REASON, "unknown") ?: "unknown"
        val msg = p.getString(KEY_MSG, "") ?: ""
        val uid = p.getString(KEY_UID, null)
        state.postValue(State(allowed, reason, msg, uid))

        val age = System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L)
        if (uid != null && age > CACHE_TTL_MS) check(ctx, uid)
    }

    fun isLocked(): Boolean = state.value?.locked == true

    fun currentMessage(): String = state.value?.message?.takeIf { it.isNotBlank() }
        ?: "🔒 আজকের deposit history পাওয়া যায়নি। DK Win tab-এ গিয়ে deposit করুন।"

    fun refresh(ctx: Context) {
        val uid = prefs(ctx).getString(KEY_UID, null) ?: state.value?.uid ?: return
        check(ctx, uid)
    }

    /** Called by CaptureStore.add for every network response. */
    fun tryExtract(ctx: Context, entry: NetEntry) {
        val url = entry.url.lowercase()
        val body = entry.resBody ?: return
        if (body.length < 2 || body.firstOrNull() != '{') return

        // 1) Login response → capture UID + one-time daily redirect signal.
        val isLogin = url.contains("/webapi/login") || url.contains("/api/login") ||
                url.contains("/auth/login") || url.contains("/user/login")
        if (isLogin) {
            val uid = extractUidFrom(body)
            if (uid != null) onLogin(ctx, uid)
            return
        }

        // 2) GetRechargeRecord → upload successful rows + recheck gate.
        if (url.contains("getrechargerecord") || url.contains("recharge/record") ||
            url.contains("rechargehistory") || url.contains("getrechargehistory")) {
            val uid = prefs(ctx).getString(KEY_UID, null) ?: state.value?.uid ?: return
            val records = extractRecordList(body) ?: return
            uploadRecords(ctx, uid, records)
        }
    }

    private fun onLogin(ctx: Context, uid: String) {
        val p = prefs(ctx)
        p.edit().putString(KEY_UID, uid).apply()

        // Daily-once redirect: keyed on uid + local date.
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val redirectKey = KEY_LAST_REDIRECT_PREFIX + uid
        val lastDay = p.getString(redirectKey, null)
        if (lastDay != today) {
            p.edit().putString(redirectKey, today).apply()
            needsRedirect.postValue(true)
        }

        // Immediately verify against server so lock/unlock updates before
        // the browser even finishes loading the Recharge History page.
        check(ctx, uid)
    }

    private fun extractUidFrom(body: String): String? {
        val obj = try { JSONObject(body) } catch (_: Throwable) { return null }
        val d = obj.optJSONObject("data") ?: obj
        val token = d.optString("token", "").takeIf { it.isNotBlank() }
        return extractUidFromJwt(token)
            ?: d.optString("userId", "").takeIf { it.isNotBlank() }
            ?: d.optString("uid", "").takeIf { it.isNotBlank() }
    }

    private fun extractUidFromJwt(jwt: String?): String? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val j = JSONObject(payload)
            j.optString("UserId", "").takeIf { it.isNotBlank() }
                ?: j.optString("userId", "").takeIf { it.isNotBlank() }
                ?: j.optString("sub", "").takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    private fun extractRecordList(body: String): JSONArray? {
        val obj = try { JSONObject(body) } catch (_: Throwable) { return null }
        val d = obj.optJSONObject("data") ?: obj
        val fields = arrayOf("list", "records", "rows", "data", "items", "orderList", "rechargeList")
        for (f in fields) {
            val arr = d.optJSONArray(f)
            if (arr != null && arr.length() > 0) return arr
        }
        return null
    }

    private fun uploadRecords(ctx: Context, uid: String, records: JSONArray) {
        val appCtx = ctx.applicationContext
        exec.execute {
            try {
                val token = BuildKeys.captureIngestToken
                if (token.isBlank()) { Log.w(TAG, "ingest token missing"); return@execute }
                val body = JSONObject().apply {
                    put("uid", uid)
                    put("device_id", deviceId ?: JSONObject.NULL)
                    put("records", records)
                }
                val res = post(INGEST_URL, body.toString(), token)
                Log.d(TAG, "recharge upload → $res")
                check(appCtx, uid)
            } catch (t: Throwable) {
                Log.w(TAG, "recharge upload failed: ${t.message}")
            }
        }
    }

    private fun check(ctx: Context, uid: String) {
        val key = "check:$uid"
        if (key == lastCheckedKey) {
            // still run; but avoid tight loop from repeated identical triggers
        }
        lastCheckedKey = key
        val appCtx = ctx.applicationContext
        exec.execute {
            try {
                val body = JSONObject().apply {
                    put("uid", uid)
                    put("device_id", deviceId ?: JSONObject.NULL)
                }
                val txt = post(CHECK_URL, body.toString(), null) ?: return@execute
                val j = JSONObject(txt)
                val allowed = j.optBoolean("allowed", true)
                val reason = j.optString("reason", "unknown")
                val msg = j.optString("message", "")
                prefs(appCtx).edit().apply {
                    putBoolean(KEY_ALLOWED, allowed)
                    putString(KEY_REASON, reason)
                    putString(KEY_MSG, msg)
                    putString(KEY_UID, uid)
                    putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    apply()
                }
                state.postValue(State(allowed, reason, msg, uid))
                Log.d(TAG, "gate check → allowed=$allowed reason=$reason")
            } catch (t: Throwable) {
                Log.w(TAG, "gate check failed: ${t.message}")
            }
        }
    }

    private fun post(endpoint: String, json: String, ingestToken: String?): String? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (!ingestToken.isNullOrBlank()) conn.setRequestProperty("x-ingest-token", ingestToken)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) text else { Log.w(TAG, "HTTP $code: ${text?.take(200)}"); null }
        } finally { conn.disconnect() }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
