// Background SW — Deep Capture via chrome.debugger (CDP)
const attached = new Map(); // tabId -> true
const tabDomain = new Map();

chrome.runtime.onInstalled.addListener(async () => {
  const s = await chrome.storage.local.get(['mockRules','domainSettings','capturedLogs','deepModeTabs','wsRelay']);
  if (!s.mockRules) {
    await chrome.storage.local.set({ mockRules: [] });
  } else {
    const migratedRules = s.mockRules.map(migrateTelegramRule);
    await chrome.storage.local.set({ mockRules: migratedRules });
  }
  if (!s.domainSettings) await chrome.storage.local.set({ domainSettings: {} });
  if (!s.capturedLogs) await chrome.storage.local.set({ capturedLogs: {} });
  if (!s.deepModeTabs) await chrome.storage.local.set({ deepModeTabs: {} });
});

function migrateTelegramRule(rule) {
  if (!rule || rule.responseMode || !String(rule.urlPattern || '').includes('ads.telegram.org/api')) return rule;
  const payload = rule.mockResponse;
  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.items)) return rule;
  return { ...rule, responseMode: 'mergeByAdId', requestBodyPattern: 'method=getAdsList' };
}

async function getRulesForDomain(domain) {
  const s = await chrome.storage.local.get(['mockRules','domainSettings']);
  const all = s.mockRules || [];
  const ds = s.domainSettings || {};
  if (ds[domain] === false) return [];
  return all.filter(r => r.enabled && (!r.domain || r.domain === domain || domain.includes(r.domain)));
}

function cleanUrl(u){ try { return (u||'').split('?')[0].split('#')[0]; } catch { return u||''; } }
function matchRule(rule, url, requestBody) {
  const p = (rule.urlPattern || '').trim(); if (!p) return false;
  const t = rule.matchType || 'smart';
  const cu = cleanUrl(url), cp = cleanUrl(p);
  try {
    let urlMatches = false;
    if (t === 'exact') urlMatches = url === p || cu === cp;
    else if (t === 'regex') urlMatches = new RegExp(p, 'i').test(url);
    if (t === 'wildcard') {
      const rx = '^' + p.replace(/[-\/\\^$+?.()|[\]{}]/g, '\\$&').replace(/\\\*/g, '.*') + '$';
      urlMatches = new RegExp(rx, 'i').test(url) || new RegExp(rx, 'i').test(cu);
    }
    if (!urlMatches && t !== 'exact' && t !== 'regex' && t !== 'wildcard') {
      urlMatches = url.includes(p) || (cu && cp && (cu.includes(cp) || cp.includes(cu)));
    }
    if (!urlMatches) return false;
    return !rule.requestBodyPattern || String(requestBody || '').includes(rule.requestBodyPattern);
  } catch { return url.includes(p); }
}

async function pushLog(domain, payload) {
  const s = await chrome.storage.local.get('capturedLogs');
  const map = s.capturedLogs || {};
  const arr = map[domain] || [];
  arr.unshift(payload);
  if (arr.length > 300) arr.length = 300;
  map[domain] = arr;
  await chrome.storage.local.set({ capturedLogs: map });
}

async function attachDeep(tabId) {
  if (attached.get(tabId)) return;
  try {
    await chrome.debugger.attach({ tabId }, '1.3');
    attached.set(tabId, true);
    await chrome.debugger.sendCommand({ tabId }, 'Network.enable', {});
    await chrome.debugger.sendCommand({ tabId }, 'Fetch.enable', {
      patterns: [{ requestStage: 'Request' }, { requestStage: 'Response' }]
    });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: '#DC2626' });
    console.log('[NI BG] deep attached', tabId);
  } catch (e) { console.warn('[NI BG] attach failed', e.message); }
}
async function detachDeep(tabId) {
  if (!attached.get(tabId)) return;
  try { await chrome.debugger.detach({ tabId }); } catch {}
  attached.delete(tabId);
}

chrome.debugger.onDetach.addListener((src) => { if (src.tabId) attached.delete(src.tabId); });

