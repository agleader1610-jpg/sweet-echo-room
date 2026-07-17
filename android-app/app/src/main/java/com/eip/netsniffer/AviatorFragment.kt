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

        // ---------- adaptive stats over last 15 rounds ----------
        val recent = all.take(15)
        val last5 = all.take(5)
        val avgRecent = recent.average()
        val avgLast5 = last5.average()
        val heat5x = recent.count { it >= 5.0 }
        val heat2x = recent.count { it >= 2.0 }
        val lowStreak = all.takeWhile { it < 2.0 }.size
        val highStreak = all.takeWhile { it >= 2.0 }.size
        val droughtIdx = all.indexOfFirst { it >= 10.0 }
        val drought = if (droughtIdx < 0) all.size else droughtIdx
        val lastX = all.first()
        val prevX = all.getOrNull(1) ?: 0.0

        data class Sig(
            val action: String, val bg: Int, val targetColor: Int,
            val cashout: String, val reason: String,
            val conf: String, val confCol: Int,
            val targetX: Double?, val test: (Double) -> Boolean,
        )

        // ---------- adaptive target selection ----------
        val sig: Sig = when {
            // 1) Just crashed on 10x+ → cool-down likely
            lastX >= 10.0 -> Sig(
                "SKIP", 0xFF3B82F6.toInt(), 0xFF60A5FA.toInt(),
                "wait 1–2 rounds", "big spike just landed, cool-down.",
                "HIGH", 0xFF4ADE80.toInt(), null, { it < 2.0 },
            )
            // 2) Two big multipliers back-to-back → almost always followed by pink
            lastX >= 3.0 && prevX >= 3.0 -> Sig(
                "SKIP", 0xFF3B82F6.toInt(), 0xFF60A5FA.toInt(),
                "wait — hot pair", "two high crashes in a row → reversal likely.",
                "HIGH", 0xFF4ADE80.toInt(), null, { it < 2.0 },
            )
            // 3) Long dry spell of pinks → recovery due, aim HIGHER
            lowStreak >= 8 -> Sig(
                "STRONG BET", 0xFFDC2626.toInt(), 0xFFA78BFA.toInt(),
                "cash @ 3.00x", "$lowStreak straight pinks — big recovery overdue.",
                "HIGH", 0xFF4ADE80.toInt(), 3.0, { it >= 3.0 },
            )
            lowStreak >= 5 -> Sig(
                "STRONG BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "cash @ 2.00x", "$lowStreak pinks in a row — reversal building.",
                "HIGH", 0xFF4ADE80.toInt(), 2.0, { it >= 2.0 },
            )
            lowStreak >= 3 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "cash @ 1.70x", "cold streak — moderate rebound.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.7, { it >= 1.7 },
            )
            // 4) Big drought without any 10x
            drought >= 25 -> Sig(
                "STRONG BET", 0xFFDC2626.toInt(), 0xFFA78BFA.toInt(),
                "cash @ 5.00x", "$drought rounds since 10x — mega-spike zone.",
                "HIGH", 0xFF4ADE80.toInt(), 5.0, { it >= 5.0 },
            )
            drought >= 15 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "cash @ 2.50x", "long drought — spike window opening.",
                "MEDIUM", 0xFFFBBF24.toInt(), 2.5, { it >= 2.5 },
            )
            // 5) Very hot market → shave target, quick take
            avgRecent > 3.5 || heat5x >= 4 -> Sig(
                "BET", 0xFFF97316.toInt(), 0xFFFACC15.toInt(),
                "cash @ 1.30x", "market running hot — take small & fast.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.3, { it >= 1.3 },
            )
            // 6) Cold market → small safe bet
            avgRecent < 1.6 && heat2x <= 4 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "cash @ 1.50x", "quiet market — safe quick win.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.5, { it >= 1.5 },
            )
            // 7) High streak — momentum fading
            highStreak >= 4 -> Sig(
                "SKIP", 0xFF9333EA.toInt(), 0xFFC084FC.toInt(),
                "wait — momentum peak", "$highStreak greens in a row → crash-back.",
                "MEDIUM", 0xFFFBBF24.toInt(), null, { it < 2.0 },
            )
            // 8) Nudge target with 5-round avg for variety
            avgLast5 in 1.6..2.4 -> Sig(
                "BET", 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
                "cash @ 1.80x", "balanced trend — steady climb.",
                "MEDIUM", 0xFFFBBF24.toInt(), 1.8, { it >= 1.8 },
            )
            else -> Sig(
                "BET", 0xFF64748B.toInt(), 0xFF94A3B8.toInt(),
                "cash @ 1.60x", "neutral read — conservative play.",
                "LOW", 0xFF94A3B8.toInt(), 1.6, { it >= 1.6 },
            )
        }

        val runningId: Long? = rounds.firstOrNull()?.roundId?.plus(1)
            ?: AviatorStore.state.value?.roundId
        val signalLabel = if (sig.targetX == null) "SKIP" else "%.2fx".format(sig.targetX)

        b.predTargetBig.text = if (sig.targetX == null) "SKIP" else "%.2fx".format(sig.targetX)
        b.predTargetBig.setTextColor(sig.targetColor)
        b.predForRound.text = "#${runningId ?: "—"}"
        b.predSignal.text = sig.action
        b.predSignal.background = roundedBg(sig.bg, 8f)
        b.predSuggestion.text = "${sig.cashout} • ${sig.reason}"
        b.predConfidence.text = sig.conf
        b.predConfidence.setTextColor(sig.confCol)

        b.predDrought.text = if (droughtIdx < 0) "${all.size}+" else drought.toString()
        b.predLowStreak.text = lowStreak.toString()
        b.predHeat.text = "$heat2x/${recent.size}"

        PredictionTracker.arm(runningId, signalLabel, sig.test)
    }

    private fun renderTrackerStats(s: PredictionTracker.Stats) {
        // W/L stats intentionally hidden from top UI per user request.
        // Tracker still runs for internal accuracy tracking.
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
