package com.eip.netsniffer

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
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
 * DepositGate — per-game deposit-based access control layered on top of LicenseGate.
 *
 * Each game (aviator / wingo / wingo_auto) is checked and gated independently
 * against two conditions on the server:
 *   1. LIFETIME minimum successful deposits (one-time, persists forever)
 *   2. RECENCY window (last N days must contain >= M successful deposits)
 *
 * Flow:
 *   1. CaptureStore forwards every NetEntry here via tryExtract().
 *   2. On Login: capture UID → set daily-redirect flag → prefetch all 3 games.
 *   3. On GetRechargeRecord: upload successful rows → recheck all games.
 *   4. Per-game LiveData drives DepositLockOverlay on each fragment.
 */
object DepositGate {
    private const val TAG = "DepositGate"
    private const val PREFS = "eip_deposit"
    private const val KEY_UID = "uid"
    private const val KEY_LAST_REDIRECT_PREFIX = "redirect_"
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

    const val GAME_AVIATOR = "aviator"
    const val GAME_WINGO = "wingo"
    const val GAME_WINGO_AUTO = "wingo_auto"
    private val GAMES = listOf(GAME_AVIATOR, GAME_WINGO, GAME_WINGO_AUTO)

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
        val game: String,
    ) {
        val locked: Boolean get() = !allowed
    }

    private val states: Map<String, MutableLiveData<State>> = GAMES.associateWith {
        MutableLiveData(State(true, "unknown", "", null, it))
    }

    /** Combined "any game locked" — for backward compatibility. */
    val state = MutableLiveData(State(true, "unknown", "", null, GAME_AVIATOR))

    /** Fires true exactly once when the app should push the browser to RechargeHistory. */
    val needsRedirect = MutableLiveData(false)

    private val exec = Executors.newSingleThreadExecutor()
    private var deviceId: String? = null
    @Volatile private var lastCheckedAt: Long = 0L

    fun stateFor(game: String): LiveData<State> = states[game] ?: states[GAME_AVIATOR]!!

    fun init(ctx: Context, deviceId: String?) {
        this.deviceId = deviceId
        val p = prefs(ctx)
        val uid = p.getString(KEY_UID, null)
        GAMES.forEach { g ->
            val allowed = p.getBoolean("allowed_$g", true)
            val reason = p.getString("reason_$g", "unknown") ?: "unknown"
            val msg = p.getString("msg_$g", "") ?: ""
            states[g]?.postValue(State(allowed, reason, msg, uid, g))
        }
        recomputeCombined()
        val age = System.currentTimeMillis() - p.getLong("last_check", 0L)
        if (uid != null && age > CACHE_TTL_MS) checkAll(ctx, uid)
    }

    /** Legacy — true if ANY game is currently locked. */
    fun isLocked(): Boolean = states.values.any { it.value?.locked == true }
    /** Per-game lock check. */
    fun isLocked(game: String): Boolean = states[game]?.value?.locked == true

    fun currentMessage(): String = currentMessage(GAME_AVIATOR)
    fun currentMessage(game: String): String = states[game]?.value?.message?.takeIf { it.isNotBlank() }
        ?: "🔒 এই game ব্যবহার করার জন্য deposit প্রয়োজন। DK Win tab-এ গিয়ে deposit করুন।"

    fun refresh(ctx: Context) {
        val uid = prefs(ctx).getString(KEY_UID, null) ?: state.value?.uid ?: return
        checkAll(ctx, uid)
    }

    /** Called by CaptureStore.add for every network response. */
    fun tryExtract(ctx: Context, entry: NetEntry) {
        val url = entry.url.lowercase()
        val body = entry.resBody ?: return
        if (body.length < 2 || body.firstOrNull() != '{') return

        val isLogin = url.contains("/webapi/login") || url.contains("/api/login") ||
                url.contains("/auth/login") || url.contains("/user/login")
        if (isLogin) {
            val uid = extractUidFrom(body)
            if (uid != null) onLogin(ctx, uid)
            return
        }

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

        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val redirectKey = KEY_LAST_REDIRECT_PREFIX + uid
        val lastDay = p.getString(redirectKey, null)
        if (lastDay != today) {
            p.edit().putString(redirectKey, today).apply()
            needsRedirect.postValue(true)
        }
        checkAll(ctx, uid)
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
                val token = CaptureUploader.INGEST_TOKEN
                if (token.isBlank()) { Log.w(TAG, "ingest token missing"); return@execute }
                val body = JSONObject().apply {
                    put("uid", uid)
                    put("device_id", deviceId ?: JSONObject.NULL)
                    put("records", records)
                }
                val res = post(INGEST_URL, body.toString(), token)
                Log.d(TAG, "recharge upload → $res")
                checkAll(appCtx, uid)
            } catch (t: Throwable) {
                Log.w(TAG, "recharge upload failed: ${t.message}")
            }
        }
    }

    private fun checkAll(ctx: Context, uid: String) {
        val appCtx = ctx.applicationContext
        // De-duplicate rapid consecutive calls within 500 ms.
        val now = System.currentTimeMillis()
        if (now - lastCheckedAt < 500L) return
        lastCheckedAt = now
        GAMES.forEach { g -> check(appCtx, uid, g) }
    }

    private fun check(ctx: Context, uid: String, game: String) {
        val appCtx = ctx.applicationContext
        exec.execute {
            try {
                val body = JSONObject().apply {
                    put("uid", uid)
                    put("device_id", deviceId ?: JSONObject.NULL)
                    put("game", game)
                }
                val txt = post(CHECK_URL, body.toString(), null) ?: return@execute
                val j = JSONObject(txt)
                val allowed = j.optBoolean("allowed", true)
                val reason = j.optString("reason", "unknown")
                val msg = j.optString("message", "")
                prefs(appCtx).edit().apply {
                    putBoolean("allowed_$game", allowed)
                    putString("reason_$game", reason)
                    putString("msg_$game", msg)
                    putString(KEY_UID, uid)
                    putLong("last_check", System.currentTimeMillis())
                    apply()
                }
                states[game]?.postValue(State(allowed, reason, msg, uid, game))
                recomputeCombined()
                Log.d(TAG, "[$game] gate → allowed=$allowed reason=$reason")
            } catch (t: Throwable) {
                Log.w(TAG, "[$game] gate check failed: ${t.message}")
            }
        }
    }

    private fun recomputeCombined() {
        // Combined = locked if any game is locked (drives legacy callers).
        val firstLocked = states.values.firstOrNull { it.value?.locked == true }?.value
        state.postValue(firstLocked ?: states[GAME_AVIATOR]?.value
            ?: State(true, "unknown", "", null, GAME_AVIATOR))
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
