package com.eip.netsniffer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.MutableLiveData
import java.lang.ref.WeakReference

/**
 * WinGo Auto-Bet Engine.
 *
 * 100% on-device. Reads WinGoStore signals, drives BetInjector against the
 * user's own WebView (BrowserFragment). No VPS, no public API traffic.
 *
 * State machine (per period):
 *   IDLE       ─ engine off or no signal
 *   ARMED      ─ have signal for target issue, waiting to place
 *   PLACING    ─ JS injection in-flight
 *   WAIT_RES   ─ placed, waiting for round result from WinGoStore.history
 *   RESOLVED   ─ counted W/L, updated stats, back to ARMED for next issue
 */
object AutoBetEngine {
    private const val TAG = "AutoBetEngine"
    private const val PREF = "net_sniffer"
    private const val PREF_ENABLED     = "autobet_on"
    private const val PREF_BASE_AMT    = "autobet_base_amt"
    private const val PREF_MULT        = "autobet_mult"
    private const val PREF_STRATEGY    = "autobet_strategy"      // FLAT | MART | ANTI
    private const val PREF_STOP_LOSS   = "autobet_stop_loss"
    private const val PREF_TARGET_WIN  = "autobet_target_win"
    private const val PREF_MAX_ROUNDS  = "autobet_max_rounds"
    private const val PREF_SKIP_LOWCF  = "autobet_skip_lowconf"

    // Live stats (persist across restart via prefs)
    private const val PREF_STATE       = "autobet_state_json"

    data class Config(
        val enabled: Boolean = false,
        val baseAmount: Int = 1,
        val multiplier: Int = 1,
        val strategy: String = "FLAT",
        val stopLoss: Int = 500,
        val targetWin: Int = 1000,
        val maxRounds: Int = 50,
        val skipLowConfidence: Boolean = false,
    )

    data class State(
        val placed: Int = 0,
        val won: Int = 0,
        val lost: Int = 0,
        val pnl: Int = 0,
        val streak: Int = 0,        // + wins, - losses
        val currentSeqStep: Int = 0, // for Martingale
        val lastNote: String = "",
        val lastIssue: String? = null,
        val nextBetIssue: String? = null,
        val nextBetPick: String? = null,
        val nextBetAmount: Int = 0,
    )

    val config = MutableLiveData(Config())
    val state = MutableLiveData(State())

