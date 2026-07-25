package com.eip.netsniffer

import android.content.Context
import android.content.SharedPreferences
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
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentWingoBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WinGoFragment : Fragment() {
    private var _b: FragmentWingoBinding? = null
    private val b get() = _b!!

    private var armedIssue: String? = null
    private var armedSignal: WinGoLogic.Signal? = null
    private var wins = 0
    private var losses = 0
    private val resolvedIssues = HashSet<String>()

    private lateinit var prefs: SharedPreferences
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderCountdown()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentWingoBinding.inflate(i, c, false)
        return LicenseLockOverlay.wrap(this, DepositLockOverlay.wrap(this, b.root, DepositGate.GAME_WINGO))
    }

    // Review reminder handled by ReviewNagger.showWhenUnlocked — fires only
    // when both LicenseGate and DepositGate report unlocked for this tab.




    override fun onViewCreated(view: View, s: Bundle?) {
        ReviewNagger.showWhenUnlocked(this, DepositGate.GAME_WINGO)

        prefs = requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE)
        AutoBetEngine.init(requireContext())  // keep engine alive; UI lives in WinGoAutoBetFragment

        // Restore persisted score + resolved issues so counts survive app restart.
        wins = prefs.getInt(PREF_WINS, 0)
        losses = prefs.getInt(PREF_LOSSES, 0)
        loadResolvedIssues()
        renderScoreboard()
        renderLog()
        WinGoStore.score.postValue(wins to losses)

        WinGoStore.start()
        WinGoStore.history.observe(viewLifecycleOwner) { onHistory(it) }
        WinGoStore.live.observe(viewLifecycleOwner) { renderCountdown() }
        ui.post(ticker)

        b.clearBtn.setOnClickListener {
            wins = 0; losses = 0
            resolvedIssues.clear()
            armedIssue = null; armedSignal = null
            prefs.edit()
                .putInt(PREF_WINS, 0)
                .putInt(PREF_LOSSES, 0)
                .remove(PREF_RESOLVED)
                .apply()
            renderScoreboard()
            b.lastResultBadge.text = "—"
            b.lastResultBadge.setTextColor(0xFF94A3B8.toInt())
            b.lastResultBadge.background = bg(0xFF1F2937.toInt(), 6f)
            b.lastResultDetail.text = ""
            WinGoStore.lastResult.postValue(null)
            WinGoStore.score.postValue(0 to 0)
        }
        b.logClearBtn.setOnClickListener {
            prefs.edit().remove(PREF_LOG).apply()
            renderLog()
        }

        // Browser-tab overlay toggle (mutually exclusive with Aviator overlay).
        b.overlaySwitch.isChecked = prefs.getBoolean(PREF_WINGO_OVERLAY, false)
        WinGoStore.overlayEnabled.postValue(b.overlaySwitch.isChecked)
        b.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_WINGO_OVERLAY, checked).apply()
            WinGoStore.overlayEnabled.postValue(checked)
            if (checked) {
                prefs.edit().putBoolean("vip_overlay_enabled", false).apply()
                AviatorStore.overlayEnabled.postValue(false)
            }
        }
        WinGoStore.overlayEnabled.observe(viewLifecycleOwner) { on ->
            if (b.overlaySwitch.isChecked != (on == true)) b.overlaySwitch.isChecked = on == true
        }

        // Signal source: AI or Server (random). Mutually exclusive.
        val mode = prefs.getString(PREF_SIGNAL_MODE, "ai") ?: "ai"
        b.aiSignalSwitch.isChecked = mode == "ai"
        b.serverSignalSwitch.isChecked = mode == "server"
        applySignalModeState()
        updateSignalSourceUi()
        attachAiSignalListener()
        attachServerSignalListener()
    }

    private fun updateSignalSourceUi() {
        val bnd = _b ?: return
        val mode = currentSignalMode()
        bnd.aiSignalRow.isActivated = mode == "ai"
        bnd.serverSignalRow.isActivated = mode == "server"
        bnd.signalSourceStatus.text = when (mode) {
            "ai" -> "● GEMINI LIVE"
            "server" -> "● RNG LIVE"
            else -> "○ OFF"
        }
        bnd.signalSourceStatus.setTextColor(
            when (mode) {
                "ai" -> 0xFF22D3EE.toInt()
                "server" -> 0xFFF59E0B.toInt()
                else -> 0xFF64748B.toInt()
            }
        )

    private fun attachAiSignalListener() {
        val bnd = _b ?: return
        bnd.aiSignalSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (checked) {
                bnd.serverSignalSwitch.setOnCheckedChangeListener(null)
                bnd.serverSignalSwitch.isChecked = false
                attachServerSignalListener()
                prefs.edit().putString(PREF_SIGNAL_MODE, "ai").apply()
            } else if (!bnd.serverSignalSwitch.isChecked) {
                prefs.edit().putString(PREF_SIGNAL_MODE, "off").apply()
            }
            resetArmedSignalForModeChange()
        }
    }

    private fun attachServerSignalListener() {
        val bnd = _b ?: return
        bnd.serverSignalSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (checked) {
                bnd.aiSignalSwitch.setOnCheckedChangeListener(null)
                bnd.aiSignalSwitch.isChecked = false
                attachAiSignalListener()
                prefs.edit().putString(PREF_SIGNAL_MODE, "server").apply()
            } else if (!bnd.aiSignalSwitch.isChecked) {
                prefs.edit().putString(PREF_SIGNAL_MODE, "off").apply()
            }
            resetArmedSignalForModeChange()
        }
    }


    private fun currentSignalMode(): String =
        prefs.getString(PREF_SIGNAL_MODE, "ai") ?: "ai"

    private fun resetArmedSignalForModeChange() {
        armedIssue = null
        armedSignal = null
        signalsStopped = false
        creditsExhaustedPending = false
        applySignalModeState()
        updateSignalSourceUi()
        // Force a fresh evaluation on next history tick.
        WinGoStore.history.value?.let { onHistory(it) }
    }

    private fun applySignalModeState() {
        val bnd = _b ?: return
        val mode = currentSignalMode()
        if (mode == "off") {
            bnd.signalCard.visibility = View.VISIBLE
            bnd.predPick.text = "OFF"
            bnd.predPick.setBackgroundResource(R.drawable.hero_neutral_gradient)
            bnd.predCategory.text = "SIGNAL OFF"
            bnd.predConfidence.text = "—"
            bnd.predConfidence.setTextColor(0xFF94A3B8.toInt())
            bnd.predReasoning.text = "Signal source (AI বা Server) থেকে একটি on করুন।"
            WinGoStore.clearCurrentSignal()
        }
    }

    companion object {
        private const val PREF_WINGO_OVERLAY = "wingo_overlay_on"
        private const val PREF_WINS = "wingo_wins"
        private const val PREF_LOSSES = "wingo_losses"
        private const val PREF_LOG = "wingo_signal_log"
        private const val PREF_RESOLVED = "wingo_resolved_issues"
        private const val PREF_SIGNAL_MODE = "wingo_signal_mode"  // "ai" | "server" | "off"
        private const val LOG_CAP = 60
    }


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
                prefs.edit()
                    .putInt(PREF_WINS, wins)
                    .putInt(PREF_LOSSES, losses)
                    .apply()
                saveResolvedIssues()
                resolveLogEntry(armedId, win, match)
                b.lastResultBadge.text = if (win) "✓ WIN" else "✗ LOSS"
                b.lastResultBadge.setTextColor(0xFFFFFFFF.toInt())
                b.lastResultBadge.background = bg(
                    if (win) 0xFF16A34A.toInt() else 0xFFDC2626.toInt(), 6f
                )
                b.lastResultDetail.text =
                    "#${match.issue.takeLast(6)} → ${match.number} " +
                    "• bet ${armed.pick}"
                WinGoStore.lastResult.postValue(
                    WinGoStore.LastResult(match.issue, win, match.number, match.colors, armed.pick)
                )
                WinGoStore.score.postValue(wins to losses)
                // Notify auto-bet engine so it counts P/L for placed bets.
                val actualSize = if (match.number >= 5) "BIG" else "SMALL"
                AutoBetEngine.onOutcome(actualSize)
                AutoBetEngine.onResolved(armedId, actualSize)
                armedIssue = null; armedSignal = null
                renderScoreboard()
                renderLog()
                // Recovery step: if AI credit ran out, stop AFTER a win so user
                // doesn't get stuck on a losing random signal.
                if (win && creditsExhaustedPending) {
                    creditsExhaustedPending = false
                    stopSignalsCreditExhausted()
                    return
                }
            }
        }

        // 2) Render history strip
        renderHistoryStrip(list)

        if (signalsStopped) return

        val mode = currentSignalMode()
        if (mode == "off") {
            applySignalModeState()
            return
        }

        // 3) Lock one signal per target period.
        //    - AI mode: shared across devices via /api/public/wingo-signal (credit-safe).
        //    - SERVER mode: pure local random per device, no network call.
        val runningId = AutoBetEngine.state.value?.apiCurrentIssue ?: WinGoStore.live.value?.currentIssue
        val targetIssue = when {
            runningId != null && runningId != newest.issue -> runningId
            else -> nextIssueOf(newest.issue)
        }
        if (targetIssue != armedIssue || armedSignal == null) {
            val existing = readPendingLogSignal(targetIssue, mode)
            if (existing != null) {
                armedIssue = targetIssue
                armedSignal = existing
                renderLog()
            } else if (mode == "server") {
                // Local random — different for every device, no server round-trip.
                val sig = localRandomSignal()
                armedIssue = targetIssue
                armedSignal = sig
                appendPendingLog(targetIssue, sig, mode)
                renderLog()
            } else {
                val aiCached = WinGoAiClient.cachedFor(targetIssue, mode)
                val sig = aiCached?.let(::signalFromAi)
                if (sig != null) {
                    armedIssue = targetIssue
                    armedSignal = sig
                    appendPendingLog(targetIssue, sig, mode)
                    renderLog()
                    if (aiCached.creditsExhausted) creditsExhaustedPending = true
                } else {
                    requestAiSignal(targetIssue, list, mode)
                    return
                }
            }
        }

        val lockedSignal = armedSignal ?: return
        WinGoStore.setCurrentSignal(targetIssue, lockedSignal)
        renderSignal(lockedSignal, targetIssue)
        // Auto-bet: fire once per issue when signal is stable
        AutoBetEngine.onSignal(targetIssue, lockedSignal)
    }

    private var signalsStopped = false
    private var creditsExhaustedPending = false

    private fun signalFromAi(ai: WinGoAiClient.AiSignal): WinGoLogic.Signal {
        // Never leak "AI credit shesh" state into the user-visible reasoning.
        // Show the signal exactly as if everything is normal — the internal
        // creditsExhausted flag still triggers the silent stop after a win.
        val label = when (ai.mode) { "server" -> "Random Engine"; else -> "Connected Gemini AI" }
        val reasoning = "${label} · ${ai.reasoning}".take(180)
        return WinGoLogic.Signal(
            pick = ai.pick,
            category = "SIZE",
            confidence = ai.confidence,
            reasoning = reasoning,
        )
    }

    private fun localRandomSignal(): WinGoLogic.Signal {
        val pick = if (Math.random() < 0.5) "BIG" else "SMALL"
        return WinGoLogic.Signal(
            pick = pick,
            category = "SIZE",
            confidence = "MEDIUM",
            reasoning = "Random Engine · Local random pick।",
        )
    }

    private fun requestAiSignal(targetIssue: String, history: List<WinGoRound>, mode: String) {
        val deviceId = prefs.getString("device_id", "") ?: ""
        val gameCode = WinGoStore.activeGameCode.value ?: "WinGo_30S"
        WinGoAiClient.fetchSignal(deviceId, gameCode, targetIssue, mode, history) { ai ->
            if (ai == null) return@fetchSignal
            if (signalsStopped) return@fetchSignal
            if (currentSignalMode() != mode) return@fetchSignal
            // Period may have advanced while we waited.
            val newestNow = WinGoStore.history.value?.firstOrNull() ?: return@fetchSignal
            val runningNow = AutoBetEngine.state.value?.apiCurrentIssue ?: WinGoStore.live.value?.currentIssue
            val stillTarget = when {
                runningNow != null && runningNow != newestNow.issue -> runningNow
                else -> nextIssueOf(newestNow.issue)
            }
            if (stillTarget != targetIssue) return@fetchSignal
            val sig = signalFromAi(ai)
            armedIssue = targetIssue
            armedSignal = sig
            appendPendingLog(targetIssue, sig, mode)
            renderLog()
            WinGoStore.setCurrentSignal(targetIssue, sig)
            renderSignal(sig, targetIssue)
            AutoBetEngine.onSignal(targetIssue, sig)
            // Silent: mark exhausted internally, but DO NOT change the visible
            // signal card. User only sees the stop message after a win.
            if (ai.creditsExhausted) creditsExhaustedPending = true
        }
    }


    private fun stopSignalsCreditExhausted() {
        signalsStopped = true
        armedIssue = null; armedSignal = null
        val bnd = _b ?: return
        bnd.signalCard.visibility = View.VISIBLE
        bnd.predPick.text = "OFF"
        bnd.predPick.setBackgroundResource(R.drawable.hero_neutral_gradient)
        bnd.predCategory.text = "SYSTEM STOPPED"
        bnd.predConfidence.text = "—"
        bnd.predConfidence.setTextColor(0xFF94A3B8.toInt())
        bnd.predReasoning.text =
            "🛑 System সাময়িকভাবে বন্ধ। কোনো সমস্যার কারণে signal off হয়েছে। Owner-কে inbox করুন।"

        WinGoStore.clearCurrentSignal()
        // Turn off auto-bet so no more random bets go through.
        val cfg = AutoBetEngine.config.value ?: AutoBetEngine.Config()
        if (cfg.enabled) AutoBetEngine.updateConfig(cfg.copy(enabled = false))
        // Turn off browser overlay to stop injecting.
        prefs.edit().putBoolean(PREF_WINGO_OVERLAY, false).apply()
        WinGoStore.overlayEnabled.postValue(false)
        android.widget.Toast.makeText(
            requireContext(),
            "System off — Owner-কে inbox করুন।",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }


    private fun renderSignal(sig: WinGoLogic.Signal, forIssue: String) {
        b.signalCard.visibility = View.VISIBLE
        val (color, label, heroBg) = when (sig.pick) {
            "BIG" -> Triple(0xFFF59E0B.toInt(), "BIG", R.drawable.hero_big_gradient)
            "SMALL" -> Triple(0xFF3B82F6.toInt(), "SMALL", R.drawable.hero_small_gradient)
            else -> Triple(0xFF64748B.toInt(), sig.pick, R.drawable.hero_neutral_gradient)
        }
        b.predPick.text = label
        b.predPick.setBackgroundResource(heroBg)
        val muted = VoicePlayer.isMuted(requireContext())
        b.predCategory.text = if (muted) "BET ON SIZE  🔇 (long-press to unmute)"
                              else "BET ON SIZE  🔊 (long-press to mute)"
        b.predForIssue.text = "PERIOD  #${forIssue.takeLast(6)}"
        b.predConfidence.text = sig.confidence
        val confCol = when (sig.confidence) {
            "HIGH" -> 0xFF4ADE80.toInt()
            "MEDIUM" -> 0xFFFBBF24.toInt()
            else -> 0xFF94A3B8.toInt()
        }
        b.predConfidence.setTextColor(confCol)
        b.predReasoning.text = sig.reasoning

        // Speak BIG/SMALL once per period, respecting mute pref.
        VoicePlayer.speakForIssue(requireContext(), forIssue, sig.pick)

        // Long-press the signal card to toggle mute.
        b.signalCard.setOnLongClickListener {
            val nowMuted = VoicePlayer.toggleMuted(requireContext())
            android.widget.Toast.makeText(
                requireContext(),
                if (nowMuted) "🔇 Voice muted" else "🔊 Voice on",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            b.predCategory.text = if (nowMuted) "BET ON SIZE  🔇 (long-press to unmute)"
                                  else "BET ON SIZE  🔊 (long-press to mute)"
            true
        }
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

    // ---------------------------------------------------------------------
    // Local signal log (SharedPreferences — never uploaded)
    // ---------------------------------------------------------------------

    private fun readLog(): JSONArray = try {
        JSONArray(prefs.getString(PREF_LOG, "[]") ?: "[]")
    } catch (_: Throwable) { JSONArray() }

    private fun writeLog(arr: JSONArray) {
        prefs.edit().putString(PREF_LOG, arr.toString()).apply()
    }

    private fun appendPendingLog(issue: String, sig: WinGoLogic.Signal, mode: String) {
        val arr = readLog()
        // dedupe: if same issue already logged, keep that original locked signal
        for (i in 0 until arr.length()) {
            val existing = arr.optJSONObject(i) ?: continue
            if (existing.optString("issue") == issue && existing.optString("mode") == mode) return
        }
        val o = JSONObject().apply {
            put("issue", issue)
            put("mode", mode)
            put("pick", sig.pick)
            put("category", sig.category)
            put("conf", sig.confidence)
            put("reasoning", sig.reasoning)
            put("ts", System.currentTimeMillis())
            put("status", "PENDING")
        }
        // newest first
        val next = JSONArray().put(o)
        for (i in 0 until arr.length()) {
            if (next.length() >= LOG_CAP) break
            next.put(arr.get(i))
        }
        writeLog(next)
    }

    private fun readPendingLogSignal(issue: String, mode: String): WinGoLogic.Signal? {
        val arr = readLog()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("issue") != issue) continue
            if (o.optString("mode") != mode) continue
            if (o.optString("status", "PENDING") != "PENDING") return null
            val pick = o.optString("pick").takeIf { it == "BIG" || it == "SMALL" } ?: return null
            return WinGoLogic.Signal(
                pick = pick,
                category = o.optString("category", "SIZE"),
                confidence = o.optString("conf", "LOW"),
                reasoning = o.optString("reasoning", "locked signal for this period"),
            )
        }
        return null
    }

    private fun resolveLogEntry(issue: String, win: Boolean, match: WinGoRound) {
        val arr = readLog()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("issue") == issue) {
                o.put("status", if (win) "WIN" else "LOSS")
                o.put("number", match.number)
                break
            }
        }
        writeLog(arr)
    }

    private fun renderLog() {
        val ctx = context ?: return
        val binding = _b ?: return
        binding.signalLog.removeAllViews()
        val arr = readLog()
        if (arr.length() == 0) {
            val tv = TextView(ctx)
            tv.text = "No signals yet — waiting for first round."
            tv.setTextColor(0xFF546A85.toInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            tv.setPadding(0, dp(ctx, 6f).toInt(), 0, dp(ctx, 6f).toInt())
            binding.signalLog.addView(tv)
            return
        }
        val show = minOf(arr.length(), 30)
        for (i in 0 until show) {
            val o = arr.optJSONObject(i) ?: continue
            binding.signalLog.addView(makeLogRow(ctx, o))
        }
    }

    private fun makeLogRow(ctx: Context, o: JSONObject): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val padV = dp(ctx, 7f).toInt()
        val padH = dp(ctx, 10f).toInt()
        row.setPadding(padH, padV, padH, padV)
        row.background = bg(0xFF0A1428.toInt(), 8f)
        val lpRow = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lpRow.topMargin = dp(ctx, 4f).toInt()
        row.layoutParams = lpRow

        val ts = TextView(ctx)
        ts.text = timeFmt.format(Date(o.optLong("ts")))
        ts.setTextColor(0xFF64748B.toInt())
        ts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        ts.typeface = android.graphics.Typeface.MONOSPACE
        row.addView(ts)

        val issue = TextView(ctx)
        issue.text = "  #${o.optString("issue").takeLast(6)}"
        issue.setTextColor(0xFF94A3B8.toInt())
        issue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        issue.typeface = android.graphics.Typeface.MONOSPACE
        row.addView(issue)

        val spacer = View(ctx)
        val sp = LinearLayout.LayoutParams(0, 1, 1f)
        spacer.layoutParams = sp
        row.addView(spacer)

        val pick = TextView(ctx)
        val pickStr = o.optString("pick")
        pick.text = pickStr
        pick.setTextColor(Color.WHITE)
        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        pick.setTypeface(pick.typeface, android.graphics.Typeface.BOLD)
        val pickPadH = dp(ctx, 8f).toInt()
        val pickPadV = dp(ctx, 3f).toInt()
        pick.setPadding(pickPadH, pickPadV, pickPadH, pickPadV)
        pick.background = bg(
            if (pickStr == "BIG") 0xFFF59E0B.toInt() else 0xFF3B82F6.toInt(), 6f
        )
        row.addView(pick)

        val status = TextView(ctx)
        val st = o.optString("status")
        val (bgCol, txt) = when (st) {
            "WIN" -> 0xFF16A34A.toInt() to "✓ WIN · ${o.optInt("number", -1)}"
            "LOSS" -> 0xFFDC2626.toInt() to "✗ LOSS · ${o.optInt("number", -1)}"
            else -> 0xFF334155.toInt() to "PENDING"
        }
        status.text = txt
        status.setTextColor(Color.WHITE)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        status.setTypeface(status.typeface, android.graphics.Typeface.BOLD)
        status.setPadding(pickPadH, pickPadV, pickPadH, pickPadV)
        status.background = bg(bgCol, 6f)
        val lpSt = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lpSt.marginStart = dp(ctx, 6f).toInt()
        status.layoutParams = lpSt
        row.addView(status)

        return row
    }

    private fun loadResolvedIssues() {
        resolvedIssues.clear()
        val s = prefs.getString(PREF_RESOLVED, "") ?: ""
        if (s.isNotEmpty()) resolvedIssues.addAll(s.split(",").filter { it.isNotEmpty() })
    }

    private fun saveResolvedIssues() {
        // keep only newest 200 to avoid unbounded growth
        val trimmed = resolvedIssues.toList().takeLast(200)
        prefs.edit().putString(PREF_RESOLVED, trimmed.joinToString(",")).apply()
    }

    private fun makeChip(ctx: Context, r: WinGoRound): View {
        val tv = TextView(ctx)
        val color = when {
            r.number >= 5 -> 0xFFF59E0B.toInt()   // BIG
            else -> 0xFF3B82F6.toInt()             // SMALL
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

