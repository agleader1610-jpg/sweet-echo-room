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
    )

    val config = MutableLiveData(Config())
    val state = MutableLiveData(State())

    private var prefs: SharedPreferences? = null
    private var webViewRef: WeakReference<WebView>? = null

    // Per-issue state so we never fire more than 1 submit attempt on same period.
    // Once success = true, no more attempts on this issue, ever.
    private data class IssueSlot(var tries: Int = 0, var success: Boolean = false, var inflight: Boolean = false)
    private val issueSlots = HashMap<String, IssueSlot>()
    private var anchorLadder: List<Int> = emptyList()
    private var awaitingBalanceRebuild = false

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
        config.postValue(newCfg)
        if (!newCfg.enabled) {
            val st = state.value ?: State()
            state.postValue(st.copy(startedAtMs = 0L, stopAfterClear = false, lastNote = "OFF — auto-bet disabled"))
        } else if (!oldCfg.enabled) {
            // Fresh run: keep visible stats, but start a clean ladder + timer.
            val st = state.value ?: State()
            issueSlots.clear()
            awaitingBalanceRebuild = false
            anchorLadder = emptyList()
            state.postValue(st.copy(
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
        state.postValue(State(startedAtMs = started, lastNote = "Stats reset"))
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
        if (awaitingBalanceRebuild) { note("Rebuilding ladder …"); return }

        // Per-issue lock — HARD guarantee: no duplicate bets on same period.
        val slot = issueSlots.getOrPut(targetIssue) { IssueSlot() }
        if (slot.success) return              // already bet successfully this period
        if (slot.inflight) return              // request in progress
        if (slot.tries >= 1) return            // one submit attempt only — prevents duplicate bet loop

        // ── Pick based on signal mode ────────────────────────────
        val pick: String = when (cfg.mode) {
            "RANDOM"  -> if (Math.random() < 0.5) "BIG" else "SMALL"
            "PATTERN" -> if (st.lastResult == "BIG") "SMALL" else "BIG"   // mean-reversion
            else      -> {
                if (cfg.skipLowConfidence && sig.confidence == "LOW") {
                    slot.success = true       // treat as "handled" so we don't retry
                    state.postValue(st.copy(
                        nextBetIssue = targetIssue, nextBetPick = "SKIP",
                        nextBetAmount = 0, lastNote = "SKIP · low confidence",
                    ))
                    return
                }
                sig.pick
            }
        }

        // ── Build ladder if needed ────────────────────────────────
        if (!st.ladderReady || st.ladder.isEmpty()) {
            val lad = buildLadder(cfg.balance, cfg.steps)
            anchorLadder = lad
            st = st.copy(ladder = lad, ladderReady = true, stepIdx = 0)
            state.postValue(st.copy(lastNote = "Ladder ৳${cfg.balance} → ${lad.joinToString("·")}"))
        }

        val stakeIdx = min(st.stepIdx, st.ladder.size - 1)
        val stake = max(1, st.ladder[stakeIdx])
        val stepNum = st.stepIdx + 1
        val stepLabel = if (stepNum > cfg.steps) "$stepNum/${cfg.steps}+" else "$stepNum/${cfg.steps}"

        state.postValue(st.copy(
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
        BetInjector.placeBet(wv, pick, amount = 1, multiplier = stake, issueNumber = targetIssue) { res ->
            slot.inflight = false
            val cur = state.value ?: State()
            if (res.ok) {
                slot.success = true
                state.postValue(cur.copy(
                    placed = cur.placed + 1,
                    lastIssue = targetIssue,
                    stepPendingIssue = targetIssue,
                    lastNote = "✓ Placed $pick ৳$stake · step $stepLabel · ${res.stage}",
                ))
            } else {
                // Do NOT clear the slot — no retry this period, so duplicate bets cannot loop.
                state.postValue(cur.copy(
                    lastNote = "✗ ${res.stage}: ${res.note} · no retry this period",
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
        // Prevent double-counting: mark resolved by clearing pending pointer.
        val st = state.value ?: State()
        if (st.stepPendingIssue != issue && st.lastIssue != issue) return
        val cfg = config.value ?: Config()

        val stake = st.nextBetAmount.takeIf { it > 0 } ?: 1
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
            lastNote = (if (win) "✓ WIN +৳$delta" else "✗ LOSS ৳$delta") +
                " · step " + (if (stepNum > cfg.steps) "$stepNum/${cfg.steps}+" else "$stepNum/${cfg.steps}"),
        )
        if (updated.stopAfterClear && isStepClear(updated)) {
            prefs?.edit()?.putBoolean(PREF_ENABLED, false)?.apply()
            config.postValue(cfg.copy(enabled = false))
            state.postValue(updated.copy(
                startedAtMs = 0L,
                stopAfterClear = false,
                lastNote = updated.lastNote + " · OFF — time completed after step clear",
            ))
        } else {
            state.postValue(updated)
        }
        Log.i(TAG, "resolved $issue win=$win Δ$delta nextStep=${newStepIdx+1}")
    }

    /** Optionally feed the raw BIG/SMALL outcome for PATTERN mode. */
    fun onOutcome(bigOrSmall: String) {
        val v = bigOrSmall.uppercase()
        if (v != "BIG" && v != "SMALL") return
        val st = state.value ?: return
        state.postValue(st.copy(lastResult = v))
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
            state.postValue(st.copy(
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
        config.postValue(c.copy(enabled = false))
        val st = state.value ?: State()
        state.postValue(st.copy(startedAtMs = 0L, stopAfterClear = false, lastNote = message))
    }

    private fun note(msg: String) {
        val st = state.value ?: State()
        state.postValue(st.copy(lastNote = msg))
        Log.i(TAG, msg)
    }
}
