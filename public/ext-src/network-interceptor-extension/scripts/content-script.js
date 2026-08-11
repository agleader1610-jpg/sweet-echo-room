(function () {
  const domain = location.hostname || 'unknown';

  async function syncDataset() {
    try {
      const s = await chrome.storage.local.get(['mockRules', 'domainSettings', 'wsRelay']);
      const all = s.mockRules || [];
      const ds = s.domainSettings || {};
      const rules = all.filter(r => !r.domain || r.domain === domain || domain.includes(r.domain));
      const cfg = { rules, enabled: ds[domain] !== false, domain, wsRelay: s.wsRelay || null };
      if (document.documentElement) document.documentElement.dataset.niConfig = JSON.stringify(cfg);
      return cfg;
    } catch { return { rules: [], enabled: true, domain, wsRelay: null }; }
  }

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

  async function saveLog(payload) {
    try {
      payload.domain = domain;
      const s = await chrome.storage.local.get('capturedLogs');
      const map = s.capturedLogs || {};
      const arr = map[domain] || [];
      arr.unshift(payload);
      if (arr.length > 300) arr.length = 300;
      map[domain] = arr;
      await chrome.storage.local.set({ capturedLogs: map });
      chrome.runtime.sendMessage({ action: 'BADGE', domain, count: arr.length }).catch(() => {});
    } catch (e) {}
  }

  syncDataset().then(inject);
  if (document.readyState === 'loading') document.addEventListener('readystatechange', inject);
  else inject();

  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data) return;
    if (e.data.type === 'NI_GET_RULES') {
      syncDataset().then(cfg => window.postMessage({ type: 'NI_SET_RULES', rules: cfg.rules, enabled: cfg.enabled, wsRelay: cfg.wsRelay }, '*'));
    } else if (e.data.type === 'NI_CAPTURED') {
      saveLog(e.data.payload);
    }
  });

  chrome.storage.onChanged.addListener((changes, ns) => {
    if (ns !== 'local') return;
    if (changes.mockRules || changes.domainSettings || changes.wsRelay) {
      syncDataset().then(cfg => window.postMessage({ type: 'NI_SET_RULES', rules: cfg.rules, enabled: cfg.enabled, wsRelay: cfg.wsRelay }, '*'));
    }
  });

  // Receive deep-mode captures forwarded from background
  chrome.runtime.onMessage.addListener((msg) => {
    if (msg && msg.action === 'DEEP_CAPTURED' && msg.payload) saveLog(msg.payload);
  });
})();
