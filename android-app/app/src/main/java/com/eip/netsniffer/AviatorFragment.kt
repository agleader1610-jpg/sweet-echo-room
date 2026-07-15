package com.eip.netsniffer

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentAviatorBinding

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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAviatorBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        AviatorStore.state.observe(viewLifecycleOwner) { renderState(it) }
        AviatorStore.rounds.observe(viewLifecycleOwner) { renderRounds(it) }
        b.clearBtn.setOnClickListener { AviatorStore.clear() }
    }

    override fun onResume() { super.onResume(); ticker.post(tickRunnable) }
    override fun onPause()  { super.onPause();  ticker.removeCallbacks(tickRunnable) }

    private fun renderState(st: AviatorState?) {
        if (st == null || st.updatedAt == 0L) {
            b.emptyText.visibility = View.VISIBLE
            b.phaseCard.visibility = View.GONE
            b.timingRow.visibility = View.GONE
            return
        }
        b.emptyText.visibility = View.GONE
        b.phaseCard.visibility = View.VISIBLE
        b.timingRow.visibility = View.VISIBLE

        val (label, color) = when (st.phase) {
            AviatorPhase.FLYING   -> "BET"      to 0xFF4CAF50.toInt()
            AviatorPhase.CRASHED  -> "CRASHED"  to 0xFFE53935.toInt()
            AviatorPhase.BETTING  -> "FLYING"   to 0xFFFFB74D.toInt()
            AviatorPhase.IDLE     -> "IDLE"     to 0xFF455A64.toInt()
        }
        b.phaseBadge.text = label
        b.phaseBadge.background = ColorDrawable(color)

        val big = when (st.phase) {
            AviatorPhase.CRASHED -> st.crashX ?: st.prevCrashX
            AviatorPhase.FLYING  -> st.liveMultiplier
            else                 -> st.prevCrashX
        }
        b.liveMultiplier.text = if (big != null) "%.2fx".format(big) else "—"
        b.liveMultiplier.setTextColor(color)

        b.roundLine.text = buildString {
            append("round #${st.roundId ?: "-"}")
            if (st.prevRoundId != null && st.prevCrashX != null)
                append("   • prev #${st.prevRoundId} → %.2fx".format(st.prevCrashX))
            if (st.openBetsCount != null) append("   • open bets: ${st.openBetsCount}")
        }

        b.cyclesText.text = st.cycleCount.toString()
        b.flyMsText.text  = fmtMs(st.lastFlyDurationMs)
        b.waitMsText.text = fmtMs(st.lastWaitDurationMs)
    }

    private fun renderTimers() {
        val st = AviatorStore.state.value ?: return
        if (st.phaseSince == 0L) return
        val elapsed = System.currentTimeMillis() - st.phaseSince
        val phaseName = when (st.phase) {
            AviatorPhase.FLYING  -> "bet window for"
            AviatorPhase.CRASHED -> "crashed since"
            AviatorPhase.BETTING -> "flying for"
            AviatorPhase.IDLE    -> "idle"
        }
        b.phaseTimer.text = "$phaseName ${fmtMs(elapsed)}"
    }

    private fun renderRounds(list: List<AviatorRound>) {
        if (list.isEmpty()) { b.roundsText.text = "(none)"; return }
        b.roundsText.text = list.take(40).joinToString("  ") { r ->
            val color = when {
                r.crashX >= 10.0 -> "🟪"
                r.crashX >= 2.0  -> "🟩"
                else             -> "🟥"
            }
            "$color%.2fx".format(r.crashX)
        }
    }

    private fun fmtMs(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000.0
        return if (s < 60) "%.1fs".format(s) else "%dm %02ds".format((s/60).toInt(), (s%60).toInt())
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
