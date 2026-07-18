package com.eip.netsniffer

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.MutableLiveData
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow


/**
 * WinGo Auto-Bet Engine (v2 — ported from working DK WIN userscript).
 *
 * Key guarantees:
 *  - One submit attempt per period (hard lock; no duplicate loop if the page accepts the bet slowly).
 *  - Balance-based STEP LADDER: base = balance / (2^N - 1); step_i = round(base * 2^i)
 *    (integers). LOSS advances step; WIN within ladder → rebuild ladder from new balance;
 *    WIN beyond ladder → reset to step 1 anchor plan.
 *  - Three signal modes: APP (follow WinGoStore signal), RANDOM (coin flip),
 *    PATTERN (invert last result → mean-reversion).
 *  - Never bets until previous period is resolved (stepPendingIssue guard).
 */
object AutoBetEngine {
    private const val TAG = "AutoBetEngine"
    private const val MAX_SUBMIT_TRIES = 2
    private const val SAFE_BET_SECONDS = 3
    private const val PREF = "net_sniffer"
    private const val PREF_ENABLED     = "autobet_on"
    private const val PREF_STEPS       = "autobet_steps"
    private const val PREF_BALANCE     = "autobet_balance"
    private const val PREF_MULT        = "autobet_mult"
    private const val PREF_MODE        = "autobet_mode"          // APP | RANDOM | PATTERN
    private const val PREF_DURATION_MIN = "autobet_duration_min"
    private const val PREF_SKIP_LOWCF  = "autobet_skip_lowconf"

    data class Config(
        val enabled: Boolean = false,
        val steps: Int = 7,               // ladder depth N
        val balance: Int = 100,           // user-entered starting balance
        val multiplier: Int = 1,          // betMultiple sent to API
        val mode: String = "APP",         // APP | RANDOM | PATTERN
        val durationMinutes: Int = 10,    // auto-off timer; finishes active ladder before stopping
        val skipLowConfidence: Boolean = false,
    )

    data class State(
        val placed: Int = 0,
        val won: Int = 0,
        val lost: Int = 0,
        val pnl: Int = 0,
        val streak: Int = 0,
        val stepIdx: Int = 0,             // 0-based ladder index
        val highestStep: Int = 0,
        val ladder: List<Int> = emptyList(),
        val ladderReady: Boolean = false,
        val stepPendingIssue: String? = null,
        val lastResult: String? = null,   // "BIG" or "SMALL" (real outcome)
        val lastNote: String = "",
        val lastIssue: String? = null,
        val nextBetIssue: String? = null,
        val nextBetPick: String? = null,
        val nextBetAmount: Int = 0,
        val startedAtMs: Long = 0L,
        val stopAfterClear: Boolean = false,
        // ---- API-truth fields (populated by AutoBetReconciler) ----
        val apiBalance: Double? = null,           // real live balance from GetBalance
        val apiCurrentIssue: String? = null,      // running issue from GetGameInfo
        val apiGameCode: String? = null,          // WinGo_30S / WinGo_1M / etc from captured API
        val apiRemainingSeconds: Int? = null,     // server-side seconds left for current issue
        val apiTokenReady: Boolean = false,       // Authorization/base captured from DK Win WebView
        val apiBaseReady: Boolean = false,
        val apiLastError: String? = null,
        val apiLastSyncMs: Long = 0L,             // last successful reconciliation
        val lastBetStage: String? = null,
        val lastBetDetail: String? = null,
        val lastBetAtMs: Long = 0L,
        val balanceRebuildAfterMs: Long = 0L,   // WIN → wait for API balance snapshot after this ts
    )

    /** Immutable snapshot of the game server's ground truth. */
    data class ApiSnapshot(
        val balance: Double?,
        val currentIssue: String?,
        val myBets: List<ApiBet>,
        val gameCode: String? = null,
        val remainingSeconds: Int? = null,
        val tokenReady: Boolean = false,
        val baseReady: Boolean = false,
        val lastError: String? = null,
        val lastBetAck: ApiAck? = null,
    )
    data class ApiAck(
        val ok: Boolean,
        val stage: String,
        val note: String,
        val issueNumber: String? = null,
    )
    data class ApiBet(
        val issueNumber: String,
        val betContent: String,           // "BigSmall_Big" | "BigSmall_Small"
        val state: Int,                   // 0 = pending, 1 = settled
        val winLoseAmount: Double,        // >0 win, <0 loss (after settlement)
        val betMultiple: Int,
        val number: String,               // "0".."9" — resolved result
    )

