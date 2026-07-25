package com.eip.netsniffer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentWingoAutobetBinding


/**
 * Dedicated tab for the WinGo Auto-Bet control panel.
 * Signal source stays in WinGoFragment; this fragment only drives AutoBetEngine.
 */
class WinGoAutoBetFragment : Fragment() {
    private var _b: FragmentWingoAutobetBinding? = null
    private val b get() = _b!!
    private var syncingUi = false
    private val ui = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            AutoBetEngine.tick()
            renderTimer(AutoBetEngine.state.value)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentWingoAutobetBinding.inflate(i, c, false)
        return LicenseLockOverlay.wrap(this, DepositLockOverlay.wrap(this, b.root, DepositGate.GAME_WINGO_AUTO))
    }



    override fun onViewCreated(view: View, s: Bundle?) {
        ReviewNagger.showWhenUnlocked(this, DepositGate.GAME_WINGO_AUTO)
        observeUnlockForBriefing()



        AutoBetEngine.init(requireContext())
        val cfg = AutoBetEngine.config.value ?: AutoBetEngine.Config()

        b.autoBetSwitch.isChecked = cfg.enabled
        b.balanceInput.setText(cfg.balance.toString())
        b.stepsInput.setText(cfg.steps.toString())
        b.multInput.setText(cfg.multiplier.toString())
        b.durationInput.setText(cfg.durationMinutes.toString())
        b.skipLowConfBox.isChecked = cfg.skipLowConfidence
        b.useApiBalanceSwitch.isChecked = cfg.useApiBalance
        b.balanceInput.isEnabled = !cfg.useApiBalance

        val push = {
            AutoBetEngine.updateConfig(AutoBetEngine.Config(
                enabled = b.autoBetSwitch.isChecked,
                steps = (b.stepsInput.text.toString().toIntOrNull() ?: 7).coerceIn(1, 15),
                balance = (b.balanceInput.text.toString().toIntOrNull() ?: 100).coerceAtLeast(1),
                multiplier = (b.multInput.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                // Signal source is now always driven by WinGoFragment's active mode;
                // engine follows sig.pick regardless of this legacy field.
                mode = "APP",
                durationMinutes = (b.durationInput.text.toString().toIntOrNull() ?: 10).coerceIn(1, 1440),
                skipLowConfidence = b.skipLowConfBox.isChecked,
                useApiBalance = b.useApiBalanceSwitch.isChecked
            ))
        }

        b.autoBetSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingUi) push() }
        b.skipLowConfBox.setOnCheckedChangeListener { _, _ -> if (!syncingUi) push() }
        b.useApiBalanceSwitch.setOnCheckedChangeListener { _, checked ->
            b.balanceInput.isEnabled = !checked
            if (!syncingUi) push()
        }
        val lostListener = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) push() }
        b.balanceInput.onFocusChangeListener = lostListener
        b.stepsInput.onFocusChangeListener = lostListener
        b.multInput.onFocusChangeListener = lostListener
        b.durationInput.onFocusChangeListener = lostListener
        b.abResetStats.setOnClickListener { AutoBetEngine.reset() }

        AutoBetEngine.config.observe(viewLifecycleOwner) { c ->
            c ?: return@observe
            syncingUi = true
            try {
                if (b.autoBetSwitch.isChecked != c.enabled) b.autoBetSwitch.isChecked = c.enabled
                if (b.useApiBalanceSwitch.isChecked != c.useApiBalance) b.useApiBalanceSwitch.isChecked = c.useApiBalance
                b.balanceInput.isEnabled = !c.useApiBalance
                if (!b.durationInput.hasFocus() && b.durationInput.text.toString() != c.durationMinutes.toString()) {
                    b.durationInput.setText(c.durationMinutes.toString())
                }
            } finally {
                syncingUi = false
            }
            renderTimer(AutoBetEngine.state.value)
        }

        // Live signal-source label — mirrors whatever mode is on in WinGo Tab.
        WinGoStore.signalMode.observe(viewLifecycleOwner) { renderSignalSource(it) }
        renderSignalSource(WinGoStore.signalMode.value)

        AutoBetEngine.state.observe(viewLifecycleOwner) { st ->
            st ?: return@observe
            renderTimer(st)
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
                "Trade: ${st.nextBetPick} · ৳${st.nextBetAmount} · step $stepLbl  (#${st.nextBetIssue.takeLast(6)})"
            else "Trade: — · step $stepLbl"
            val apiLine = buildString {
                val gameCode = st.apiGameCode ?: "detecting"
                append("GAME $gameCode  ")
                if (st.apiBalance != null) append("💰 ৳" + String.format("%.2f", st.apiBalance) + "  ")
                if (st.apiCurrentIssue != null) append("• API #" + st.apiCurrentIssue.takeLast(6) + "  ")
                if (st.apiRemainingSeconds != null) append("• ${st.apiRemainingSeconds}s left  ")
                if (st.apiLastSyncMs > 0) {
                    val age = (System.currentTimeMillis() - st.apiLastSyncMs) / 1000
                    append("• sync ${age}s ago")
                }
                if (!st.apiTokenReady || !st.apiBaseReady) append("\nWAITING: open/login DK Win tab")
                if (!st.apiLastError.isNullOrBlank()) append("\nAPI: ${st.apiLastError}")
                if (!st.lastBetStage.isNullOrBlank()) {
                    val detail = st.lastBetDetail ?: ""
                    append("\nBET: ${st.lastBetStage} $detail")
                }
            }
            b.abNote.text = st.lastNote +
                (if (apiLine.isNotEmpty()) "\n$apiLine" else "") +
                (if (st.ladder.isNotEmpty()) "  ·  ladder ${st.ladder.joinToString("·")}" else "")
        }
        ui.post(timerTick)
    }

    private fun renderSignalSource(mode: String?) {
        val bnd = _b ?: return
        val (text, color) = when (mode) {
            "ai" -> "🤖 Connected Gemini AI থেকে Bet হবে" to 0xFF22D3EE.toInt()
            "server" -> "🎲 Random Engine থেকে Bet হবে" to 0xFFF59E0B.toInt()
            "jack" -> "🧩 Your Jack থেকে Bet হবে" to 0xFF10B981.toInt()
            else -> "⚠️ WinGo Tab থেকে Signal Mode চালু করুন" to 0xFFF59E0B.toInt()
        }
        bnd.abSignalSourceText.text = text
        bnd.abSignalSourceText.setTextColor(color)
    }

    private fun renderTimer(st: AutoBetEngine.State?) {
        val cfg = AutoBetEngine.config.value ?: AutoBetEngine.Config()
        val state = st ?: AutoBetEngine.State()
        if (!cfg.enabled || state.startedAtMs <= 0L) {
            b.abTimerText.text = "Timer: OFF"
            return
        }
        val end = state.startedAtMs + cfg.durationMinutes * 60_000L
        val left = (end - System.currentTimeMillis()).coerceAtLeast(0L)
        val sec = left / 1000L
        val mm = sec / 60L
        val ss = sec % 60L
        b.abTimerText.text = if (state.stopAfterClear) {
            "Timer: clearing step then OFF"
        } else {
            "Timer: %02d:%02d left".format(mm, ss)
        }
    }

    // ─────────────────────── First-time safety briefing ───────────────────────
    // Shown ONCE, only after both License + Deposit gates unlock for AutoBet.
    // Never shown while locked — user must reach the real panel first.

    private fun observeUnlockForBriefing() {
        val check = {
            val licenseLocked = try { LicenseGate.isLocked() } catch (_: Throwable) { false }
            val depositLocked = try { DepositGate.isLocked(DepositGate.GAME_WINGO_AUTO) } catch (_: Throwable) { true }
            if (!licenseLocked && !depositLocked) maybeShowSafetyBriefing()
        }
        LicenseGate.state.observe(viewLifecycleOwner) { check() }
        DepositGate.stateFor(DepositGate.GAME_WINGO_AUTO).observe(viewLifecycleOwner) { check() }
    }

    private fun maybeShowSafetyBriefing() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("autobet_briefing", Context.MODE_PRIVATE)
        if (prefs.getBoolean("shown_v1", false)) return
        if (!isResumed) return
        prefs.edit().putBoolean("shown_v1", true).apply()
        showSafetyBriefingDialog(ctx)
    }

    private fun showSafetyBriefingDialog(ctx: Context) {
        val pad = dpPx(ctx, 22)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dpPx(ctx, 20), pad, dpPx(ctx, 8))
            background = GradientDrawable().apply {
                cornerRadius = dpPx(ctx, 18).toFloat()
                setColor(0xFF0B1220.toInt())
                setStroke(dpPx(ctx, 1), 0xFF22D3EE.toInt())
            }
        }

        val title = TextView(ctx).apply {
            text = "🚀 Auto-Bet Unlock হয়েছে!"
            textSize = 20f
            setTextColor(0xFF22D3EE.toInt())
            setPadding(0, 0, 0, dpPx(ctx, 6))
        }
        val subtitle = TextView(ctx).apply {
            text = "শুরুর আগে কয়েকটা গুরুত্বপূর্ণ কথা 🙏"
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 0, 0, dpPx(ctx, 14))
        }

        val bodyHtml = """
            <b><font color='#FBBF24'>⚠️ Auto-Bet একটা high-risk system।</font></b><br/>
            নিচের কথাগুলা মেনে চললে আপনার balance অনেক দিন চলবে —<br/><br/>

            <b><font color='#22D3EE'>💰 Balance বেশি রাখুন</font></b><br/>
            কম balance-এ ladder ভেঙে যাবে। Recommended: <b>কমপক্ষে ৳500+</b>।<br/><br/>

            <b><font color='#22D3EE'>📈 Stake একটু বেশি নিন</font></b><br/>
            ছোট stake-এ profit কম দেখাবে, motivation কমে যাবে। আপনার balance অনুযায়ী confident stake সেট করুন।<br/><br/>

            <b><font color='#22D3EE'>⏱️ কম সময় Auto-Bet চালান</font></b><br/>
            যত বেশি সময় চালু রাখবেন, risk তত বাড়ে। ছোট session (<b>5–15 মিনিট</b>) সবচেয়ে safe।<br/><br/>

            <b><font color='#22D3EE'>🎯 Profit হলে থামুন</font></b><br/>
            Target hit হলে সাথে সাথে Auto-Bet OFF করুন। লোভ = loss।<br/><br/>

            <b><font color='#F87171'>❌ যা করবেন না:</font></b><br/>
            • সব balance এক session-এ লাগাবেন না<br/>
            • Loss recover-এর জন্য stake বাড়াবেন না<br/>
            • System-কে সারাদিন unattended রাখবেন না<br/><br/>

            <b><font color='#10B981'>✅ মনে রাখবেন:</font></b> AI ৮০-৯০% accurate — কিন্তু ১০০% না। ধৈর্য + discipline = long-term profit। 🌟
        """.trimIndent()

        val body = TextView(ctx).apply {
            text = Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_COMPACT)
            textSize = 14f
            setTextColor(0xFFE5E7EB.toInt())
            setLineSpacing(dpPx(ctx, 2).toFloat(), 1.15f)
            movementMethod = LinkMovementMethod.getInstance()
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(body)

        val dialog = AlertDialog.Builder(ctx)
            .setView(FrameLayout(ctx).apply {
                setPadding(dpPx(ctx, 12), dpPx(ctx, 12), dpPx(ctx, 12), dpPx(ctx, 12))
                addView(root, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            })
            .setCancelable(false)
            .setPositiveButton("বুঝেছি, শুরু করি 🚀") { d, _ -> d.dismiss() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dpPx(ctx, 20).toFloat()
                setColor(Color.TRANSPARENT)
            })
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFF0B1220.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dpPx(ctx, 24).toFloat()
                    setColor(0xFF22D3EE.toInt())
                }
                setPadding(dpPx(ctx, 24), dpPx(ctx, 10), dpPx(ctx, 24), dpPx(ctx, 10))
            }
        }
        dialog.show()
    }

    private fun dpPx(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()



    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(timerTick)
        _b = null
    }
}
