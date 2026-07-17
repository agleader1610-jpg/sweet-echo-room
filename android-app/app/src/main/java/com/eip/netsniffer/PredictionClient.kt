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
 * PredictionClient — pushes recent round history to the Lovable backend, which
 * asks Cloud AI (Gemini 3 Flash) for a signal and falls back to local logic
 * if AI credits are exhausted. Returns the parsed signal via callback on the
 * main thread. If the network itself fails, callback receives null so the
 * caller can degrade to on-device logic.
 */
object PredictionClient {
    private const val TAG = "PredictionClient"
    private const val ENDPOINT =
        "https://sweet-echo-room.lovable.app/api/public/predict-signal"
    private const val VERIFY_ENDPOINT =
        "https://sweet-echo-room.lovable.app/api/public/verify-result"
    private const val TIMEOUT_MS = 6000

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Signal(
        val predictionId: Long?,
        val roundId: Long,
        val action: String,
        val target: Double?,
        val confidence: String,
        val reasoning: String,
        val source: String, // "ai" | "local" | "offline"
        val model: String?,
        val latencyMs: Int,
        val historyNewestRoundId: Long?,
        val historyNewestMultiplier: Double?,
        val historyLen: Int,
        val aiRaw: String?,
        val aiError: String?,
        val httpStatus: Int,
        val requestedAt: Long,
    )


    fun fetchSignal(
        deviceId: String,
        rounds: List<AviatorRound>,
        callback: (Signal?) -> Unit,
    ) {
        if (rounds.size < 3) { callback(null); return }
        exec.execute {
            val result = try { doFetch(deviceId, rounds) } catch (t: Throwable) {
                Log.w(TAG, "fetch failed: ${t.message}"); null
            }
            main.post { callback(result) }
        }
    }

    fun verify(deviceId: String, predictionId: Long?, roundId: Long, actual: Double, target: Double?) {
        exec.execute {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    if (predictionId != null) put("prediction_id", predictionId)
                    put("round_id", roundId)
                    put("actual_multiplier", actual)
                    if (target != null) put("target_multiplier", target) else put("target_multiplier", JSONObject.NULL)
                }
                postJson(VERIFY_ENDPOINT, body.toString())
            } catch (t: Throwable) { Log.w(TAG, "verify failed: ${t.message}") }
        }
    }

    private fun doFetch(deviceId: String, rounds: List<AviatorRound>): Signal? {
        val hist = JSONArray()
        rounds.take(20).forEach { r ->
            hist.put(JSONObject().apply {
                put("round_id", r.roundId)
                put("multiplier", r.crashX)
            })
        }
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("history", hist)
        }
        val respText = postJson(ENDPOINT, body.toString()) ?: return null
        val obj = JSONObject(respText)
        if (!obj.optBoolean("ok", false)) return null
        val targetRaw = if (obj.isNull("target")) null else obj.optDouble("target", Double.NaN)
        val target = if (targetRaw == null || targetRaw.isNaN()) null else targetRaw
        return Signal(
            predictionId = if (obj.isNull("prediction_id")) null else obj.optLong("prediction_id"),
            roundId = obj.optLong("round_id"),
            action = obj.optString("action", "BET"),
            target = target,
            confidence = obj.optString("confidence", "MEDIUM"),
            reasoning = obj.optString("reasoning", ""),
            source = obj.optString("source", "local"),
            model = if (obj.isNull("model")) null else obj.optString("model"),
            latencyMs = obj.optInt("latency_ms", 0),
            historyNewestRoundId = if (obj.isNull("history_newest_round_id")) null else obj.optLong("history_newest_round_id"),
            historyNewestMultiplier = if (obj.isNull("history_newest_multiplier")) null else obj.optDouble("history_newest_multiplier"),
            historyLen = obj.optInt("history_len", 0),
            aiRaw = if (obj.isNull("ai_raw")) null else obj.optString("ai_raw"),
            aiError = if (obj.isNull("ai_error")) null else obj.optString("ai_error"),
            httpStatus = 200,
            requestedAt = System.currentTimeMillis(),
        )

    }

    private fun postJson(endpoint: String, json: String): String? {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return if (code in 200..299) text else { Log.w(TAG, "HTTP $code: ${text?.take(200)}"); null }
    }
}
