(function () {
  const TAG = '[NetInterceptor v2.0]';
  console.log(TAG, 'MAIN world active');

  let activeRules = window.__NI_RULES__ || [];
  let isEnabled = window.__NI_ENABLED__ !== false;
  let wsRelay = null; // { url, token }

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
  window.postMessage({ type: 'NI_GET_RULES' }, '*');

  function report(kind, data) {
    try {
      window.postMessage({
        type: 'NI_CAPTURED',
        payload: {
          id: 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8),
          kind, // fetch|xhr|ws|sse|beacon
          url: data.url,
          method: data.method || 'GET',
          status: data.status || 200,
          timestamp: new Date().toISOString(),
          requestData: data.requestData || null,
          responseData: data.responseData || null,
          isMocked: !!data.isMocked,
          direction: data.direction || null
        }
      }, '*');
    } catch (_) {}
  }

  function cleanUrl(u) { try { return (u || '').split('?')[0].split('#')[0]; } catch { return u || ''; } }

  function findRule(rawUrl) {
    if (!isEnabled || !activeRules.length) return null;
    const cu = cleanUrl(rawUrl);
    return activeRules.find(r => {
      if (!r.enabled || !r.urlPattern) return false;
      const p = r.urlPattern.trim();
      const cp = cleanUrl(p);
      const t = r.matchType || 'smart';
      try {
        if (t === 'exact') return rawUrl === p || cu === cp;
        if (t === 'regex') return new RegExp(p, 'i').test(rawUrl);
        if (t === 'wildcard') {
          const rx = '^' + p.replace(/[-\/\\^$+?.()|[\]{}]/g, '\\$&').replace(/\\\*/g, '.*') + '$';
          return new RegExp(rx, 'i').test(rawUrl) || new RegExp(rx, 'i').test(cu);
        }
        return rawUrl.includes(p) || (cu && cp && (cu.includes(cp) || cp.includes(cu)));
      } catch { return rawUrl.includes(p); }
    });
  }

  // ---------- FETCH ----------
  if (!window.__NI_FETCH__) {
    window.__NI_FETCH__ = true;
    const oFetch = window.fetch;
    window.fetch = async function (resource, config) {
      let url = '';
      if (typeof resource === 'string') url = resource;
      else if (resource instanceof URL) url = resource.href;
      else if (resource && resource.url) url = resource.url;
      else url = String(resource);
      try { url = new URL(url, location.href).href; } catch {}
      const method = ((config && config.method) || (resource && resource.method) || 'GET').toUpperCase();
      const reqBody = config && config.body ? (typeof config.body === 'string' ? config.body : '[Binary/FormData]') : null;

      const rule = findRule(url);
      if (rule) {
        let body = rule.mockResponse;
        if (typeof body === 'object' && body !== null) body = JSON.stringify(body);
        const resp = new Response(body, {
          status: rule.statusCode || 200,
          statusText: 'OK (Mocked)',
          headers: new Headers(rule.headers || { 'Content-Type': 'application/json' })
        });
        report('fetch', { url, method, status: rule.statusCode || 200, requestData: reqBody, responseData: body, isMocked: true });
        return resp;
      }

      try {
        const r = await oFetch.apply(this, arguments);
        try {
          const c = r.clone();
          const ct = (c.headers.get('content-type') || '').toLowerCase();
          if (ct.includes('json') || ct.includes('text') || ct.includes('xml') || ct.includes('javascript') || ct === '') {
            c.text().then(t => report('fetch', { url, method, status: r.status, requestData: reqBody, responseData: t, isMocked: false }))
                   .catch(() => report('fetch', { url, method, status: r.status, requestData: reqBody, responseData: '[unreadable]', isMocked: false }));
          } else {
            report('fetch', { url, method, status: r.status, requestData: reqBody, responseData: '[binary: ' + ct + ']', isMocked: false });
          }
        } catch { report('fetch', { url, method, status: r.status, requestData: reqBody, responseData: null, isMocked: false }); }
        return r;
      } catch (err) { throw err; }
    };
  }

  // ---------- XHR ----------
  if (!window.__NI_XHR__) {
    window.__NI_XHR__ = true;
    const oOpen = XMLHttpRequest.prototype.open;
    const oSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this._niMethod = (m || 'GET').toUpperCase();
      try { this._niUrl = new URL(u, location.href).href; } catch { this._niUrl = u; }
      return oOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      const xhr = this;
      const url = xhr._niUrl, method = xhr._niMethod || 'GET';
      const reqBody = typeof body === 'string' ? body : (body ? '[FormData/Binary]' : null);
      const rule = findRule(url);
      if (rule) {
        let rb = rule.mockResponse;
        if (typeof rb === 'object' && rb !== null) rb = JSON.stringify(rb);
        Object.defineProperty(xhr, 'readyState', { writable: true, value: 4 });
        Object.defineProperty(xhr, 'status', { writable: true, value: rule.statusCode || 200 });
        Object.defineProperty(xhr, 'statusText', { writable: true, value: 'OK (Mocked)' });
        Object.defineProperty(xhr, 'responseText', { writable: true, value: rb });
        Object.defineProperty(xhr, 'response', { writable: true, value: rb });
        report('xhr', { url, method, status: rule.statusCode || 200, requestData: reqBody, responseData: rb, isMocked: true });
        setTimeout(() => {
          try { xhr.onreadystatechange && xhr.onreadystatechange(); } catch {}
          try { xhr.onload && xhr.onload(); } catch {}
          xhr.dispatchEvent(new Event('readystatechange'));
          xhr.dispatchEvent(new Event('load'));
          xhr.dispatchEvent(new Event('loadend'));
        }, 5);
        return;
      }
      xhr.addEventListener('load', function () {
        try {
          report('xhr', { url, method, status: xhr.status, requestData: reqBody, responseData: xhr.responseText || '[non-text]', isMocked: false });
        } catch {}
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
          const preview = typeof d === 'string' ? d : '[binary]';
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
        const preview = typeof data === 'string' ? data : '[binary/form]';
        report('beacon', { url, method: 'BEACON', status: 204, requestData: preview });
      } catch {}
      return oBeacon(url, data);
    };
  }
})();
