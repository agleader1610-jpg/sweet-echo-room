document.addEventListener('DOMContentLoaded', async () => {
  let currentDomain = '';
  let activeTabId = null;
  let allLogsMap = {};
  let allRules = [];
  let domainSettings = {};
  let editingRuleId = null;
  let editingRequestBody = '';

  // DOM Elements
  const elCurrentDomain = document.getElementById('current-domain');
  const elDomainToggle = document.getElementById('domain-toggle');
  const elCapturedBadge = document.getElementById('captured-count-badge');
  const elMocksBadge = document.getElementById('mocks-count-badge');
  const elLogsContainer = document.getElementById('logs-container');
  const elRulesContainer = document.getElementById('rules-container');
  const elSearchLogs = document.getElementById('search-logs');
  const elRuleEditorPanel = document.getElementById('rule-editor-panel');
  
  // Form fields
  const elEditorTitle = document.getElementById('editor-title');
  const elRuleName = document.getElementById('rule-name');
  const elRuleUrl = document.getElementById('rule-url');
  const elRuleMatchType = document.getElementById('rule-match-type');
  const elRuleStatus = document.getElementById('rule-status');
  const elRuleTargetDomain = document.getElementById('rule-target-domain');
  const elRuleJson = document.getElementById('rule-json');
  const elJsonError = document.getElementById('json-error');

  // Helper to clean URL by stripping dynamic query string parameters (e.g. ?hash=..., ?_t=...)
  function cleanUrlForPattern(url) {
    if (!url) return '';
    try {
      return url.split('?')[0].split('#')[0];
    } catch (e) {
      return url;
    }
  }

  // Initialize Active Tab & Domain
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs && tabs[0] && tabs[0].url) {
      activeTabId = tabs[0].id;
      const url = new URL(tabs[0].url);
      currentDomain = url.hostname;
      elCurrentDomain.textContent = currentDomain;
    } else {
      elCurrentDomain.textContent = 'Global Context';
    }
  } catch (e) {
    elCurrentDomain.textContent = 'All Domains';
  }

  // Load Data from Chrome Storage
  async function loadData() {
    try {
      const storage = await chrome.storage.local.get(['capturedLogs', 'mockRules', 'domainSettings']);
      allLogsMap = storage.capturedLogs || {};
      allRules = storage.mockRules || [];
      domainSettings = storage.domainSettings || {};

      // Domain Toggle state
      if (currentDomain) {
        elDomainToggle.checked = domainSettings[currentDomain] !== false;
      }

      renderLogs();
      renderRules();
    } catch (e) {
      console.error('[Popup] Failed to load storage:', e);
    }
  }

  // Real-time Storage Listener to auto-update Popup UI when new API calls are captured
  chrome.storage.onChanged.addListener((changes, namespace) => {
    if (namespace === 'local') {
      if (changes.capturedLogs) {
        allLogsMap = changes.capturedLogs.newValue || {};
        renderLogs();
      }
      if (changes.mockRules) {
        allRules = changes.mockRules.newValue || [];
        renderRules();
      }
      if (changes.domainSettings && currentDomain) {
        domainSettings = changes.domainSettings.newValue || {};
        elDomainToggle.checked = domainSettings[currentDomain] !== false;
      }
    }
  });

  // Toast notification helper
  function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 2500);
  }

  // -------------------------------------------------------------
  // TAB NAVIGATION
  // -------------------------------------------------------------
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

      btn.classList.add('active');
      const targetTabId = btn.getAttribute('data-tab');
      document.getElementById(targetTabId).classList.add('active');
    });
  });

  // Domain Enable/Disable Toggle
  elDomainToggle.addEventListener('change', async () => {
    if (!currentDomain) return;
    domainSettings[currentDomain] = elDomainToggle.checked;
    await chrome.storage.local.set({ domainSettings });
    showToast(elDomainToggle.checked ? `Enabled for ${currentDomain}` : `Disabled for ${currentDomain}`);
  });

  // -------------------------------------------------------------
  // TAB 1: CAPTURED REQUESTS RENDER
  // -------------------------------------------------------------
  function renderLogs() {
    const domainLogs = currentDomain ? (allLogsMap[currentDomain] || []) : [];
    const query = elSearchLogs.value.trim().toLowerCase();

    const filteredLogs = domainLogs.filter(log => {
      if (!query) return true;
      return (log.url && log.url.toLowerCase().includes(query)) ||
             (log.responseData && log.responseData.toLowerCase().includes(query)) ||
             (log.requestData && log.requestData.toLowerCase().includes(query));
    });

    elCapturedBadge.textContent = String(filteredLogs.length);

    if (!filteredLogs.length) {
      elLogsContainer.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">🌐</div>
          <p class="empty-title">No API requests captured yet</p>
          <p class="empty-sub">Reload or interact with ${currentDomain || 'the page'} to capture network responses.</p>
        </div>
      `;
      return;
    }

    elLogsContainer.innerHTML = '';
    filteredLogs.forEach(log => {
      const card = document.createElement('div');
      card.className = 'log-card';

      const isStatusSuccess = log.status >= 200 && log.status < 300;
      const statusClass = log.isMocked ? 'mocked' : (isStatusSuccess ? 'success' : 'error');
      const statusText = log.isMocked ? 'MOCKED (200)' : log.status;

      let formattedJson = '';
      if (log.responseData) {
        try {
          const parsed = JSON.parse(log.responseData);
          formattedJson = JSON.stringify(parsed, null, 2);
        } catch (e) {
          formattedJson = log.responseData;
        }
      }

        let catalogMeta = '';
        if (log.kind === 'catalog' && formattedJson) {
          try {
            const catalog = JSON.parse(log.responseData);
            catalogMeta = `<div class="req-payload-snippet">FULL AD CATALOG: ${Number(catalog._ni_total_ads || catalog.items?.length || 0)} ads from ${Number(catalog._ni_pages || 1)} pages</div>`;
          } catch {}
        }

        card.innerHTML = `
        <div class="log-header">
          <div class="log-meta">
            <span class="method-tag ${log.method}">${log.method}</span>
            <span class="log-url" title="${log.url}">${log.url}</span>
          </div>
          <span class="status-pill ${statusClass}">${statusText}</span>
        </div>
          ${catalogMeta || (log.requestData ? `<div class="req-payload-snippet">Payload: ${escapeHtml(log.requestData.substr(0, 100))}</div>` : '')}
        <div class="log-actions">
          <button class="btn btn-secondary btn-sm btn-edit-mock">✏️ Edit & Mock</button>
          <button class="btn btn-secondary btn-sm btn-toggle-preview">${formattedJson ? '👁️ Toggle Preview' : 'No Data'}</button>
        </div>
        ${formattedJson ? `<pre class="json-preview hidden">${escapeHtml(formattedJson)}</pre>` : ''}
      `;

      // Toggle Preview
      const previewBtn = card.querySelector('.btn-toggle-preview');
      const previewEl = card.querySelector('.json-preview');
      if (previewBtn && previewEl) {
        previewBtn.addEventListener('click', () => {
          previewEl.classList.toggle('hidden');
        });
      }

      // Edit & Mock Button -> Switches to Tab 2 and fills Editor
      const editBtn = card.querySelector('.btn-edit-mock');
      editBtn.addEventListener('click', () => {
        openEditorForCapturedLog(log);
      });

      elLogsContainer.appendChild(card);
    });
  }

  elSearchLogs.addEventListener('input', renderLogs);

  // Clear Logs for current domain
  document.getElementById('btn-clear-logs').addEventListener('click', async () => {
    if (currentDomain) {
      delete allLogsMap[currentDomain];
      await chrome.storage.local.set({ capturedLogs: allLogsMap });
      if (activeTabId) {
        await chrome.action.setBadgeText({ tabId: activeTabId, text: '' });
      }
      renderLogs();
      showToast('Logs cleared for ' + currentDomain);
    }
  });

  // -------------------------------------------------------------
  // TAB 2: MOCK RULES & EDITOR
  // -------------------------------------------------------------
  function renderRules() {
    const domainRules = allRules.filter(r => !r.domain || r.domain === currentDomain || currentDomain.includes(r.domain));
    elMocksBadge.textContent = String(domainRules.length);

    if (!domainRules.length) {
      elRulesContainer.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📝</div>
          <p class="empty-title">No active mock rules for this domain</p>
          <p class="empty-sub">Capture APIs or click "+ New Mock Rule" to create one.</p>
        </div>
      `;
      return;
    }

    elRulesContainer.innerHTML = '';
    domainRules.forEach(rule => {
      const card = document.createElement('div');
      card.className = 'rule-card';
      card.innerHTML = `
        <div class="rule-info">
          <span class="rule-title">${escapeHtml(rule.name || 'Untitled Rule')}</span>
          <span class="rule-pattern">${escapeHtml(rule.urlPattern)}</span>
        </div>
        <div class="rule-actions">
          <label class="switch-toggle" title="Enable/Disable Rule">
            <input type="checkbox" class="rule-toggle" ${rule.enabled ? 'checked' : ''}>
            <span class="slider round"></span>
          </label>
          <button class="btn-icon btn-edit-rule" title="Edit Rule">✏️</button>
          <button class="btn-icon btn-delete-rule" title="Delete Rule">🗑️</button>
        </div>
      `;

      // Toggle rule enabled
      card.querySelector('.rule-toggle').addEventListener('change', async (e) => {
        rule.enabled = e.target.checked;
        await chrome.storage.local.set({ mockRules: allRules });
        showToast(rule.enabled ? 'Rule Enabled' : 'Rule Disabled');
      });

      // Edit Rule
      card.querySelector('.btn-edit-rule').addEventListener('click', () => {
        openEditorWithRule(rule);
      });

      // Delete Rule
      card.querySelector('.btn-delete-rule').addEventListener('click', async () => {
        allRules = allRules.filter(r => r.id !== rule.id);
        await chrome.storage.local.set({ mockRules: allRules });
        renderRules();
        showToast('Rule Deleted');
      });

      elRulesContainer.appendChild(card);
    });
  }

  // Open Editor Pre-filled from Captured Log
  function openEditorForCapturedLog(log) {
    const cleanedUrl = cleanUrlForPattern(log.url);

    // Check if an existing rule already matches this URL pattern to update it instead of creating conflicting duplicate rules
    const isTelegramAdsList = cleanedUrl.includes('ads.telegram.org/api') && String(log.requestData || '').includes('method=getAdsList');
    const existingRule = allRules.find(r =>
      (r.urlPattern === cleanedUrl || r.urlPattern === log.url) &&
      (!isTelegramAdsList || r.responseMode === 'mergeByAdId')
    );
    if (existingRule) {
      if (isTelegramAdsList && existingRule.responseMode === 'mergeByAdId') {
        try {
          const liveResponse = JSON.parse(log.responseData || '{}');
          const savedResponse = typeof existingRule.mockResponse === 'string'
            ? JSON.parse(existingRule.mockResponse)
            : existingRule.mockResponse;
          if (Array.isArray(liveResponse.items) && savedResponse && Array.isArray(savedResponse.items)) {
            const savedById = new Map(savedResponse.items
              .filter(item => item && item.ad_id != null)
              .map(item => [String(item.ad_id), item]));
            const currentIds = new Set(liveResponse.items
              .filter(item => item && item.ad_id != null)
              .map(item => String(item.ad_id)));
            const currentPage = liveResponse.items.map(item => {
              const saved = item && savedById.get(String(item.ad_id));
              return saved ? { ...item, ...saved, ad_id: item.ad_id } : item;
            });
            const otherSavedPages = savedResponse.items.filter(item =>
              item && item.ad_id != null && !currentIds.has(String(item.ad_id))
            );
            openEditorWithRule({
              ...existingRule,
              mockResponse: { ...liveResponse, items: [...currentPage, ...otherSavedPages] }
            });
            return;
          }
        } catch {}
      }
      openEditorWithRule(existingRule);
      return;
    }

    editingRuleId = null;
    editingRequestBody = isTelegramAdsList ? 'method=getAdsList' : '';
    elEditorTitle.textContent = '✏️ Create Persistent Mock Rule';
    elRuleName.value = log.kind === 'catalog' ? 'Telegram Ads — Full Catalog' : 'Mock for ' + getUrlPathname(log.url);
    elRuleUrl.value = cleanedUrl;
    elRuleMatchType.value = 'smart';
    elRuleStatus.value = '200';
    elRuleTargetDomain.value = currentDomain;

    let jsonStr = log.responseData || '{}';
    try {
      jsonStr = JSON.stringify(JSON.parse(jsonStr), null, 2);
    } catch (e) {}
    elRuleJson.value = jsonStr;

    // Switch tab to Mocks tab
    document.querySelector('[data-tab="tab-mocks"]').click();
    elRuleEditorPanel.classList.remove('hidden');
    elRuleJson.focus();
  }

  function openEditorWithRule(rule) {
    editingRuleId = rule.id;
    editingRequestBody = rule.requestBodyPattern || '';
    elEditorTitle.textContent = '✏️ Edit Mock Rule';
    elRuleName.value = rule.name || '';
    elRuleUrl.value = rule.urlPattern || '';
    elRuleMatchType.value = rule.matchType || 'smart';
    elRuleStatus.value = rule.statusCode || 200;
    elRuleTargetDomain.value = rule.domain || '';
    
    let jsonStr = typeof rule.mockResponse === 'object' ? JSON.stringify(rule.mockResponse, null, 2) : rule.mockResponse;
    elRuleJson.value = jsonStr;

    document.querySelector('[data-tab="tab-mocks"]').click();
    elRuleEditorPanel.classList.remove('hidden');
  }

  // New Rule Button
  document.getElementById('btn-new-rule').addEventListener('click', () => {
    editingRuleId = null;
    editingRequestBody = '';
    elEditorTitle.textContent = '➕ Create New Mock Rule';
    elRuleName.value = '';
    elRuleUrl.value = '';
    elRuleMatchType.value = 'smart';
    elRuleStatus.value = '200';
    elRuleTargetDomain.value = currentDomain;
    elRuleJson.value = '{\n  "ok": true,\n  "items": []\n}';

    elRuleEditorPanel.classList.remove('hidden');
  });

  document.getElementById('btn-close-editor').addEventListener('click', () => {
    elRuleEditorPanel.classList.add('hidden');
  });
  document.getElementById('btn-cancel-rule').addEventListener('click', () => {
    elRuleEditorPanel.classList.add('hidden');
  });

  // Telegram Ads Full Multi-Ad Sample Preset Handler (Matching exact Telegram Ads campaigns)
  const btnTelegramPreset = document.getElementById('btn-preset-telegram');
  if (btnTelegramPreset) {
    btnTelegramPreset.addEventListener('click', () => {
      const telegramMultiSample = {
        "ok": true,
        "items": [
          { "ad_id": 923, "title": "ASD", "views": 50000, "opened": 0, "clicks": 22700, "actions": 3500, "status": "active" },
          { "ad_id": 924, "title": "SDVCS", "views": 45000, "opened": 0, "clicks": 29500, "actions": 4200, "status": "active" },
          { "ad_id": 925, "title": "ZV", "views": 38000, "opened": 0, "clicks": 18200, "actions": 2100, "status": "active" },
          { "ad_id": 926, "title": "WSSAWD", "views": 31000, "opened": 0, "clicks": 15400, "actions": 1900, "status": "active" },
          { "ad_id": 927, "title": "sadz", "views": 28000, "opened": 0, "clicks": 12300, "actions": 1500, "status": "active" },
          { "ad_id": 928, "title": "sdf", "views": 25000, "opened": 0, "clicks": 11000, "actions": 1200, "status": "active" },
          { "ad_id": 929, "title": "Wjgw", "views": 85000, "opened": 0, "clicks": 42000, "actions": 9500, "status": "active" },
          { "ad_id": 930, "title": "Jeeg", "views": 96000, "opened": 0, "clicks": 51000, "actions": 12800, "status": "active" }
        ]
      };
      elRuleJson.value = JSON.stringify(telegramMultiSample, null, 2);
      showToast('Telegram Ads Multi-Campaign Preset Loaded 📋');
    });
  }

  // Format JSON button
  document.getElementById('btn-format-json').addEventListener('click', () => {
    try {
      const parsed = JSON.parse(elRuleJson.value);
      elRuleJson.value = JSON.stringify(parsed, null, 2);
      elJsonError.classList.add('hidden');
      showToast('JSON Formatted ✨');
    } catch (e) {
      elJsonError.classList.remove('hidden');
      elJsonError.textContent = '⚠️ Invalid JSON Syntax: ' + e.message;
    }
  });

  // Save Rule
  document.getElementById('btn-save-rule').addEventListener('click', async () => {
    const name = elRuleName.value.trim() || 'Custom Rule';
    const urlPattern = elRuleUrl.value.trim();
    const matchType = elRuleMatchType.value;
    const statusCode = parseInt(elRuleStatus.value, 10) || 200;
    const domain = elRuleTargetDomain.value.trim() || currentDomain;
    const rawJson = elRuleJson.value.trim();

    if (!urlPattern) {
      alert('Please provide a target API URL or pattern.');
      return;
    }

    let parsedMock = rawJson;
    try {
      parsedMock = JSON.parse(rawJson);
      elJsonError.classList.add('hidden');
    } catch (e) {
      elJsonError.classList.remove('hidden');
      elJsonError.textContent = '⚠️ Invalid JSON Syntax: ' + e.message;
      return;
    }
    const mergeByAdId = urlPattern.includes('ads.telegram.org/api') &&
      editingRequestBody.includes('method=getAdsList') &&
      parsedMock && Array.isArray(parsedMock.items);

    if (editingRuleId) {
      const idx = allRules.findIndex(r => r.id === editingRuleId);
      if (idx !== -1) {
        allRules[idx] = {
          ...allRules[idx],
          name, urlPattern, matchType, statusCode, domain, mockResponse: parsedMock, enabled: true,
          requestBodyPattern: editingRequestBody || undefined,
          responseMode: mergeByAdId ? 'mergeByAdId' : undefined
        };
      }
    } else {
      // Overwrite any existing rule matching same urlPattern for this domain to avoid shadow conflicts
      const existingIdx = allRules.findIndex(r => (r.domain === domain || !r.domain) && r.urlPattern === urlPattern);
      if (existingIdx !== -1) {
        allRules[existingIdx] = {
          ...allRules[existingIdx],
          name, urlPattern, matchType, statusCode, domain, mockResponse: parsedMock, enabled: true,
          requestBodyPattern: editingRequestBody || undefined,
          responseMode: mergeByAdId ? 'mergeByAdId' : undefined
        };
      } else {
        allRules.push({
          id: 'rule_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
          name,
          urlPattern,
          matchType,
          statusCode,
          domain,
          mockResponse: parsedMock,
          requestBodyPattern: editingRequestBody || undefined,
          responseMode: mergeByAdId ? 'mergeByAdId' : undefined,
          enabled: true
        });
      }
    }

    await chrome.storage.local.set({ mockRules: allRules });
    elRuleEditorPanel.classList.add('hidden');
    renderRules();
    showToast('Mock Rule Saved & Applied Live 🚀');
  });

  // -------------------------------------------------------------
  // TAB 3: BACKUP & DIAGNOSTICS
  // -------------------------------------------------------------
  document.getElementById('btn-export-rules').addEventListener('click', () => {
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(allRules, null, 2));
    const downloadAnchor = document.createElement('a');
    downloadAnchor.setAttribute("href", dataStr);
    downloadAnchor.setAttribute("download", `network_mock_rules_${Date.now()}.json`);
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
    showToast('Mock Rules Exported 📥');
  });

  // Export Debug Diagnostics Log
  const btnExportDiagnostics = document.getElementById('btn-export-diagnostics');
  if (btnExportDiagnostics) {
    btnExportDiagnostics.addEventListener('click', async () => {
      const storage = await chrome.storage.local.get(null);
      const diagnosticsReport = {
        extensionVersion: '2.3.0',
        generatedAt: new Date().toISOString(),
        currentDomain: currentDomain,
        activeTabId: activeTabId,
        mockRulesCount: (storage.mockRules || []).length,
        mockRules: storage.mockRules || [],
        domainSettings: storage.domainSettings || {},
        capturedLogsSummary: Object.keys(storage.capturedLogs || {}).map(dom => ({
          domain: dom,
          count: (storage.capturedLogs[dom] || []).length,
          latestLogs: (storage.capturedLogs[dom] || []).slice(0, 5)
        })),
        fullCapturedLogs: storage.capturedLogs || {}
      };

      const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(diagnosticsReport, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute("href", dataStr);
      downloadAnchor.setAttribute("download", `extension_debug_diagnostics_v1.4.0_${Date.now()}.json`);
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
      showToast('Debug Diagnostics Log Downloaded 🛠️');
    });
  }

  document.getElementById('file-import-rules').addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (event) => {
      try {
        const imported = JSON.parse(event.target.result);
        if (Array.isArray(imported)) {
          allRules = imported;
          await chrome.storage.local.set({ mockRules: allRules });
          renderRules();
          showToast('Mock Rules Imported Successfully 📤');
        } else {
          alert('Invalid format: File should contain an array of rules.');
        }
      } catch (err) {
        alert('Error parsing JSON file: ' + err.message);
      }
    };
    reader.readAsText(file);
  });

  document.getElementById('btn-clear-all-data').addEventListener('click', async () => {
    if (confirm('Are you sure you want to clear all captured logs and rules?')) {
      await chrome.storage.local.set({ capturedLogs: {}, mockRules: [] });
      allLogsMap = {};
      allRules = [];
      renderLogs();
      renderRules();
      showToast('All storage data cleared!');
    }
  });

  // Helper utils
  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, "&amp;")
              .replace(/</g, "&lt;")
              .replace(/>/g, "&gt;")
              .replace(/"/g, "&quot;")
              .replace(/'/g, "&#039;");
  }

  function getUrlPathname(fullUrl) {
    try {
      const u = new URL(fullUrl);
      return u.pathname;
    } catch (e) {
      return fullUrl;
    }
  }

  // Initial load
  await loadData();
});

