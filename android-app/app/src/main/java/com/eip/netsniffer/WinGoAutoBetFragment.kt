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
        val amtId = when (cfg.baseAmount) { 10 -> R.id.amt10; 100 -> R.id.amt100; 1000 -> R.id.amt1000; else -> R.id.amt1 }
        b.autoBetAmountGroup.check(amtId)
        val multId = when (cfg.multiplier) { 5 -> R.id.mult5; 10 -> R.id.mult10; 50 -> R.id.mult50; 100 -> R.id.mult100; else -> R.id.mult1 }
        b.autoBetMultGroup.check(multId)
        val stratId = when (cfg.strategy) { "MART" -> R.id.stratMart; "ANTI" -> R.id.stratAnti; else -> R.id.stratFlat }
        b.autoBetStrategyGroup.check(stratId)

        b.stopLossInput.setText(cfg.stopLoss.toString())
        b.targetWinInput.setText(cfg.targetWin.toString())
        b.maxRoundsInput.setText(cfg.maxRounds.toString())
        b.skipLowConfBox.isChecked = cfg.skipLowConfidence

        val push = {
            val amt = when (b.autoBetAmountGroup.checkedRadioButtonId) {
                R.id.amt10 -> 10; R.id.amt100 -> 100; R.id.amt1000 -> 1000; else -> 1
            }
            val mult = when (b.autoBetMultGroup.checkedRadioButtonId) {
                R.id.mult5 -> 5; R.id.mult10 -> 10; R.id.mult50 -> 50; R.id.mult100 -> 100; else -> 1
            }
            val strat = when (b.autoBetStrategyGroup.checkedRadioButtonId) {
                R.id.stratMart -> "MART"; R.id.stratAnti -> "ANTI"; else -> "FLAT"
            }
            AutoBetEngine.updateConfig(AutoBetEngine.Config(
                enabled = b.autoBetSwitch.isChecked,
                baseAmount = amt,
                multiplier = mult,
                strategy = strat,
                stopLoss = b.stopLossInput.text.toString().toIntOrNull() ?: 500,
                targetWin = b.targetWinInput.text.toString().toIntOrNull() ?: 1000,
                maxRounds = b.maxRoundsInput.text.toString().toIntOrNull() ?: 50,
                skipLowConfidence = b.skipLowConfBox.isChecked,
            ))
        }

        b.autoBetSwitch.setOnCheckedChangeListener { _, _ -> push() }
        b.autoBetAmountGroup.setOnCheckedChangeListener { _, _ -> push() }
        b.autoBetMultGroup.setOnCheckedChangeListener { _, _ -> push() }
        b.autoBetStrategyGroup.setOnCheckedChangeListener { _, _ -> push() }
        b.skipLowConfBox.setOnCheckedChangeListener { _, _ -> push() }
        val lostListener = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) push() }
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
            b.abNextBet.text = if (st.nextBetPick != null && st.nextBetIssue != null)
                "Next: ${st.nextBetPick} · ৳${st.nextBetAmount}  (#${st.nextBetIssue.takeLast(6)})"
            else "Next: —"
            b.abNote.text = st.lastNote
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
