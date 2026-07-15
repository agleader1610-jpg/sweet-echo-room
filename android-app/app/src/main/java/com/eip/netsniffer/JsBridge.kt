package com.eip.netsniffer

import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.URLEncoder

/** Bridge exposed to page JS as window.__NetSniffer */
class JsBridge {
    @JavascriptInterface
    fun log(json: String) {
        try {
            val o = JSONObject(json)
            val reqH = mutableMapOf<String, String>()
            o.optJSONObject("reqHeaders")?.let { h ->
                h.keys().forEach { k -> reqH[k] = h.optString(k) }
            }
            val resH = mutableMapOf<String, String>()
            o.optJSONObject("resHeaders")?.let { h ->
                h.keys().forEach { k -> resH[k] = h.optString(k) }
            }
            CaptureStore.add(
                NetEntry(
                    method = o.optString("method", "-"),
                    url = o.optString("url"),
                    status = o.optInt("status", 0),
                    mime = o.optString("mime", o.optString("kind")),
                    bytes = o.optLong("bytes", 0),
                    ms = o.optLong("ms", 0),
                    reqHeaders = reqH,
                    resHeaders = resH,
                    reqBody = o.optString("reqBody").ifEmpty { null },
                    resBody = o.optString("resBody").ifEmpty { null },
                    source = o.optString("kind", "js")
                )
            )
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun relayUrl(originalUrl: String, relayBaseUrl: String): String {
        return try {
            val abs = originalUrl.trim()
            val base = relayBaseUrl.trim()
            if (abs.isEmpty() || base.isEmpty()) return abs
            if (!abs.startsWith("ws://", true) && !abs.startsWith("wss://", true)) return abs
            if (!base.startsWith("ws://", true) && !base.startsWith("wss://", true)) return abs

            val cleanedBase = base.replace(Regex("([?&])target=[^&]*&?"), "$1")
                .trimEnd('?', '&')
            val out = StringBuilder(cleanedBase)
                .append(if (cleanedBase.contains("?")) "&" else "?")
                .append("target=")
                .append(URLEncoder.encode(abs, "UTF-8"))

            val cookieUrl = abs.replaceFirst("ws://", "http://", true)
                .replaceFirst("wss://", "https://", true)
            val cookie = try { CookieManager.getInstance().getCookie(cookieUrl) } catch (_: Exception) { null }
            if (!cookie.isNullOrBlank()) {
                out.append("&target_cookie=")
                    .append(URLEncoder.encode(cookie, "UTF-8"))
            }
            out.toString()
        } catch (_: Exception) {
            originalUrl
        }
    }
}

object JsHook {
    /** Injected on every page load. */
    fun script(wsRelayEnabled: Boolean = false, wsRelayBaseUrl: String = ""): String {
        val relayEnabled = wsRelayEnabled && wsRelayBaseUrl.isNotBlank()
        val relayBaseLiteral = JSONObject.quote(wsRelayBaseUrl.trim())
        return """
(function(){
  window.__NetSnifferConfig = window.__NetSnifferConfig || {};
  window.__NetSnifferConfig.wsRelayEnabled = $relayEnabled;
  window.__NetSnifferConfig.wsRelayBase = $relayBaseLiteral;
  // WS capture/relay always ON (no gating).
  window.__NetSnifferConfig.wsArmed = true;
  function armWsIfMatches(){ /* no-op: gating disabled */ }
  window.__NetSnifferArmWs = armWsIfMatches;

  if (window.__NetSnifferInstalled) return;
  window.__NetSnifferInstalled = true;
  var B = window.__NetSniffer;
  if (!B) return;
  function send(o){ try { B.log(JSON.stringify(o)); } catch(e){} }

  function bodyToStr(b){
    try {
      if (b == null) return null;
      if (typeof b === 'string') return b.slice(0, 65536);
      if (b instanceof URLSearchParams) return b.toString();
      if (b instanceof FormData) { var a=[]; b.forEach(function(v,k){ a.push(k+'='+v);}); return a.join('&'); }
      if (b instanceof ArrayBuffer) return '[ArrayBuffer '+b.byteLength+' bytes]';
      if (b && b.byteLength != null) return '[Binary '+b.byteLength+' bytes]';
      return String(b);
    } catch(e){ return null; }
  }
  function headersToObj(h){
    var o = {};
    try {
      if (!h) return o;
      if (typeof h.forEach === 'function') { h.forEach(function(v,k){ o[k]=v; }); return o; }
      if (Array.isArray(h)) { h.forEach(function(p){ o[p[0]]=p[1]; }); return o; }
      return Object.assign({}, h);
    } catch(e){ return o; }
  }

  // --- fetch ---
  var _fetch = window.fetch;
  if (_fetch) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var reqHeaders = headersToObj(init && init.headers);
      var reqBody = bodyToStr(init && init.body);
      var t0 = Date.now();
      return _fetch.apply(this, arguments).then(function(res){
        var resHeaders = headersToObj(res.headers);
        var mime = res.headers.get('content-type') || '';
        var isText = /^(text\/|application\/(json|xml|javascript|x-www-form-urlencoded)|.*\+json|.*\+xml)/i.test(mime);
        var clone; try { clone = res.clone(); } catch(e){}
        function done(bodyStr, byteLen){
          try { armWsIfMatches(url, res.status); } catch(e){}
          send({kind:'fetch', method:method, url:url, status:res.status, mime:mime,
                bytes:byteLen, ms:Date.now()-t0,
                reqHeaders:reqHeaders, resHeaders:resHeaders,
                reqBody:reqBody, resBody:bodyStr});
        }

        if (!clone) { done(null, 0); return res; }
        if (isText) {
          clone.text().then(function(txt){ done((txt||'').slice(0,262144), txt?txt.length:0); })
                     .catch(function(){ done(null, 0); });
        } else {
          clone.arrayBuffer().then(function(buf){
            try {
              var u8 = new Uint8Array(buf);
              var n = u8.byteLength;
              var lim = Math.min(n, 262144);
              var bin = ''; for (var i=0;i<lim;i++) bin += String.fromCharCode(u8[i]);
              var b64 = (typeof btoa==='function') ? btoa(bin) : '';
              var hexLim = Math.min(n, 512), hex = '';
              for (var j=0;j<hexLim;j++){ var h=u8[j].toString(16); if(h.length<2)h='0'+h; hex+=h+' '; }
              var out = '[Binary '+n+' bytes • mime='+mime+']\n\n── HEX (first '+hexLim+' bytes) ──\n'+hex.trim()+'\n\n── BASE64 (first '+lim+' bytes) ──\n'+b64;
              if (n>lim) out += '\n\n… [truncated, '+(n-lim)+' more bytes]';
              done(out, n);
            } catch(e){ done('[Binary body – decode error: '+e+']', 0); }
          }).catch(function(){ done(null, 0); });
        }
        return res;
      }).catch(function(err){
        send({kind:'fetch', method:method, url:url, status:-1, bytes:0, ms:Date.now()-t0,
              reqHeaders:reqHeaders, reqBody:reqBody, resBody:String(err)});
        throw err;
      });
    };
  }

  // --- XHR ---
  var XHR = window.XMLHttpRequest;
  if (XHR) {
    var _open = XHR.prototype.open;
    var _send = XHR.prototype.send;
    var _setH = XHR.prototype.setRequestHeader;
    XHR.prototype.open = function(m, u){ this.__ns = {m:m, u:u, h:{}}; return _open.apply(this, arguments); };
    XHR.prototype.setRequestHeader = function(k, v){
      if (this.__ns) this.__ns.h[k] = v;
      return _setH.apply(this, arguments);
    };
    XHR.prototype.send = function(body){
      var self = this;
      var t0 = Date.now();
      var reqBody = bodyToStr(body);
      self.addEventListener('loadend', function(){
        var txt = '';
        try { txt = (self.responseType === '' || self.responseType === 'text') ? (self.responseText || '') : ''; } catch(e){}
        var resHeaders = {};
        try {
          var raw = self.getAllResponseHeaders() || '';
          raw.split(/\r?\n/).forEach(function(line){
            var i = line.indexOf(':'); if (i>0) resHeaders[line.slice(0,i).trim()] = line.slice(i+1).trim();
          });
        } catch(e){}
        var xhrUrl = (self.__ns&&self.__ns.u)||'';
        try { armWsIfMatches(xhrUrl, self.status||0); } catch(e){}
        send({kind:'xhr', method:(self.__ns&&self.__ns.m)||'GET', url:xhrUrl,
              status:self.status||-1, mime:resHeaders['content-type']||'',
              bytes:txt.length, ms:Date.now()-t0,
              reqHeaders:(self.__ns&&self.__ns.h)||{}, resHeaders:resHeaders,
              reqBody:reqBody, resBody:txt.slice(0,65536)});

      });
      return _send.apply(this, arguments);
    };
  }

  // --- WebSocket ---
  function dumpBinary(u8, mime){
    var n = u8.byteLength;
    var lim = Math.min(n, 262144);
    var bin = ''; for (var i=0;i<lim;i++) bin += String.fromCharCode(u8[i]);
    var b64 = ''; try { b64 = btoa(bin); } catch(e){}
    var hexLim = Math.min(n, 512), hex = '';
    for (var j=0;j<hexLim;j++){ var h=u8[j].toString(16); if(h.length<2)h='0'+h; hex+=h+' '; }
    var out = '[Binary '+n+' bytes'+(mime?(' • '+mime):'')+']\n\n── HEX (first '+hexLim+' bytes) ──\n'+hex.trim()+'\n\n── BASE64 (first '+lim+' bytes) ──\n'+b64;
    if (n>lim) out += '\n\n… [truncated, '+(n-lim)+' more bytes]';
    return out;
  }
  function wsDataToStr(d, cb){
    try {
      if (d == null) { cb(0, '(empty)'); return; }
      if (typeof d === 'string') { cb(d.length, d.slice(0, 262144)); return; }
      if (d instanceof ArrayBuffer) { var u=new Uint8Array(d); cb(u.byteLength, dumpBinary(u, 'ArrayBuffer')); return; }
      if (d && d.buffer instanceof ArrayBuffer) { var u2=new Uint8Array(d.buffer, d.byteOffset||0, d.byteLength); cb(u2.byteLength, dumpBinary(u2, 'TypedArray')); return; }
      if (typeof Blob !== 'undefined' && d instanceof Blob) {
        var fr = new FileReader();
        fr.onload = function(){ var u3=new Uint8Array(fr.result); cb(u3.byteLength, dumpBinary(u3, d.type||'Blob')); };
        fr.onerror = function(){ cb(d.size||0, '[Blob '+(d.size||0)+' bytes – read error]'); };
        fr.readAsArrayBuffer(d); return;
      }
      cb(0, '['+Object.prototype.toString.call(d)+']');
    } catch(e){ cb(0, '[decode error: '+e+']'); }
  }
  var _WS = window.WebSocket;
  if (_WS && !_WS.__netSnifferWrapped) {
    var _addEL = _WS.prototype.addEventListener;
    var _remEL = _WS.prototype.removeEventListener;
    var _sendProto = _WS.prototype.send;
    var _closeProto = _WS.prototype.close;

    function tapMessage(url, ev){
      try {
        wsDataToStr(ev.data, function(n, preview){
          send({kind:'ws-msg-in', method:'WS<', url:url, status:0, bytes:n, ms:0, resBody:preview});
        });
      } catch(e){
        send({kind:'ws-msg-in', method:'WS<', url:url, status:0, bytes:0, ms:0, resBody:'[capture failed: '+e+']'});
      }
    }
    function tapOpen(url, t0){ send({kind:'ws-open', method:'WS', url:url, status:101, bytes:0, ms:Date.now()-t0, resBody:'[open]'}); }
    function tapClose(url, t0, ev){ send({kind:'ws-close', method:'WS', url:url, status:(ev&&ev.code)||0, bytes:0, ms:Date.now()-t0, resBody:'[close code='+((ev&&ev.code)||0)+' reason='+((ev&&ev.reason)||'')+']'}); }
    function tapError(url, t0){ send({kind:'ws-error', method:'WS', url:url, status:-1, bytes:0, ms:Date.now()-t0, resBody:'[error event]'}); }
    function normalizeWsUrl(url){
      try { return new URL(String(url), window.location.href).href; }
      catch(e){ return String(url); }
    }
    function relayWsUrl(originalUrl){
      var abs = normalizeWsUrl(originalUrl);
      var cfg = window.__NetSnifferConfig || {};
      if (!cfg.wsRelayEnabled || !cfg.wsRelayBase) return abs;
      if (!/^wss?:\/\//i.test(abs)) return abs;
      try {
        var base = String(cfg.wsRelayBase).trim();
        if (!base) return abs;
        if (/^https?:\/\//i.test(base)) base = base.replace(/^http/i, 'ws');
        if (!/^wss?:\/\//i.test(base)) return abs;
        try {
          var relayUrl = new URL(base);
          relayUrl.searchParams.delete('target');
          base = relayUrl.toString();
        } catch(e){}
        if (abs.indexOf(base) === 0 || abs.indexOf('/api/public/ws-proxy') !== -1 || abs.indexOf('/ws-proxy') !== -1) return abs;
        try {
          if (B && typeof B.relayUrl === 'function') {
            var nativeRelayUrl = B.relayUrl(abs, base);
            if (nativeRelayUrl && /^wss?:\/\//i.test(nativeRelayUrl) && nativeRelayUrl !== abs) return nativeRelayUrl;
          }
        } catch(e){}
        var out = base + (base.indexOf('?') >= 0 ? '&' : '?') + 'target=' + encodeURIComponent(abs);
        try {
          if (document.cookie) out += '&target_cookie=' + encodeURIComponent(document.cookie);
        } catch(e){}
        return out;
      } catch(e) {
        send({kind:'ws-relay-fallback', method:'WS', url:abs, status:-1, bytes:0, ms:0, resBody:'[relay disabled for this socket: '+e+']'});
        return abs;
      }
    }

    function WrappedWS(url, protocols){
      if (!(this instanceof WrappedWS)) return new WrappedWS(url, protocols);
      var originalUrl = normalizeWsUrl(url);

      var connectUrl = relayWsUrl(originalUrl);
      var usingRelay = connectUrl !== originalUrl;
      var ws = protocols ? new _WS(connectUrl, protocols) : new _WS(connectUrl);
      try { ws.binaryType = 'arraybuffer'; } catch(e){}
      var t0 = Date.now();
      send({kind:'ws-open', method:'WS', url:originalUrl, status:0, bytes:0, ms:0, resBody:(usingRelay ? '[connecting via relay] ' : '[connecting] ')+originalUrl+(usingRelay ? '\nRelay: '+connectUrl : '')});


      // Native listener path (bypasses on* setters entirely — always fires)
      _addEL.call(ws, 'open',    function(){ tapOpen(originalUrl, t0); });
      _addEL.call(ws, 'message', function(ev){ tapMessage(originalUrl, ev); });
      _addEL.call(ws, 'close',   function(ev){ tapClose(originalUrl, t0, ev); });
      _addEL.call(ws, 'error',   function(){ tapError(originalUrl, t0); });

      // Intercept on* setters so we also see handlers assigned that way
      ['onopen','onmessage','onclose','onerror'].forEach(function(prop){
        var current = null;
        var eventName = prop.slice(2);
        try {
          Object.defineProperty(ws, prop, {
            configurable: true,
            enumerable: true,
            get: function(){ return current; },
            set: function(fn){
              try { if (current) _remEL.call(ws, eventName, current); } catch(e){}
              current = (typeof fn === 'function') ? fn : null;
              try { if (current) _addEL.call(ws, eventName, current); } catch(e){}
            }, // native listener above already taps
          });
        } catch(e){}
      });

      // Intercept .send even if the app reassigns it
      var wrappedSend = function(d){
        try {
          wsDataToStr(d, function(n, preview){
            send({kind:'ws-msg-out', method:'WS>', url:originalUrl, status:0, bytes:n, ms:0, reqBody:preview});
          });
        } catch(e){
          send({kind:'ws-msg-out', method:'WS>', url:originalUrl, status:0, bytes:0, ms:0, reqBody:'[capture failed: '+e+']'});
        }
        return _sendProto.call(ws, d);
      };
      try {
        Object.defineProperty(ws, 'send', { value: wrappedSend, writable: false, configurable: false });
      } catch(e){ ws.send = wrappedSend; }

      return ws;
    }

    WrappedWS.prototype = _WS.prototype;
    WrappedWS.CONNECTING = _WS.CONNECTING; WrappedWS.OPEN = _WS.OPEN;
    WrappedWS.CLOSING = _WS.CLOSING; WrappedWS.CLOSED = _WS.CLOSED;
    WrappedWS.__netSnifferWrapped = true;
    try { Object.defineProperty(WrappedWS, Symbol.hasInstance, { value: function(x){ return x instanceof _WS; } }); } catch(e){}

    // Lock it in so page scripts cannot restore the original
    try {
      Object.defineProperty(window, 'WebSocket', {
        value: WrappedWS, writable: false, configurable: false,
      });
    } catch(e){ window.WebSocket = WrappedWS; }
  }

  // --- sendBeacon ---
  if (navigator.sendBeacon) {
    var _sb = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function(url, data){
      var n = 0; try { n = (typeof data === 'string') ? data.length : (data && data.size) || (data && data.byteLength) || 0; } catch(e){}
      send({kind:'beacon', method:'POST', url:url, status:0, bytes:n, ms:0, reqBody:bodyToStr(data)});
      return _sb(url, data);
    };
  }
})();
        """.trimIndent()
    }
}
