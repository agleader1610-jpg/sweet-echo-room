package com.eip.netsniffer

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference

/**
 * API-first ground-truth poller (v74).
 *
 * Every ~2 seconds we ask the page to hit the game's own signed API:
 *   GetBalance      → real balance
 *   GetRecordPage   → my last 10 bets (issueNumber, betContent, state, winLoseAmount)
 *   GetGameInfo     → currently-running issue
 *
 * The engine consumes these facts as SINGLE SOURCE OF TRUTH:
 *   - never bets on an issue that isn't the currently running one
 *   - resolves outcomes only when the API says state=1 with winLoseAmount
 *   - detects "we thought we submitted but no order actually reached the server"
 *
 * Uses the same JS bridge / __DK_TOKEN capture that BetInjector uses, so 100%
 * traffic stays on the user's phone IP.
 */
object AutoBetReconciler {
    private const val TAG = "AutoBetReconciler"
    private const val POLL_MS = 2000L

    private val ui = Handler(Looper.getMainLooper())
    private var wvRef: WeakReference<WebView>? = null
    private var running = false
    private var gameCode: String = ""
    private var inFlight = false

    fun attach(webView: WebView) {
        wvRef = WeakReference(webView)
    }

    fun detach() {
        wvRef = null
        stop()
    }

    fun setGameCode(code: String) {
        if (code.isNotBlank()) gameCode = code
    }

    fun start() {
        if (running) return
        WinGoStore.start()
        running = true
        ui.post(tick)
        Log.i(TAG, "reconciler started · game=$gameCode")
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            ui.postDelayed(this, POLL_MS)
        }
    }

    private fun poll() {
        if (inFlight) return
        val wv = wvRef?.get() ?: return
        val cfg = AutoBetEngine.config.value
        // Only poll when auto-bet is armed OR still resolving a pending step.
        val armed = cfg?.enabled == true
        val pending = AutoBetEngine.state.value?.stepPendingIssue != null
        if (!armed && !pending) return

        val gc = if (gameCode.isBlank()) "null" else JSONObject.quote(gameCode)
        val js = """
        (function(){
          try {
            if (typeof window.__dkReconcile !== 'function') {
              window.__NetSniffer.autoBetReconcile(JSON.stringify({ok:false, err:'NO_HELPER'}));
              return 'NO_HELPER';
            }
            window.__dkReconcile($gc).then(function(x){
              window.__NetSniffer.autoBetReconcile(JSON.stringify(x));
            }).catch(function(e){
              window.__NetSniffer.autoBetReconcile(JSON.stringify({ok:false, err:String(e)}));
            });
            return 'DISPATCHED';
          } catch(e){
            window.__NetSniffer.autoBetReconcile(JSON.stringify({ok:false, err:String(e)}));
            return 'EXC';
          }
        })();
        """.trimIndent()
        inFlight = true
        ui.post {
            wv.evaluateJavascript(js, null)
            ui.postDelayed({ if (inFlight) inFlight = false }, 6_000L)
        }
    }

    fun handleJson(jsonText: String?) {
        ui.post { handleRaw(jsonText, alreadyJson = true) }
    }

    private fun handleRaw(raw: String?, alreadyJson: Boolean = false) {
        inFlight = false
        if (raw.isNullOrBlank() || raw == "null") return
        val cleaned = if (alreadyJson) raw else try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (_: Exception) { raw }
        val json = try { JSONObject(cleaned) } catch (_: Exception) { return }
        if (!json.optBoolean("ok", false)) return

        val balance = json.optDouble("balance", Double.NaN)
        val currentIssue = json.optString("currentIssue", "")
        val detectedGame = json.optString("gameCode", "")
        if (detectedGame.isNotBlank()) {
            gameCode = detectedGame
            WinGoStore.setGameCode(detectedGame)
        }
        val remainingSeconds = if (json.has("remainingSeconds")) json.optInt("remainingSeconds") else null
        val ackObj = json.optJSONObject("lastBetAck")
        val bets = json.optJSONArray("myBets") ?: JSONArray()

        val list = mutableListOf<AutoBetEngine.ApiBet>()
        for (i in 0 until bets.length()) {
            val b = bets.optJSONObject(i) ?: continue
            list.add(
                AutoBetEngine.ApiBet(
                    issueNumber = b.optString("issueNumber", ""),
                    betContent = b.optString("betContent", ""),
                    state = b.optInt("state", 0),
                    winLoseAmount = b.optDouble("winLoseAmount", 0.0),
                    betMultiple = b.optInt("betMultiple", 1),
                    number = b.optString("number", ""),
                )
            )
        }

        AutoBetEngine.applyReconciliation(
            AutoBetEngine.ApiSnapshot(
                balance = if (balance.isNaN()) null else balance,
                currentIssue = currentIssue.ifBlank { null },
                myBets = list,
                gameCode = detectedGame.ifBlank { gameCode.ifBlank { null } },
                remainingSeconds = remainingSeconds,
                tokenReady = json.optBoolean("tokenReady", false),
                baseReady = json.optBoolean("baseReady", false),
                lastError = json.optString("lastError", "").ifBlank { null },
                lastBetAck = ackObj?.let {
                    AutoBetEngine.ApiAck(
                        ok = it.optBoolean("ok", false),
                        stage = it.optString("stage", ""),
                        note = it.optString("note", ""),
                    )
                },
            )
        )
    }
}
