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

        // ---- COLOR signal (RED vs GREEN, ignore violet-only which never happens) ----
        val colorSeq = recent.mapNotNull {
            when {
                it.colors.contains("red") && !it.colors.contains("green") -> "RED"
                it.colors.contains("green") && !it.colors.contains("red") -> "GREEN"
                else -> null // pure-violet edge (0 or 5) counted as neutral
            }
        }
        val cStreak = streak(colorSeq)
        val cRedCount = colorSeq.count { it == "RED" }
        val cGreenCount = colorSeq.count { it == "GREEN" }

        // ---- SIZE signal (BIG=5..9, SMALL=0..4) ----
        val sizeSeq = recent.map { if (it.number >= 5) "BIG" else "SMALL" }
        val sStreak = streak(sizeSeq)
        val sBig = sizeSeq.count { it == "BIG" }
        val sSmall = sizeSeq.count { it == "SMALL" }

        // Score = which category gives strongest anti-streak / imbalance signal
        val colorScore = cStreak.second + kotlin.math.abs(cRedCount - cGreenCount)
        val sizeScore = sStreak.second + kotlin.math.abs(sBig - sSmall)

        return if (sizeScore >= colorScore) sizePick(sStreak, sBig, sSmall, sizeSeq.size)
        else colorPick(cStreak, cRedCount, cGreenCount, colorSeq.size)
    }

    private fun sizePick(streak: Pair<String, Int>, big: Int, small: Int, total: Int): Signal {
        val (last, len) = streak
        // strong anti-streak
        if (len >= 4) return Signal(
            pick = if (last == "BIG") "SMALL" else "BIG",
            category = "SIZE",
            confidence = if (len >= 6) "HIGH" else "MEDIUM",
            reasoning = "$len consecutive $last → reversal due.",
        )
        // frequency lean
        if (big - small >= 4) return Signal("SMALL", "SIZE", "MEDIUM", "BIG dominating ($big/$total) — SMALL catch-up.")
        if (small - big >= 4) return Signal("BIG", "SIZE", "MEDIUM", "SMALL dominating ($small/$total) — BIG catch-up.")
        // mild — follow current streak (2-3 same)
        if (len >= 2) return Signal(last, "SIZE", "LOW", "$last streak of $len — follow the trend.")
        return Signal(if (big <= small) "BIG" else "SMALL", "SIZE", "LOW", "balanced market — mild lean.")
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
