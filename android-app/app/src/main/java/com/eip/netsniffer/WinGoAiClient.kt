package com.eip.netsniffer

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * WinGo AI signal client — talks to the shared /api/public/wingo-signal
 * endpoint. Server caches ONE signal per issue_id so every device on the
 * same period gets the same pick (no per-user AI calls).
 *
 * If the server responds with credits_exhausted=true, the caller should
 * treat this as the "AI is down" state: use the random pick for the current
 * period, then after the NEXT resolved win, stop signals and show an
 * update-notice banner ("owner-কে inbox করুন").
 */
object WinGoAiClient {
    private const val TAG = "WinGoAiClient"
    private const val ENDPOINT = "https://apiapk.eip.lat/api/public/wingo-signal"
    private const val TIMEOUT_MS = 8000

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Local per-issue cache so this device never re-hits the server for the same period. */
    private val inflight = HashSet<String>()
    private val cache = HashMap<String, AiSignal>()

    data class AiSignal(
        val issueId: String,
        val pick: String,           // "BIG" | "SMALL"
        val confidence: String,     // "LOW" | "MEDIUM" | "HIGH"
        val reasoning: String,
        val source: String,         // "ai" | "random"
        val model: String?,
        val cached: Boolean,        // was returned from server-side cache
        val aiError: String?,
        val creditsExhausted: Boolean,
    )

    fun cachedFor(issueId: String): AiSignal? = synchronized(cache) { cache[issueId] }

    fun fetchSignal(
        deviceId: String,
        gameCode: String,
        issueId: String,
        history: List<WinGoRound>,
        callback: (AiSignal?) -> Unit,
    ) {
        val cached = cachedFor(issueId)
        if (cached != null) { callback(cached); return }
        if (history.size < 3) { callback(null); return }
        synchronized(inflight) {
            if (issueId in inflight) { callback(null); return }
            inflight.add(issueId)
        }
        exec.execute {
            val result = try { doFetch(deviceId, gameCode, issueId, history) } catch (t: Throwable) {
                Log.w(TAG, "fetch failed: ${t.message}"); null
            }
            if (result != null) synchronized(cache) { cache[issueId] = result }
            synchronized(inflight) { inflight.remove(issueId) }
            main.post { callback(result) }
        }
    }

    private fun doFetch(deviceId: String, gameCode: String, issueId: String, history: List<WinGoRound>): AiSignal? {
        val hist = JSONArray()
        history.take(10).forEach { r ->
            hist.put(JSONObject().apply {
                put("issue", r.issue)
                put("number", r.number)
            })
        }
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("game_code", gameCode)
            put("issue_id", issueId)
            put("history", hist)
        }
        val text = postJson(ENDPOINT, body.toString()) ?: return null
        val obj = JSONObject(text)
        if (!obj.optBoolean("ok", false)) return null
        val pick = obj.optString("pick").uppercase()
        if (pick != "BIG" && pick != "SMALL") return null
        return AiSignal(
            issueId = obj.optString("issue_id", issueId),
            pick = pick,
            confidence = obj.optString("confidence", "MEDIUM").uppercase(),
            reasoning = obj.optString("reasoning", ""),
            source = obj.optString("source", "ai"),
            model = if (obj.isNull("model")) null else obj.optString("model"),
            cached = obj.optBoolean("cached", false),
            aiError = if (obj.isNull("ai_error")) null else obj.optString("ai_error"),
            creditsExhausted = obj.optBoolean("credits_exhausted", false),
        )
    }

    private fun postJson(endpoint: String, json: String): String? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return if (code in 200..299) body else { Log.w(TAG, "HTTP $code: ${body?.take(200)}"); null }
    }
}
