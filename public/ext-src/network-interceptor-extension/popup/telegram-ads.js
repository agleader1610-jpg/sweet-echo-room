// ============================================================
// Telegram Ads Manager (v2.4.0)
// Per-ad, id-based editor. Works for EVERY ad (all pages),
// because patches are applied by ad_id on every API response
// and also directly in the rendered DOM.
// ============================================================
(async function () {
  const elList = document.getElementById('tg-list');
  if (!elList) return;

  const elSearch = document.getElementById('tg-search');
  const elCount = document.getElementById('tg-count');
  const elToggle = document.getElementById('tg-patch-toggle');
  const btnReset = document.getElementById('tg-reset');
  const btnSyncAll = document.getElementById('tg-save-all');

  let catalog = {};   // ad_id -> original ad object
  let patches = {};   // ad_id -> { fields:{}, original:{} }
  let dirty = {};     // ad_id -> { field: value }

  const PRIORITY = ['title', 'ad_title', 'text', 'status', 'views', 'opened', 'clicks', 'actions',
    'joined', 'budget', 'cpm', 'daily_budget', 'spent', 'active_views', 'url'];

  function toast(msg) {
    const t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.remove('hidden');
    setTimeout(() => t.classList.add('hidden'), 2200);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  async function load() {
    const s = await chrome.storage.local.get(['telegramAdsCatalog', 'telegramAdPatches', 'telegramPatchEnabled']);
    catalog = s.telegramAdsCatalog || {};
    patches = s.telegramAdPatches || {};
    if (elToggle) elToggle.checked = s.telegramPatchEnabled !== false;
    render();
  }

  function getPath(obj, path) {
    return String(path).split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
  }

  function flatten(obj, prefix, out, depth) {
    Object.keys(obj || {}).forEach(k => {
      const path = prefix ? prefix + '.' + k : k;
      if (path === 'ad_id') return;
      const v = obj[k];
      if (v === null) { out.push(path); return; }
      const t = typeof v;
      if (t === 'string' || t === 'number' || t === 'boolean') { out.push(path); return; }
      if (Array.isArray(v)) {
        if (depth < 3) v.forEach((item, i) => {
          if (item && typeof item === 'object') flatten(item, path + '.' + i, out, depth + 1);
          else out.push(path + '.' + i);
        });
        return;
      }
      if (t === 'object' && depth < 3) flatten(v, path, out, depth + 1);
    });
    return out;
  }

  function fieldsOf(ad) {
    const keys = flatten(ad, '', [], 0);
    keys.sort((a, b) => {
      const ia = PRIORITY.indexOf(a), ib = PRIORITY.indexOf(b);
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib) || a.localeCompare(b);
    });
    return keys;
  }

  function currentValue(id, ad, key) {
    if (dirty[id] && dirty[id][key] !== undefined) return dirty[id][key];
    const p = patches[id];
    if (p && p.fields && p.fields[key] !== undefined) return p.fields[key];
    return getPath(ad, key);
  }

  function baselineValue(id, ad, key) {
    const p = patches[id];
    if (p && p.original && p.original[key] !== undefined) return p.original[key];
    return getPath(ad, key);
  }

  function render() {
    const q = (elSearch && elSearch.value || '').trim().toLowerCase();
    const ids = Object.keys(catalog).sort((a, b) => Number(b) - Number(a));
    const filtered = ids.filter(id => {
      if (!q) return true;
      const ad = catalog[id];
      return id.includes(q) || JSON.stringify(ad).toLowerCase().includes(q);
    });

    if (elCount) elCount.textContent = String(ids.length);

    if (!filtered.length) {
      elList.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">✈️</div>
          <p class="empty-title">No Telegram ads captured yet</p>
          <p class="empty-sub">Open ads.telegram.org and let the page load — every ad (all pages) will appear here automatically.</p>
        </div>`;
      return;
    }

    elList.innerHTML = filtered.map(id => {
      const ad = catalog[id];
      const edited = !!patches[id];
      const title = currentValue(id, ad, 'title') || currentValue(id, ad, 'ad_title') || ('Ad #' + id);
      const rows = fieldsOf(ad).map(k => {
        const val = currentValue(id, ad, k);
        const changed = String(val) !== String(baselineValue(id, ad, k));
        const type = typeof ad[k] === 'number' ? 'number' : 'text';
        return `
          <label class="tg-field ${changed ? 'changed' : ''}">
            <span class="tg-key">${esc(k)}</span>
            <input class="tg-input" data-id="${esc(id)}" data-key="${esc(k)}" type="${type}" value="${esc(val)}">
          </label>`;
      }).join('');

      return `
        <div class="tg-card" data-card="${esc(id)}">
          <div class="tg-card-head">
            <div class="tg-avatar">${esc(String(title).slice(0, 1).toUpperCase() || 'A')}</div>
            <div class="tg-card-meta">
              <div class="tg-card-title">${esc(title)}</div>
              <div class="tg-card-id">ad_id: ${esc(id)}${edited ? ' • <span class="tg-edited">edited</span>' : ''}</div>
            </div>
            <button class="tg-btn tg-btn-ghost tg-expand">▾</button>
          </div>
          <div class="tg-fields">${rows}</div>
          <div class="tg-card-actions">
            <button class="tg-btn tg-btn-primary tg-save" data-id="${esc(id)}">Save & Apply</button>
            <button class="tg-btn tg-btn-ghost tg-revert" data-id="${esc(id)}">Revert</button>
          </div>
        </div>`;
    }).join('');

    elList.querySelectorAll('.tg-card-head').forEach(head => {
      head.addEventListener('click', () => {
        const card = head.closest('.tg-card');
        card.classList.toggle('open');
        const btn = card.querySelector('.tg-expand');
        if (btn) btn.textContent = card.classList.contains('open') ? '▴' : '▾';
      });
    });

    elList.querySelectorAll('.tg-input').forEach(inp => {
      inp.addEventListener('input', () => {
        const id = inp.getAttribute('data-id');
        const key = inp.getAttribute('data-key');
        dirty[id] = dirty[id] || {};
        dirty[id][key] = inp.value;
        inp.parentElement.classList.add('changed');
      });
    });

    elList.querySelectorAll('.tg-save').forEach(btn => {
      btn.addEventListener('click', () => saveAd(btn.getAttribute('data-id')));
    });

    elList.querySelectorAll('.tg-revert').forEach(btn => {
      btn.addEventListener('click', () => revertAd(btn.getAttribute('data-id')));
    });
  }

  async function persist() {
    await chrome.storage.local.set({ telegramAdPatches: patches });
  }

  async function saveAd(id) {
    const ad = catalog[id];
    if (!ad) return;
    const entry = patches[id] || { fields: {}, original: {} };
    const changes = dirty[id] || {};
    Object.keys(changes).forEach(k => {
      const base = baselineValue(id, ad, k);
      const raw = changes[k];
      const value = typeof ad[k] === 'number' ? Number(String(raw).replace(/[\s,]/g, '')) : raw;
      if (String(value) === String(base)) {
        delete entry.fields[k];
        delete entry.original[k];
        return;
      }
      entry.fields[k] = value;
      if (entry.original[k] === undefined) entry.original[k] = base;
    });
    entry.updated_at = Date.now();
    entry.title = ad.title != null ? ad.title : ad.ad_title;
    entry.ad_title = ad.ad_title != null ? ad.ad_title : ad.title;
    if (Object.keys(entry.fields).length) patches[id] = entry;
    else delete patches[id];
    delete dirty[id];
    await persist();
    render();
    toast('✅ Ad ' + id + ' patched — reload the page');
  }

  async function revertAd(id) {
    delete patches[id];
    delete dirty[id];
    await persist();
    render();
    toast('↩️ Ad ' + id + ' restored to original');
  }

  if (elSearch) elSearch.addEventListener('input', render);

  if (btnSyncAll) {
    btnSyncAll.addEventListener('click', async () => {
      const ids = Object.keys(dirty);
      for (const id of ids) await saveAd(id);
      toast(ids.length ? '✅ ' + ids.length + ' ads patched' : 'Nothing to save');
    });
  }

  if (btnReset) {
    btnReset.addEventListener('click', async () => {
      if (!confirm('Remove ALL Telegram ad edits?')) return;
      patches = {}; dirty = {};
      await persist();
      render();
      toast('All ad edits removed');
    });
  }

  if (elToggle) {
    elToggle.addEventListener('change', async () => {
      await chrome.storage.local.set({ telegramPatchEnabled: elToggle.checked });
      toast(elToggle.checked ? 'Ad patching ON — reload page' : 'Ad patching OFF — reload page');
    });
  }

  const btnRefresh = document.getElementById('tg-refresh');
  if (btnRefresh) btnRefresh.addEventListener('click', () => { load(); toast('Synced from captures'); });

  chrome.storage.onChanged.addListener((changes, ns) => {
    if (ns !== 'local') return;
    if (changes.telegramAdsCatalog) { catalog = changes.telegramAdsCatalog.newValue || {}; render(); }
    if (changes.telegramAdPatches) { patches = changes.telegramAdPatches.newValue || {}; render(); }
  });

  await load();
})();
