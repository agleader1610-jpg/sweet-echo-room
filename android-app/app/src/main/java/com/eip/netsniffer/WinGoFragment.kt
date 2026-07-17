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
import com.eip.netsniffer.databinding.FragmentWingoBinding

class WinGoFragment : Fragment() {
    private var _b: FragmentWingoBinding? = null
    private val b get() = _b!!

    private var armedIssue: String? = null
    private var armedSignal: WinGoLogic.Signal? = null
    private var wins = 0
    private var losses = 0
    private val resolvedIssues = HashSet<String>()

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderCountdown()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentWingoBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        WinGoStore.start()
        WinGoStore.history.observe(viewLifecycleOwner) { onHistory(it) }
        WinGoStore.live.observe(viewLifecycleOwner) { renderCountdown() }
        ui.post(ticker)
        b.clearBtn.setOnClickListener {
            wins = 0; losses = 0
            resolvedIssues.clear()
            armedIssue = null; armedSignal = null
            renderScoreboard()
            b.lastResultBadge.text = "—"
            b.lastResultBadge.setTextColor(0xFF94A3B8.toInt())
            b.lastResultBadge.background = bg(0xFF1F2937.toInt(), 6f)
            b.lastResultDetail.text = ""
            WinGoStore.lastResult.postValue(null)
            WinGoStore.score.postValue(0 to 0)
        }

