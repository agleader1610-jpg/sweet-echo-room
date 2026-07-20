package com.eip.netsniffer

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

class SetupFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_setup, c, false)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        wire(view, R.id.playAutoBet, "https://eip.lat/video_2026-07-20_18-33-27.mp4")
        wire(view, R.id.playWinGo,   "https://eip.lat/video_2026-07-20_18-44-18.mp4")
        wire(view, R.id.playAviator, "https://eip.lat/video_2026-07-20_19-27-00.mp4")
    }

    private fun wire(root: View, id: Int, url: String) {
        root.findViewById<View>(id)?.setOnClickListener {
            openVideoInsideApp(url)
        }
    }

    private fun openVideoInsideApp(url: String) {
        val ctx = context ?: return
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = FrameLayout(ctx).apply {
            setBackgroundColor(Color.rgb(5, 10, 18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54),
            )
        }

        val title = TextView(ctx).apply {
            text = "সেটআপ ভিডিও"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16), 0, dp(64), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        topBar.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(close, FrameLayout.LayoutParams(dp(56), dp(54), android.view.Gravity.END))

        val webView = WebView(ctx).apply {
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }

        container.addView(topBar)
        container.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnDismissListener {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
        dialog.show()

        val html = """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
                <style>
                  html, body { margin:0; width:100%; height:100%; background:#000; overflow:hidden; }
                  video { width:100%; height:100%; background:#000; object-fit:contain; }
                </style>
              </head>
              <body>
                <video controls autoplay playsinline preload="metadata">
                  <source src="$url" type="video/mp4" />
                </video>
              </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://eip.lat/", html, "text/html", "UTF-8", null)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
