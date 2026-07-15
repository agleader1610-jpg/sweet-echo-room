package com.eip.netsniffer

import androidx.lifecycle.MutableLiveData

enum class AviatorPhase { IDLE, BETTING, FLYING, CRASHED }

data class AviatorCashout(
    val playerId: String,
    val winAmount: Double,
    val multiplier: Double,
    val betId: Long,
    val at: Long = System.currentTimeMillis(),
)

data class AviatorRound(
    val roundId: Long,
    val crashX: Double,
    val at: Long = System.currentTimeMillis(),
)

data class AviatorState(
    val roundId: Long? = null,
    val prevRoundId: Long? = null,
    val prevCrashX: Double? = null,
    val liveMultiplier: Double? = null,   // current climbing multiplier while FLYING
    val crashX: Double? = null,            // only set for the just-ended round (CRASHED phase)
    val openBetsCount: Int? = null,
    val liveCashouts: List<AviatorCashout> = emptyList(),

    // Phase detector
    val phase: AviatorPhase = AviatorPhase.IDLE,
    val phaseSince: Long = 0L,
    val lastFlyDurationMs: Long = 0L,     // how long the plane flew before crashing
    val lastWaitDurationMs: Long = 0L,    // gap between crash and next takeoff (betting window)
    val cycleCount: Int = 0,               // full crash→crash cycles observed

    // Hard timestamps — the ONLY source of truth for bet/fly durations.
    val lastCrashAt: Long = 0L,           // when the last CRASH event was received
    val lastTakeoffAt: Long = 0L,         // when the first FLY tick after that crash arrived

    val lastCommand: String? = null,
    val lastSourceUrl: String? = null,
    val updatedAt: Long = 0L,
)

object AviatorStore {
    val state = MutableLiveData(AviatorState())
    val rounds = MutableLiveData<List<AviatorRound>>(emptyList())
    val cashoutFeed = MutableLiveData<List<AviatorCashout>>(emptyList())
    val overlayEnabled = MutableLiveData(false)
    private const val MAX_HISTORY = 200

    @Synchronized
    fun ingestFromEntry(entry: NetEntry) {
        if (entry.source != "ws-msg-in" && entry.method != "WS<") return
        if (!entry.url.contains("spribegaming", ignoreCase = true)) return
        if (!entry.url.contains("BlueBox", ignoreCase = true)) return
        val bytes = BlueBoxParser.extractBase64Bytes(entry.resBody) ?: return
        val root = BlueBoxParser.parse(bytes) as? Map<*, *> ?: return
        ingestPayload(root, entry.url)
    }

    @Synchronized
    fun ingestPayload(root: Map<*, *>, sourceUrl: String) {
        val outerP = root["p"] as? Map<*, *> ?: return
        val payload = outerP["p"] as? Map<*, *> ?: return
        val cmd = outerP["c"] as? String

        var snap = state.value ?: AviatorState()
        val now = System.currentTimeMillis()

        val incomingRoundId = (payload["roundId"] as? Number)?.toLong()?.and(0xffffffffL)
        val incomingMax = (payload["maxMultiplier"] as? Number)?.toDouble()
        val incomingCrash = (payload["crashX"] as? Number)?.toDouble()
        val incomingOpen = (payload["openBetsCount"] as? Number)?.toInt()

        // ── PHASE TRANSITIONS ──────────────────────────────────────────
        if (incomingCrash != null) {
            // Plane crashed for this roundId. Fly duration = now - flyStart.
            val flyStart = if (snap.phase == AviatorPhase.FLYING) snap.phaseSince else snap.phaseSince
            val flyMs = if (snap.phase == AviatorPhase.FLYING && snap.phaseSince > 0)
                now - snap.phaseSince else snap.lastFlyDurationMs
            snap = snap.copy(
                prevRoundId = incomingRoundId ?: snap.roundId,
                prevCrashX = incomingCrash,
                crashX = incomingCrash,
                liveMultiplier = incomingCrash,
                phase = AviatorPhase.CRASHED,
                phaseSince = now,
                lastFlyDurationMs = flyMs,
                cycleCount = snap.cycleCount + 1,
            )
            val rid = incomingRoundId ?: snap.roundId ?: 0L
            val newRounds = ArrayList<AviatorRound>(MAX_HISTORY + 1)
            newRounds.add(AviatorRound(rid, incomingCrash))
            newRounds.addAll(rounds.value.orEmpty())
            while (newRounds.size > MAX_HISTORY) newRounds.removeAt(newRounds.size - 1)
            rounds.postValue(newRounds)
        } else if (incomingMax != null) {
            // Multiplier ticking → plane is FLYING.
            if (snap.phase != AviatorPhase.FLYING) {
                // We just took off. If previous phase was CRASHED/BETTING, record wait window.
                val waitMs = if (snap.phaseSince > 0 &&
                    (snap.phase == AviatorPhase.CRASHED || snap.phase == AviatorPhase.BETTING))
                    now - snap.phaseSince else snap.lastWaitDurationMs
                snap = snap.copy(
                    phase = AviatorPhase.FLYING,
                    phaseSince = now,
                    lastWaitDurationMs = waitMs,
                    crashX = null, // clear the just-ended crash marker once new flight starts
                )
            }
            snap = snap.copy(
                roundId = incomingRoundId ?: snap.roundId,
                liveMultiplier = incomingMax,
            )
        } else if (incomingRoundId != null && incomingRoundId != snap.roundId) {
            // New round id with no multiplier and no crash → BETTING window.
            val waitBaseline = snap.phaseSince
            snap = snap.copy(
                prevRoundId = snap.roundId,
                roundId = incomingRoundId,
                phase = AviatorPhase.BETTING,
                phaseSince = now,
                crashX = null,
                liveMultiplier = null,
                lastWaitDurationMs = if (waitBaseline > 0 && snap.phase == AviatorPhase.CRASHED)
                    now - waitBaseline else snap.lastWaitDurationMs,
            )
        }

        if (incomingOpen != null) snap = snap.copy(openBetsCount = incomingOpen)

        // ── LIVE CASHOUTS ──────────────────────────────────────────────
        val cashoutsList = payload["cashouts"] as? List<*>
        if (cashoutsList != null) {
            val parsed = cashoutsList.mapNotNull { it as? Map<*, *> }.map { m ->
                AviatorCashout(
                    playerId = (m["player_id"] as? String) ?: "",
                    winAmount = (m["winAmount"] as? Number)?.toDouble() ?: 0.0,
                    multiplier = (m["multiplier"] as? Number)?.toDouble() ?: 0.0,
                    betId = ((m["betId"] as? Number)?.toLong() ?: 0L) and 0xffffffffL,
                )
            }
            snap = snap.copy(liveCashouts = parsed)
            if (parsed.isNotEmpty()) {
                val feed = ArrayList<AviatorCashout>(MAX_HISTORY + parsed.size)
                feed.addAll(parsed.asReversed())
                feed.addAll(cashoutFeed.value.orEmpty())
                while (feed.size > MAX_HISTORY) feed.removeAt(feed.size - 1)
                cashoutFeed.postValue(feed)
            }
        }

        snap = snap.copy(
            lastCommand = cmd ?: snap.lastCommand,
            lastSourceUrl = sourceUrl,
            updatedAt = now,
        )
        state.postValue(snap)
    }

    fun clear() {
        state.postValue(AviatorState())
        rounds.postValue(emptyList())
        cashoutFeed.postValue(emptyList())
    }
}
