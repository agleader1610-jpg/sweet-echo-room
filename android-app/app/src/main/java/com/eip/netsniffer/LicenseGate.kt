package com.eip.netsniffer

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * LicenseGate — team-invite based unlock system.
 *
 * Flow:
 *   1. SniffingWebViewClient captures the DK Win / lottery Login response.
 *   2. CaptureStore.add() forwards each NetEntry here via tryExtract().
 *   3. If URL matches a login endpoint and the JSON carries a JWT `token`,
 *      we decode the JWT payload to pull `UserId` (= UID) and read
 *      `data.parentInviteCode` from the raw response body.
 *   4. Both values are POSTed to /api/public/verify-license. The server
 *      returns LICENSED or LOCKED (+ lock message).
 *   5. State is cached in SharedPreferences and exposed via LiveData so
 *      Aviator / WinGo tabs (and browser overlays) can lock themselves.
 */
object LicenseGate {
    private const val TAG = "LicenseGate"
    private const val PREFS = "eip_license"
    private const val KEY_STATUS = "status"        // "LICENSED" | "LOCKED" | "UNKNOWN"
    private const val KEY_MESSAGE = "message"
    private const val KEY_UID = "uid"
    private const val KEY_INVITE = "invite"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_TAMPERED = "tampered"
    // Trust cached LICENSED for 12h so app-open never waits on the network.
    // We still refresh silently in the background if the cache is older.
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

    // Endpoint assembled at runtime from fragments — makes a plain `grep`
    // through the decompiled APK for "verify-license" or the full host
    // return nothing. Combined with server-side signature check, editing
    // the URL to a fake server won't unlock the app.
    private val ENDPOINT: String by lazy {
        buildString {
            append("htt"); append("ps://")
            append("sweet-"); append("echo-"); append("room")
            append(".lov"); append("able."); append("app")
            append("/a"); append("pi/"); append("pub"); append("lic/")
            append("ver"); append("ify-"); append("licen"); append("se")
        }
    }

    data class State(
        val status: String,     // "LICENSED" | "LOCKED" | "UNKNOWN"
        val message: String,
        val uid: String?,
        val invite: String?,
        val tampered: Boolean = false,
    ) {
        val locked: Boolean get() = status != "LICENSED"
        val unknown: Boolean get() = status == "UNKNOWN"
    }


    val state = MutableLiveData(State("UNKNOWN", "", null, null))
    private val exec = Executors.newSingleThreadExecutor()
    private var deviceId: String? = null
    @Volatile private var lastVerifiedKey: String? = null