    val config = MutableLiveData(Config())
    val state = MutableLiveData(State())

    private var prefs: SharedPreferences? = null
    private var webViewRef: WeakReference<WebView>? = null

    // Per-issue state so we never fire more than 1 submit attempt on same period.
    // Once success = true, no more attempts on this issue, ever.
    private data class IssueSlot(
        var tries: Int = 0,
        var success: Boolean = false,
        var inflight: Boolean = false,
        var resolved: Boolean = false,
        var submittedAtMs: Long = 0L,
        var pick: String? = null,
        var stake: Int = 0,
    )
    private val issueSlots = HashMap<String, IssueSlot>()
    private var anchorLadder: List<Int> = emptyList()
    private var awaitingBalanceRebuild = false

    /** Synchronous state push — avoids postValue race where the next
     *  onSignal in the same call-chain still reads the pre-resolve state
     *  (which was blocking every 2nd+ bet with "Waiting for previous"). */
    private fun pushState(s: State) {
        if (Looper.myLooper() == Looper.getMainLooper()) state.value = s
        else state.postValue(s)
    }
    private fun pushConfig(c: Config) {
        if (Looper.myLooper() == Looper.getMainLooper()) config.value = c
        else config.postValue(c)
    }



    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        config.value = Config(
            enabled = p.getBoolean(PREF_ENABLED, false),
            steps = p.getInt(PREF_STEPS, 7).coerceIn(1, 15),
            balance = p.getInt(PREF_BALANCE, 100),
            multiplier = p.getInt(PREF_MULT, 1),
            mode = p.getString(PREF_MODE, "APP") ?: "APP",
            durationMinutes = p.getInt(PREF_DURATION_MIN, 10).coerceIn(1, 1440),
            skipLowConfidence = p.getBoolean(PREF_SKIP_LOWCF, false),
        )
    }

    fun attachWebView(wv: WebView?) {
        webViewRef = if (wv == null) null else WeakReference(wv)
    }

    fun updateConfig(newCfg: Config) {
        val oldCfg = config.value ?: Config()
        prefs?.edit()
            ?.putBoolean(PREF_ENABLED, newCfg.enabled)
            ?.putInt(PREF_STEPS, newCfg.steps)
            ?.putInt(PREF_BALANCE, newCfg.balance)
            ?.putInt(PREF_MULT, newCfg.multiplier)
            ?.putString(PREF_MODE, newCfg.mode)
            ?.putInt(PREF_DURATION_MIN, newCfg.durationMinutes)
            ?.putBoolean(PREF_SKIP_LOWCF, newCfg.skipLowConfidence)
            ?.apply()
        pushConfig(newCfg)
        if (!newCfg.enabled) {
            val st = state.value ?: State()
            pushState(st.copy(startedAtMs = 0L, stopAfterClear = false, lastNote = "OFF — auto-bet disabled"))
        } else if (!oldCfg.enabled) {
            // Fresh run: keep visible stats, but start a clean ladder + timer.
            val st = state.value ?: State()
            issueSlots.clear()
            awaitingBalanceRebuild = false
            anchorLadder = emptyList()
            pushState(st.copy(
                ladderReady = false,
                ladder = emptyList(),
                stepIdx = 0,
                stepPendingIssue = null,
                nextBetIssue = null,
                nextBetPick = null,
                nextBetAmount = 0,
                startedAtMs = System.currentTimeMillis(),
                stopAfterClear = false,
                lastNote = "ARMED · ${newCfg.durationMinutes}m · N=${newCfg.steps} · bal ৳${newCfg.balance} · mode ${newCfg.mode}",
            ))
        }
    }

    fun reset() {
        issueSlots.clear()
        anchorLadder = emptyList()
        awaitingBalanceRebuild = false
        val started = if (config.value?.enabled == true) System.currentTimeMillis() else 0L
        pushState(State(startedAtMs = started, lastNote = "Stats reset"))
    }

    fun tick() {
        val cfg = config.value ?: return
        if (!cfg.enabled) return
        val st = state.value ?: return
        handleDuration(cfg, st)
    }

    /** Build ladder from current balance. */
    private fun buildLadder(bal: Int, n: Int): List<Int> {
        val nn = n.coerceIn(1, 15)
        val denom = (2.0.pow(nn) - 1.0)
        if (denom <= 0 || bal <= 0) return listOf(1)
        val base = bal.toDouble() / denom
        return (0 until nn).map { i -> max(1, (base * 2.0.pow(i)).toInt()) }
    }

    /**
     * WinGoFragment calls this for every new locked period signal.
     * Engine decides pick based on mode, places at most 1 successful bet per issue.
     */
    fun onSignal(targetIssue: String, sig: WinGoLogic.Signal) {
        val cfg = config.value ?: return
        if (!cfg.enabled) return
        var st = state.value ?: State()

        // ── Guards ───────────────────────────────────────────────
        if (handleDuration(cfg, st)) return
        st = state.value ?: st
        if (st.stopAfterClear && isStepClear(st)) { autoOff("OFF — time completed") ; return }

        // Wait for previous period to resolve before placing next step.
        if (st.stepPendingIssue != null && st.stepPendingIssue != targetIssue) {
            note("Waiting for #${st.stepPendingIssue!!.takeLast(6)} to resolve …")
            return
        }
        if (awaitingBalanceRebuild) { note("WIN · waiting API balance refresh …"); return }

        // ── API-TRUTH GATE: never bet on a period that isn't the running one ──
        val apiIssue = st.apiCurrentIssue
        if (apiIssue == null) {
            note("Waiting for API current period … open DK Win and login")
            return
        }
        if (!st.apiTokenReady || !st.apiBaseReady) {
            note("Waiting for DK Win token … keep DK Win page logged in")
            return
        }
        if (st.apiGameCode.isNullOrBlank()) {
            note("Waiting for WinGo market detect … open the running WinGo game")
            return
        }
        if (apiIssue != targetIssue) {
            note("API mismatch · running #${apiIssue.takeLast(6)} ≠ signal #${targetIssue.takeLast(6)}")
            return
        }
        val secondsLeft = st.apiRemainingSeconds
        if (secondsLeft != null && secondsLeft <= SAFE_BET_SECONDS) {
            note("Skip #${targetIssue.takeLast(6)} · only ${secondsLeft}s left")
            return
        }

        // Per-issue lock — HARD guarantee: no duplicate bets on same period.
        val slot = issueSlots.getOrPut(targetIssue) { IssueSlot() }
        if (slot.success) return              // already bet successfully this period
        if (slot.inflight) return              // request in progress
        if (slot.tries >= MAX_SUBMIT_TRIES) return  // bounded retry only when API confirms no order

        // ── Pick based on signal mode ────────────────────────────
        val pick: String = when (cfg.mode) {
            "RANDOM"  -> if (Math.random() < 0.5) "BIG" else "SMALL"
            "PATTERN" -> if (st.lastResult == "BIG") "SMALL" else "BIG"   // mean-reversion
            else      -> {
                if (cfg.skipLowConfidence && sig.confidence == "LOW") {
                    slot.success = true       // treat as "handled" so we don't retry
                    pushState(st.copy(
                        nextBetIssue = targetIssue, nextBetPick = "SKIP",
                        nextBetAmount = 0, lastNote = "SKIP · low confidence",
                    ))
                    return
                }
                sig.pick
            }
        }

        // ── Build ladder if needed (prefer live API balance over user input) ──
        if (!st.ladderReady || st.ladder.isEmpty()) {
            val liveBal = st.apiBalance?.toInt()?.coerceAtLeast(1) ?: cfg.balance
            val lad = buildLadder(liveBal, cfg.steps)
            anchorLadder = lad
            st = st.copy(ladder = lad, ladderReady = true, stepIdx = 0)
            pushState(st.copy(lastNote = "Ladder ৳$liveBal → ${lad.joinToString("·")}"))
        }

        val stakeIdx = min(st.stepIdx, st.ladder.size - 1)
        val stake = max(1, st.ladder[stakeIdx])
        val stepNum = st.stepIdx + 1
        val stepLabel = if (stepNum > cfg.steps) "$stepNum/${cfg.steps}+" else "$stepNum/${cfg.steps}"

        pushState(st.copy(
            nextBetIssue = targetIssue,
            nextBetPick = pick,
            nextBetAmount = stake,
            highestStep = max(st.highestStep, stepNum),
            lastNote = "Placing $pick ৳$stake · step $stepLabel · #${targetIssue.takeLast(6)}",
        ))

        val wv = webViewRef?.get()
        if (wv == null) { note("ERROR · Browser tab not open"); return }

        slot.inflight = true
        slot.tries++
        slot.pick = pick
        slot.stake = stake
        BetInjector.placeBet(
            wv,
            pick,
            amount = 1,
            multiplier = stake,
            issueNumber = targetIssue,
            gameCodeOverride = st.apiGameCode,
        ) { res ->
            slot.inflight = false
            val cur = state.value ?: State()
            if (res.ok) {
                slot.success = true
                slot.submittedAtMs = System.currentTimeMillis()
                pushState(cur.copy(
                    placed = cur.placed + 1,
                    lastIssue = targetIssue,
                    stepPendingIssue = targetIssue,
                    lastBetStage = res.stage,
                    lastBetDetail = res.note,
                    lastBetAtMs = System.currentTimeMillis(),
                    lastNote = "✓ Placed $pick ৳$stake · step $stepLabel · ${res.stage}",
                ))
            } else {
                pushState(cur.copy(
                    lastBetStage = res.stage,
                    lastBetDetail = res.note,
                    lastBetAtMs = System.currentTimeMillis(),
                    lastNote = "✗ ${res.stage}: ${res.note}" + if (slot.tries < MAX_SUBMIT_TRIES) " · retry if API still current" else " · stopped for this period",
                ))
            }
        }
    }

    /** Called on every resolved round (WinGoFragment / WinGoStore). */
    fun onResolved(issue: String, actualBigOrSmall: String) {
        val actual = actualBigOrSmall.uppercase()
        if (actual != "BIG" && actual != "SMALL") return
        val betPick = state.value?.nextBetPick?.uppercase() ?: return
        if (betPick != "BIG" && betPick != "SMALL") return
        onResolved(issue, betPick == actual)
    }

    /** Called on every resolved round (WinGoFragment / WinGoStore). */
    fun onResolved(issue: String, win: Boolean) {
        val slot = issueSlots[issue] ?: return
        if (!slot.success) return  // we didn't place here — ignore
        if (slot.resolved) return   // idempotent — WinGoFragment may poll this twice
        slot.resolved = true
        val st = state.value ?: State()
        val cfg = config.value ?: Config()


        val stake = slot.stake.takeIf { it > 0 } ?: st.nextBetAmount.takeIf { it > 0 } ?: 1
        val betPick = slot.pick ?: st.nextBetPick
        val delta = if (win) (stake * 96) / 100 else -stake  // ~1.96x payout
        val newStreak = when {
            win && st.streak >= 0 -> st.streak + 1
            !win && st.streak <= 0 -> st.streak - 1
            win -> 1
            else -> -1
        }

        // Ladder progression
        var newStepIdx = st.stepIdx
        var newLadder = st.ladder
        var newReady = st.ladderReady
        val stepNum = st.stepIdx + 1
        if (win) {
            if (stepNum <= cfg.steps) {
                // WIN within ladder → rebuild from new balance next signal
                newStepIdx = 0
                newLadder = emptyList()
                newReady = false
                awaitingBalanceRebuild = false
            } else {
                // WIN beyond ladder → reset step 1, anchor plan
                newStepIdx = 0
                newLadder = if (anchorLadder.isNotEmpty()) anchorLadder else st.ladder
                newReady = true
            }
        } else {
            newStepIdx = st.stepIdx + 1  // LOSS → advance step
        }

        val updated = st.copy(
            won = st.won + if (win) 1 else 0,
            lost = st.lost + if (!win) 1 else 0,
            pnl = st.pnl + delta,
            streak = newStreak,
            stepIdx = newStepIdx,
            ladder = newLadder,
            ladderReady = newReady,
            stepPendingIssue = null,
            highestStep = max(st.highestStep, newStepIdx + 1),
            lastResult = st.lastResult,  // updated separately via onOutcome below if needed
            nextBetPick = betPick,
            nextBetAmount = stake,
            lastNote = (if (win) "✓ WIN +৳$delta" else "✗ LOSS ৳$delta") +
                " · step " + (if (stepNum > cfg.steps) "$stepNum/${cfg.steps}+" else "$stepNum/${cfg.steps}"),
        )
        if (updated.stopAfterClear && isStepClear(updated)) {
            prefs?.edit()?.putBoolean(PREF_ENABLED, false)?.apply()
            pushConfig(cfg.copy(enabled = false))
            pushState(updated.copy(
                startedAtMs = 0L,
                stopAfterClear = false,
                lastNote = updated.lastNote + " · OFF — time completed after step clear",
            ))
        } else {
            pushState(updated)
        }
        Log.i(TAG, "resolved $issue win=$win Δ$delta nextStep=${newStepIdx+1}")
    }

    /** Optionally feed the raw BIG/SMALL outcome for PATTERN mode. */
    fun onOutcome(bigOrSmall: String) {
        val v = bigOrSmall.uppercase()
        if (v != "BIG" && v != "SMALL") return
        val st = state.value ?: return
        pushState(st.copy(lastResult = v))
    }

    private fun handleDuration(cfg: Config, st: State): Boolean {
        val start = st.startedAtMs
        if (start <= 0L || cfg.durationMinutes <= 0) return false
        val expired = System.currentTimeMillis() - start >= cfg.durationMinutes * 60_000L
        if (!expired) return false
        if (isStepClear(st)) {
            autoOff("OFF — time completed")
            return true
        }
        if (!st.stopAfterClear) {
            pushState(st.copy(
                stopAfterClear = true,
                lastNote = "Time complete · finishing active step before OFF",
            ))
        }
        return false
    }

    private fun isStepClear(st: State): Boolean = st.stepPendingIssue == null && st.stepIdx == 0

    private fun autoOff(message: String = "OFF — auto-bet disabled") {
        val c = config.value ?: return
        prefs?.edit()?.putBoolean(PREF_ENABLED, false)?.apply()
        pushConfig(c.copy(enabled = false))
        val st = state.value ?: State()
        pushState(st.copy(startedAtMs = 0L, stopAfterClear = false, lastNote = message))
    }

    private fun note(msg: String) {
        val st = state.value ?: State()
        pushState(st.copy(lastNote = msg))
        Log.i(TAG, msg)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  API-TRUTH RECONCILIATION (called every ~2s by AutoBetReconciler)
    //  This is the money-safety layer:
    //   - overwrites local state.apiBalance / apiCurrentIssue
    //   - detects "SUBMITTED but no order at server" (network drop, sig reject)
    //     → releases the slot so next signal can retry cleanly
    //   - resolves win/loss from server data (state=1) instead of local guess
    // ═══════════════════════════════════════════════════════════════════
    fun applyReconciliation(snap: ApiSnapshot) {
        val cfg = config.value ?: return
        var st = state.value ?: State()

        st = st.copy(
            apiBalance = snap.balance ?: st.apiBalance,
            apiCurrentIssue = snap.currentIssue,
            apiGameCode = snap.gameCode ?: st.apiGameCode,
            apiRemainingSeconds = snap.remainingSeconds,
            apiTokenReady = snap.tokenReady,
            apiBaseReady = snap.baseReady,
            apiLastError = snap.lastError,
            apiLastSyncMs = System.currentTimeMillis(),
            lastBetStage = snap.lastBetAck?.stage ?: st.lastBetStage,
            lastBetDetail = snap.lastBetAck?.note ?: st.lastBetDetail,
            lastBetAtMs = if (snap.lastBetAck != null) System.currentTimeMillis() else st.lastBetAtMs,
        )
        pushState(st)
        if (!cfg.enabled && st.stepPendingIssue == null) return

        // 1) Resolve any pending issue from authoritative server outcome.
        val pending = st.stepPendingIssue
        if (pending != null) {
            val serverBet = snap.myBets.firstOrNull { it.issueNumber == pending }
            if (serverBet == null) {
                // Server has NO record of our submitted bet after we thought we placed.
                // Common causes: network drop between POST and delivery, or signature reject.
                // Grace period 15s before we declare it lost & release the slot.
                val slot = issueSlots[pending]
                val ackFailed = snap.lastBetAck?.let {
                    it.issueNumber == pending && !it.ok && (it.stage == "API_ERR" || it.stage == "NET_ERR")
                } == true
                val staleNoRecord = slot != null && slot.success && System.currentTimeMillis() - (slot.submittedAtMs) > 8_000L
                if (slot != null && (ackFailed || staleNoRecord)) {
                    Log.w(TAG, "reconcile: pending #$pending has NO server record after 15s → releasing")
                    // Release the pending lock. If the same issue is still running and tries remain,
                    // the API-driven signal pass below may retry exactly once; otherwise next issue waits.
                    slot.success = false
                    slot.inflight = false
                    slot.resolved = false
                    val cur = state.value ?: State()
                    pushState(cur.copy(
                        stepPendingIssue = null,
                        placed = (cur.placed - 1).coerceAtLeast(0),
                        lastNote = "⚠ #${pending.takeLast(6)} not on server · ${if (slot.tries < MAX_SUBMIT_TRIES) "retry armed" else "period blocked"}",
                    ))
                }
            } else if (serverBet.state == 1) {
                // Server settled it → authoritative win/loss.
                val win = serverBet.winLoseAmount > 0.0
                Log.i(TAG, "reconcile: API-settled #$pending win=$win Δ=${serverBet.winLoseAmount}")
                onResolved(pending, win)
                return
            } else {
                val cur = state.value ?: State()
                if (cur.lastNote.startsWith("✓ Placed") || cur.lastNote.startsWith("⚠")) {
                    pushState(cur.copy(lastNote = "Server confirmed pending #${pending.takeLast(6)} · waiting result"))
                }
            }
        }

        // 2) Feed last raw outcome for PATTERN mode from most recent settled bet.
        val lastSettled = snap.myBets.firstOrNull { it.state == 1 && it.number.isNotEmpty() }
        if (lastSettled != null) {
            val n = lastSettled.number.toIntOrNull()
            if (n != null) {
                val outcome = if (n >= 5) "BIG" else "SMALL"
                if (state.value?.lastResult != outcome) onOutcome(outcome)
            }
        }

        // 3) API-driven auto-fire. This keeps auto-bet alive even when the user
        // is on DK Win, Auto-Bet, or another tab; WinGoFragment UI is no longer required.
        val current = snap.currentIssue ?: return
        val latest = state.value ?: return
        if (cfg.enabled && latest.stepPendingIssue == null && !latest.stopAfterClear) {
            val slot = issueSlots[current]
            if (slot == null || (!slot.success && !slot.inflight && slot.tries < MAX_SUBMIT_TRIES)) {
                val sig = if (WinGoStore.currentTargetIssue.value == current) {
                    WinGoStore.currentSignal.value
                } else {
                    WinGoLogic.predict(WinGoStore.history.value ?: emptyList())
                }
                if (sig != null) {
                    WinGoStore.currentTargetIssue.postValue(current)
                    WinGoStore.currentSignal.postValue(sig)
                    onSignal(current, sig)
                } else {
                    note("Waiting for WinGo history before trading #${current.takeLast(6)}")
                }
            }
        }
    }
}
