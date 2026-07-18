package com.eip.netsniffer

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject

/**
 * Places a bet by calling the game's own signed API from the WebView.
 *
 * Approach (learned from the working DK WIN userscript):
 *   1) JsHook script sniffs Authorization + base + gameCode from any
 *      /api/Lottery/* traffic and stashes them on window.__DK_TOKEN,
 *      window.__DK_BASE, window.__DK_GAME.
 *   2) window.__dkPlaceBet(payload) signs (MD5, sorted-scalar) and POSTs
 *      directly to <base>/WinGoBet using the page's cookies + phone IP.
 *
 * This bypasses the fragile DOM click flow (which was freezing at the
 * "Total amount / Confirm" step) and is far more reliable.
 *
 * DOM click is kept as a last-resort fallback in case the token has not
 * been captured yet (user hasn't opened WinGo tab this session).
 */
object BetInjector {

    data class Result(val ok: Boolean, val stage: String, val note: String)

    private val ui = Handler(Looper.getMainLooper())

    fun placeBet(
        webView: WebView,
        pick: String,          // "BIG" or "SMALL"
        amount: Int,           // base amount unit — usually 1
        multiplier: Int,       // betMultiple — the real risk
        onDone: (Result) -> Unit,
        issueNumber: String? = null,
        gameCodeOverride: String? = null,
    ) {
        val js = buildApiJs(pick, amount, multiplier, issueNumber, gameCodeOverride)
        ui.post {
            webView.evaluateJavascript(js) { raw ->
                val res = parseResult(raw)
                if (res.ok) {
                    onDone(res)
                } else if (res.stage == "NO_TOKEN") {
                    // Token not captured yet — fall back to DOM click once.
                    webView.evaluateJavascript(buildDomJs(pick, amount, multiplier)) { raw2 ->
                        onDone(parseResult(raw2))
                    }
                } else {
                    onDone(res)
                }
            }
        }
    }

    private fun parseResult(raw: String?): Result {
        val cleaned = (raw ?: "").trim()
        // Result may be a JSON object (from Promise resolve) or a bare "OK|..." string.
        if (cleaned.startsWith("{")) {
            return try {
                val o = JSONObject(cleaned)
                Result(o.optBoolean("ok"), o.optString("stage"), o.optString("note"))
            } catch (_: Exception) {
                Result(false, "PARSE", cleaned)
            }
        }
        val stripped = cleaned.trim('"').replace("\\\"", "\"")
        val ok = stripped.startsWith("OK|")
        val parts = stripped.split("|", limit = 3)
        return Result(ok, parts.getOrNull(1) ?: "?", parts.getOrNull(2) ?: stripped)
    }

    private fun buildApiJs(
        pick: String,
        amount: Int,
        multiplier: Int,
        issueNumber: String?,
        gameCodeOverride: String?,
    ): String {
        val betContent = if (pick.equals("BIG", true)) "BigSmall_Big" else "BigSmall_Small"
        val issueLit = if (issueNumber.isNullOrBlank()) "null" else JSONObject.quote(issueNumber)
        val gameLit = if (gameCodeOverride.isNullOrBlank()) "null" else JSONObject.quote(gameCodeOverride)
        // language=JavaScript
        return """
        (async function(){
          try {
            if (typeof window.__dkPlaceBet !== 'function') {
              return JSON.stringify({ok:false, stage:'NO_HELPER', note:'__dkPlaceBet not installed — reload page'});
            }
            var gameCode = ($gameLit) || window.__DK_GAME || 'WinGo_30S';
            var issue = ($issueLit);
            if (!issue) {
              // Best-effort: read the visible issue number from the page.
              try {
                var m = document.body.innerText.match(/\d{15,20}/);
                if (m) issue = m[0];
              } catch(e){}
            }
            if (!issue) return JSON.stringify({ok:false, stage:'NO_ISSUE', note:'could not resolve issueNumber'});
            var payload = {
              gameCode: gameCode,
              issueNumber: String(issue),
              amount: $amount,
              betMultiple: $multiplier,
              betContent: '$betContent'
            };
            var r = await window.__dkPlaceBet(payload);
            return JSON.stringify(r || {ok:false, stage:'NO_RES', note:'no result'});
          } catch(e){
            return JSON.stringify({ok:false, stage:'EXC', note:String(e && e.message || e)});
          }
        })();
        """.trimIndent()
    }

    /** Legacy DOM click fallback — used only if the token has not been captured. */
    private fun buildDomJs(pick: String, amount: Int, multiplier: Int): String {
        return """
        (function(){
          try {
            var PICK='${pick.uppercase()}', AMT='$amount', MULT='X$multiplier', log=[];
            var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toUpperCase();};
            var visible=function(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>2&&r.height>2;};
            var findByText=function(text,tags){text=norm(text);tags=tags||['button','div','span','a','li'];var out=[];
              for(var i=0;i<tags.length;i++){var els=document.querySelectorAll(tags[i]);for(var j=0;j<els.length;j++){var el=els[j];if(!visible(el))continue;if(norm(el.textContent)===text)out.push(el);}}
              return out;};
            var click=function(el){if(!el)return false;try{var o={bubbles:true,cancelable:true,view:window};el.dispatchEvent(new MouseEvent('mousedown',o));el.dispatchEvent(new MouseEvent('mouseup',o));el.dispatchEvent(new MouseEvent('click',o));return true;}catch(e){return false;}};
            var wait=function(ms){return new Promise(function(r){setTimeout(r,ms);});};
            var s=findByText(PICK==='BIG'?'Big':'Small',['div','button','span']);
            if(!s.length) return 'ERR|PICK|no '+PICK;
            click(s[0]); log.push('pick='+PICK);
            return (async function(){
              await wait(300);
              var a=findByText(AMT,['div','span','button']); if(a.length){click(a[0]);log.push('amt='+AMT);}
              await wait(150);
              var mm=findByText(MULT,['div','span','button']); if(mm.length){click(mm[0]);log.push('mult='+MULT);}
              await wait(180);
              var labels=['Confirm','TOTAL AMOUNT','Total amount','Submit','OK'];
              var cb=null;
              for(var i=0;i<labels.length;i++){var arr=findByText(labels[i],['button','div','span']);if(arr.length){cb=arr[arr.length-1];break;}}
              if(!cb){cb=document.querySelector('.van-dialog__confirm,.btn-confirm,.confirm-btn,[class*="confirm"]');}
              if(!cb) return 'ERR|CONFIRM|none';
              click(cb); log.push('confirmed');
              return 'OK|DOM|'+log.join('>');
            })();
          } catch(e){ return 'ERR|EXC|'+(e&&e.message||e); }
        })();
        """.trimIndent()
    }
}
