(function () {
  const domain = location.hostname || 'unknown';

  // Aggregate captures under the top-level page hostname when in an iframe,
  // so subdomain/iframe requests show up in the popup for the tab's domain.
  let rootDomain = domain;
  try {
    if (window.top && window.top !== window && window.top.location && window.top.location.hostname) {
      rootDomain = window.top.location.hostname;
    }
  } catch { /* cross-origin iframe — keep own domain */ }

  async function syncDataset() {
    try {
      const s = await chrome.storage.local.get(['mockRules', 'domainSettings', 'wsRelay', 'telegramAdPatches']);
      const all = s.mockRules || [];
      const ds = s.domainSettings || {};
      const rules = all.filter(r => !r.domain || r.domain === domain || domain.includes(r.domain) || r.domain === rootDomain);
      const cfg = { rules, enabled: ds[rootDomain] !== false && ds[domain] !== false, domain, rootDomain, wsRelay: s.wsRelay || null, adPatches: s.telegramAdPatches || {} };
      if (document.documentElement) document.documentElement.dataset.niConfig = JSON.stringify(cfg);
      return cfg;
    } catch { return { rules: [], enabled: true, domain, rootDomain, wsRelay: null, adPatches: {} }; }
  }

  // Fallback inject in case manifest MAIN-world content_script did not run
  function inject() {
    try {
      const tgt = document.head || document.documentElement || document.body;
      if (!tgt) { window.addEventListener('DOMContentLoaded', inject, { once: true }); return; }
      if (document.getElementById('ni-script-tag')) return;
      const s = document.createElement('script');
      s.id = 'ni-script-tag';
      s.src = chrome.runtime.getURL('scripts/injected-interceptor.js');
      tgt.appendChild(s);
    } catch (e) { console.error('[NI CS] inject error', e); }
  }

  // Telemetry — forward every capture to a configurable endpoint so the
  // developer/agent can inspect them server-side. Off unless URL is set.
  let telemetryCfg = { url: '', token: '', enabled: false };
  let telemetryQueue = [];
  let telemetryTimer = null;

  async function loadTelemetryCfg() {
    try {
      const s = await chrome.storage.local.get('telemetry');
      telemetryCfg = Object.assign({ url: '', token: '', enabled: false }, s.telemetry || {});
    } catch {}
  }
  loadTelemetryCfg();

  function scheduleFlush() {
    if (telemetryTimer) return;
    telemetryTimer = setTimeout(flushTelemetry, 1500);
  }

  async function flushTelemetry() {
    telemetryTimer = null;
    if (!telemetryCfg.enabled || !telemetryCfg.url || !telemetryQueue.length) return;
    const batch = telemetryQueue.splice(0, 100);
    try {
      await fetch(telemetryCfg.url, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          ...(telemetryCfg.token ? { 'x-ingest-token': telemetryCfg.token } : {})
        },
        body: JSON.stringify(batch),
        keepalive: true,
        mode: 'cors',
        credentials: 'omit'
      });
    } catch (e) {
      // put back at front so we retry once
      telemetryQueue = batch.concat(telemetryQueue).slice(0, 500);
    }
    if (telemetryQueue.length) scheduleFlush();
  }

  function queueTelemetry(payload) {
    if (!telemetryCfg.enabled || !telemetryCfg.url) return;
    try {
      telemetryQueue.push({
        device_id: 'ext:' + rootDomain,
        session_id: rootDomain + ':' + (window.__NI_SESSION__ || (window.__NI_SESSION__ = Date.now().toString(36))),
        source: 'ext-' + (payload.kind || 'req'),
        method: payload.method || null,
        url: payload.url,
        status: payload.status || null,
        mime: payload.mime || null,
        bytes: payload.bytes || null,
        ms: payload.ms || null,
        req_headers: payload.reqHeaders || null,
        res_headers: payload.resHeaders || null,
        req_body: payload.requestData || null,
        res_body: payload.responseData || null,
        captured_at: payload.timestamp || new Date().toISOString()
      });
      if (telemetryQueue.length > 500) telemetryQueue = telemetryQueue.slice(-500);
      scheduleFlush();
    } catch {}
  }

  // Harvest Telegram ads from any captured getAdsList / catalog response so the
  // popup can offer a full per-ad editor, independent of pagination.
  async function harvestTelegramAds(payload) {
    try {
      if (!payload || !payload.url || !String(payload.url).includes('ads.telegram.org')) return;
      if (!payload.responseData) return;
      const data = JSON.parse(payload.responseData);
      const found = [];
      (function walk(node) {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) { node.forEach(walk); return; }
        if (node.ad_id != null) found.push(node);
        Object.keys(node).forEach(k => { const v = node[k]; if (v && typeof v === 'object') walk(v); });
      })(data);
      if (!found.length) return;
      const s = await chrome.storage.local.get('telegramAdsCatalog');
      const map = s.telegramAdsCatalog || {};
      let changed = false;
      found.forEach(item => {
        const id = String(item.ad_id);
        const prev = map[id];
        if (!prev || JSON.stringify(prev) !== JSON.stringify(item)) { map[id] = item; changed = true; }
      });
      if (changed) await chrome.storage.local.set({ telegramAdsCatalog: map });
    } catch {}
  }

  async function saveLog(payload) {
    try {
      harvestTelegramAds(payload);
      payload.domain = rootDomain;
      payload.originDomain = domain;
      const s = await chrome.storage.local.get('capturedLogs');
      const map = s.capturedLogs || {};
      const arr = map[rootDomain] || [];
      arr.unshift(payload);
      if (arr.length > 500) arr.length = 500;
      map[rootDomain] = arr;
      await chrome.storage.local.set({ capturedLogs: map });
      chrome.runtime.sendMessage({ action: 'BADGE', domain: rootDomain, count: arr.length }).catch(() => {});
      queueTelemetry(payload);
    } catch (e) {}
  }

  syncDataset().then(inject);
  if (document.readyState === 'loading') document.addEventListener('readystatechange', inject);
  else inject();

  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data) return;
    if (e.data.type === 'NI_GET_RULES') {
      syncDataset().then(cfg => window.postMessage({ type: 'NI_SET_RULES', rules: cfg.rules, enabled: cfg.enabled, wsRelay: cfg.wsRelay, adPatches: cfg.adPatches }, '*'));
    } else if (e.data.type === 'NI_CAPTURED') {
      saveLog(e.data.payload);
    }
  });

  chrome.storage.onChanged.addListener((changes, ns) => {
    if (ns !== 'local') return;
    if (changes.mockRules || changes.domainSettings || changes.wsRelay || changes.telegramAdPatches) {
      syncDataset().then(cfg => window.postMessage({ type: 'NI_SET_RULES', rules: cfg.rules, enabled: cfg.enabled, wsRelay: cfg.wsRelay, adPatches: cfg.adPatches }, '*'));
    }
    if (changes.telemetry) {
      telemetryCfg = Object.assign({ url: '', token: '', enabled: false }, changes.telemetry.newValue || {});
    }
  });

  // Receive deep-mode captures forwarded from background
  chrome.runtime.onMessage.addListener((msg) => {
    if (msg && msg.action === 'DEEP_CAPTURED' && msg.payload) saveLog(msg.payload);
  });
})();
