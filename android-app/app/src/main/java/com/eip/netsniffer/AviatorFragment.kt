package com.eip.netsniffer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentAviatorBinding
import kotlin.math.roundToInt

class AviatorFragment : Fragment() {
    private var _b: FragmentAviatorBinding? = null
    private val b get() = _b!!
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            renderTimers()
            ticker.postDelayed(this, 100)
        }
    }
    private val prefs by lazy { requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAviatorBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        AviatorStore.state.observe(viewLifecycleOwner) { renderState(it) }
        AviatorStore.rounds.observe(viewLifecycleOwner) { list ->
            // Resolve the most recent armed prediction against the newest crash
            list.firstOrNull()?.let { PredictionTracker.resolve(it) }
            renderRounds(list)
        }
        PredictionTracker.stats.observe(viewLifecycleOwner) { renderTrackerStats(it) }
        b.clearBtn.setOnClickListener {
            AviatorStore.clear()
            PredictionTracker.reset()
        }

        b.overlaySwitch.isChecked = prefs.getBoolean(PREF_OVERLAY, false)
        AviatorStore.overlayEnabled.postValue(b.overlaySwitch.isChecked)
        b.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_OVERLAY, checked).apply()
            AviatorStore.overlayEnabled.postValue(checked)
        }
    }

    override fun onResume() { super.onResume(); ticker.post(tickRunnable) }
    override fun onPause()  { super.onPause();  ticker.removeCallbacks(tickRunnable) }

    private fun renderState(st: AviatorState?) {
        if (st == null || st.updatedAt == 0L) {
            b.emptyText.visibility = View.VISIBLE
            b.phaseCard.visibility = View.GONE
            b.analysisCard.visibility = View.GONE
            b.predictionCard.visibility = View.GONE
            b.historyLabel.visibility = View.GONE
            b.historyScroll.visibility = View.GONE
            return
        }
        b.emptyText.visibility = View.GONE
        b.phaseCard.visibility = View.VISIBLE
        b.analysisCard.visibility = View.GONE  // simplified per request


        // Phase semantics:
        //  • CRASHED / BETTING = plane just crashed → this is the BET WINDOW
        //    (user places bet now, signal is valid for the NEXT round)
        //  • FLYING = plane is in the air, multiplier climbing
        val (label, color) = when (st.phase) {
            AviatorPhase.FLYING   -> "✈ FLYING"   to 0xFFFFB74D.toInt()
            AviatorPhase.CRASHED  -> "🎯 BET TIME" to 0xFF16A34A.toInt()
            AviatorPhase.BETTING  -> "🎯 BET TIME" to 0xFF16A34A.toInt()
            AviatorPhase.IDLE     -> "IDLE"       to 0xFF455A64.toInt()
        }
        b.phaseBadge.text = label
        b.phaseBadge.background = roundedBg(color, 8f)

        val big = when (st.phase) {
            AviatorPhase.CRASHED -> st.crashX ?: st.prevCrashX
            AviatorPhase.FLYING  -> st.liveMultiplier
            else                 -> st.prevCrashX
        }
        b.liveMultiplier.text = if (big != null) "%.2fx".format(big) else "—"
        b.liveMultiplier.setTextColor(color)

        // Derive the round the user is watching RIGHT NOW.
        // - BETTING/FLYING: server-supplied roundId is the running round
        // - CRASHED: crash event's roundId is the round that just ended,
        //   so the NEXT running round = prevRoundId + 1
        val displayCurrent: Long? = when (st.phase) {
            AviatorPhase.CRASHED -> st.prevRoundId?.plus(1) ?: st.roundId?.plus(1)
            AviatorPhase.BETTING, AviatorPhase.FLYING -> st.roundId
            AviatorPhase.IDLE -> st.roundId
        }
        b.roundLine.text = buildString {
            append(if (st.phase == AviatorPhase.FLYING) "flying #" else "bet round #")
            append(displayCurrent ?: "-")
            if (st.prevRoundId != null && st.prevCrashX != null)
                append("   • last #${st.prevRoundId} → %.2fx".format(st.prevCrashX))
        }

        b.cyclesText.text = st.cycleCount.toString()
        // BET tile (id=flyMsText) = betting-window duration
        // FLY tile (id=waitMsText) = flight duration
        b.flyMsText.text  = fmtMs(st.lastWaitDurationMs)
        b.waitMsText.text = fmtMs(st.lastFlyDurationMs)
    }

    private fun renderTimers() {
        val st = AviatorStore.state.value ?: return
        if (st.phaseSince == 0L) return
        val elapsed = System.currentTimeMillis() - st.phaseSince
        val phaseName = when (st.phase) {
            AviatorPhase.FLYING              -> "flying for"
            AviatorPhase.CRASHED,
            AviatorPhase.BETTING             -> "bet time open"
            AviatorPhase.IDLE                -> "idle"
        }
        b.phaseTimer.text = "$phaseName ${fmtMs(elapsed)}"
    }

    private fun renderRounds(list: List<AviatorRound>) {
        // History strip (previous rounds)
        b.historyStrip.removeAllViews()
        if (list.isEmpty()) {
            b.historyLabel.visibility = View.GONE
            b.historyScroll.visibility = View.GONE
            b.predictionCard.visibility = View.GONE
            b.roundsText.text = "(none)"
            b.streakText.text = "—"
            b.statAvg.text = "—"; b.statMax.text = "—"; b.statMin.text = "—"
            b.statLowCount.text = "0"; b.statMidCount.text = "0"; b.statHighCount.text = "0"
            b.trendBadge.text = "—"
            return
        }

        b.historyLabel.visibility = View.VISIBLE
        b.historyScroll.visibility = View.VISIBLE

        val ctx = requireContext()
        list.take(30).forEach { r ->
            b.historyStrip.addView(makeChip(ctx, r.crashX))
        }

        // ── EIP PREDICTION SIGNAL ──
        renderPrediction(list.map { it.crashX })

        // Compact legacy line
        b.roundsText.text = list.take(30).joinToString("  ") { "%.2fx".format(it.crashX) }



        // Analysis over last 20
        val sample = list.take(20).map { it.crashX }
        val avg = sample.average()
        val max = sample.max()
        val min = sample.min()
        val low = sample.count { it < 2.0 }
        val mid = sample.count { it >= 2.0 && it < 10.0 }
        val high = sample.count { it >= 10.0 }
        b.statAvg.text = "%.2fx".format(avg)
        b.statMax.text = "%.2fx".format(max)
        b.statMin.text = "%.2fx".format(min)
        b.statLowCount.text = low.toString()
        b.statMidCount.text = mid.toString()
        b.statHighCount.text = high.toString()

        // Streak: consecutive from the newest side of the same category
        val newest = sample.first()
        val cat = category(newest)
        val streak = sample.takeWhile { category(it) == cat }.size
        val catLabel = when (cat) { 0 -> "LOW <2x"; 1 -> "MID 2-10x"; else -> "HIGH ≥10x" }
        val sinceHighIdx = sample.indexOfFirst { it >= 10.0 }
        val sinceHigh = if (sinceHighIdx < 0) "20+" else sinceHighIdx.toString()
        b.streakText.text = "streak: $streak× $catLabel   •   rounds since ≥10x: $sinceHigh"

        // Trend badge (compare first-half avg vs second-half avg of the 20)
        if (sample.size >= 6) {
            val half = sample.size / 2
            val recent = sample.take(half).average()
            val older = sample.drop(half).average()
            val (t, col) = when {
                recent > older * 1.15 -> "▲ HOT" to 0xFF4ADE80.toInt()
                recent < older * 0.85 -> "▼ COLD" to 0xFFF87171.toInt()
                else -> "◆ STABLE" to 0xFF94A3B8.toInt()
            }
            b.trendBadge.text = t
            b.trendBadge.setTextColor(col)
        } else {
            b.trendBadge.text = "—"
        }
    }

    /**
     * EIP prediction — simplified into 3 clear ACTIONS: STRONG BET, BET, SKIP.
     * The card header shows exactly WHICH round this signal is for; the WIN
     * detector below shows exactly WHICH round it was scored against.
     */
    private fun renderPrediction(all: List<Double>) {
        if (all.size < 5) {
            b.predictionCard.visibility = View.GONE
            return
        }
        b.predictionCard.visibility = View.VISIBLE

        val droughtIdx = all.indexOfFirst { it >= 10.0 }
        val drought = if (droughtIdx < 0) all.size else droughtIdx
        val lowStreak = all.takeWhile { it < 2.0 }.size
        val last10 = all.take(10)
        val heat = last10.count { it >= 2.0 }
        val avg10 = last10.average()
        val lastRound = all.first()

        data class Sig(
            val action: String,      // "BET" / "STRONG BET" / "SKIP"
            val emoji: String,
            val bg: Int,
            val cashout: String,     // "cash out @1.5x" etc.
            val reason: String,
            val conf: String,
            val confCol: Int,
            val targetX: Double?,
            val test: (Double) -> Boolean,
        )
        val sig = when {
            drought >= 20 || lowStreak >= 7 -> Sig(
                "STRONG BET", "🔥", 0xFFDC2626.toInt(),
                "Cash out at 1.5x", "Big multiplier long overdue.",
                "HIGH", 0xFF4ADE80.toInt(), 1.5, { it >= 1.5 },
            )
            drought >= 12 || lowStreak >= 5 -> Sig(
                "BET", "⚡", 0xFF16A34A.toInt(),
                "Cash out at 1.5x", "Recovery round likely.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.5, { it >= 1.5 },
            )
            lastRound >= 10.0 -> Sig(
                "SKIP", "❄", 0xFF3B82F6.toInt(),
                "Wait 1–2 rounds", "Big spike just landed, cool-down expected.",
                "MEDIUM", 0xFFFBBF24.toInt(), null, { it < 2.0 },
            )
            avg10 < 1.6 && heat <= 3 -> Sig(
                "BET", "◈", 0xFF16A34A.toInt(),
                "Cash out at 1.3x", "Cold market — quick small wins.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.3, { it >= 1.3 },
            )
            heat >= 7 -> Sig(
                "SKIP", "▲", 0xFF9333EA.toInt(),
                "Crash-back near", "Hot streak — momentum fading.",
                "MEDIUM", 0xFFFBBF24.toInt(), null, { it < 2.0 },
            )
            else -> Sig(
                "BET", "◆", 0xFF64748B.toInt(),
                "Cash out at 1.5x", "Normal range — conservative play.",
                "LOW", 0xFF94A3B8.toInt(), 1.5, { it >= 1.5 },
            )
        }

        // Signal is always generated from completed history for the NEXT round.
        // This avoids arming the already-crashed round when LiveData posts
        // history before the latest phase snapshot reaches the UI.
        val runningId: Long? = AviatorStore.rounds.value.orEmpty().firstOrNull()?.roundId?.plus(1)
            ?: AviatorStore.state.value?.roundId

        val signalLabel = if (sig.targetX == null) {
            "${sig.emoji} SKIP ROUND"
        } else {
            "${sig.emoji} ${sig.action} %.2fx".format(sig.targetX)
        }
        b.predSignal.text = if (sig.targetX == null) {
            "${sig.emoji} SKIP ROUND\nNO BET"
        } else {
            "${sig.emoji} ${sig.action}\nTARGET %.2fx".format(sig.targetX)
        }
        b.predSignal.background = roundedBg(sig.bg, 12f)
        b.predSuggestion.text = "ACTIVE: BET TIME  •  RESULT: AFTER CRASH\n${sig.cashout} — ${sig.reason}"
        b.predConfidence.text = "ROUND #${runningId ?: "-"}  •  ${sig.conf}"
        b.predConfidence.setTextColor(sig.confCol)

        b.predDrought.text = if (droughtIdx < 0) "${all.size}+" else drought.toString()
        b.predLowStreak.text = lowStreak.toString()
        b.predHeat.text = "$heat/10"

        // Arm the WIN detector so we score THIS signal against runningId.
        PredictionTracker.arm(runningId, signalLabel, sig.test)
    }

    private fun renderTrackerStats(s: PredictionTracker.Stats) {
        b.predWins.text = s.wins.toString()
        b.predLosses.text = s.losses.toString()
        b.predAccuracy.text = if (s.total == 0) "—" else "${s.accuracy}%"
        b.predStreak.text = when {
            s.streak > 0 -> "W${s.streak}"
            s.streak < 0 -> "L${-s.streak}"
            else -> "—"
        }
        val last = s.lastResult
        if (last == null) {
            b.predResultBadge.text = "◌ WAITING"
            b.predResultBadge.setBackgroundColor(0xFF1F2937.toInt())
            b.predResultBadge.setTextColor(0xFF94A3B8.toInt())
            b.predResultDetail.text = "waiting for round to end…"
        } else {
            if (last.win) {
                b.predResultBadge.text = "✓ WIN"
                b.predResultBadge.setBackgroundColor(0xFF16A34A.toInt())
                b.predResultBadge.setTextColor(0xFFFFFFFF.toInt())
            } else {
                b.predResultBadge.text = "✗ LOSS"
                b.predResultBadge.setBackgroundColor(0xFFDC2626.toInt())
                b.predResultBadge.setTextColor(0xFFFFFFFF.toInt())
            }
            b.predResultDetail.text = "${last.label}  •  #${last.roundId} → %.2fx".format(last.crashX)
    }
    }

    private fun category(x: Double): Int = when {
        x < 2.0 -> 0
        x < 10.0 -> 1
        else -> 2
    }


    private fun makeChip(ctx: Context, x: Double): View {
        val tv = TextView(ctx)
        val (bg, fg) = when {
            x >= 10.0 -> 0xFF7C3AED.toInt() to 0xFFFFFFFF.toInt()
            x >= 2.0  -> 0xFF16A34A.toInt() to 0xFFFFFFFF.toInt()
            else      -> 0xFFB91C1C.toInt() to 0xFFFFFFFF.toInt()
        }
        val bgDr = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 8f)
            setColor(bg)
        }
        tv.background = bgDr
        tv.text = "%.2fx".format(x)
        tv.setTextColor(fg)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        val padH = dp(ctx, 10f).toInt()
        val padV = dp(ctx, 6f).toInt()
        tv.setPadding(padH, padV, padH, padV)
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(ctx, 6f).toInt()
        tv.layoutParams = lp
        return tv
    }

    private fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(requireContext(), radiusDp)
        setColor(color)
    }

    private fun fmtMs(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000.0
        return if (s < 60) "%.1fs".format(s) else "%dm %02ds".format((s/60).toInt(), (s%60).toInt())
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val PREF_OVERLAY = "vip_overlay_enabled"
    }
}
