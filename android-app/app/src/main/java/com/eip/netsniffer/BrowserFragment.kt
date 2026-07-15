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

        // ---- VIP overlay (compact aviator status pinned in browser tab) ----
        AviatorStore.overlayEnabled.observe(viewLifecycleOwner) { on ->
            updateOverlayVisibility()
        }
        AviatorStore.state.observe(viewLifecycleOwner) { renderOverlay() }
        AviatorStore.rounds.observe(viewLifecycleOwner) { renderOverlay() }
    }

    private fun updateOverlayVisibility() {
        val v = _b ?: return
        val on = AviatorStore.overlayEnabled.value == true
        val st = AviatorStore.state.value
        v.vipOverlay.visibility = if (on && st != null && st.updatedAt != 0L) View.VISIBLE else View.GONE
    }

    private fun renderOverlay() {
        val v = _b ?: return
        updateOverlayVisibility()
        val st = AviatorStore.state.value ?: return
        val (label, color) = when (st.phase) {
            AviatorPhase.FLYING  -> "FLYING"   to 0xFFFFB74D.toInt()
            AviatorPhase.CRASHED -> "CRASHED"  to 0xFFE53935.toInt()
            AviatorPhase.BETTING -> "BET"      to 0xFF4CAF50.toInt()
            AviatorPhase.IDLE    -> "IDLE"     to 0xFF455A64.toInt()
        }
        v.overlayPhase.text = label
        v.overlayPhase.setBackgroundColor(color)
        val big = when (st.phase) {
            AviatorPhase.FLYING  -> st.liveMultiplier
            AviatorPhase.CRASHED -> st.crashX ?: st.prevCrashX
            else -> st.prevCrashX
        }
        v.overlayLive.text = big?.let { "%.2fx".format(it) } ?: "—"
        v.overlayLive.setTextColor(color)
        val curId: Long? = when {
            st.phase == AviatorPhase.CRASHED && st.prevRoundId != null -> st.prevRoundId + 1
            st.roundId != null && st.prevRoundId != null && st.roundId == st.prevRoundId -> st.roundId + 1
            else -> st.roundId
        }
        val prevId = st.prevRoundId ?: (curId?.minus(1))
        v.overlayPrev.text = buildString {
            append("cur #${curId ?: "-"}")
            if (prevId != null) append("  •  prev #$prevId")
            if (st.prevCrashX != null) append(" %.2fx".format(st.prevCrashX))
        }

        val hist = AviatorStore.rounds.value.orEmpty().take(8)
        if (hist.isEmpty()) {
            v.overlayHistory.text = ""
        } else {
            val sb = StringBuilder()
            hist.forEach { r ->
                val c = when {
                    r.crashX >= 10.0 -> "🟪"
                    r.crashX >= 2.0  -> "🟩"
                    else             -> "🟥"
                }
                sb.append(c).append("%.2f ".format(r.crashX))
            }
            v.overlayHistory.text = sb.toString().trim()
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

    override fun onDestroyView() {
        try { docStartScriptToken?.remove() } catch (_: Exception) {}
        docStartScriptToken = null
        dotsAnim?.cancel(); dotsAnim = null
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
