package com.eip.netsniffer

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentAviatorBinding

/**
 * EIP Aviator tab — signal-first UI.
 * Phase labels (BET/FLYING) were removed because live phase detection is
 * unreliable across the source stream. Everything below is derived purely
 * from confirmed crash history:
 *   next running round = last completed round + 1
 *   signal = statistical prediction against that next round
 */
class AviatorFragment : Fragment() {
    private var _b: FragmentAviatorBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAviatorBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        AviatorStore.state.observe(viewLifecycleOwner) { renderState(it) }
        AviatorStore.rounds.observe(viewLifecycleOwner) { list ->
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

    private fun renderState(st: AviatorState?) {
        if (st == null || st.updatedAt == 0L) {
            b.emptyText.visibility = View.VISIBLE
            b.phaseCard.visibility = View.GONE
            b.predictionCard.visibility = View.GONE
            b.historyLabel.visibility = View.GONE
            b.historyScroll.visibility = View.GONE
            return
        }
        b.emptyText.visibility = View.GONE
        b.phaseCard.visibility = View.VISIBLE

        // Last confirmed crash — this is a fact, not a phase guess
        val lastX = st.prevCrashX ?: st.crashX
        b.liveMultiplier.text = if (lastX != null) "%.2fx".format(lastX) else "—"
        b.liveMultiplier.setTextColor(
            when {
                lastX == null -> 0xFF64748B.toInt()
                lastX >= 10.0 -> 0xFFA78BFA.toInt()
                lastX >= 2.0  -> 0xFF4ADE80.toInt()
                else          -> 0xFFEF4444.toInt()
            }
        )

        val lastId = st.prevRoundId ?: st.roundId
        val nextId = lastId?.plus(1)
        b.roundLine.text = "#${lastId ?: "-"}  →  next running #${nextId ?: "-"}"
    }

    private fun renderRounds(list: List<AviatorRound>) {
        b.historyStrip.removeAllViews()
        if (list.isEmpty()) {
            b.historyLabel.visibility = View.GONE
            b.historyScroll.visibility = View.GONE
            b.predictionCard.visibility = View.GONE
            return
        }
        b.historyLabel.visibility = View.VISIBLE
        b.historyScroll.visibility = View.VISIBLE

        val ctx = requireContext()
        list.take(30).forEach { r -> b.historyStrip.addView(makeChip(ctx, r.crashX)) }

        renderPrediction(list)
    }

    private fun renderPrediction(rounds: List<AviatorRound>) {
        val all = rounds.map { it.crashX }
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
            val bg: Int,
            val targetColor: Int,
            val cashout: String,
            val reason: String,
            val conf: String,
            val confCol: Int,
            val targetX: Double?,
            val test: (Double) -> Boolean,
        )
        val sig = when {
            drought >= 20 || lowStreak >= 7 -> Sig(
                "STRONG BET", 0xFFDC2626.toInt(), 0xFF4ADE80.toInt(),
                "Cash out at 1.5x", "Big multiplier long overdue.",
                "HIGH", 0xFF4ADE80.toInt(), 1.5, { it >= 1.5 },
            )
            drought >= 12 || lowStreak >= 5 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "Cash out at 1.5x", "Recovery round likely.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.5, { it >= 1.5 },
            )
            lastRound >= 10.0 -> Sig(
                "SKIP", 0xFF3B82F6.toInt(), 0xFF60A5FA.toInt(),
                "Wait 1–2 rounds", "Big spike just landed, cool-down expected.",
                "MEDIUM", 0xFFFBBF24.toInt(), null, { it < 2.0 },
            )
            avg10 < 1.6 && heat <= 3 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "Cash out at 1.3x", "Cold market — quick small wins.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.3, { it >= 1.3 },
            )
            heat >= 7 -> Sig(
                "SKIP", 0xFF9333EA.toInt(), 0xFFC084FC.toInt(),
                "Crash-back near", "Hot streak — momentum fading.",
                "MEDIUM", 0xFFFBBF24.toInt(), null, { it < 2.0 },
            )
            else -> Sig(
                "BET", 0xFF64748B.toInt(), 0xFF94A3B8.toInt(),
                "Cash out at 1.5x", "Normal range — conservative play.",
                "LOW", 0xFF94A3B8.toInt(), 1.5, { it >= 1.5 },
            )
        }

        // Next running round id = last completed + 1
        val runningId: Long? = rounds.firstOrNull()?.roundId?.plus(1)
            ?: AviatorStore.state.value?.roundId

        val signalLabel = if (sig.targetX == null) "SKIP"
                          else "%.2fx".format(sig.targetX)

        // HERO: big target
        b.predTargetBig.text = if (sig.targetX == null) "SKIP" else "%.2fx".format(sig.targetX)
        b.predTargetBig.setTextColor(sig.targetColor)

        b.predForRound.text = "FOR ROUND #${runningId ?: "—"}"

        b.predSignal.text = sig.action
        b.predSignal.background = roundedBg(sig.bg, 8f)

        b.predSuggestion.text = "${sig.cashout} — ${sig.reason}"
        b.predConfidence.text = sig.conf
        b.predConfidence.setTextColor(sig.confCol)

        b.predDrought.text = if (droughtIdx < 0) "${all.size}+" else drought.toString()
        b.predLowStreak.text = lowStreak.toString()
        b.predHeat.text = "$heat/10"

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

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val PREF_OVERLAY = "vip_overlay_enabled"
    }
}
