package com.eip.netsniffer

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

/**
 * EIP Prediction Tracker — WIN detector + persistent local signal history.
 *
 * Flow:
 *  1. When the fragment renders a signal for the CURRENT running round, it
 *     calls [arm] with that roundId + a predicate that decides whether the
 *     signal was "hit" by the eventual crashX.
 *  2. When a new crash arrives (rounds list grows), the fragment calls
 *     [resolve] with the newest round. If it matches the armed roundId,
 *     we score WIN / LOSS, push it into [lastResult] + session stats, and
 *     append it to the persistent local [history] list (max 50 entries).
 */
object PredictionTracker {

    data class Armed(
        val roundId: Long,
        val label: String,
        val test: (Double) -> Boolean,
    )

    data class Result(
        val roundId: Long,
        val label: String,        // target text e.g. "1.80x" or "SKIP"
        val crashX: Double,
        val win: Boolean,
        val skipped: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    data class Stats(
        val wins: Int = 0,
        val losses: Int = 0,
        val skips: Int = 0,
        val streak: Int = 0,          // + win streak, − loss streak; skips don't affect
        val lastResult: Result? = null,
    ) {
        val total: Int get() = wins + losses
        val accuracy: Int get() = if (total == 0) 0 else (wins * 100) / total
    }

    val stats = MutableLiveData(Stats())
    /** Newest-first persisted list of resolved signals. */
    val history = MutableLiveData<List<Result>>(emptyList())

    private var armed: Armed? = null
    private var prefs: SharedPreferences? = null
    private const val PREFS = "eip_prediction_tracker"
    private const val KEY_HISTORY = "history_v1"
    private const val MAX_HISTORY = 50

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        history.postValue(loadHistory())
    }

    @Synchronized
    fun arm(roundId: Long?, label: String, test: (Double) -> Boolean) {
        if (roundId == null) return
        val cur = armed
        if (cur != null && cur.roundId == roundId) return
        armed = Armed(roundId, label, test)
    }

    @Synchronized
    fun clearArmed() { armed = null }

    @Synchronized
    fun currentArmedLabel(): String? = armed?.label

    @Synchronized
    fun currentArmed(): Armed? = armed

    @Synchronized
    fun resolve(newest: AviatorRound) {
        val a = armed ?: return
        if (a.roundId != newest.roundId) return
        val isSkip = a.label.equals("SKIP", ignoreCase = true)
        val s = stats.value ?: Stats()
        val res: Result
        if (isSkip) {
            res = Result(newest.roundId, a.label, newest.crashX, win = false, skipped = true)
            stats.postValue(s.copy(skips = s.skips + 1, lastResult = res))
        } else {
            val win = a.test(newest.crashX)
            res = Result(newest.roundId, a.label, newest.crashX, win)
            val newStreak = when {
                win && s.streak >= 0 -> s.streak + 1
                win && s.streak < 0  -> 1
                !win && s.streak <= 0 -> s.streak - 1
                else -> -1
            }
            stats.postValue(
                s.copy(
                    wins = s.wins + if (win) 1 else 0,
                    losses = s.losses + if (win) 0 else 1,
                    streak = newStreak,
                    lastResult = res,
                )
            )
        }
        appendHistory(res)
        armed = null
    }

    @Synchronized
    fun reset() {
        armed = null
        stats.postValue(Stats())
        history.postValue(emptyList())
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
    }

    private fun appendHistory(r: Result) {
        val cur = history.value ?: emptyList()
        // Dedup by roundId (in case resolve fires twice)
        val filtered = cur.filter { it.roundId != r.roundId }
        val next = (listOf(r) + filtered).take(MAX_HISTORY)
        history.postValue(next)
        saveHistory(next)
    }

    private fun saveHistory(list: List<Result>) {
        val p = prefs ?: return
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("roundId", r.roundId)
                put("label", r.label)
                put("crashX", r.crashX)
                put("win", r.win)
                put("skipped", r.skipped)
                put("at", r.at)
            })
        }
        p.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun loadHistory(): List<Result> {
        val p = prefs ?: return emptyList()
        val raw = p.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Result>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Result(
                        roundId = o.optLong("roundId"),
                        label = o.optString("label", "-"),
                        crashX = o.optDouble("crashX", 0.0),
                        win = o.optBoolean("win", false),
                        skipped = o.optBoolean("skipped", false),
                        at = o.optLong("at", System.currentTimeMillis()),
                    )
                )
            }
            out
        } catch (_: Throwable) { emptyList() }
    }
}
