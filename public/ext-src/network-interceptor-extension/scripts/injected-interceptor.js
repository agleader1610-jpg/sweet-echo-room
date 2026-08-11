(function () {
  const TAG = '[NetInterceptor v2.2]';
  console.log(TAG, 'MAIN world active @', location.href);

  let activeRules = window.__NI_RULES__ || [];
  let isEnabled = window.__NI_ENABLED__ !== false;
  let wsRelay = null;

  // ---- config bridge ----
  function loadDataset() {
    try {
      const raw = document.documentElement && document.documentElement.dataset.niConfig;
      if (!raw) return;
      const cfg = JSON.parse(raw);
      activeRules = cfg.rules || [];
      isEnabled = cfg.enabled !== false;
      wsRelay = cfg.wsRelay || null;
      window.__NI_RULES__ = activeRules;
      window.__NI_ENABLED__ = isEnabled;
    } catch (_) {}
  }
  loadDataset();

  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data) return;
    if (e.data.type === 'NI_SET_RULES') {
      activeRules = e.data.rules || [];
      isEnabled = e.data.enabled !== false;
      wsRelay = e.data.wsRelay || null;
      window.__NI_RULES__ = activeRules;
      window.__NI_ENABLED__ = isEnabled;
    }
  });
  try { window.postMessage({ type: 'NI_GET_RULES' }, '*'); } catch {}

  function report(kind, data) {
    try {
      window.postMessage({
        type: 'NI_CAPTURED',
        payload: {
          id: 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8),
          kind,
          url: data.url,
          method: data.method || 'GET',
          status: data.status || 0,
          mime: data.mime || null,
          bytes: data.bytes || null,
          ms: data.ms || null,
          timestamp: new Date().toISOString(),
          requestData: data.requestData || null,
          responseData: data.responseData || null,
          reqHeaders: data.reqHeaders || null,
          resHeaders: data.resHeaders || null,
          isMocked: !!data.isMocked,
          direction: data.direction || null,
          frame: location.href
        }
      }, '*');
    } catch (e) { console.warn(TAG, 'report failed', e); }
  }

  function cleanUrl(u) { try { return (u || '').split('?')[0].split('#')[0]; } catch { return u || ''; } }

  function findRule(rawUrl, requestBody) {
    if (!isEnabled || !activeRules.length) return null;
    const cu = cleanUrl(rawUrl);
    return activeRules.find(r => {
      if (!r.enabled || !r.urlPattern) return false;
      const p = r.urlPattern.trim();
      const cp = cleanUrl(p);
      const t = r.matchType || 'smart';
      try {
        let urlMatches = false;
        if (t === 'exact') urlMatches = rawUrl === p || cu === cp;
        else if (t === 'regex') urlMatches = new RegExp(p, 'i').test(rawUrl);
        if (t === 'wildcard') {
          const rx = '^' + p.replace(/[-\/\\^$+?.()|[\]{}]/g, '\\$&').replace(/\\\*/g, '.*') + '$';
          urlMatches = new RegExp(rx, 'i').test(rawUrl) || new RegExp(rx, 'i').test(cu);
        }
        if (!urlMatches && t !== 'exact' && t !== 'regex' && t !== 'wildcard') {
          urlMatches = rawUrl.includes(p) || (cu && cp && (cu.includes(cp) || cp.includes(cu)));
        }
        if (!urlMatches) return false;
        return !r.requestBodyPattern || String(requestBody || '').includes(r.requestBodyPattern);
      } catch { return rawUrl.includes(p); }
    });
  }

  function mergeByAdId(rawText, mockResponse) {
    try {
      const live = JSON.parse(rawText);
      const patch = typeof mockResponse === 'string' ? JSON.parse(mockResponse) : mockResponse;
      if (!live || !patch || !Array.isArray(live.items) || !Array.isArray(patch.items)) return rawText;
      const edits = new Map(patch.items.filter(item => item && item.ad_id != null).map(item => [String(item.ad_id), item]));
      live.items = live.items.map(item => {
        const edited = item && edits.get(String(item.ad_id));
        return edited ? { ...item, ...edited, ad_id: item.ad_id } : item;
      });
      return JSON.stringify(live);
    } catch { return rawText; }
  }

  // Serialize request bodies for all common shapes
  function serializeBody(body) {
    if (body == null) return null;
    try {
      if (typeof body === 'string') return body.length > 8000 ? body.slice(0, 8000) + '…[truncated]' : body;
      if (body instanceof URLSearchParams) return body.toString();
      if (body instanceof FormData) {
        const o = {};
        for (const [k, v] of body.entries()) o[k] = typeof v === 'string' ? v : `[File:${v.name || 'blob'}:${v.size}b]`;
        return JSON.stringify(o);
      }
      if (body instanceof Blob) return `[Blob ${body.type || ''} ${body.size}b]`;
      if (body instanceof ArrayBuffer) return `[ArrayBuffer ${body.byteLength}b]`;
      if (typeof body === 'object') return JSON.stringify(body);
      return String(body);
    } catch { return '[unserializable body]'; }
  }

  function headersToObj(h) {
    try {
      if (!h) return null;
      if (h instanceof Headers) { const o = {}; h.forEach((v, k) => (o[k] = v)); return o; }
      if (Array.isArray(h)) return Object.fromEntries(h);
      if (typeof h === 'object') return { ...h };
    } catch {}
    return null;
  }

  // ---------- FETCH ----------
  if (!window.__NI_FETCH__) {
    window.__NI_FETCH__ = true;
    const oFetch = window.fetch;
    window.fetch = async function (resource, config) {
      const t0 = performance.now();
      let url = '';
      if (typeof resource === 'string') url = resource;
      else if (resource instanceof URL) url = resource.href;
      else if (resource && resource.url) url = resource.url;
      else url = String(resource);
      try { url = new URL(url, location.href).href; } catch {}
      const method = ((config && config.method) || (resource && resource.method) || 'GET').toUpperCase();
      const reqBody = serializeBody(config && config.body);
      const reqHeaders = headersToObj((config && config.headers) || (resource && resource.headers));

      const rule = findRule(url, reqBody);
      if (rule && rule.responseMode !== 'mergeByAdId') {
        let body = rule.mockResponse;
        if (typeof body === 'object' && body !== null) body = JSON.stringify(body);
        const resp = new Response(body, {
          status: rule.statusCode || 200,
          statusText: 'OK (Mocked)',
          headers: new Headers(rule.headers || { 'Content-Type': 'application/json' })
        });
        report('fetch', { url, method, status: rule.statusCode || 200, requestData: reqBody, responseData: body, reqHeaders, isMocked: true, ms: Math.round(performance.now() - t0) });
        return resp;
      }

      try {
        const r = await oFetch.apply(this, arguments);
        const ms = Math.round(performance.now() - t0);
        try {
          let response = r;
          if (rule && rule.responseMode === 'mergeByAdId') {
            const raw = await r.clone().text();
            const merged = mergeByAdId(raw, rule.mockResponse);
            response = new Response(merged, { status: r.status, statusText: r.statusText, headers: r.headers });
          }
          const c = response.clone();
          const ct = (c.headers.get('content-type') || '').toLowerCase();
          const resHeaders = headersToObj(c.headers);
          const isText = ct.includes('json') || ct.includes('text') || ct.includes('xml') || ct.includes('javascript') || ct.includes('form-urlencoded') || ct === '';
          if (isText) {
            c.text().then(t => report('fetch', {
              url, method, status: response.status, mime: ct, bytes: t ? t.length : 0, ms,
              requestData: reqBody, responseData: t && t.length > 65000 ? t.slice(0, 65000) + '…[truncated]' : t,
              reqHeaders, resHeaders, isMocked: false
            })).catch(() => report('fetch', { url, method, status: r.status, mime: ct, ms, requestData: reqBody, responseData: '[unreadable]', reqHeaders, resHeaders, isMocked: false }));
          } else {
            // Non-text: still record metadata; try to read as ArrayBuffer for size
            c.arrayBuffer().then(buf => report('fetch', {
              url, method, status: r.status, mime: ct, bytes: buf.byteLength, ms,
              requestData: reqBody, responseData: `[binary ${ct} ${buf.byteLength}b]`,
              reqHeaders, resHeaders, isMocked: false
            })).catch(() => report('fetch', { url, method, status: r.status, mime: ct, ms, requestData: reqBody, responseData: '[binary]', reqHeaders, resHeaders, isMocked: false }));
          }
          report('fetch', { url, method, status: response.status, ms, requestData: reqBody, responseData: null, reqHeaders, isMocked: false }); }
        return response;
      } catch (err) {
        report('fetch', { url, method, status: 0, requestData: reqBody, responseData: '[network error: ' + (err && err.message) + ']', reqHeaders, isMocked: false, ms: Math.round(performance.now() - t0) });
        throw err;
      }
    };
  }

  // ---------- XHR ----------
  if (!window.__NI_XHR__) {
    window.__NI_XHR__ = true;
    const oOpen = XMLHttpRequest.prototype.open;
    const oSend = XMLHttpRequest.prototype.send;
    const oSetHdr = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function (m, u) {
      this._niMethod = (m || 'GET').toUpperCase();
      try { this._niUrl = new URL(u, location.href).href; } catch { this._niUrl = u; }
      this._niReqHeaders = {};
      return oOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
      try { this._niReqHeaders[name] = String(value); } catch {}
      return oSetHdr.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      const xhr = this;
      const url = xhr._niUrl, method = xhr._niMethod || 'GET';
      const reqBody = serializeBody(body);
      const t0 = performance.now();
      const rule = findRule(url, reqBody);
      if (rule && rule.responseMode !== 'mergeByAdId') {
        let rb = rule.mockResponse;
        if (typeof rb === 'object' && rb !== null) rb = JSON.stringify(rb);
        Object.defineProperty(xhr, 'readyState', { configurable: true, writable: true, value: 4 });
        Object.defineProperty(xhr, 'status', { configurable: true, writable: true, value: rule.statusCode || 200 });
        Object.defineProperty(xhr, 'statusText', { configurable: true, writable: true, value: 'OK (Mocked)' });
        Object.defineProperty(xhr, 'responseText', { configurable: true, writable: true, value: rb });
        Object.defineProperty(xhr, 'response', { configurable: true, writable: true, value: rb });
        report('xhr', { url, method, status: rule.statusCode || 200, requestData: reqBody, responseData: rb, reqHeaders: xhr._niReqHeaders, isMocked: true, ms: 0 });
        setTimeout(() => {
          try { xhr.onreadystatechange && xhr.onreadystatechange(); } catch {}
          try { xhr.onload && xhr.onload(); } catch {}
          xhr.dispatchEvent(new Event('readystatechange'));
          xhr.dispatchEvent(new Event('load'));
          xhr.dispatchEvent(new Event('loadend'));
        }, 5);
        return;
      }
      if (rule && rule.responseMode === 'mergeByAdId') {
        const responseTextGetter = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
        const responseGetter = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');
        try {
          Object.defineProperty(xhr, 'responseText', {
            configurable: true,
            get() {
              const raw = responseTextGetter && responseTextGetter.get ? responseTextGetter.get.call(xhr) : '';
              return mergeByAdId(raw, rule.mockResponse);
            }
          });
          Object.defineProperty(xhr, 'response', {
            configurable: true,
            get() {
              const nativeValue = responseGetter && responseGetter.get ? responseGetter.get.call(xhr) : null;
              if ((xhr.responseType || '') === 'json') {
                const raw = JSON.stringify(nativeValue);
                return JSON.parse(mergeByAdId(raw, rule.mockResponse));
              }
              if (!xhr.responseType || xhr.responseType === 'text') {
                const raw = typeof nativeValue === 'string' ? nativeValue : '';
                return mergeByAdId(raw, rule.mockResponse);
              }
              return nativeValue;
            }
          });
        } catch {}
      }
      xhr.addEventListener('loadend', function () {
        try {
          const ms = Math.round(performance.now() - t0);
          const rt = (xhr.responseType || '').toLowerCase();
          let respText = null;
          try {
            if (!rt || rt === 'text') respText = xhr.responseText;
            else if (rt === 'json') respText = JSON.stringify(xhr.response);
            else if (rt === 'arraybuffer' && xhr.response) respText = `[arraybuffer ${xhr.response.byteLength}b]`;
            else if (rt === 'blob' && xhr.response) respText = `[blob ${xhr.response.type} ${xhr.response.size}b]`;
            else if (rt === 'document' && xhr.response) respText = new XMLSerializer().serializeToString(xhr.response);
            else respText = String(xhr.response);
          } catch { respText = '[unreadable]'; }
          if (respText && respText.length > 65000) respText = respText.slice(0, 65000) + '…[truncated]';

          // Parse response headers
          let resHeaders = null;
          try {
            const raw = xhr.getAllResponseHeaders();
            if (raw) {
              resHeaders = {};
              raw.trim().split(/[\r\n]+/).forEach(line => {
                const idx = line.indexOf(':');
                if (idx > 0) resHeaders[line.slice(0, idx).trim().toLowerCase()] = line.slice(idx + 1).trim();
              });
            }
          } catch {}
          const mime = resHeaders && resHeaders['content-type'] ? resHeaders['content-type'] : null;
          report('xhr', { url, method, status: xhr.status, mime, bytes: respText ? respText.length : 0, ms, requestData: reqBody, responseData: respText, reqHeaders: xhr._niReqHeaders, resHeaders, isMocked: false });
        } catch (e) { console.warn(TAG, 'xhr capture err', e); }
      });
      return oSend.apply(this, arguments);
    };
  }

  // ---------- WebSocket ----------
  if (!window.__NI_WS__) {
    window.__NI_WS__ = true;
    const OrigWS = window.WebSocket;
    function NIWebSocket(url, protocols) {
      let finalUrl = url;
      try {
        if (wsRelay && wsRelay.url) {
          const sep = wsRelay.url.includes('?') ? '&' : '?';
          const tok = wsRelay.token ? ('token=' + encodeURIComponent(wsRelay.token) + '&') : '';
          finalUrl = wsRelay.url + sep + tok + 'target=' + encodeURIComponent(url);
        }
      } catch {}
      const ws = protocols ? new OrigWS(finalUrl, protocols) : new OrigWS(finalUrl);
      report('ws', { url, method: 'WS-OPEN', status: 101, responseData: 'relay=' + (finalUrl !== url), direction: 'open' });
      const oSend = ws.send.bind(ws);
      ws.send = function (data) {
        try {
          const preview = typeof data === 'string' ? data : '[binary ' + (data.byteLength || data.size || '?') + 'b]';
          report('ws', { url, method: 'WS-SEND', status: 0, requestData: preview, direction: 'out' });
        } catch {}
        return oSend(data);
      };
      ws.addEventListener('message', (ev) => {
        try {
          const d = ev.data;
          const preview = typeof d === 'string' ? (d.length > 8000 ? d.slice(0, 8000) + '…' : d) : '[binary]';
          report('ws', { url, method: 'WS-RECV', status: 0, responseData: preview, direction: 'in' });
        } catch {}
      });
      ws.addEventListener('close', () => report('ws', { url, method: 'WS-CLOSE', status: 0, direction: 'close' }));
      return ws;
    }
    NIWebSocket.prototype = OrigWS.prototype;
    NIWebSocket.CONNECTING = 0; NIWebSocket.OPEN = 1; NIWebSocket.CLOSING = 2; NIWebSocket.CLOSED = 3;
    window.WebSocket = NIWebSocket;
  }

  // ---------- EventSource (SSE) ----------
  if (!window.__NI_SSE__ && window.EventSource) {
    window.__NI_SSE__ = true;
    const OrigES = window.EventSource;
    function NIEventSource(url, cfg) {
      const es = new OrigES(url, cfg);
      report('sse', { url, method: 'SSE-OPEN', status: 200, direction: 'open' });
      es.addEventListener('message', (ev) => {
        report('sse', { url, method: 'SSE', status: 200, responseData: ev.data, direction: 'in' });
      });
      return es;
    }
    NIEventSource.prototype = OrigES.prototype;
    window.EventSource = NIEventSource;
  }

  // ---------- sendBeacon ----------
  if (!window.__NI_BEACON__ && navigator.sendBeacon) {
    window.__NI_BEACON__ = true;
    const oBeacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (url, data) {
      try {
        const preview = serializeBody(data);
        report('beacon', { url, method: 'BEACON', status: 204, requestData: preview });
      } catch {}
      return oBeacon(url, data);
    };
  }
})();
