package com.eip.netsniffer

import android.util.Log
import androidx.lifecycle.MutableLiveData
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WinGo (30S) polling store.
 *
 * Two endpoints polled directly (no network-capture required):
 *  - Live:    https://draw.ar-lottery01.com/WinGo/WinGo_30S.json
 *             → current/previous/next issueNumber + startTime/endTime (ms)
 *  - History: https://draw.ar-lottery01.com/WinGo/WinGo_30S/GetHistoryIssuePage.json
 *             → last ~10 results with number(0-9) + color(red|green|violet|combos)
 */
data class WinGoRound(
    val issue: String,
    val number: Int,           // 0..9
    val colors: List<String>,  // ["red"], ["green"], ["red","violet"], ["green","violet"]
)

data class WinGoLive(
    val currentIssue: String?,
    val nextIssue: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

object WinGoStore {
    private const val TAG = "WinGoStore"
    private const val LIVE_URL = "https://draw.ar-lottery01.com/WinGo/WinGo_30S.json"
    private const val HIST_URL = "https://draw.ar-lottery01.com/WinGo/WinGo_30S/GetHistoryIssuePage.json"

    val history = MutableLiveData<List<WinGoRound>>(emptyList())
    val live = MutableLiveData<WinGoLive?>(null)
    val overlayEnabled = MutableLiveData(false)
    /** Live signal + target period, updated by WinGoFragment for the browser overlay. */
    val currentSignal = MutableLiveData<WinGoLogic.Signal?>(null)
    val currentTargetIssue = MutableLiveData<String?>(null)
    data class LastResult(val issue: String, val win: Boolean, val number: Int, val colors: List<String>, val pick: String)
    val lastResult = MutableLiveData<LastResult?>(null)
    val score = MutableLiveData(0 to 0) // wins to losses

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ safeFetch(LIVE_URL, ::parseLive) }, 0, 3, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ safeFetch(HIST_URL, ::parseHistory) }, 500, 5000, TimeUnit.MILLISECONDS)
    }

    private fun safeFetch(url: String, onBody: (String) -> Unit) {
        try {
            val req = Request.Builder()
                .url("$url?ts=${System.currentTimeMillis()}")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.ar-lottery01.com/")
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return
                if (resp.isSuccessful) onBody(body)
            }
        } catch (t: Throwable) { Log.w(TAG, "fetch $url failed: ${t.message}") }
    }

    private fun parseLive(body: String) {
        try {
            val j = JSONObject(body)
            val cur = j.optJSONObject("current")
            val nxt = j.optJSONObject("next")
            val start = cur?.optLong("startTime", 0L) ?: 0L
            val end = cur?.optLong("endTime", 0L) ?: 0L
            live.postValue(WinGoLive(
                currentIssue = cur?.optString("issueNumber"),
                nextIssue = nxt?.optString("issueNumber"),
                startTimeMs = start,
                endTimeMs = end,
            ))
        } catch (_: Throwable) {}
    }

    private fun parseHistory(body: String) {
        try {
            val j = JSONObject(body)
            val arr = j.optJSONObject("data")?.optJSONArray("list") ?: return
            val out = ArrayList<WinGoRound>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val issue = o.optString("issueNumber")
                val n = o.optString("number").toIntOrNull() ?: continue
                val cRaw = o.optString("color", "")
                val cList = cRaw.split(",").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                out.add(WinGoRound(issue, n, cList))
            }
            if (out.isNotEmpty()) history.postValue(out)
        } catch (_: Throwable) {}
    }
}

/** Pure local signal — no AI, streak + frequency heuristic. */
object WinGoLogic {
    data class Signal(
        val pick: String,       // "RED" | "GREEN" | "BIG" | "SMALL"
        val category: String,   // "COLOR" | "SIZE"
        val confidence: String, // "HIGH" | "MEDIUM" | "LOW"
        val reasoning: String,
    )

    fun predict(rounds: List<WinGoRound>): Signal? {
        if (rounds.size < 5) return null
        val recent = rounds.take(20)

        // SIZE-only. BIG = 5..9, SMALL = 0..4.
        val sizeSeq = recent.map { if (it.number >= 5) "BIG" else "SMALL" }
        val (last, len) = streak(sizeSeq)
        val big = sizeSeq.count { it == "BIG" }
        val small = sizeSeq.count { it == "SMALL" }
        val total = sizeSeq.size

        // Weighted BIG probability starts neutral at 0.5, nudged by heuristics.
        var pBig = 0.5

        // Mean reversion after long streak (soft, not hard).
        if (len >= 4) pBig += if (last == "BIG") -0.20 else 0.20
        else if (len >= 2) pBig += if (last == "BIG") 0.08 else -0.08  // mild trend follow on short streaks

        // Frequency correction — dominant side gets small negative weight.
        val diff = (big - small).toDouble() / total   // -1..+1
        pBig -= diff * 0.15

        // Small jitter so identical states don't always produce identical pick.
        pBig += (Math.random() - 0.5) * 0.20
        pBig = pBig.coerceIn(0.05, 0.95)

        val pick = if (Math.random() < pBig) "BIG" else "SMALL"
        val conf = when {
            kotlin.math.abs(pBig - 0.5) >= 0.25 -> "HIGH"
            kotlin.math.abs(pBig - 0.5) >= 0.12 -> "MEDIUM"
            else -> "LOW"
        }
        val reason = "streak=$len $last · big/small=$big/$small · pBig=${"%.2f".format(pBig)}"
        return Signal(pick, "SIZE", conf, reason)
    }


    private fun colorPick(streak: Pair<String, Int>, red: Int, green: Int, total: Int): Signal {
        val (last, len) = streak
        if (len >= 4) return Signal(
            pick = if (last == "RED") "GREEN" else "RED",
            category = "COLOR",
            confidence = if (len >= 6) "HIGH" else "MEDIUM",
            reasoning = "$len consecutive $last → reversal due.",
        )
        if (red - green >= 4) return Signal("GREEN", "COLOR", "MEDIUM", "RED heavy ($red/$total) — GREEN catch-up.")
        if (green - red >= 4) return Signal("RED", "COLOR", "MEDIUM", "GREEN heavy ($green/$total) — RED catch-up.")
        if (len >= 2) return Signal(last, "COLOR", "LOW", "$last streak of $len — follow the trend.")
        return Signal(if (red <= green) "RED" else "GREEN", "COLOR", "LOW", "balanced market — mild lean.")
    }

    private fun streak(seq: List<String>): Pair<String, Int> {
        if (seq.isEmpty()) return "" to 0
        val head = seq.first()
        var n = 0
        for (x in seq) { if (x == head) n++ else break }
        return head to n
    }

    /** Check whether [signal] would have won given [actual] round. */
    fun didWin(signal: Signal, actual: WinGoRound): Boolean {
        return when (signal.pick) {
            "BIG" -> actual.number >= 5
            "SMALL" -> actual.number < 5
            "RED" -> actual.colors.contains("red")
            "GREEN" -> actual.colors.contains("green")
            else -> false
        }
    }
}
