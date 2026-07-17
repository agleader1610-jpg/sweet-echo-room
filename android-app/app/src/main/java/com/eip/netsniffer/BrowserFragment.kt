package com.eip.netsniffer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.eip.netsniffer.databinding.FragmentBrowserBinding

class BrowserFragment : Fragment() {
    private var _b: FragmentBrowserBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { requireContext().getSharedPreferences("net_sniffer", Context.MODE_PRIVATE) }
    @Volatile private var wsRelayEnabled = false
    @Volatile private var wsRelayBaseUrl = ""
    private var docStartScriptToken: androidx.webkit.ScriptHandler? = null
    @Volatile private var triedFallback = false
    @Volatile private var currentTarget: String = PRIMARY_URL
    private var dotsAnim: Animation? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentBrowserBinding.inflate(inflater, container, false)
        return b.root
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onViewCreated(view: View, s: Bundle?) {
        val wv: WebView = b.webView
        val savedRelay = prefs.getString(PREF_WS_RELAY_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        wsRelayBaseUrl = if (savedRelay == null || savedRelay.startsWith(BROKEN_TLS_RELAY_PREFIX, true)) {
            prefs.edit().putString(PREF_WS_RELAY_URL, DEFAULT_WS_RELAY_URL).apply()
            DEFAULT_WS_RELAY_URL
        } else savedRelay
        wsRelayEnabled = prefs.getBoolean(PREF_WS_RELAY_ENABLED, true)
        b.relayProxyInput.setText(wsRelayBaseUrl)
        b.relayToggle.isChecked = wsRelayEnabled

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // JS bridge so page scripts can report fetch / XHR / WebSocket events
        wv.addJavascriptInterface(JsBridge(), "__NetSniffer")

        // Inject hook at document-start in EVERY frame (main + iframes) BEFORE
        // any page script runs. This is what makes iframe players / chat widgets'
        // WebSocket calls also get captured (Tampermonkey parity).
        installDocStartHook(wv)

        wv.webViewClient = object : SniffingWebViewClient({ currentHookScript() }) {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoading()
                view.evaluateJavascript(currentHookScript(), null)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                hideLoading()
                view.evaluateJavascript(currentHookScript(), null)
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest?, err: WebResourceError?) {
                super.onReceivedError(view, req, err)
                if (req?.isForMainFrame == true) handleMainFrameFailure(view, req.url?.toString())
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 100) hideLoading()
            }
        }

        startLoadingAnimation()

        // Auto-load default site on first appearance
        if (wv.url.isNullOrEmpty()) {
            triedFallback = false
            currentTarget = PRIMARY_URL
            b.urlInput.setText(PRIMARY_URL)
            showLoading()
            wv.loadUrl(PRIMARY_URL)
        } else {
            hideLoading()
        }



