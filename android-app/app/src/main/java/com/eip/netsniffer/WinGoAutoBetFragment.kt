package com.eip.netsniffer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentWingoAutobetBinding

/**
 * Dedicated tab for the WinGo Auto-Bet control panel.
 * Signal source stays in WinGoFragment; this fragment only drives AutoBetEngine.
 */
class WinGoAutoBetFragment : Fragment() {
    private var _b: FragmentWingoAutobetBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentWingoAutobetBinding.inflate(i, c, false)
        return LicenseLockOverlay.wrap(this, b.root)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        AutoBetEngine.init(requireContext())
        val cfg = AutoBetEngine.config.value ?: AutoBetEngine.Config()

        b.autoBetSwitch.isChecked = cfg.enabled
        b.balanceInput.setText(cfg.balance.toString())
        b.stepsInput.setText(cfg.steps.toString())
        b.multInput.setText(cfg.multiplier.toString())
        val modeId = when (cfg.mode) { "RANDOM" -> R.id.modeRandom; "PATTERN" -> R.id.modePattern; else -> R.id.modeApp }
        b.autoBetModeGroup.check(modeId)

        b.stopLossInput.setText(cfg.stopLoss.toString())
        b.targetWinInput.setText(cfg.targetWin.toString())
        b.maxRoundsInput.setText(cfg.maxRounds.toString())
        b.skipLowConfBox.isChecked = cfg.skipLowConfidence

        val push = {
            val mode = when (b.autoBetModeGroup.checkedRadioButtonId) {
                R.id.modeRandom -> "RANDOM"; R.id.modePattern -> "PATTERN"; else -> "APP"
            }
            AutoBetEngine.updateConfig(AutoBetEngine.Config(
                enabled = b.autoBetSwitch.isChecked,
                steps = (b.stepsInput.text.toString().toIntOrNull() ?: 7).coerceIn(1, 15),
                balance = (b.balanceInput.text.toString().toIntOrNull() ?: 100).coerceAtLeast(1),
                multiplier = (b.multInput.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                mode = mode,
                stopLoss = b.stopLossInput.text.toString().toIntOrNull() ?: 500,
                targetWin = b.targetWinInput.text.toString().toIntOrNull() ?: 1000,
                maxRounds = b.maxRoundsInput.text.toString().toIntOrNull() ?: 50,
                skipLowConfidence = b.skipLowConfBox.isChecked,
            ))
        }

        b.autoBetSwitch.setOnCheckedChangeListener { _, _ -> push() }
        b.autoBetModeGroup.setOnCheckedChangeListener { _, _ -> push() }
        b.skipLowConfBox.setOnCheckedChangeListener { _, _ -> push() }
        val lostListener = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) push() }
        b.balanceInput.onFocusChangeListener = lostListener
        b.stepsInput.onFocusChangeListener = lostListener
        b.multInput.onFocusChangeListener = lostListener
        b.stopLossInput.onFocusChangeListener = lostListener
        b.targetWinInput.onFocusChangeListener = lostListener
        b.maxRoundsInput.onFocusChangeListener = lostListener
        b.abResetStats.setOnClickListener { AutoBetEngine.reset() }

        AutoBetEngine.state.observe(viewLifecycleOwner) { st ->
            st ?: return@observe
            b.abPlaced.text = st.placed.toString()
            b.abWon.text = st.won.toString()
            b.abLost.text = st.lost.toString()
            b.abPnl.text = (if (st.pnl >= 0) "+৳" else "-৳") + kotlin.math.abs(st.pnl).toString()
            b.abPnl.setTextColor(if (st.pnl >= 0) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
            val cfgNow = AutoBetEngine.config.value ?: AutoBetEngine.Config()
            val stepLbl = if (st.ladderReady) {
                val n = st.stepIdx + 1
                if (n > cfgNow.steps) "$n/${cfgNow.steps}+" else "$n/${cfgNow.steps}"
            } else "—"
            b.abNextBet.text = if (st.nextBetPick != null && st.nextBetIssue != null)
                "Next: ${st.nextBetPick} · ৳${st.nextBetAmount} · step $stepLbl  (#${st.nextBetIssue.takeLast(6)})"
            else "Next: — · step $stepLbl"
            b.abNote.text = st.lastNote +
                (if (st.ladder.isNotEmpty()) "  ·  ladder ${st.ladder.joinToString("·")}" else "")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