// ===== v2.0 PRO extensions: Deep Mode + WS Relay =====
(async function () {
  try {
    const deepToggle = document.getElementById('deep-mode-toggle');
    const deepLabel = document.getElementById('deep-domain-label');
    const wsUrl = document.getElementById('ws-relay-url');
    const wsTok = document.getElementById('ws-relay-token');
    const btnSaveRelay = document.getElementById('btn-save-relay');
    const btnClearRelay = document.getElementById('btn-clear-relay');
    if (!deepToggle) return;

    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    const tab = tabs && tabs[0];
    let domain = '';
    try { domain = tab && tab.url ? new URL(tab.url).hostname : ''; } catch {}
    if (deepLabel) deepLabel.textContent = domain || '(no domain)';

    const s = await chrome.storage.local.get(['deepModeTabs', 'wsRelay']);
    const dm = s.deepModeTabs || {};
    deepToggle.checked = !!(domain && dm[domain]);
    const relay = s.wsRelay || {};
    if (wsUrl) wsUrl.value = relay.url || '';
    if (wsTok) wsTok.value = relay.token || '';

    deepToggle.addEventListener('change', async () => {
      if (!domain) return;
      await chrome.runtime.sendMessage({ action: 'TOGGLE_DEEP', domain, tabId: tab.id, enabled: deepToggle.checked });
      const t = document.getElementById('toast');
      if (t) { t.textContent = deepToggle.checked ? 'Deep Mode ON — reload page' : 'Deep Mode OFF'; t.classList.remove('hidden'); setTimeout(()=>t.classList.add('hidden'), 2200); }
    });

    btnSaveRelay && btnSaveRelay.addEventListener('click', async () => {
      const url = (wsUrl.value || '').trim();
      const token = (wsTok.value || '').trim();
      if (!url) { alert('Enter relay URL'); return; }
      await chrome.storage.local.set({ wsRelay: { url, token } });
      const t = document.getElementById('toast'); if (t) { t.textContent = '✅ Relay saved — reload page'; t.classList.remove('hidden'); setTimeout(()=>t.classList.add('hidden'), 2200); }
    });
    btnClearRelay && btnClearRelay.addEventListener('click', async () => {
      await chrome.storage.local.remove('wsRelay');
      wsUrl.value = ''; wsTok.value = '';
      const t = document.getElementById('toast'); if (t) { t.textContent = '🚫 Relay disabled'; t.classList.remove('hidden'); setTimeout(()=>t.classList.add('hidden'), 2200); }
    });
  } catch (e) { console.error('[popup v2] init error', e); }
})();

