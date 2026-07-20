package com.eip.netsniffer

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
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
 * EIP Aviator tab — AI-first signal UI with local logic fallback.
 *
 * Flow on every new confirmed crash:
 *   1. Verify the previous armed prediction against the newest crash.
     *   2. Fire PredictionClient.fetchSignal → Cloud AI with saved W/L memory via backend.
 *      Backend saves everything and returns { source: "ai" | "local" }.
 *   3. If the network itself fails → degrade to on-device logic → source="offline".
 *   4. Render the signal, arm PredictionTracker for the next round.
 */
class AviatorFragment : Fragment() {
    private var _b: FragmentAviatorBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE) }
    private val deviceId by lazy {
        Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // Track the last round we already dispatched a fetch for so we don't spam
    // on every LiveData observer tick.
    private var lastFetchedRoundId: Long? = null
    // The prediction the current on-screen signal came from — used for
    // server-side verification once the next crash arrives.
    private var armedPredictionId: Long? = null
    private var armedRoundId: Long? = null
    private var armedTarget: Double? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAviatorBinding.inflate(i, c, false)
        return LicenseLockOverlay.wrap(this, DepositLockOverlay.wrap(this, b.root))
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        PredictionTracker.init(requireContext())
        AviatorStore.state.observe(viewLifecycleOwner) { renderState(it) }
        AviatorStore.rounds.observe(viewLifecycleOwner) { list ->
            list.firstOrNull()?.let { newest ->
                // 1) verify last armed local prediction (for W/L stats)
                PredictionTracker.resolve(newest)
                // 2) verify server-side prediction if any
                if (armedRoundId != null && armedRoundId == newest.roundId) {
                    PredictionClient.verify(deviceId, armedPredictionId, newest.roundId, newest.crashX, armedTarget)
                    armedPredictionId = null; armedRoundId = null; armedTarget = null
                }
            }
            renderRounds(list)
        }
        PredictionTracker.stats.observe(viewLifecycleOwner) { renderTrackerStats(it) }
        PredictionTracker.history.observe(viewLifecycleOwner) { renderSignalHistory(it) }
        b.clearBtn.setOnClickListener {
            AviatorStore.clear()
            PredictionTracker.reset()
            lastFetchedRoundId = null
            armedPredictionId = null; armedRoundId = null; armedTarget = null
        }

        b.overlaySwitch.isChecked = prefs.getBoolean(PREF_OVERLAY, false)
        AviatorStore.overlayEnabled.postValue(b.overlaySwitch.isChecked)
        b.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_OVERLAY, checked).apply()
            AviatorStore.overlayEnabled.postValue(checked)
            if (checked) {
                prefs.edit().putBoolean("wingo_overlay_on", false).apply()
                WinGoStore.overlayEnabled.postValue(false)
            }
        }
        AviatorStore.overlayEnabled.observe(viewLifecycleOwner) { on ->
            if (b.overlaySwitch.isChecked != (on == true)) b.overlaySwitch.isChecked = on == true
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

        maybeFetchSignal(list)
        renderStatsRow(list) // heat/drought/streak micro stats
    }

    /** Fire an AI signal fetch once per new confirmed round. */
    private fun maybeFetchSignal(rounds: List<AviatorRound>) {
        if (rounds.size < 3) { b.predictionCard.visibility = View.GONE; return }
        b.predictionCard.visibility = View.VISIBLE
        val newestRound = rounds.first().roundId
        if (lastFetchedRoundId == newestRound) return
        lastFetchedRoundId = newestRound

        // Show a placeholder while we wait for AI
        b.predSignal.text = "ANALYZING…"
        b.predSignal.background = roundedBg(0xFF334155.toInt(), 8f)
        b.predSource.text = "🤖 CONNECTING"
        b.predSource.setTextColor(0xFFFACC15.toInt())

        // Debug: mark fetch dispatched
        val expectPredictFor = newestRound + 1
        b.debugStatus.text = "FETCHING…"
        b.debugStatus.setTextColor(0xFFFACC15.toInt())
        b.debugRoundLine.text = "#$newestRound → predicting #$expectPredictFor"
        b.debugMatch.text = ""
        b.debugFetchLog.text = "req sent • ${timeStamp()} • hist=${rounds.size}"

        val t0 = System.currentTimeMillis()
        PredictionClient.fetchSignal(deviceId, rounds) { sig ->
            if (_b == null) return@fetchSignal
            val roundTrip = System.currentTimeMillis() - t0
            if (sig == null) {
                b.debugStatus.text = "OFFLINE"
                b.debugStatus.setTextColor(0xFFF87171.toInt())
                b.debugError.visibility = View.VISIBLE
                b.debugError.text = "network failed — server unreachable. round-trip ${roundTrip}ms"
                b.debugRawAi.text = "(no server response)"
                renderOfflineFallback(rounds)
            } else {
                b.debugError.visibility = if (sig.aiError.isNullOrBlank()) View.GONE else View.VISIBLE
                if (!sig.aiError.isNullOrBlank()) b.debugError.text = "AI error: ${sig.aiError}"
                renderRemoteSignal(sig, rounds)
                renderDebug(sig, newestRound, roundTrip)
            }
        }
    }

    private fun renderDebug(sig: PredictionClient.Signal, requestedNewest: Long, roundTrip: Long) {
        val statusLabel = when (sig.source) {
            "ai" -> "🤖 AI OK"
            "local" -> "⚙ SERVER LOCAL"
            else -> "📴 OFFLINE"
        }
        val statusColor = when (sig.source) {
            "ai" -> 0xFF4ADE80.toInt()
            "local" -> 0xFF60A5FA.toInt()
            else -> 0xFFF87171.toInt()
        }
        b.debugStatus.text = statusLabel
        b.debugStatus.setTextColor(statusColor)

        val serverNewest = sig.historyNewestRoundId
        val expected = requestedNewest + 1
        b.debugRoundLine.text = "sent top #${serverNewest ?: "?"} → got signal for #${sig.roundId}"
        val match = sig.roundId == expected && (serverNewest == null || serverNewest == requestedNewest)
        b.debugMatch.text = if (match) "✓ ROUND MATCH — signal is for current running round" else "⚠ ROUND MISMATCH — expected #$expected"
        b.debugMatch.setTextColor(if (match) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())

        b.debugModel.text = "${sig.source.uppercase()} / ${sig.model ?: "—"}"
        b.debugLatencyId.text = "server ${sig.latencyMs}ms • rt ${roundTrip}ms • id=${sig.predictionId ?: "—"}"
        b.debugReasoning.text = sig.reasoning.ifBlank { "(empty)" }
        b.debugRawAi.text = sig.aiRaw ?: "(no AI raw — fallback used)"
        b.debugFetchLog.text = "req ${timeStamp()} • hist=${sig.historyLen} • mem=${sig.memoryCount} • L${sig.memoryLossStreak} • acc=${sig.memoryAccuracy}% • skip=${sig.memorySkipAccuracy}% • pending=${sig.memoryPending}"
    }

    private fun timeStamp(): String {
        val d = java.util.Date()
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(d)
    }


    private fun renderRemoteSignal(sig: PredictionClient.Signal, rounds: List<AviatorRound>) {
        val runningId = sig.roundId
        val targetText = if (sig.target == null) "SKIP" else "%.2fx".format(sig.target)
        val targetColor = when {
            sig.action == "SKIP" -> 0xFF60A5FA.toInt()
            (sig.target ?: 0.0) >= 3.0 -> 0xFFA78BFA.toInt()
            (sig.target ?: 0.0) >= 2.0 -> 0xFF4ADE80.toInt()
            else -> 0xFFFACC15.toInt()
        }
        val signalBg = when (sig.action) {
            "STRONG BET" -> 0xFFDC2626.toInt()
            "SKIP" -> 0xFF3B82F6.toInt()
            else -> 0xFF16A34A.toInt()
        }
        val confCol = when (sig.confidence) {
            "HIGH" -> 0xFF4ADE80.toInt()
            "MEDIUM" -> 0xFFFBBF24.toInt()
            else -> 0xFF94A3B8.toInt()
        }
        b.predTargetBig.text = targetText
        b.predTargetBig.setTextColor(targetColor)
        b.predForRound.text = "#$runningId"
        b.predSignal.text = sig.action
        b.predSignal.background = roundedBg(signalBg, 8f)
        b.predSuggestion.text = sig.reasoning.ifBlank { "signal generated" }
        b.predConfidence.text = sig.confidence
        b.predConfidence.setTextColor(confCol)
        val (srcLabel, srcCol) = when (sig.source) {
            "ai" -> "🤖 AI" to 0xFF4ADE80.toInt()
            "local" -> "⚙ LOGIC" to 0xFF60A5FA.toInt()
            else -> "📡 REMOTE" to 0xFF94A3B8.toInt()
        }
        b.predSource.text = srcLabel
        b.predSource.setTextColor(srcCol)
        b.predLatency.text = "${sig.latencyMs}ms"

        // Arm both trackers so the next crash verifies this signal
        armedPredictionId = sig.predictionId
        armedRoundId = runningId
        armedTarget = sig.target
        val test: (Double) -> Boolean =
            if (sig.target == null) ({ it < 2.0 }) else ({ it >= sig.target })
        PredictionTracker.arm(runningId, targetText, test)
    }

    private fun renderOfflineFallback(rounds: List<AviatorRound>) {
        // On-device version of the same 9-scenario logic used server-side
        val all = rounds.map { it.crashX }
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

        data class Sig(val action: String, val target: Double?, val conf: String, val reason: String)
        val sig = when {
            lastX >= 10.0 -> Sig("SKIP", null, "HIGH", "big spike just landed — cool-down.")
            lastX >= 3.0 && prevX >= 3.0 -> Sig("SKIP", null, "HIGH", "two high crashes back-to-back — reversal likely.")
            lowStreak >= 8 -> Sig("STRONG BET", 3.0, "HIGH", "$lowStreak straight pinks — big recovery overdue.")
            lowStreak >= 5 -> Sig("STRONG BET", 2.0, "HIGH", "$lowStreak pinks in a row — reversal building.")
            lowStreak >= 3 -> Sig("BET", 1.7, "MEDIUM", "cold streak — moderate rebound.")
            drought >= 25 -> Sig("STRONG BET", 5.0, "HIGH", "$drought rounds since 10x — mega-spike zone.")
            drought >= 15 -> Sig("BET", 2.5, "MEDIUM", "long drought — spike window opening.")
            avgRecent > 3.5 || heat5x >= 4 -> Sig("BET", 1.3, "MEDIUM", "market hot — take small & fast.")
            avgRecent < 1.6 && heat2x <= 4 -> Sig("BET", 1.5, "MEDIUM", "quiet market — safe quick win.")
            highStreak >= 4 -> Sig("SKIP", null, "MEDIUM", "$highStreak greens in a row — crash-back due.")
            avgLast5 in 1.6..2.4 -> Sig("BET", 1.8, "MEDIUM", "balanced trend — steady climb.")
            else -> Sig("BET", 1.6, "LOW", "neutral read — conservative play.")
        }
        val runningId = rounds.first().roundId + 1
        val remote = PredictionClient.Signal(
            predictionId = null,
            roundId = runningId,
            action = sig.action,
            target = sig.target,
            confidence = sig.conf,
            reasoning = sig.reason,
            source = "offline",
            model = null,
            latencyMs = 0,
            historyNewestRoundId = rounds.first().roundId,
            historyNewestMultiplier = rounds.first().crashX,
            historyLen = rounds.size,
            aiRaw = null,
            aiError = "offline_fallback",
            memoryCount = 0,
            memoryLossStreak = 0,
            memoryAccuracy = 0,
            memorySkipAccuracy = 0,
            memoryPending = 0,
            httpStatus = 0,
            requestedAt = System.currentTimeMillis(),
        )

        renderRemoteSignal(remote, rounds)
        b.predSource.text = "📴 OFFLINE"
        b.predSource.setTextColor(0xFFF87171.toInt())
    }

    private fun renderStatsRow(rounds: List<AviatorRound>) {
        val all = rounds.map { it.crashX }
        if (all.isEmpty()) return
        val recent = all.take(15)
        val heat2x = recent.count { it >= 2.0 }
        val lowStreak = all.takeWhile { it < 2.0 }.size
        val droughtIdx = all.indexOfFirst { it >= 10.0 }
        val drought = if (droughtIdx < 0) all.size else droughtIdx
        b.predDrought.text = if (droughtIdx < 0) "${all.size}+" else drought.toString()
        b.predLowStreak.text = lowStreak.toString()
        b.predHeat.text = "$heat2x/${recent.size}"
    }

    private fun renderTrackerStats(s: PredictionTracker.Stats) {
        b.predWins.text = s.wins.toString()
        b.predLosses.text = s.losses.toString()
        b.predAccuracy.text = "${s.accuracy}%"
        val streakLabel = when {
            s.streak > 0 -> "W${s.streak}"
            s.streak < 0 -> "L${-s.streak}"
            else -> "0"
        }
        b.predStreak.text = streakLabel
        b.predStreak.setTextColor(
            when {
                s.streak > 0 -> 0xFF4ADE80.toInt()
                s.streak < 0 -> 0xFFF87171.toInt()
                else -> 0xFFE2E8F0.toInt()
            }
        )
        val r = s.lastResult
        if (r == null) {
            b.predResultBadge.text = "—"
            b.predResultBadge.setTextColor(0xFF94A3B8.toInt())
            b.predResultBadge.background = roundedBg(0xFF1F2937.toInt(), 6f)
            b.predResultDetail.text = ""
        } else if (r.skipped) {
            b.predResultBadge.text = "◌ SKIPPED"
            b.predResultBadge.setTextColor(0xFFFFFFFF.toInt())
            b.predResultBadge.background = roundedBg(0xFF3B82F6.toInt(), 6f)
            b.predResultDetail.text = "#${r.roundId} • skipped • crashed %.2fx".format(r.crashX)
        } else {
            b.predResultBadge.text = if (r.win) "✓ WIN" else "✗ LOSS"
            b.predResultBadge.setTextColor(0xFFFFFFFF.toInt())
            b.predResultBadge.background =
                roundedBg(if (r.win) 0xFF16A34A.toInt() else 0xFFDC2626.toInt(), 6f)
            b.predResultDetail.text = "#${r.roundId} • target ${r.label} • crashed %.2fx".format(r.crashX)
        }
    }

    private fun renderSignalHistory(list: List<PredictionTracker.Result>) {
        val ctx = context ?: return
        if (list.isEmpty()) {
            b.signalHistoryCard.visibility = View.GONE
            return
        }
        b.signalHistoryCard.visibility = View.VISIBLE

        val betOnly = list.filterNot { it.skipped }
        val wins = betOnly.count { it.win }
        val losses = betOnly.count { !it.win }
        val skips = list.count { it.skipped }
        val acc = if (wins + losses > 0) (wins * 100) / (wins + losses) else 0
        b.signalHistoryStats.text = "${wins}W · ${losses}L · ${skips}S · ${acc}%"
        b.signalHistoryStats.setTextColor(
            when {
                acc >= 60 -> 0xFF4ADE80.toInt()
                acc >= 40 -> 0xFFFACC15.toInt()
                else -> 0xFFF87171.toInt()
            }
        )

        b.signalHistoryList.removeAllViews()
        list.take(20).forEach { r -> b.signalHistoryList.addView(makeHistoryRow(ctx, r)) }
    }

    private fun makeHistoryRow(ctx: Context, r: PredictionTracker.Result): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pv = dp(ctx, 8f).toInt()
            setPadding(0, pv, 0, pv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // divider background via bottom border
        val divider = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000)
            setStroke(0, 0)
        }
        row.background = divider

        fun cell(text: String, weight: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(color)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.gravity = gravity
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            tv.layoutParams = lp
            return tv
        }

        row.addView(cell("#${r.roundId}", 1.1f, 0xFFCBD5F5.toInt(), bold = true))
        row.addView(cell(r.label, 1f, 0xFF94A3B8.toInt()))
        val crashColor = when {
            r.crashX >= 10.0 -> 0xFFA78BFA.toInt()
            r.crashX >= 2.0 -> 0xFF4ADE80.toInt()
            else -> 0xFFEF4444.toInt()
        }
        row.addView(cell("%.2fx".format(r.crashX), 1f, crashColor, bold = true))

        // Result badge
        val badge = TextView(ctx)
        val (bg, fg, txt) = when {
            r.skipped -> Triple(0xFF3B82F6.toInt(), 0xFFFFFFFF.toInt(), "◌ SKIP")
            r.win -> Triple(0xFF16A34A.toInt(), 0xFFFFFFFF.toInt(), "✓ WIN")
            else -> Triple(0xFFDC2626.toInt(), 0xFFFFFFFF.toInt(), "✗ LOSS")
        }
        badge.text = txt
        badge.setTextColor(fg)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
        badge.background = roundedBg(bg, 6f)
        val padH = dp(ctx, 8f).toInt(); val padV = dp(ctx, 3f).toInt()
        badge.setPadding(padH, padV, padH, padV)
        badge.gravity = Gravity.CENTER
        val badgeWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
            addView(badge)
        }
        row.addView(badgeWrap)
        return row
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