    private var prefs: SharedPreferences? = null
    private var webViewRef: WeakReference<WebView>? = null
    private var lastPlacedIssue: String? = null
    private var lastResolvedIssue: String? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        config.postValue(Config(
            enabled = p.getBoolean(PREF_ENABLED, false),
            baseAmount = p.getInt(PREF_BASE_AMT, 1),
            multiplier = p.getInt(PREF_MULT, 1),
            strategy = p.getString(PREF_STRATEGY, "FLAT") ?: "FLAT",
            stopLoss = p.getInt(PREF_STOP_LOSS, 500),
            targetWin = p.getInt(PREF_TARGET_WIN, 1000),
            maxRounds = p.getInt(PREF_MAX_ROUNDS, 50),
            skipLowConfidence = p.getBoolean(PREF_SKIP_LOWCF, false),
        ))
    }

    fun attachWebView(wv: WebView?) {
        webViewRef = if (wv == null) null else WeakReference(wv)
    }

    fun updateConfig(newCfg: Config) {
        prefs?.edit()
            ?.putBoolean(PREF_ENABLED, newCfg.enabled)
            ?.putInt(PREF_BASE_AMT, newCfg.baseAmount)
            ?.putInt(PREF_MULT, newCfg.multiplier)
            ?.putString(PREF_STRATEGY, newCfg.strategy)
            ?.putInt(PREF_STOP_LOSS, newCfg.stopLoss)
            ?.putInt(PREF_TARGET_WIN, newCfg.targetWin)
            ?.putInt(PREF_MAX_ROUNDS, newCfg.maxRounds)
            ?.putBoolean(PREF_SKIP_LOWCF, newCfg.skipLowConfidence)
            ?.apply()
        config.postValue(newCfg)
        if (!newCfg.enabled) {
            note("OFF — auto-bet disabled")
        } else {
            note("ARMED — waiting for next period")
        }
    }

    fun reset() {
        lastPlacedIssue = null
        lastResolvedIssue = null
        state.postValue(State(lastNote = "Stats reset"))
    }

    /** Called by WinGoFragment whenever a new locked signal is ready for [targetIssue]. */
    fun onSignal(targetIssue: String, sig: WinGoLogic.Signal) {
        val cfg = config.value ?: return
        if (!cfg.enabled) return
        val st = state.value ?: State()

        // Guards
        if (st.placed >= cfg.maxRounds) { note("STOPPED · max rounds (${cfg.maxRounds}) reached"); autoOff(); return }
        if (st.pnl <= -cfg.stopLoss)    { note("STOPPED · stop-loss ৳${cfg.stopLoss} hit"); autoOff(); return }
        if (st.pnl >=  cfg.targetWin)   { note("STOPPED · target win ৳${cfg.targetWin} reached"); autoOff(); return }
        if (cfg.skipLowConfidence && sig.confidence == "LOW") {
            state.postValue(st.copy(
                nextBetIssue = targetIssue, nextBetPick = "SKIP",
                nextBetAmount = 0, lastNote = "SKIP · low confidence"
            ))
            return
        }
        if (targetIssue == lastPlacedIssue) return  // duplicate guard

        // Compute amount for strategy
        val amount = when (cfg.strategy) {
            "MART" -> cfg.baseAmount * pow2(st.currentSeqStep.coerceIn(0, 8))
            "ANTI" -> cfg.baseAmount * pow2((-st.streak).coerceIn(0, 5))
            else   -> cfg.baseAmount
        }
        val roundedAmt = snapAmount(amount)

        state.postValue(st.copy(
            nextBetIssue = targetIssue,
            nextBetPick = sig.pick,
            nextBetAmount = roundedAmt,
            lastNote = "Placing ${sig.pick} ৳$roundedAmt on #${targetIssue.takeLast(6)} …"
        ))

        val wv = webViewRef?.get()
        if (wv == null) { note("ERROR · browser tab not open (open Browser tab first)"); return }
        lastPlacedIssue = targetIssue
        BetInjector.placeBet(wv, sig.pick, roundedAmt, cfg.multiplier, { res ->
            val current = state.value ?: State()
            if (res.ok) {
                state.postValue(current.copy(
                    placed = current.placed + 1,
                    lastIssue = targetIssue,
                    lastNote = "✓ Placed ${sig.pick} ৳$roundedAmt · ${res.stage}",
                ))
            } else {
                // Failed to place — do NOT count as loss; unlock so we can retry next issue.
                lastPlacedIssue = null
                state.postValue(current.copy(
                    lastNote = "✗ Place failed at ${res.stage}: ${res.note}",
                ))
            }
        }
    }

    /** Called by WinGoFragment on every round resolve so engine tracks P/L. */
    fun onResolved(issue: String, win: Boolean) {
        if (issue == lastResolvedIssue) return
        val cfg = config.value ?: return
        if (!cfg.enabled) return
        // Only account for rounds we actually placed on
        if (issue != state.value?.lastIssue) return
        lastResolvedIssue = issue

        val st = state.value ?: State()
        val amt = st.nextBetAmount.takeIf { it > 0 } ?: cfg.baseAmount
        val delta = if (win) (amt * 0.98).toInt() else -amt
        val newStreak = when {
            win && st.streak >= 0 -> st.streak + 1
            !win && st.streak <= 0 -> st.streak - 1
            win -> 1
            else -> -1
        }
        val newSeqStep = when (cfg.strategy) {
            "MART" -> if (win) 0 else (st.currentSeqStep + 1).coerceAtMost(8)
            else -> 0
        }
        state.postValue(st.copy(
            won = st.won + if (win) 1 else 0,
            lost = st.lost + if (!win) 1 else 0,
            pnl = st.pnl + delta,
            streak = newStreak,
            currentSeqStep = newSeqStep,
            lastNote = (if (win) "✓ WIN " else "✗ LOSS ") + "#${issue.takeLast(6)} · ${if (delta>=0) "+" else ""}৳$delta",
        ))
    }

    private fun autoOff() {
        val c = config.value ?: return
        updateConfig(c.copy(enabled = false))
    }

    private fun note(msg: String) {
        val st = state.value ?: State()
        state.postValue(st.copy(lastNote = msg))
        Log.i(TAG, msg)
    }

    private fun pow2(n: Int): Int { var r = 1; repeat(n){ r *= 2 }; return r }

    /** Snap to nearest supported preset the game exposes: 1, 10, 100, 1000. */
    private fun snapAmount(v: Int): Int = when {
        v <= 1 -> 1
        v <= 10 -> 10
        v <= 100 -> 100
        else -> 1000
    }
}