// ===== v2.1 Live Telemetry Forwarding =====
(async function () {
  try {
    const DEFAULT_URL = 'https://apiapk.eip.lat/api/public/captures-ingest';
    const DEFAULT_TOKEN = 'ns-auto-ingest-4d184ca9-2578-4035-8b23-aab3a3aa6222';
    const elToggle = document.getElementById('telemetry-toggle');
    const elUrl = document.getElementById('telemetry-url');
    const elTok = document.getElementById('telemetry-token');
    const btnSave = document.getElementById('btn-save-telemetry');
    const btnTest = document.getElementById('btn-test-telemetry');
    if (!elToggle) return;

    function toast(msg) {
      const t = document.getElementById('toast');
      if (!t) return;
      t.textContent = msg;
      t.classList.remove('hidden');
      setTimeout(() => t.classList.add('hidden'), 2500);
    }

    const s = await chrome.storage.local.get('telemetry');
    const cfg = s.telemetry || {};
    elToggle.checked = !!cfg.enabled;
    elUrl.value = cfg.url || DEFAULT_URL;
    elTok.value = cfg.token || DEFAULT_TOKEN;

    btnSave.addEventListener('click', async () => {
      const url = (elUrl.value || '').trim();
      const token = (elTok.value || '').trim();
      const enabled = elToggle.checked;
      if (enabled && !url) { alert('Enter telemetry URL first.'); return; }
      await chrome.storage.local.set({ telemetry: { url, token, enabled } });
      toast(enabled ? '✅ Telemetry ON — reload target page' : '💾 Telemetry saved (OFF)');
    });

    btnTest.addEventListener('click', async () => {
      const url = (elUrl.value || '').trim();
      const token = (elTok.value || '').trim();
      if (!url) { alert('URL required'); return; }
      try {
        const res = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json', ...(token ? { 'x-ingest-token': token } : {}) },
          body: JSON.stringify([{
            device_id: 'ext:test',
            session_id: 'popup-test-' + Date.now(),
            source: 'ext-test',
            method: 'GET',
            url: 'https://ext.local/test-ping',
            status: 200,
            captured_at: new Date().toISOString(),
            res_body: 'ping from extension popup'
          }])
        });
        const text = await res.text();
        toast(res.ok ? ('✅ ' + res.status + ' ' + text.slice(0, 60)) : ('❌ ' + res.status + ' ' + text.slice(0, 80)));
      } catch (e) {
        toast('❌ ' + e.message);
      }
    });
  } catch (e) { console.error('[popup telemetry] init error', e); }
})();