chrome.debugger.onEvent.addListener(async (source, method, params) => {
  const tabId = source.tabId; if (!tabId) return;
  const domain = tabDomain.get(tabId) || 'unknown';

  if (method === 'Fetch.requestPaused') {
    const { requestId, request, responseStatusCode, responseHeaders } = params;
    const url = request.url;
    const isResponseStage = typeof responseStatusCode === 'number';

    try {
      if (!isResponseStage) {
        // Request stage — check for mock
        const rules = await getRulesForDomain(domain);
        const rule = rules.find(r => matchRule(r, url, request.postData));
        if (rule && rule.responseMode !== 'mergeByAdId') {
          let body = rule.mockResponse;
          if (typeof body === 'object' && body !== null) body = JSON.stringify(body);
          const b64 = btoa(unescape(encodeURIComponent(body || '')));
          await chrome.debugger.sendCommand({ tabId }, 'Fetch.fulfillRequest', {
            requestId,
            responseCode: rule.statusCode || 200,
            responseHeaders: Object.entries(rule.headers || { 'Content-Type': 'application/json' }).map(([name,value]) => ({ name, value: String(value) })),
            body: b64
          });
          await pushLog(domain, {
            id: 'deep_'+Date.now()+'_'+Math.random().toString(36).slice(2,6),
            kind: 'deep', url, method: request.method, status: rule.statusCode || 200,
            timestamp: new Date().toISOString(), requestData: request.postData || null,
            responseData: body, isMocked: true, domain
          });
          return;
        }
        // No rule — continue to response stage
        await chrome.debugger.sendCommand({ tabId }, 'Fetch.continueRequest', { requestId });
        return;
      }

      // Response stage — capture body
      let bodyText = null;
      try {
        const r = await chrome.debugger.sendCommand({ tabId }, 'Fetch.getResponseBody', { requestId });
        if (r && r.body) bodyText = r.base64Encoded ? atob(r.body) : r.body;
      } catch {}
      await pushLog(domain, {
        id: 'deep_'+Date.now()+'_'+Math.random().toString(36).slice(2,6),
        kind: 'deep', url, method: request.method, status: responseStatusCode,
        timestamp: new Date().toISOString(), requestData: request.postData || null,
        responseData: bodyText, isMocked: false, domain
      });
      try { await chrome.debugger.sendCommand({ tabId }, 'Fetch.continueResponse', { requestId }); } catch {}
    } catch (e) {
      try { await chrome.debugger.sendCommand({ tabId }, 'Fetch.continueRequest', { requestId }); } catch {}
    }
  } else if (method === 'Network.webSocketFrameReceived' || method === 'Network.webSocketFrameSent') {
    const dir = method.endsWith('Received') ? 'in' : 'out';
    await pushLog(domain, {
      id: 'ws_'+Date.now()+'_'+Math.random().toString(36).slice(2,6),
      kind: 'ws', url: '[ws frame]', method: 'WS-'+dir.toUpperCase(), status: 0,
      timestamp: new Date().toISOString(),
      responseData: dir === 'in' ? (params.response && params.response.payloadData) : null,
      requestData: dir === 'out' ? (params.response && params.response.payloadData) : null,
      direction: dir, domain
    });
  }
});

// Track tab URL → domain
async function refreshTabDomain(tabId) {
  try {
    const t = await chrome.tabs.get(tabId);
    if (t && t.url) {
      try { tabDomain.set(tabId, new URL(t.url).hostname); } catch {}
    }
  } catch {}
}

chrome.tabs.onUpdated.addListener(async (tabId, info) => {
  if (info.url || info.status === 'loading') {
    await refreshTabDomain(tabId);
    const s = await chrome.storage.local.get('deepModeTabs');
    const dm = s.deepModeTabs || {};
    const domain = tabDomain.get(tabId);
    if (domain && dm[domain]) attachDeep(tabId);
  }
});
chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  await refreshTabDomain(tabId);
  const s = await chrome.storage.local.get(['deepModeTabs','capturedLogs']);
  const dm = s.deepModeTabs || {};
  const domain = tabDomain.get(tabId);
  if (domain && dm[domain] && !attached.get(tabId)) attachDeep(tabId);
  const arr = ((s.capturedLogs || {})[domain] || []);
  if (arr.length) {
    try { await chrome.action.setBadgeText({ tabId, text: String(arr.length) }); } catch {}
  }
});
chrome.tabs.onRemoved.addListener((tabId) => { attached.delete(tabId); tabDomain.delete(tabId); });

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    try {
      if (msg.action === 'BADGE' && sender.tab) {
        await chrome.action.setBadgeText({ tabId: sender.tab.id, text: String(msg.count || '') });
        await chrome.action.setBadgeBackgroundColor({ tabId: sender.tab.id, color: '#3B82F6' });
        sendResponse({ ok: true });
      } else if (msg.action === 'TOGGLE_DEEP') {
        const { domain, enabled, tabId } = msg;
        const s = await chrome.storage.local.get('deepModeTabs');
        const dm = s.deepModeTabs || {}; dm[domain] = !!enabled;
        await chrome.storage.local.set({ deepModeTabs: dm });
        if (enabled && tabId) await attachDeep(tabId);
        else if (!enabled && tabId) await detachDeep(tabId);
        sendResponse({ ok: true });
      } else if (msg.action === 'CLEAR_LOGS') {
        const s = await chrome.storage.local.get('capturedLogs');
        const map = s.capturedLogs || {};
        if (msg.domain) delete map[msg.domain]; else Object.keys(map).forEach(k => delete map[k]);
        await chrome.storage.local.set({ capturedLogs: map });
        sendResponse({ ok: true });
      }
    } catch (e) { sendResponse({ ok: false, error: e.message }); }
  })();
  return true;
});