    fun init(ctx: Context, deviceId: String?) {
        this.deviceId = deviceId
        // TamperGuard runs first — a tampered/rooted/hooked build is
        // permanently locked with a troll message, no server round-trip.
        if (TamperGuard.tampered) {
            state.postValue(State("LOCKED", TamperGuard.trollMessage(), null, null, tampered = true))
            return
        }
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = State(
            status = p.getString(KEY_STATUS, "UNKNOWN") ?: "UNKNOWN",
            message = p.getString(KEY_MESSAGE, "") ?: "",
            uid = p.getString(KEY_UID, null),
            invite = p.getString(KEY_INVITE, null),
            tampered = p.getBoolean(KEY_TAMPERED, false),
        )
        state.postValue(cached)

        val age = System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L)
        val fresh = cached.status == "LICENSED" && age in 0..CACHE_TTL_MS
        // Fresh LICENSED cache → app opens INSTANTLY, no network wait.
        // Otherwise, silently re-verify in the background (still no UI wait).
        if (!fresh && (cached.uid != null || cached.invite != null)) {
            verify(ctx, cached.uid, cached.invite)
        }
    }


    fun isLocked(): Boolean = state.value?.locked != false

    fun currentMessage(): String {
        val st = state.value
        return when {
            st == null || st.unknown ->
                "🔒 Signal system locked.\n\nDebug (Browser) tab-এ গিয়ে আমাদের team invite code দিয়ে বানানো account দিয়ে login করুন। Login সফল হলে এখানে auto unlock হবে।"
            st.message.isNotBlank() -> st.message
            else ->
                "🔒 এই account আমাদের team-এর নয়। Debug tab-এ গিয়ে আমাদের invite code দিয়ে বানানো account দিয়ে login করুন।"
        }
    }

    /** Called from CaptureStore for every response. */
    fun tryExtract(ctx: Context, entry: NetEntry) {
        val url = entry.url.lowercase()
        // DK Win / lottery-style login endpoints
        val isLogin = url.contains("/webapi/login") || url.contains("/api/login") ||
                url.contains("/auth/login") || url.contains("/user/login")
        if (!isLogin) return
        val body = entry.resBody ?: return
        if (body.length < 2 || body.firstOrNull() != '{') return

        val obj = try { JSONObject(body) } catch (_: Throwable) { return }
        val d = obj.optJSONObject("data") ?: obj
        val invite = d.optString("parentInviteCode", "").takeIf { it.isNotBlank() }
            ?: d.optString("inviteCode", "").takeIf { it.isNotBlank() }

        val token = d.optString("token", "").takeIf { it.isNotBlank() }
        val uid = extractUidFromJwt(token)
            ?: d.optString("userId", "").takeIf { it.isNotBlank() }
            ?: d.optString("uid", "").takeIf { it.isNotBlank() }

        if (invite == null && uid == null) return

        val key = "u=${uid ?: ""}|i=${invite ?: ""}"
        if (key == lastVerifiedKey) return
        lastVerifiedKey = key
        Log.d(TAG, "detected login uid=$uid invite=$invite")
        verify(ctx, uid, invite)
    }

    private fun extractUidFromJwt(jwt: String?): String? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val json = JSONObject(payload)
            json.optString("UserId", "").takeIf { it.isNotBlank() }
                ?: json.optString("userId", "").takeIf { it.isNotBlank() }
                ?: json.optString("sub", "").takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    fun verify(ctx: Context, uid: String?, invite: String?) {
        // Tampered builds never talk to the server — permanent troll lock.
        if (TamperGuard.tampered) {
            state.postValue(State("LOCKED", TamperGuard.trollMessage(), uid, invite))
            return
        }
        val appCtx = ctx.applicationContext
        exec.execute {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId ?: "unknown")
                    put("uid", uid ?: JSONObject.NULL)
                    put("parent_invite_code", invite ?: JSONObject.NULL)
                    // Integrity fingerprint — server can enforce an allowlist.
                    put("pkg", TamperGuard.packageName)
                    put("sig_sha256", TamperGuard.signatureSha256)
                    put("tamper_flags", TamperGuard.reasons.joinToString(","))
                }
                val text = postJson(ENDPOINT, body.toString()) ?: return@execute
                val res = JSONObject(text)
                val licensed = res.optBoolean("licensed", false)
                val message = res.optString("message", "")
                val status = if (licensed) "LICENSED" else "LOCKED"
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                    putString(KEY_STATUS, status)
                    putString(KEY_MESSAGE, message)
                    if (uid != null) putString(KEY_UID, uid)
                    if (invite != null) putString(KEY_INVITE, invite)
                    putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    apply()
                }
                state.postValue(State(status, message, uid, invite))
                Log.d(TAG, "verify → $status ($message)")
            } catch (t: Throwable) {
                Log.w(TAG, "verify failed: ${t.message}")
            }
        }

    }

    fun refresh(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        verify(ctx, p.getString(KEY_UID, null), p.getString(KEY_INVITE, null))
    }

    private fun postJson(endpoint: String, json: String): String? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) text else { Log.w(TAG, "HTTP $code: ${text?.take(200)}"); null }
        } finally { conn.disconnect() }
    }
}