        // Browser-tab overlay toggle (mutually exclusive with Aviator overlay).
        val prefs = requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE)
        b.overlaySwitch.isChecked = prefs.getBoolean(PREF_WINGO_OVERLAY, false)
        WinGoStore.overlayEnabled.postValue(b.overlaySwitch.isChecked)
        b.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_WINGO_OVERLAY, checked).apply()
            WinGoStore.overlayEnabled.postValue(checked)
            if (checked) {
                // Kill the aviator overlay to keep only one active.
                prefs.edit().putBoolean("vip_overlay_enabled", false).apply()
                AviatorStore.overlayEnabled.postValue(false)
            }
        }
        // Reflect external changes (Aviator toggling on) back into our switch.
        WinGoStore.overlayEnabled.observe(viewLifecycleOwner) { on ->
            if (b.overlaySwitch.isChecked != (on == true)) b.overlaySwitch.isChecked = on == true
        }
    }

    companion object { private const val PREF_WINGO_OVERLAY = "wingo_overlay_on" }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(ticker)
        _b = null
    }

    private fun onHistory(list: List<WinGoRound>) {
        if (list.isEmpty()) return

        // 1) Resolve any armed prediction if its round now appears in history
        val newest = list.first()
        val armed = armedSignal
        val armedId = armedIssue
        if (armed != null && armedId != null && !resolvedIssues.contains(armedId)) {
            val match = list.firstOrNull { it.issue == armedId }
            if (match != null) {
                val win = WinGoLogic.didWin(armed, match)
                if (win) wins++ else losses++
                resolvedIssues.add(armedId)
                b.lastResultBadge.text = if (win) "✓ WIN" else "✗ LOSS"
                b.lastResultBadge.setTextColor(0xFFFFFFFF.toInt())
                b.lastResultBadge.background = bg(
                    if (win) 0xFF16A34A.toInt() else 0xFFDC2626.toInt(), 6f
                )
                b.lastResultDetail.text =
                    "#${match.issue.takeLast(6)} → ${match.number} ${match.colors.joinToString("+")} " +
                    "• bet ${armed.pick}"
                WinGoStore.lastResult.postValue(
                    WinGoStore.LastResult(match.issue, win, match.number, match.colors, armed.pick)
                )
                WinGoStore.score.postValue(wins to losses)
                armedIssue = null; armedSignal = null
                renderScoreboard()
            }
        }

        // 2) Render history strip
        renderHistoryStrip(list)

        // 3) Compute next signal (arm for the round *after* newest, i.e. currently-running)
        val sig = WinGoLogic.predict(list) ?: return
        val runningId = WinGoStore.live.value?.currentIssue
        // Only arm if that current running issue isn't already the newest history entry
        val armIssue = when {
            runningId != null && runningId != newest.issue -> runningId
            else -> nextIssueOf(newest.issue)
        }
        if (armIssue != armedIssue) {
            armedIssue = armIssue
            armedSignal = sig
        }
        WinGoStore.currentSignal.postValue(sig)
        WinGoStore.currentTargetIssue.postValue(armIssue)
        renderSignal(sig, armIssue)
    }

    private fun renderSignal(sig: WinGoLogic.Signal, forIssue: String) {
        b.signalCard.visibility = View.VISIBLE
        val (color, label) = when (sig.pick) {
            "RED" -> 0xFFDC2626.toInt() to "RED"
            "GREEN" -> 0xFF16A34A.toInt() to "GREEN"
            "BIG" -> 0xFFF59E0B.toInt() to "BIG (5-9)"
            "SMALL" -> 0xFF3B82F6.toInt() to "SMALL (0-4)"
            else -> 0xFF64748B.toInt() to sig.pick
        }
        b.predPick.text = label
        b.predPick.background = bg(color, 12f)
        b.predCategory.text = "BET ON ${sig.category}"
        b.predForIssue.text = "PERIOD  #${forIssue.takeLast(6)}"
        b.predConfidence.text = sig.confidence
        val confCol = when (sig.confidence) {
            "HIGH" -> 0xFF4ADE80.toInt()
            "MEDIUM" -> 0xFFFBBF24.toInt()
            else -> 0xFF94A3B8.toInt()
        }
        b.predConfidence.setTextColor(confCol)
        b.predReasoning.text = sig.reasoning
    }

    private fun renderHistoryStrip(list: List<WinGoRound>) {
        b.historyStrip.removeAllViews()
        val ctx = requireContext()
        list.take(20).forEach { r -> b.historyStrip.addView(makeChip(ctx, r)) }
    }

    private fun renderScoreboard() {
        b.winsText.text = wins.toString()
        b.lossesText.text = losses.toString()
        val total = wins + losses
        b.accuracyText.text = if (total == 0) "0%" else "${wins * 100 / total}%"
    }

    private fun renderCountdown() {
        val l = WinGoStore.live.value ?: return
        val now = System.currentTimeMillis()
        val remainMs = (l.endTimeMs - now).coerceAtLeast(0)
        val s = remainMs / 1000
        b.countdownText.text = "%02d:%02d".format(s / 60, s % 60)
        b.currentPeriod.text = "#${l.currentIssue?.takeLast(6) ?: "—"}"
        b.countdownText.setTextColor(
            when {
                s <= 5 -> 0xFFF87171.toInt()
                s <= 10 -> 0xFFFBBF24.toInt()
                else -> 0xFF4ADE80.toInt()
            }
        )
    }

    private fun makeChip(ctx: Context, r: WinGoRound): View {
        val tv = TextView(ctx)
        val color = when {
            r.colors.contains("red") && r.colors.contains("violet") -> 0xFF7C3AED.toInt()
            r.colors.contains("green") && r.colors.contains("violet") -> 0xFF7C3AED.toInt()
            r.colors.contains("red") -> 0xFFDC2626.toInt()
            r.colors.contains("green") -> 0xFF16A34A.toInt()
            else -> 0xFF64748B.toInt()
        }
        tv.background = bg(color, 10f)
        tv.text = r.number.toString()
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        val pad = dp(ctx, 10f).toInt()
        tv.setPadding(pad, dp(ctx, 6f).toInt(), pad, dp(ctx, 6f).toInt())
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(dp(ctx, 36f).toInt(), dp(ctx, 36f).toInt())
        lp.marginEnd = dp(ctx, 6f).toInt()
        tv.layoutParams = lp
        return tv
    }

    private fun nextIssueOf(issue: String): String {
        // issueNumber is a monotonically increasing numeric string
        return try {
            val big = java.math.BigInteger(issue)
            big.add(java.math.BigInteger.ONE).toString()
        } catch (_: Throwable) { issue }
    }

    private fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun bg(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(requireContext(), radiusDp)
        setColor(color)
    }
}
