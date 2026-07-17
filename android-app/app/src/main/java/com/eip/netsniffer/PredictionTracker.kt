package com.eip.netsniffer

import androidx.lifecycle.MutableLiveData

/**
 * EIP Prediction Tracker — WIN detector.
 *
 * Flow:
 *  1. When the fragment renders a signal for the CURRENT running round, it
 *     calls [arm] with that roundId + a predicate that decides whether the
 *     signal was "hit" by the eventual crashX.
 *  2. When a new crash arrives (rounds list grows), the fragment calls
 *     [resolve] with the newest round. If it matches the armed roundId,
 *     we score WIN / LOSS and push it into [lastResult] + session stats.
 */
object PredictionTracker {

    data class Armed(
        val roundId: Long,
        val label: String,
        val test: (Double) -> Boolean,
    )

    data class Result(
        val roundId: Long,
        val label: String,
        val crashX: Double,
        val win: Boolean,
        val at: Long = System.currentTimeMillis(),
    )

    data class Stats(
        val wins: Int = 0,
        val losses: Int = 0,
        val streak: Int = 0,          // current same-kind streak (+ = win streak, − = loss streak)
        val lastResult: Result? = null,
    ) {
        val total: Int get() = wins + losses
        val accuracy: Int get() = if (total == 0) 0 else (wins * 100) / total
    }

    val stats = MutableLiveData(Stats())
    private var armed: Armed? = null

    @Synchronized
    fun arm(roundId: Long?, label: String, test: (Double) -> Boolean) {
        if (roundId == null) return
        val cur = armed
        if (cur != null && cur.roundId == roundId && cur.label == label) return
        armed = Armed(roundId, label, test)
    }

    @Synchronized
    fun clearArmed() { armed = null }

    @Synchronized
    fun resolve(newest: AviatorRound) {
        val a = armed ?: return
        if (a.roundId != newest.roundId) return
        val win = a.test(newest.crashX)
        val res = Result(newest.roundId, a.label, newest.crashX, win)
        val s = stats.value ?: Stats()
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
        armed = null
    }

    @Synchronized
    fun reset() {
        armed = null
        stats.postValue(Stats())
    }
}
