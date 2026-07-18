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
    fun autoBetReconcile(json: String) {
        AutoBetReconciler.handleJson(json)
    }

    @JavascriptInterface
    fun relayUrl(originalUrl: String, relayBaseUrl: String): String {
        return try {
            val abs = originalUrl.trim()
            val base = relayBaseUrl.trim()
            if (abs.isEmpty() || base.isEmpty()) return abs
            if (!abs.startsWith("ws://", true) && !abs.startsWith("wss://", true)) return abs
            if (!base.startsWith("ws://", true) && !base.startsWith("wss://", true)) return abs

            val cleanedBase = base.replace(Regex("([?&])target=[^&]*&?"), "\$1")
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
          try { if (window.__dkStash) window.__dkStash(url, reqHeaders, reqBody); } catch(e){}
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
      try { if (window.__dkStash) window.__dkStash((self.__ns&&self.__ns.u)||'', (self.__ns&&self.__ns.h)||{}, reqBody); } catch(e){}
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

  // ============================================================
  // DK WIN — direct API bet helper (based on ar-lottery01 signing)
  // Captures Authorization + base + gameCode from any /api/Lottery/ call
  // and exposes window.__dkPlaceBet(payload) for the Kotlin engine.
  // ============================================================
  if (!window.__dkInstalled) {
    window.__dkInstalled = true;

    window.__dkStash = function(url, reqHeaders, reqBody){
      try {
        if (!url || !/\/api\/Lottery\//i.test(url)) return;
        var auth = null;
        if (reqHeaders) {
          for (var k in reqHeaders) {
            if (String(k).toLowerCase() === 'authorization') { auth = reqHeaders[k]; break; }
          }
        }
        if (auth) window.__DK_TOKEN = auth;
        var m = String(url).match(/^(https?:\/\/[^\/]+\/api\/Lottery)/i);
        if (m) window.__DK_BASE = m[1];
        if (reqBody && typeof reqBody === 'string') {
          try { var jb = JSON.parse(reqBody); if (jb && jb.gameCode) window.__DK_GAME = jb.gameCode; } catch(e){}
        }
        try {
          var uu = new URL(String(url), location.href);
          var gc = uu.searchParams.get('gameCode');
          if (gc) window.__DK_GAME = gc;
        } catch(e){}
      } catch(e){}
    };

    // --- MD5 (Joseph Myers, public domain, compact) ---
    function md5(s){
      function md5cycle(x,k){var a=x[0],b=x[1],c=x[2],d=x[3];
        a=ff(a,b,c,d,k[0],7,-680876936);d=ff(d,a,b,c,k[1],12,-389564586);c=ff(c,d,a,b,k[2],17,606105819);b=ff(b,c,d,a,k[3],22,-1044525330);
        a=ff(a,b,c,d,k[4],7,-176418897);d=ff(d,a,b,c,k[5],12,1200080426);c=ff(c,d,a,b,k[6],17,-1473231341);b=ff(b,c,d,a,k[7],22,-45705983);
        a=ff(a,b,c,d,k[8],7,1770035416);d=ff(d,a,b,c,k[9],12,-1958414417);c=ff(c,d,a,b,k[10],17,-42063);b=ff(b,c,d,a,k[11],22,-1990404162);
        a=ff(a,b,c,d,k[12],7,1804603682);d=ff(d,a,b,c,k[13],12,-40341101);c=ff(c,d,a,b,k[14],17,-1502002290);b=ff(b,c,d,a,k[15],22,1236535329);
        a=gg(a,b,c,d,k[1],5,-165796510);d=gg(d,a,b,c,k[6],9,-1069501632);c=gg(c,d,a,b,k[11],14,643717713);b=gg(b,c,d,a,k[0],20,-373897302);
        a=gg(a,b,c,d,k[5],5,-701558691);d=gg(d,a,b,c,k[10],9,38016083);c=gg(c,d,a,b,k[15],14,-660478335);b=gg(b,c,d,a,k[4],20,-405537848);
        a=gg(a,b,c,d,k[9],5,568446438);d=gg(d,a,b,c,k[14],9,-1019803690);c=gg(c,d,a,b,k[3],14,-187363961);b=gg(b,c,d,a,k[8],20,1163531501);
        a=gg(a,b,c,d,k[13],5,-1444681467);d=gg(d,a,b,c,k[2],9,-51403784);c=gg(c,d,a,b,k[7],14,1735328473);b=gg(b,c,d,a,k[12],20,-1926607734);
        a=hh(a,b,c,d,k[5],4,-378558);d=hh(d,a,b,c,k[8],11,-2022574463);c=hh(c,d,a,b,k[11],16,1839030562);b=hh(b,c,d,a,k[14],23,-35309556);
        a=hh(a,b,c,d,k[1],4,-1530992060);d=hh(d,a,b,c,k[4],11,1272893353);c=hh(c,d,a,b,k[7],16,-155497632);b=hh(b,c,d,a,k[10],23,-1094730640);
        a=hh(a,b,c,d,k[13],4,681279174);d=hh(d,a,b,c,k[0],11,-358537222);c=hh(c,d,a,b,k[3],16,-722521979);b=hh(b,c,d,a,k[6],23,76029189);
        a=hh(a,b,c,d,k[9],4,-640364487);d=hh(d,a,b,c,k[12],11,-421815835);c=hh(c,d,a,b,k[15],16,530742520);b=hh(b,c,d,a,k[2],23,-995338651);
        a=ii(a,b,c,d,k[0],6,-198630844);d=ii(d,a,b,c,k[7],10,1126891415);c=ii(c,d,a,b,k[14],15,-1416354905);b=ii(b,c,d,a,k[5],21,-57434055);
        a=ii(a,b,c,d,k[12],6,1700485571);d=ii(d,a,b,c,k[3],10,-1894986606);c=ii(c,d,a,b,k[10],15,-1051523);b=ii(b,c,d,a,k[1],21,-2054922799);
        a=ii(a,b,c,d,k[8],6,1873313359);d=ii(d,a,b,c,k[15],10,-30611744);c=ii(c,d,a,b,k[6],15,-1560198380);b=ii(b,c,d,a,k[13],21,1309151649);
        a=ii(a,b,c,d,k[4],6,-145523070);d=ii(d,a,b,c,k[11],10,-1120210379);c=ii(c,d,a,b,k[2],15,718787259);b=ii(b,c,d,a,k[9],21,-343485551);
        x[0]=add32(a,x[0]);x[1]=add32(b,x[1]);x[2]=add32(c,x[2]);x[3]=add32(d,x[3]);}
      function cmn(q,a,b,x,s,t){a=add32(add32(a,q),add32(x,t));return add32((a<<s)|(a>>>32-s),b);}
      function ff(a,b,c,d,x,s,t){return cmn((b&c)|((~b)&d),a,b,x,s,t);}
      function gg(a,b,c,d,x,s,t){return cmn((b&d)|(c&(~d)),a,b,x,s,t);}
      function hh(a,b,c,d,x,s,t){return cmn(b^c^d,a,b,x,s,t);}
      function ii(a,b,c,d,x,s,t){return cmn(c^(b|(~d)),a,b,x,s,t);}
      function md51(s){var n=s.length,state=[1732584193,-271733879,-1732584194,271733878],i;
        for (i=64;i<=n;i+=64) md5cycle(state,md5blk(s.substring(i-64,i)));
        s=s.substring(i-64);var tail=[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];
        for (i=0;i<s.length;i++) tail[i>>2]|=s.charCodeAt(i)<<((i%4)<<3);
        tail[i>>2]|=0x80<<((i%4)<<3);
        if (i>55){md5cycle(state,tail);for(i=0;i<16;i++) tail[i]=0;}
        tail[14]=n*8;md5cycle(state,tail);return state;}
      function md5blk(s){var b=[],i;for(i=0;i<64;i+=4) b[i>>2]=s.charCodeAt(i)+(s.charCodeAt(i+1)<<8)+(s.charCodeAt(i+2)<<16)+(s.charCodeAt(i+3)<<24);return b;}
      function rhex(n){var s='',j;for(j=0;j<4;j++) s+='0123456789abcdef'.charAt((n>>(j*8+4))&0x0F)+'0123456789abcdef'.charAt((n>>(j*8))&0x0F);return s;}
      function hex(x){for(var i=0;i<x.length;i++) x[i]=rhex(x[i]);return x.join('');}
      function add32(a,b){return (a+b)&0xFFFFFFFF;}
      return hex(md51(unescape(encodeURIComponent(s))));
    }
    window.__dkMd5 = md5;

    function rand12(){var i=1e12,t=Math.floor(Math.random()*i);if(t<i/10&&t!==0)return rand12();return t;}
    function sign(params){
      var o={}; for (var k in params) o[k]=params[k];
      o.random = rand12();
      var keys = Object.keys(o).filter(function(k){var v=o[k];return !(v&&typeof v==='object');});
      keys.sort();
      var l={};
      keys.forEach(function(k){var v=o[k]; if(v!==null&&v!==''&&k!=='signature') l[k]=(v===0?0:v);});
      o.signature = md5(JSON.stringify(l)).toUpperCase();
      o.timestamp = Math.floor(Date.now()/1000);
      return o;
    }
    window.__dkSign = sign;

    // ---- Signed request helpers (reuse stashed token/base) ----
    function _dkQs(o){
      var out = [];
      Object.keys(o).sort().forEach(function(k){
        var v = o[k];
        if (v === null || v === undefined || v === '') return;
        out.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
      });
      return out.join('&');
    }
    window.__dkSignedGet = function(path, params){
      var base = window.__DK_BASE, token = window.__DK_TOKEN;
      if (!base || !token) return Promise.resolve({ok:false, err:'NO_TOKEN'});
      var signed = sign(Object.assign({language:'en'}, params||{}));
      var url = base + path + '?' + _dkQs(signed);
      return fetch(url, {method:'GET', headers:{'Authorization':token,'Accept':'application/json'}, credentials:'include'})
        .then(function(r){return r.text();}).then(function(t){
          try { return {ok:true, json: JSON.parse(t)}; } catch(e){ return {ok:false, err:'PARSE', raw:t}; }
        }).catch(function(e){ return {ok:false, err:String(e)}; });
    };

    // Aggregate snapshot the Android reconciler polls every ~2s.
    // Returns { ok, balance, currentIssue, myBets:[{issueNumber, betContent, state, winLoseAmount}] }
    window.__dkReconcile = function(gameCode){
      var gc = gameCode || window.__DK_GAME || 'WinGo_30S';
      var p1 = window.__dkSignedGet('/GetBalance', {});
      var p2 = window.__dkSignedGet('/GetRecordPage', {pageSize:10, pageNo:1, gameCode:gc});
      var p3 = window.__dkSignedGet('/GetGameInfo', {gameCode:gc});
      return Promise.all([p1,p2,p3]).then(function(arr){
        var errs = [];
        for (var ei=0; ei<arr.length; ei++) if (!arr[ei] || !arr[ei].ok) errs.push((arr[ei] && arr[ei].err) || ('ERR_'+ei));
        var out = {
          ok:true,
          ts: Date.now(),
          tokenReady: !!window.__DK_TOKEN,
          baseReady: !!window.__DK_BASE,
          gameCode: gc,
          myBets: [],
          lastError: errs.length ? errs.join('|') : '',
          lastBetAck: window.__dkLastBetAck || null
        };
        try { out.balance = arr[0] && arr[0].json && arr[0].json.data && arr[0].json.data.balance; } catch(e){}
        try {
          var list = arr[1] && arr[1].json && arr[1].json.data && arr[1].json.data.list;
          if (Array.isArray(list)) {
            out.myBets = list.slice(0, 10).map(function(x){
              return {
                issueNumber: String(x.issueNumber||''),
                betContent: String(x.betContent||''),
                state: x.state,           // 0 = pending, 1 = settled
                winLoseAmount: x.winLoseAmount,
                betMultiple: x.betMultiple,
                betAmount: x.amount,
                number: x.number,
              };
            });
          }
        } catch(e){}
        try {
          var gi = arr[2] && arr[2].json && arr[2].json.data;
          if (gi) {
            // GetGameInfo may return currentIssueNumber / issue depending on API version.
            out.currentIssue = String(gi.currentIssueNumber || gi.issueNumber || (gi.issue && gi.issue.issueNumber) || (gi.current && gi.current.issueNumber) || '');
            var rem = gi.remainingTime || gi.remainTime || gi.seconds || gi.countdown || (gi.issue && (gi.issue.remainingTime || gi.issue.remainTime));
            if (rem != null && rem !== '') out.remainingSeconds = parseInt(rem, 10);
            var end = gi.endTime || gi.endTimestamp || (gi.issue && gi.issue.endTime) || (gi.current && gi.current.endTime);
            if ((!out.remainingSeconds || isNaN(out.remainingSeconds)) && end) {
              var endMs = Number(end);
              if (endMs < 1000000000000) endMs = endMs * 1000;
              out.remainingSeconds = Math.max(0, Math.floor((endMs - Date.now()) / 1000));
            }
          }
        } catch(e){}
        return out;
      });
    };

    // payload: {gameCode, issueNumber, amount, betMultiple, betContent, language?}
    window.__dkBetSlots = window.__dkBetSlots || {};
    window.__dkPlaceBet = function(payload){
      try {
        var base = window.__DK_BASE, token = window.__DK_TOKEN;
        if (!base || !token) return {ok:false, stage:'NO_TOKEN', note:'open WinGo tab first so token is captured'};
        payload = payload || {};
        var issue = String(payload.issueNumber || '');
        if (!issue) return {ok:false, stage:'NO_ISSUE', note:'missing issueNumber'};
        var key = issue;
        var prev = window.__dkBetSlots[key];
        if (prev && prev.status === 'DONE') {
          return {ok:true, stage:'LOCKED', note:'already submitted for #' + issue.slice(-6)};
        }
        if (prev && prev.status === 'SUBMITTED' && Date.now() - (prev.at || 0) < 8000) {
          return {ok:false, stage:'WAIT_ACK', note:'previous request still waiting for #' + issue.slice(-6)};
        }

        var body = sign(Object.assign({ language: payload.language || 'en' }, payload));
        window.__dkBetSlots[key] = {status:'SUBMITTED', at:Date.now(), body:body};
        fetch(base + '/WinGoBet', {
          method:'POST',
          headers:{'Content-Type':'application/json','Authorization':token},
          body: JSON.stringify(body),
          credentials:'include'
        }).then(function(r){ return r.text(); }).then(function(t){
          var j = null; try { j = JSON.parse(t); } catch(e){}
          var code = j && j.code, msg = (j && (j.msg || j.message)) || '';
          var ok = code === 0 || code === 200 || (j && (j.success === true || j.status === true));
          if (!ok && /success|succeed|accepted|bet/i.test(String(msg))) ok = true;
          window.__dkBetSlots[key].status = ok ? 'DONE' : 'API_ERR';
            window.__dkBetSlots[key].result = {code:code, msg:msg, raw:t};
            window.__dkLastBetAck = {ok:ok, stage:(ok?'DONE':'API_ERR'), note:(ok?'API OK':'code='+code+' '+msg), issueNumber:issue};
        }).catch(function(e){
          window.__dkBetSlots[key].status = 'NET_ERR';
          window.__dkBetSlots[key].result = String(e);
            window.__dkLastBetAck = {ok:false, stage:'NET_ERR', note:String(e), issueNumber:issue};
        });
        return {ok:true, stage:'SUBMITTED', note:'request sent · '+(payload.betContent||'')+' x'+(payload.betMultiple||1)};
      } catch(e){ return {ok:false, stage:'EXC', note:String(e)}; }
    };
  }
})();
        """.trimIndent()
    }
}