        b.goBtn.setOnClickListener { loadFromInput() }
        b.relayToggle.setOnCheckedChangeListener { _, _ ->
            saveRelaySettings()
            wv.evaluateJavascript(currentHookScript(), null)
        }
        b.relayProxyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveRelaySettings()
                wv.evaluateJavascript(currentHookScript(), null)
            }
        }
        b.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadFromInput(); true } else false
        }
        b.relayProxyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveRelaySettings()
                wv.evaluateJavascript(currentHookScript(), null)
                true
            } else false
        }

        // ---- EIP overlay (compact aviator status pinned in browser tab) ----
        AviatorStore.overlayEnabled.observe(viewLifecycleOwner) { updateOverlayVisibility() }
        AviatorStore.state.observe(viewLifecycleOwner) { renderOverlay() }
        AviatorStore.rounds.observe(viewLifecycleOwner) { renderOverlay() }
        PredictionTracker.stats.observe(viewLifecycleOwner) { renderOverlay() }
        setupOverlayDrag()
        b.overlayClose.setOnClickListener {
            AviatorStore.overlayEnabled.postValue(false)
        }

        // ---- WIN GO overlay ----
        WinGoStore.overlayEnabled.observe(viewLifecycleOwner) { updateOverlayVisibility(); renderWinGoOverlay() }
        WinGoStore.currentSignal.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        WinGoStore.currentTargetIssue.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        WinGoStore.lastResult.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        WinGoStore.score.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        WinGoStore.history.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        WinGoStore.live.observe(viewLifecycleOwner) { renderWinGoOverlay() }
        setupWinGoOverlayDrag()
        b.wingoOverlayClose.setOnClickListener { WinGoStore.overlayEnabled.postValue(false) }
        LicenseGate.state.observe(viewLifecycleOwner) { updateOverlayVisibility() }
        wingoTicker.also { ui.post(it) }
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val wingoTicker: Runnable = object : Runnable {
        override fun run() {
            if (WinGoStore.overlayEnabled.value == true) renderWinGoCountdown()
            ui.postDelayed(this, 500)
        }
    }

    private fun setupWinGoOverlayDrag() {
        val card = _b?.wingoOverlay ?: return
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var dragging = false
        val slop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        card.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = view.x - ev.rawX; dY = view.y - ev.rawY
                    downX = ev.rawX; downY = ev.rawY; dragging = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (kotlin.math.abs(ev.rawX - downX) > slop || kotlin.math.abs(ev.rawY - downY) > slop)) dragging = true
                    if (dragging) {
                        val parent = view.parent as? View
                        val maxX = ((parent?.width ?: 0) - view.width).toFloat()
                        val maxY = ((parent?.height ?: 0) - view.height).toFloat()
                        view.x = (ev.rawX + dX).coerceIn(0f, maxX.coerceAtLeast(0f))
                        view.y = (ev.rawY + dY).coerceIn(0f, maxY.coerceAtLeast(0f))
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) view.performClick(); true
                }
                else -> false
            }
        }
    }

    private fun renderWinGoCountdown() {
        val v = _b ?: return
        val l = WinGoStore.live.value ?: return
        val s = ((l.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0)) / 1000
        v.wingoOverlayCountdown.text = "%02d:%02d".format(s / 60, s % 60)
        v.wingoOverlayCountdown.setTextColor(
            when { s <= 5 -> 0xFFF87171.toInt(); s <= 10 -> 0xFFFBBF24.toInt(); else -> 0xFF4ADE80.toInt() }
        )
    }

    private fun renderWinGoOverlay() {
        val v = _b ?: return
        val sig = WinGoStore.currentSignal.value
        val target = WinGoStore.currentTargetIssue.value
        val last = WinGoStore.lastResult.value
        // W/L score hidden from overlay header per user request.
        v.wingoOverlayScore.visibility = View.GONE
        v.wingoOverlayPeriod.text = "PERIOD  #${target?.takeLast(6) ?: "—"}"

        if (sig == null) {
            v.wingoOverlayPick.text = "—"
            v.wingoOverlayPick.setBackgroundResource(R.drawable.hero_neutral_gradient)
            v.wingoOverlayConfidence.text = "ANALYZING…"
        } else {
            val (color, label, heroBg) = when (sig.pick) {
                "BIG" -> Triple(0xFFF59E0B.toInt(), "BIG", R.drawable.hero_big_gradient)
                "SMALL" -> Triple(0xFF3B82F6.toInt(), "SMALL", R.drawable.hero_small_gradient)
                else -> Triple(0xFF64748B.toInt(), sig.pick, R.drawable.hero_neutral_gradient)
            }
            v.wingoOverlayPick.text = label
            v.wingoOverlayPick.setBackgroundResource(heroBg)
            val confCol = when (sig.confidence) {
                "HIGH" -> 0xFF4ADE80.toInt(); "MEDIUM" -> 0xFFFBBF24.toInt(); else -> 0xFF94A3B8.toInt()
            }
            v.wingoOverlayConfidence.text = "${sig.category} • ${sig.confidence}"
            v.wingoOverlayConfidence.setTextColor(confCol)
            // Speak the pick once per target period (respects mute pref).
            target?.let { VoicePlayer.speakForIssue(requireContext(), it, sig.pick) }
        }
        // Long-press overlay pick to toggle mute globally.
        v.wingoOverlayPick.setOnLongClickListener {
            val nowMuted = VoicePlayer.toggleMuted(requireContext())
            android.widget.Toast.makeText(
                requireContext(),
                if (nowMuted) "🔇 Voice muted" else "🔊 Voice on",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }

        if (last == null) {
            v.wingoOverlayResult.text = "◌ WAIT"
            v.wingoOverlayResult.setBackgroundColor(0xFF1F2937.toInt())
            v.wingoOverlayResult.setTextColor(0xFF94A3B8.toInt())
        } else {
            v.wingoOverlayResult.text = (if (last.win) "✓ WIN " else "✗ LOSS ") +
                "#${last.issue.takeLast(6)} → ${last.number} ${last.colors.joinToString("+")}"
            v.wingoOverlayResult.setBackgroundColor(if (last.win) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
            v.wingoOverlayResult.setTextColor(0xFFFFFFFF.toInt())
        }

        val hist = WinGoStore.history.value.orEmpty().take(20)
        v.wingoOverlayHistory.text = if (hist.isEmpty()) "" else buildString {
            append("  ")
            hist.forEach { r ->
                val c = when {
                    r.colors.contains("red") && r.colors.contains("violet") -> "🟪"
                    r.colors.contains("green") && r.colors.contains("violet") -> "🟪"
                    r.colors.contains("red") -> "🟥"
                    r.colors.contains("green") -> "🟩"
                    else -> "⬜"
                }
                append(c).append(r.number).append("  ")
            }
        }
        v.wingoOverlayHistory.isSelected = true
        renderWinGoCountdown()
    }

    private fun setupOverlayDrag() {
        val card = _b?.vipOverlay ?: return
        var dX = 0f; var dY = 0f
        var downX = 0f; var downY = 0f
        var dragging = false
        val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        card.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = view.x - ev.rawX
                    dY = view.y - ev.rawY
                    downX = ev.rawX; downY = ev.rawY
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(ev.rawX - downX) > touchSlop ||
                         kotlin.math.abs(ev.rawY - downY) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val parent = view.parent as? View
                        val maxX = ((parent?.width ?: 0) - view.width).toFloat()
                        val maxY = ((parent?.height ?: 0) - view.height).toFloat()
                        val nx = (ev.rawX + dX).coerceIn(0f, maxX.coerceAtLeast(0f))
                        val ny = (ev.rawY + dY).coerceIn(0f, maxY.coerceAtLeast(0f))
                        view.animate().x(nx).y(ny).setDuration(0).start()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = dragging
                    dragging = false
                    if (!wasDrag) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOverlayVisibility() {
        val v = _b ?: return
        if (LicenseGate.isLocked()) {
            v.vipOverlay.visibility = View.GONE
            v.wingoOverlay.visibility = View.GONE
            return
        }
        val aviOn = AviatorStore.overlayEnabled.value == true
        val wgOn = WinGoStore.overlayEnabled.value == true
        // Mutual exclusion: WinGo wins if both somehow true.
        val showAvi = aviOn && !wgOn
        val st = AviatorStore.state.value
        v.vipOverlay.visibility = if (showAvi && st != null && st.updatedAt != 0L) View.VISIBLE else View.GONE
        v.wingoOverlay.visibility = if (wgOn) View.VISIBLE else View.GONE
    }

    private fun renderOverlay() {
        val v = _b ?: return
        updateOverlayVisibility()
        val st = AviatorStore.state.value ?: return

        val rounds = AviatorStore.rounds.value.orEmpty()
        val lastRound = rounds.firstOrNull()
        val lastId = lastRound?.roundId ?: st.prevRoundId ?: st.roundId
        val lastX = lastRound?.crashX ?: st.prevCrashX ?: st.crashX
        val nextId = lastId?.plus(1)

        val armed = PredictionTracker.currentArmed()
        val stats = PredictionTracker.stats.value
        val last = stats?.lastResult

        // HERO signal must ONLY reflect the CURRENT running round.
        // If we're not armed for nextId, show nothing (no stale previous label).
        val showLive = armed != null && nextId != null && armed.roundId == nextId
        val armedLabel = if (showLive) armed?.label else null
        val isSkip = armedLabel?.equals("SKIP", ignoreCase = true) == true
        val target = armedLabel
            ?.removeSuffix("x")?.removeSuffix("X")
            ?.toDoubleOrNull()

        v.overlayLive.text = when {
            !showLive -> "—"
            isSkip -> "SKIP"
            target != null -> "%.2fx".format(target)
            else -> "—"
        }
        v.overlayLive.setTextColor(
            when {
                !showLive -> 0xFF64748B.toInt()
                isSkip -> 0xFF60A5FA.toInt()
                target != null -> 0xFF4ADE80.toInt()
                else -> 0xFF64748B.toInt()
            }
        )

        v.overlayPhase.text = if (showLive) "SIGNAL • FOR #${nextId}" else "WAITING • NEXT #${nextId ?: "—"}"
        v.overlayPhase.setTextColor(0xFF94A3B8.toInt())

        v.overlaySignal.text = when {
            !showLive -> "WAIT"
            isSkip -> "SKIP ROUND"
            else -> "BET NOW"
        }
        v.overlaySignal.setBackgroundColor(
            when {
                !showLive -> 0xFF455A64.toInt()
                isSkip -> 0xFF3B82F6.toInt()
                else -> 0xFF16A34A.toInt()
            }
        )
        v.overlaySignal.setTextColor(0xFFFFFFFF.toInt())

        v.overlayPrev.text = if (lastId != null && lastX != null)
            "LAST #%d → %.2fx".format(lastId, lastX)
        else "LAST —"

        // Previous-round outcome strip: shows WIN / LOSS / SKIPPED distinctly.
        when {
            last == null -> {
                v.overlayResult.text = "◌ WAIT"
                v.overlayResult.setBackgroundColor(0xFF1F2937.toInt())
                v.overlayResult.setTextColor(0xFF94A3B8.toInt())
            }
            last.skipped -> {
                v.overlayResult.text = "◌ SKIPPED #${last.roundId} → %.2fx".format(last.crashX)
                v.overlayResult.setBackgroundColor(0xFF3B82F6.toInt())
                v.overlayResult.setTextColor(0xFFFFFFFF.toInt())
            }
            last.win -> {
                v.overlayResult.text = "✓ WIN #${last.roundId} → %.2fx".format(last.crashX)
                v.overlayResult.setBackgroundColor(0xFF16A34A.toInt())
                v.overlayResult.setTextColor(0xFFFFFFFF.toInt())
            }
            else -> {
                v.overlayResult.text = "✗ LOSS #${last.roundId} → %.2fx".format(last.crashX)
                v.overlayResult.setBackgroundColor(0xFFDC2626.toInt())
                v.overlayResult.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        v.overlayScore.visibility = View.GONE

        val hist = rounds.take(30)
        if (hist.isEmpty()) {
            v.overlayHistory.text = ""
        } else {
            val sb = StringBuilder("  ")
            hist.forEach { r ->
                val c = when {
                    r.crashX >= 10.0 -> "🟪"
                    r.crashX >= 2.0  -> "🟩"
                    else             -> "🟥"
                }
                sb.append(c).append(" %.2fx   ".format(r.crashX))
            }
            v.overlayHistory.text = sb.toString()
            v.overlayHistory.isSelected = true
        }
    }

    private fun loadFromInput() {
        saveRelaySettings()
        var url = b.urlInput.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        triedFallback = false
        currentTarget = url
        showLoading()
        b.webView.loadUrl(url)
    }

    private fun showLoading() {
        _b?.loadingOverlay?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        _b?.loadingOverlay?.let { overlay ->
            if (overlay.visibility != View.GONE) {
                overlay.animate().alpha(0f).setDuration(280).withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }.start()
            }
        }
    }

    private fun startLoadingAnimation() {
        val dots = _b?.loadingDots ?: return
        val anim = AlphaAnimation(0.25f, 1f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        dots.startAnimation(anim)
        dotsAnim = anim
    }

    private fun handleMainFrameFailure(view: WebView, failedUrl: String?) {
        if (!triedFallback && (failedUrl == null || failedUrl.contains("dkwin8", ignoreCase = true) || failedUrl == currentTarget)) {
            triedFallback = true
            currentTarget = FALLBACK_URL
            b.urlInput.setText(FALLBACK_URL)
            showLoading()
            view.loadUrl(FALLBACK_URL)
        }
    }

    private fun currentHookScript(): String = JsHook.script(
        wsRelayEnabled = wsRelayEnabled,
        wsRelayBaseUrl = wsRelayBaseUrl
    )

    private fun installDocStartHook(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try { docStartScriptToken?.remove() } catch (_: Exception) {}
        docStartScriptToken = WebViewCompat.addDocumentStartJavaScript(
            wv, currentHookScript(), setOf("*")
        )
    }

    private fun saveRelaySettings() {
        wsRelayEnabled = b.relayToggle.isChecked
        wsRelayBaseUrl = b.relayProxyInput.text?.toString()?.trim().orEmpty()
        prefs.edit()
            .putBoolean(PREF_WS_RELAY_ENABLED, wsRelayEnabled)
            .putString(PREF_WS_RELAY_URL, wsRelayBaseUrl)
            .apply()
        _b?.webView?.let { installDocStartHook(it) }
    }

    // ---- Public API for the floating launcher sheet ----

    fun navigateTo(url: String) {
        val v = _b ?: return
        var u = url.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        v.urlInput.setText(u)
        triedFallback = false
        currentTarget = u
        showLoading()
        v.webView.loadUrl(u)
    }

    fun currentUrl(): String = _b?.urlInput?.text?.toString().orEmpty()

    fun setRelay(enabled: Boolean, url: String) {
        val v = _b ?: return
        v.relayToggle.isChecked = enabled
        v.relayProxyInput.setText(url)
        saveRelaySettings()
        v.webView.evaluateJavascript(currentHookScript(), null)
    }

    fun relayEnabled(): Boolean = wsRelayEnabled
    fun relayUrl(): String = wsRelayBaseUrl

    /** Returns true if the WebView consumed the back press (navigated back in history). */
    fun handleBackPressed(): Boolean {
        val wv = _b?.webView ?: return false
        if (wv.canGoBack()) {
            wv.goBack()
            return true
        }
        return false
    }


    override fun onDestroyView() {
        try { docStartScriptToken?.remove() } catch (_: Exception) {}
        docStartScriptToken = null
        dotsAnim?.cancel(); dotsAnim = null
        ui.removeCallbacks(wingoTicker)
        super.onDestroyView(); _b = null
    }

    companion object {
        private const val PREF_WS_RELAY_ENABLED = "ws_relay_enabled"
        private const val PREF_WS_RELAY_URL = "ws_relay_url"
        private const val BROKEN_TLS_RELAY_PREFIX =
            "ws://netsniffer.198-54-121-62.sslip.io/ws-proxy"
        private const val DEFAULT_WS_RELAY_URL =
            "wss://netsniffer.198-54-121-62.sslip.io/ws-proxy?token=ns-android-2026-relay-4d184ca9-2578-4035"
        private const val PRIMARY_URL = "https://dkwin8.com"
        private const val FALLBACK_URL = "https://dkwin7.com"
    }
}
