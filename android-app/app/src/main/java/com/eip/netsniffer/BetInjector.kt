package com.eip.netsniffer

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Injects JavaScript into the game WebView to simulate a bet click flow.
 *
 * Strategy: pure DOM click simulation of the game page's own UI, so every
 * request keeps the user's real cookies, session, User-Agent and phone IP.
 * No VPS/relay involvement, no signature reverse-engineering.
 *
 * Flow inside the injected JS:
 *   1) Find BIG/SMALL button by visible text (multiple fallbacks).
 *   2) Click it → opens the bet modal.
 *   3) Click amount preset (1 / 10 / 100 / 1000).
 *   4) Click multiplier preset (x1 / x5 / x10 / x50 / x100).
 *   5) Click Confirm button.
 *   6) Report result back via console log + callback.
 */
object BetInjector {

    /** Result of a placement attempt. */
    data class Result(val ok: Boolean, val stage: String, val note: String)

    private val ui = Handler(Looper.getMainLooper())

    fun placeBet(
        webView: WebView,
        pick: String,          // "BIG" or "SMALL"
        amount: Int,           // 1, 10, 100, 1000
        multiplier: Int,       // 1, 5, 10, 50, 100
        onDone: (Result) -> Unit,
    ) {
        val js = buildJs(pick, amount, multiplier)
        ui.post {
            webView.evaluateJavascript(js) { raw ->
                val cleaned = raw?.trim()?.trim('"')?.replace("\\\"", "\"") ?: ""
                val ok = cleaned.startsWith("OK|")
                val parts = cleaned.split("|", limit = 3)
                val stage = parts.getOrNull(1) ?: "unknown"
                val note = parts.getOrNull(2) ?: cleaned
                onDone(Result(ok, stage, note))
            }
        }
    }

    private fun buildJs(pick: String, amount: Int, multiplier: Int): String {
        // language=JavaScript
        return """
        (function(){
          try {
            var PICK = '${pick.uppercase()}';           // BIG | SMALL
            var AMT  = '${amount}';
            var MULT = 'X${multiplier}';                 // X1, X5, X10, X50, X100
            var log = [];
            var report = function(ok, stage, note){
              return (ok?'OK|':'ERR|') + stage + '|' + note + ' :: ' + log.join(' > ');
            };
            var norm = function(s){ return (s||'').replace(/\s+/g,' ').trim().toUpperCase(); };
            var visible = function(el){
              if(!el) return false;
              var r = el.getBoundingClientRect();
              return r.width>2 && r.height>2 && getComputedStyle(el).visibility!=='hidden' &&
                     getComputedStyle(el).display!=='none';
            };
            var findByText = function(text, tags){
              text = norm(text);
              tags = tags || ['button','div','span','a','li'];
              var out = [];
              for (var i=0;i<tags.length;i++){
                var els = document.querySelectorAll(tags[i]);
                for (var j=0;j<els.length;j++){
                  var el = els[j];
                  var t = norm(el.textContent);
                  if (!visible(el)) continue;
                  if (t === text) out.push(el);
                }
              }
              // shortest DOM depth first (usually more specific button)
              out.sort(function(a,b){ return depth(a) - depth(b); });
              return out;
            };
            var depth = function(el){ var n=0; while(el){ n++; el=el.parentElement; } return n; };
            var click = function(el){
              if (!el) return false;
              try {
                el.scrollIntoView({block:'center'});
                var opts = {bubbles:true, cancelable:true, view:window};
                el.dispatchEvent(new MouseEvent('mousedown', opts));
                el.dispatchEvent(new MouseEvent('mouseup', opts));
                el.dispatchEvent(new MouseEvent('click', opts));
                return true;
              } catch(e){ return false; }
            };
            var wait = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };

            // ---------- 1) Click BIG/SMALL ----------
            var sizeTargets = findByText(PICK==='BIG'?'Big':'Small', ['div','button','span']);
            if (sizeTargets.length === 0) {
              // fallback: class hints
              var cls = PICK==='BIG' ? '.big,.van-button--warning' : '.small,.van-button--info';
              var alt = document.querySelectorAll(cls);
              for (var k=0;k<alt.length;k++) if(visible(alt[k])) sizeTargets.push(alt[k]);
            }
            if (sizeTargets.length === 0) return report(false,'PICK','no '+PICK+' button found');
            log.push('pick='+PICK);
            click(sizeTargets[0]);

            // ---------- 2) Wait for modal, pick amount + multiplier ----------
            return (async function(){
              await wait(350);
              // amount preset
              var amtBtns = findByText(AMT, ['div','span','button']);
              if (amtBtns.length){ click(amtBtns[0]); log.push('amt='+AMT); }
              else log.push('amt=MISSING');

              await wait(150);
              // multiplier preset (X1..X100)
              var multBtns = findByText(MULT, ['div','span','button']);
              if (!multBtns.length) multBtns = findByText(MULT.toLowerCase(), ['div','span','button']);
              if (multBtns.length){ click(multBtns[0]); log.push('mult='+MULT); }
              else log.push('mult=MISSING');

              await wait(180);
              // confirm — try many labels
              var confirmLabels = ['Confirm','TOTAL AMOUNT','Total amount','Submit','OK','Ok','ঠিক আছে'];
              var confirmBtn = null;
              for (var i=0;i<confirmLabels.length;i++){
                var arr = findByText(confirmLabels[i], ['button','div','span']);
                if (arr.length){ confirmBtn = arr[arr.length-1]; break; }
              }
              if (!confirmBtn){
                var alt = document.querySelector('.van-dialog__confirm, .btn-confirm, .confirm-btn, [class*="confirm"]');
                if (alt && visible(alt)) confirmBtn = alt;
              }
              if (!confirmBtn) return report(false,'CONFIRM','no confirm button');
              click(confirmBtn);
              log.push('confirmed');
              return report(true,'DONE', PICK+' amt='+AMT+' mult='+MULT);
            })();
          } catch(e){
            return 'ERR|EXCEPTION|'+(e && e.message ? e.message : e);
          }
        })();
        """.trimIndent()
    }
}
