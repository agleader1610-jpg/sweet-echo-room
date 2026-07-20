import { createFileRoute } from '@tanstack/react-router';
import { useServerFn } from '@tanstack/react-start';
import { useEffect, useState } from 'react';
import {
  adminLogin,
  adminLogout,
  adminStatus,
  getAdminDashboard,
  publishUpdate,
  getPredictionAnalytics,
  exportPredictionsCsv,
  getLicenseSettings,
  updateLicenseSettings,
  addLicensedUid,
  removeLicensedUid,
  getDepositSettings,
  updateDepositGlobal,
  upsertDepositRule,
  removeDepositRule,
  listNotificationsAdmin,
  createNotification,
  deleteNotification,
  getUsageStats,
} from '@/lib/admin-gate.functions';

export const Route = createFileRoute('/admin')({
  head: () => ({ meta: [{ title: 'Admin Panel — App Update Control' }, { name: 'robots', content: 'noindex' }] }),
  component: AdminPage,
});

function AdminPage() {
  const status = useServerFn(adminStatus);
  const login = useServerFn(adminLogin);
  const logout = useServerFn(adminLogout);

  const [unlocked, setUnlocked] = useState<boolean | null>(null);
  const [pw, setPw] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    status().then((s) => setUnlocked(s.unlocked)).catch(() => setUnlocked(false));
  }, [status]);

  async function onLogin(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    const r = await login({ data: { password: pw } });
    if (r.ok) { setUnlocked(true); setPw(''); }
    else setErr('ভুল password');
  }

  async function onLogout() {
    await logout();
    setUnlocked(false);
  }

  if (unlocked === null) {
    return <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center">Loading…</div>;
  }

  if (!unlocked) {
    return (
      <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center px-4">
        <form onSubmit={onLogin} className="w-full max-w-sm bg-slate-900 rounded-xl p-6 border border-slate-800">
          <h1 className="text-2xl font-bold mb-1">Admin Panel</h1>
          <p className="text-sm text-slate-400 mb-6">Manual app update control</p>
          <input
            type="password"
            autoFocus
            autoComplete="current-password"
            value={pw}
            onChange={(e) => setPw(e.target.value)}
            placeholder="Password"
            className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 mb-3 outline-none focus:border-cyan-500"
          />
          {err && <p className="text-red-400 text-sm mb-3">{err}</p>}
          <button type="submit" className="w-full rounded-md bg-cyan-600 hover:bg-cyan-500 px-3 py-2 font-medium">
            Enter
          </button>
        </form>
      </div>
    );
  }

  return <Dashboard onLogout={onLogout} />;
}

function Dashboard({ onLogout }: { onLogout: () => void }) {
  const [tab, setTab] = useState<'update' | 'analytics' | 'license' | 'deposit' | 'notifications' | 'users'>('users');
  return (
    <div className="min-h-screen bg-slate-950 text-white">
      <header className="border-b border-slate-800 bg-slate-900/50 backdrop-blur">
        <div className="max-w-5xl mx-auto px-4 py-4 flex justify-between items-center">
          <h1 className="text-xl font-bold">EIP Admin</h1>
          <div className="flex items-center gap-2 flex-wrap">
            <button onClick={() => setTab('users')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'users' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              👥 Users
            </button>
            <button onClick={() => setTab('license')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'license' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              🔒 License
            </button>
            <button onClick={() => setTab('deposit')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'deposit' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              💰 Deposit
            </button>
            <button onClick={() => setTab('notifications')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'notifications' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              🔔 Notifications
            </button>
            <button onClick={() => setTab('analytics')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'analytics' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              📊 Analytics
            </button>
            <button onClick={() => setTab('update')}
              className={`px-3 py-1.5 rounded text-sm ${tab === 'update' ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
              🚀 App Update
            </button>
            <button onClick={onLogout} className="ml-2 text-sm text-slate-400 hover:text-white">Logout</button>
          </div>
        </div>
      </header>
      {tab === 'analytics' ? <AnalyticsTab />
        : tab === 'update' ? <UpdateTab onLogout={onLogout} />
        : tab === 'deposit' ? <DepositTab />
        : tab === 'notifications' ? <NotificationsTab />
        : tab === 'users' ? <UsersTab />
        : <LicenseTab />}
    </div>
  );
}

function NotificationsTab() {
  const load = useServerFn(listNotificationsAdmin);
  const create = useServerFn(createNotification);
  const remove = useServerFn(deleteNotification);
  const [items, setItems] = useState<Array<{ id: string; title: string; body: string; target_uid: string | null; created_at: string }>>([]);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [targetUid, setTargetUid] = useState('');
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState('');

  async function refresh() {
    const r = await load();
    setItems(r.items as any);
  }
  useEffect(() => { refresh().catch(console.error); }, []);

  async function onSend(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim() || !body.trim()) return;
    setBusy(true);
    try {
      await create({ data: { title: title.trim(), body: body.trim(), target_uid: targetUid.trim() || null } });
      setTitle(''); setBody(''); setTargetUid('');
      setToast('✅ Notification পাঠানো হয়েছে');
      setTimeout(() => setToast(''), 1800);
      await refresh();
    } finally { setBusy(false); }
  }
  async function onRemove(id: string) {
    if (!confirm('এই notification remove করবেন?')) return;
    await remove({ data: { id } });
    refresh();
  }

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-6">
      {toast && <div className="fixed top-4 right-4 bg-green-600 px-3 py-1.5 rounded text-sm shadow-lg z-50">{toast}</div>}

      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-1">🔔 Send Notification</h2>
        <p className="text-sm text-slate-400 mb-4">
          Target UID খালি রাখলে সকল user-এর কাছে broadcast হবে। App-এর 🔔 bell-এ instant বাজবে।
        </p>
        <form onSubmit={onSend} className="grid gap-3 md:grid-cols-2">
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title"
            className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-amber-500" />
          <input value={targetUid} onChange={(e) => setTargetUid(e.target.value)} placeholder="Target UID (খালি = সবাই)"
            className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-amber-500" />
          <textarea value={body} onChange={(e) => setBody(e.target.value)} placeholder="Message body" rows={3}
            className="md:col-span-2 rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-amber-500" />
          <button type="submit" disabled={busy}
            className="md:col-span-2 rounded-md bg-amber-600 hover:bg-amber-500 disabled:opacity-50 px-4 py-2 font-semibold">
            {busy ? 'পাঠানো হচ্ছে…' : '📢 Send'}
          </button>
        </form>
      </section>

      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h3 className="font-semibold mb-3">সাম্প্রতিক Notifications</h3>
        {items.length === 0 ? (
          <p className="text-sm text-slate-500">কোনো notification নেই।</p>
        ) : (
          <div className="space-y-2">
            {items.map((n) => (
              <div key={n.id} className="border border-slate-800 rounded-lg p-3 flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-semibold text-sm">{n.title}</span>
                    <span className={`text-[10px] px-1.5 py-0.5 rounded ${n.target_uid ? 'bg-cyan-800 text-cyan-100' : 'bg-emerald-800 text-emerald-100'}`}>
                      {n.target_uid ? `UID: ${n.target_uid}` : 'BROADCAST'}
                    </span>
                    <span className="text-[11px] text-slate-500">{new Date(n.created_at).toLocaleString()}</span>
                  </div>
                  <p className="text-sm text-slate-300 mt-1 whitespace-pre-wrap break-words">{n.body}</p>
                </div>
                <button onClick={() => onRemove(n.id)}
                  className="text-xs text-red-400 hover:text-red-300 shrink-0">Remove</button>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}


type GameKey = 'aviator' | 'wingo' | 'wingo_auto';
const GAME_META: Array<{ key: GameKey; label: string; icon: string; color: string }> = [
  { key: 'aviator',    label: 'Aviator',        icon: '✈️', color: 'from-fuchsia-600 to-pink-600' },
  { key: 'wingo',      label: 'WinGo',          icon: '🎯', color: 'from-cyan-600 to-sky-600' },
  { key: 'wingo_auto', label: 'WinGo AutoBet',  icon: '🤖', color: 'from-emerald-600 to-teal-600' },
];

function DepositTab() {
  const load = useServerFn(getDepositSettings);
  const saveGlobal = useServerFn(updateDepositGlobal);
  const upsert = useServerFn(upsertDepositRule);
  const remove = useServerFn(removeDepositRule);

  const [data, setData] = useState<any>(null);
  const [drafts, setDrafts] = useState<Record<GameKey, { lifetime_min: number; recency_days: number; recency_min: number }>>({
    aviator:    { lifetime_min: 0, recency_days: 0, recency_min: 1 },
    wingo:      { lifetime_min: 0, recency_days: 0, recency_min: 1 },
    wingo_auto: { lifetime_min: 0, recency_days: 0, recency_min: 1 },
  });
  const [toast, setToast] = useState('');

  // Override form
  const [uid, setUid] = useState('');
  const [ovGame, setOvGame] = useState<'all' | GameKey>('all');
  const [ruleType, setRuleType] = useState<'unlimited' | 'days' | 'blocked'>('unlimited');
  const [ovLifetime, setOvLifetime] = useState(0);
  const [ovDays, setOvDays] = useState(2);
  const [ovMin, setOvMin] = useState(1);
  const [note, setNote] = useState('');

  async function refresh() {
    const d = await load();
    setData(d);
    setDrafts(d.globals);
  }
  useEffect(() => { refresh().catch(console.error); }, []);

  async function onSaveGlobal(game: GameKey) {
    const g = drafts[game];
    await saveGlobal({ data: { game, ...g } });
    setToast(`✅ ${GAME_META.find(m => m.key === game)!.label} rule saved`);
    setTimeout(() => setToast(''), 1800);
    refresh();
  }
  function updateDraft(game: GameKey, patch: Partial<{ lifetime_min: number; recency_days: number; recency_min: number }>) {
    setDrafts((prev) => ({ ...prev, [game]: { ...prev[game], ...patch } }));
  }

  async function onAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!uid.trim()) return;
    await upsert({
      data: {
        uid: uid.trim(),
        game: ovGame === 'all' ? null : ovGame,
        rule_type: ruleType,
        lifetime_min: ovLifetime,
        recency_days: ovDays,
        recency_min: ovMin,
        note: note.trim(),
      },
    });
    setUid(''); setNote('');
    refresh();
  }
  async function onRemove(id: number) {
    if (!confirm(`Remove this override?`)) return;
    await remove({ data: { id } });
    refresh();
  }

  if (!data) return <div className="max-w-5xl mx-auto px-4 py-8">Loading…</div>;

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-6">
      {toast && <div className="fixed top-4 right-4 bg-green-600 px-3 py-1.5 rounded text-sm shadow-lg z-50">{toast}</div>}

      {/* Header explainer */}
      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-1">💰 Deposit Gate — Per Game Rules</h2>
        <p className="text-sm text-slate-400 leading-relaxed">
          প্রত্যেক game unlock হওয়ার জন্য <b>দুইটা condition</b> একসাথে satisfy হতে হবে (AND logic):
        </p>
        <ul className="text-sm text-slate-300 mt-2 space-y-1 list-disc list-inside">
          <li><b className="text-amber-400">Lifetime N deposits</b> — total কতটা successful deposit দরকার। একবার পূরণ হলে permanent, আর check হবে না।</li>
          <li><b className="text-cyan-400">Recency (last X days, Y deposits)</b> — শেষ X দিনে কমপক্ষে Y টা deposit থাকতে হবে। প্রতিবার check হয়।</li>
        </ul>
        <p className="text-xs text-slate-500 mt-2">টিপ: কোনো field-এ <b>0</b> দিলে সেই condition বন্ধ। দুটোই 0 = পুরো deposit gate off।</p>
      </section>

      {/* Per-game global rules */}
      <div className="grid gap-4 md:grid-cols-3">
        {GAME_META.map((meta) => {
          const g = drafts[meta.key];
          const off = g.lifetime_min === 0 && g.recency_days === 0;
          return (
            <section key={meta.key}
              className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
              <div className={`px-4 py-3 bg-gradient-to-r ${meta.color} flex items-center justify-between`}>
                <div className="flex items-center gap-2">
                  <span className="text-2xl">{meta.icon}</span>
                  <h3 className="font-semibold">{meta.label}</h3>
                </div>
                <span className={`text-xs px-2 py-0.5 rounded ${off ? 'bg-black/40' : 'bg-white/20'}`}>
                  {off ? 'OFF' : 'ACTIVE'}
                </span>
              </div>
              <div className="p-4 space-y-4 flex-1">
                <div>
                  <label className="block text-xs uppercase tracking-wider text-amber-400 mb-1">
                    Lifetime minimum deposits
                  </label>
                  <input type="number" min={0} max={100} value={g.lifetime_min}
                    onChange={(e) => updateDraft(meta.key, { lifetime_min: Number(e.target.value) })}
                    className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-amber-500" />
                  <p className="text-[11px] text-slate-500 mt-1">0 = off। একবার পূরণ হলে permanent।</p>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-xs uppercase tracking-wider text-cyan-400 mb-1">
                      Last N days
                    </label>
                    <input type="number" min={0} max={365} value={g.recency_days}
                      onChange={(e) => updateDraft(meta.key, { recency_days: Number(e.target.value) })}
                      className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-cyan-500" />
                  </div>
                  <div>
                    <label className="block text-xs uppercase tracking-wider text-cyan-400 mb-1">
                      Min deposits
                    </label>
                    <input type="number" min={1} max={100} value={g.recency_min}
                      disabled={g.recency_days === 0}
                      onChange={(e) => updateDraft(meta.key, { recency_min: Number(e.target.value) })}
                      className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 text-sm outline-none focus:border-cyan-500 disabled:opacity-40" />
                  </div>
                </div>
                <div className="text-xs text-slate-400 bg-slate-800/50 rounded p-2 leading-relaxed">
                  {off ? (
                    <span className="text-slate-500">এই game-এর জন্য deposit চেক বন্ধ।</span>
                  ) : (
                    <>
                      Unlock হবে যদি — <b className="text-amber-400">lifetime ≥ {g.lifetime_min || 0}</b>
                      {g.lifetime_min > 0 && g.recency_days > 0 && ' AND '}
                      {g.recency_days > 0 && (
                        <b className="text-cyan-400">last {g.recency_days}d-এ ≥ {g.recency_min}</b>
                      )}
                    </>
                  )}
                </div>
                <button onClick={() => onSaveGlobal(meta.key)}
                  className={`w-full rounded-md bg-gradient-to-r ${meta.color} hover:brightness-110 px-4 py-2 text-sm font-medium`}>
                  💾 Save {meta.label}
                </button>
              </div>
            </section>
          );
        })}
      </div>

      {/* Per-UID override */}
      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-1">👤 Per-UID Override ({data.rules.length})</h2>
        <p className="text-sm text-slate-400 mb-4">
          নির্দিষ্ট user-এর জন্য custom rule — যেকোনো একটা game-এর জন্য বা সব game-এর জন্য global-কে override করবে।
        </p>
        <form onSubmit={onAdd} className="space-y-3 mb-5">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            <input value={uid} onChange={(e) => setUid(e.target.value)} placeholder="UID (e.g. 101415)"
              className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-amber-500 font-mono" />
            <select value={ovGame} onChange={(e) => setOvGame(e.target.value as any)}
              className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-amber-500">
              <option value="all">🌐 All games</option>
              {GAME_META.map((m) => <option key={m.key} value={m.key}>{m.icon} {m.label} only</option>)}
            </select>
          </div>
          <div className="flex flex-wrap gap-2">
            {(['unlimited', 'days', 'blocked'] as const).map((t) => (
              <button type="button" key={t} onClick={() => setRuleType(t)}
                className={`px-3 py-1.5 rounded text-sm ${ruleType === t
                  ? (t === 'unlimited' ? 'bg-green-600' : t === 'blocked' ? 'bg-red-600' : 'bg-amber-600')
                  : 'bg-slate-800 hover:bg-slate-700'}`}>
                {t === 'unlimited' ? '♾️ Unlimited' : t === 'blocked' ? '🚫 Blocked' : '⚙️ Custom'}
              </button>
            ))}
          </div>
          {ruleType === 'days' && (
            <div className="grid grid-cols-3 gap-2 bg-slate-800/50 rounded p-3">
              <div>
                <label className="block text-xs text-amber-400 mb-1">Lifetime min</label>
                <input type="number" min={0} max={100} value={ovLifetime}
                  onChange={(e) => setOvLifetime(Number(e.target.value))}
                  className="w-full rounded bg-slate-800 border border-slate-700 px-2 py-1.5 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-cyan-400 mb-1">Last N days</label>
                <input type="number" min={0} max={365} value={ovDays}
                  onChange={(e) => setOvDays(Number(e.target.value))}
                  className="w-full rounded bg-slate-800 border border-slate-700 px-2 py-1.5 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-cyan-400 mb-1">Min deposits</label>
                <input type="number" min={1} max={100} value={ovMin}
                  onChange={(e) => setOvMin(Number(e.target.value))}
                  className="w-full rounded bg-slate-800 border border-slate-700 px-2 py-1.5 text-sm" />
              </div>
            </div>
          )}
          <div className="flex gap-2">
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)"
              className="flex-1 rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-amber-500" />
            <button type="submit" className="rounded-md bg-green-600 hover:bg-green-500 px-5 py-2 text-sm font-medium">
              ➕ Save Override
            </button>
          </div>
        </form>
        {data.rules.length === 0 ? (
          <p className="text-sm text-slate-500 py-4 text-center">কোনো per-UID override নেই।</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-xs text-slate-400 uppercase">
                <tr>
                  <th className="text-left py-2">UID</th>
                  <th className="text-left py-2">Game</th>
                  <th className="text-left py-2">Rule</th>
                  <th className="text-left py-2">Note</th>
                  <th className="text-left py-2">Updated</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.rules.map((r: any) => (
                  <tr key={r.id} className="border-t border-slate-800">
                    <td className="py-2 font-mono">{r.uid}</td>
                    <td className="py-2">
                      {r.game
                        ? <span className="text-xs bg-slate-800 px-2 py-0.5 rounded">
                            {GAME_META.find((m) => m.key === r.game)?.icon} {GAME_META.find((m) => m.key === r.game)?.label ?? r.game}
                          </span>
                        : <span className="text-xs bg-slate-700 px-2 py-0.5 rounded">🌐 All</span>}
                    </td>
                    <td className="py-2">
                      {r.rule_type === 'unlimited' && <span className="text-green-400">♾️ Unlimited</span>}
                      {r.rule_type === 'blocked' && <span className="text-red-400">🚫 Blocked</span>}
                      {r.rule_type === 'days' && (
                        <span className="text-amber-400">
                          Life ≥ {r.lifetime_min_deposits ?? 0}
                          {r.recency_days ? ` · ${r.recency_days}d ≥ ${r.recency_min_deposits ?? 1}` : ''}
                        </span>
                      )}
                    </td>
                    <td className="py-2 text-slate-400">{r.note ?? '—'}</td>
                    <td className="py-2 text-slate-500 text-xs">{new Date(r.updated_at).toLocaleString()}</td>
                    <td className="py-2 text-right">
                      <button onClick={() => onRemove(r.id)} className="text-red-400 hover:text-red-300 text-xs">Remove</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Recent gate checks */}
      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-3">🕒 Recent Gate Checks</h2>
        {data.recent.length === 0 ? (
          <p className="text-sm text-slate-500 py-4 text-center">এখনো কোনো check হয়নি।</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-slate-400 uppercase">
                <tr>
                  <th className="text-left py-2">Time</th>
                  <th className="text-left py-2">UID</th>
                  <th className="text-left py-2">Game</th>
                  <th className="text-left py-2">Result</th>
                  <th className="text-left py-2">Reason</th>
                  <th className="text-left py-2">Source</th>
                  <th className="text-left py-2">Life / Recent</th>
                  <th className="text-left py-2">Last Deposit</th>
                </tr>
              </thead>
              <tbody>
                {data.recent.map((r: any, i: number) => (
                  <tr key={i} className="border-t border-slate-800">
                    <td className="py-1.5 text-slate-500">{new Date(r.checked_at).toLocaleString()}</td>
                    <td className="py-1.5 font-mono">{r.uid ?? '—'}</td>
                    <td className="py-1.5">{r.game ?? '—'}</td>
                    <td className="py-1.5">{r.allowed ? <span className="text-green-400">✓</span> : <span className="text-red-400">✗</span>}</td>
                    <td className="py-1.5 text-slate-400">{r.reason}</td>
                    <td className="py-1.5 text-slate-400">{r.rule_source}</td>
                    <td className="py-1.5 text-slate-400">
                      {r.lifetime_count ?? '—'} / {r.recent_count ?? '—'}
                    </td>
                    <td className="py-1.5 text-slate-500">{r.last_deposit_at ? new Date(r.last_deposit_at).toLocaleString() : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}


function LicenseTab() {
  const load = useServerFn(getLicenseSettings);
  const save = useServerFn(updateLicenseSettings);
  const addUid = useServerFn(addLicensedUid);
  const rmUid = useServerFn(removeLicensedUid);
  const [data, setData] = useState<{ required_parent_invite_code: string; lock_message: string; uids: Array<{ uid: string; note: string | null; created_at: string }> } | null>(null);
  const [invite, setInvite] = useState('');
  const [msg, setMsg] = useState('');
  const [newUid, setNewUid] = useState('');
  const [newNote, setNewNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState('');

  async function refresh() {
    const d = await load();
    setData(d);
    setInvite(d.required_parent_invite_code);
    setMsg(d.lock_message);
  }
  useEffect(() => { refresh().catch(console.error); }, []);

  async function onSave() {
    setSaving(true);
    try {
      await save({ data: { required_parent_invite_code: invite, lock_message: msg } });
      setToast('✅ Saved'); setTimeout(() => setToast(''), 2000);
    } finally { setSaving(false); }
  }
  async function onAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!newUid.trim()) return;
    await addUid({ data: { uid: newUid.trim(), note: newNote.trim() } });
    setNewUid(''); setNewNote('');
    await refresh();
  }
  async function onRemove(uid: string) {
    if (!confirm(`Remove UID ${uid}?`)) return;
    await rmUid({ data: { uid } });
    await refresh();
  }

  if (!data) return <div className="max-w-5xl mx-auto px-4 py-8">Loading…</div>;

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-6">
      {toast && <div className="fixed top-4 right-4 bg-green-600 px-3 py-1.5 rounded text-sm">{toast}</div>}

      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-1">🔑 Required Parent Invite Code</h2>
        <p className="text-sm text-slate-400 mb-4">যে user-এর <code className="bg-slate-800 px-1 rounded">parentInviteCode</code> এই code-এর সাথে match করবে, তার app auto unlock হবে।</p>
        <label className="block text-xs uppercase tracking-wider text-slate-400 mb-1">Invite Code</label>
        <input
          value={invite}
          onChange={(e) => setInvite(e.target.value)}
          className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 mb-4 outline-none focus:border-cyan-500 font-mono"
        />
        <label className="block text-xs uppercase tracking-wider text-slate-400 mb-1">Lock Message (LOCKED user-দের কি দেখবে)</label>
        <textarea
          value={msg}
          onChange={(e) => setMsg(e.target.value)}
          rows={3}
          className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 mb-4 outline-none focus:border-cyan-500"
        />
        <button onClick={onSave} disabled={saving} className="rounded-md bg-cyan-600 hover:bg-cyan-500 px-4 py-2 text-sm font-medium disabled:opacity-50">
          {saving ? 'Saving…' : '💾 Save Settings'}
        </button>
      </section>

      <section className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <h2 className="text-lg font-semibold mb-1">✅ Whitelisted UIDs ({data.uids.length})</h2>
        <p className="text-sm text-slate-400 mb-4">এই UID-গুলো parent invite match না করলেও app unlock পাবে। Login response-এর <code className="bg-slate-800 px-1 rounded">UserId</code> field দেখে match করা হয়।</p>
        <form onSubmit={onAdd} className="grid grid-cols-[1fr,1fr,auto] gap-2 mb-4">
          <input value={newUid} onChange={(e) => setNewUid(e.target.value)} placeholder="UID (e.g. 101415)"
            className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500 font-mono" />
          <input value={newNote} onChange={(e) => setNewNote(e.target.value)} placeholder="Note (optional)"
            className="rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
          <button type="submit" className="rounded-md bg-green-600 hover:bg-green-500 px-4 py-2 text-sm font-medium">➕ Add</button>
        </form>
        {data.uids.length === 0 ? (
          <p className="text-sm text-slate-500 py-4 text-center">কোন whitelisted UID নেই।</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-xs text-slate-400 uppercase">
                <tr><th className="text-left py-2">UID</th><th className="text-left py-2">Note</th><th className="text-left py-2">Added</th><th className="py-2"></th></tr>
              </thead>
              <tbody>
                {data.uids.map((r) => (
                  <tr key={r.uid} className="border-t border-slate-800">
                    <td className="py-2 font-mono">{r.uid}</td>
                    <td className="py-2 text-slate-400">{r.note ?? '—'}</td>
                    <td className="py-2 text-slate-500 text-xs">{new Date(r.created_at).toLocaleString()}</td>
                    <td className="py-2 text-right">
                      <button onClick={() => onRemove(r.uid)} className="text-red-400 hover:text-red-300 text-xs">Remove</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function UpdateTab({ onLogout }: { onLogout: () => void }) {
  const load = useServerFn(getAdminDashboard);
  const save = useServerFn(publishUpdate);
  const [minVersion, setMinVersion] = useState<number>(1);
  const [latestVersion, setLatestVersion] = useState<number>(1);
  const [latestName, setLatestName] = useState<string>('1.0.1');
  const [apkUrl, setApkUrl] = useState<string>('');
  const [forceUpdate, setForceUpdate] = useState<boolean>(true);
  const [message, setMessage] = useState<string>('');
  const [savedMin, setSavedMin] = useState<number>(1);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState('');

  async function refresh() {
    setLoading(true);
    const r = await load();
    if (r.unauthorized || !r.config) { onLogout(); return; }
    setMinVersion(r.config.min_version ?? 1);
    setSavedMin(r.config.min_version ?? 1);
    setLatestVersion(r.config.latest_version ?? 1);
    setLatestName(r.config.latest_version_name ?? '1.0.1');
    setApkUrl(r.config.apk_url ?? '');
    setForceUpdate(!!r.config.force_update);
    setMessage(r.config.update_message ?? '');
    setLoading(false);
  }


  useEffect(() => { refresh().catch(() => setLoading(false)); }, []);

  async function onPublish(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true); setMsg('');
    try {
      await save({ data: {
        min_version: Number(minVersion) || 1,
        latest_version: Number(latestVersion) || Number(minVersion) || 1,
        latest_version_name: latestName,
        apk_url: apkUrl,
        force_update: forceUpdate,
        update_message: message,
      }});
      setSavedMin(Number(minVersion) || 1);
      setMsg('✅ Saved — version checker এখন এই manual তথ্য ব্যবহার করবে');
    } catch (e: any) {
      setMsg('❌ ' + (e?.message || 'Failed'));
    } finally { setSaving(false); }
  }

  if (loading) return <div className="p-8 text-center">Loading…</div>;
  const dirty = minVersion !== savedMin;

  return (
    <main className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <section className="bg-slate-900 border border-slate-800 rounded-xl p-6">
        <div className="text-xs uppercase tracking-wide text-slate-400 mb-1">Saved update target</div>
        <div className="text-2xl font-bold mb-2">Version code {latestVersion || minVersion}</div>
        {apkUrl ? (
          <a href={apkUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 text-cyan-400 hover:text-cyan-300 text-sm break-all">
            📥 Test APK link
          </a>
        ) : (
          <div className="text-slate-400 text-sm">এখনো APK download URL দেওয়া হয়নি।</div>
        )}
        <button onClick={() => { refresh(); }} className="mt-3 text-xs text-slate-400 hover:text-white">🔄 Refresh</button>
      </section>

      <form onSubmit={onPublish} className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-5">
        <div>
          <label className="block text-sm font-medium mb-1">Minimum required version code</label>
          <input type="number" min={1} value={minVersion} onChange={(e) => setMinVersion(Number(e.target.value))}
            className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
          <div className="mt-1.5 text-xs">Saved: <b>{savedMin}</b>{dirty && <span className="text-amber-400 ml-2">● unsaved</span>}</div>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label className="block text-sm font-medium mb-1">Latest version code</label>
            <input type="number" min={1} value={latestVersion} onChange={(e) => setLatestVersion(Number(e.target.value))}
              className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Latest version name</label>
            <input value={latestName} onChange={(e) => setLatestName(e.target.value)} placeholder="1.0.32"
              className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
          </div>
        </div>
        <div>
          <label className="block text-sm font-medium mb-1">APK download URL</label>
          <input type="url" value={apkUrl} onChange={(e) => setApkUrl(e.target.value)} placeholder="https://…/app.apk"
            className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
        </div>
        <label className="flex items-start gap-3 cursor-pointer">
          <input type="checkbox" checked={forceUpdate} onChange={(e) => setForceUpdate(e.target.checked)} className="w-5 h-5 mt-0.5 accent-cyan-500" />
          <div>
            <div className="font-medium">Force update চালু</div>
            <div className="text-xs text-slate-400">On থাকলে পুরনো version block হবে।</div>
          </div>
        </label>
        <div>
          <label className="block text-sm font-medium mb-1">Update message</label>
          <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={3}
            className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
        </div>
        <div className="flex items-center gap-3 pt-2">
          <button disabled={saving} type="submit" className="rounded-md bg-cyan-600 hover:bg-cyan-500 disabled:opacity-50 px-6 py-2.5 font-semibold">
            {saving ? 'Publishing…' : '🚀 Publish'}
          </button>
          {msg && <span className="text-sm">{msg}</span>}
        </div>
      </form>
    </main>
  );
}

// ============ ANALYTICS TAB ============

type Bucket = { key: string; total: number; verified: number; wins: number; win_rate: number | null; avg_latency_ms: number | null };

function AnalyticsTab() {
  const load = useServerFn(getPredictionAnalytics);
  const csvFn = useServerFn(exportPredictionsCsv);
  const [hours, setHours] = useState(24);
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);

  async function refresh() {
    setLoading(true);
    try { setData(await load({ data: { hours } })); } finally { setLoading(false); }
  }

  useEffect(() => { refresh(); }, [hours]);
  useEffect(() => {
    if (!autoRefresh) return;
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [autoRefresh, hours]);

  async function onExport() {
    const r = await csvFn({ data: { hours: 720 } });
    const blob = new Blob([r.csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = `predictions-${Date.now()}.csv`; a.click();
    URL.revokeObjectURL(url);
  }

  if (loading && !data) return <div className="p-8 text-center text-slate-400">Loading analytics…</div>;
  if (!data) return <div className="p-8 text-center text-red-400">Failed to load</div>;

  const s = data.summary;

  return (
    <main className="max-w-5xl mx-auto px-4 py-6 space-y-5">
      {/* Controls */}
      <div className="flex flex-wrap gap-2 items-center bg-slate-900 border border-slate-800 rounded-lg p-3">
        <span className="text-sm text-slate-400 mr-2">Window:</span>
        {[1, 6, 24, 72, 168, 720].map(h => (
          <button key={h} onClick={() => setHours(h)}
            className={`px-3 py-1 rounded text-xs ${hours === h ? 'bg-cyan-600' : 'bg-slate-800 hover:bg-slate-700'}`}>
            {h < 24 ? `${h}h` : `${h / 24}d`}
          </button>
        ))}
        <div className="ml-auto flex gap-2 items-center">
          <label className="text-xs flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={autoRefresh} onChange={e => setAutoRefresh(e.target.checked)} className="accent-cyan-500" />
            Auto-refresh 15s
          </label>
          <button onClick={refresh} className="text-xs bg-slate-800 hover:bg-slate-700 px-3 py-1 rounded">🔄</button>
          <button onClick={onExport} className="text-xs bg-emerald-700 hover:bg-emerald-600 px-3 py-1 rounded">📥 Export CSV (30d)</button>
        </div>
      </div>

      {/* Summary */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Stat label="Total Predictions" value={s.total} />
        <Stat label="Verified" value={`${s.verified} / ${s.total}`} sub={s.pending ? `${s.pending} pending` : undefined} />
        <Stat label="Win Rate" value={s.win_rate == null ? '—' : `${s.win_rate}%`} sub={`${s.wins}W / ${s.losses}L`}
          tone={s.win_rate == null ? 'neutral' : s.win_rate >= 55 ? 'good' : s.win_rate >= 45 ? 'neutral' : 'bad'} />
        <Stat label="Latency" value={s.latency_p50_ms == null ? '—' : `${s.latency_p50_ms}ms`} sub={s.latency_p95_ms ? `p95: ${s.latency_p95_ms}ms` : undefined}
          tone={s.latency_p50_ms == null ? 'neutral' : s.latency_p50_ms < 2000 ? 'good' : s.latency_p50_ms < 4000 ? 'neutral' : 'bad'} />
      </div>

      <div className="grid md:grid-cols-2 gap-5">
        <Card title="By Source (AI vs Local)">
          <BucketTable rows={data.by_source} />
        </Card>
        <Card title="By Action">
          <BucketTable rows={data.by_action} />
        </Card>
        <Card title="By Confidence">
          <BucketTable rows={data.by_confidence} />
        </Card>
        <Card title="By Target Range">
          <BucketTable rows={data.by_target} />
        </Card>
        <Card title="By Model">
          <BucketTable rows={data.by_model} />
        </Card>
        <Card title="Actual Crash Distribution">
          <BucketTable rows={data.crash_distribution} showWin={false} />
        </Card>
        <Card title="🏆 Best Scenarios (min 3 verified)">
          <BucketTable rows={data.best_scenarios} />
        </Card>
        <Card title="⚠️ Worst Scenarios (min 3 verified)">
          <BucketTable rows={data.worst_scenarios} />
        </Card>
      </div>

      {/* Recent predictions */}
      <Card title={`Recent ${data.recent.length} Predictions`}>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="text-slate-400 border-b border-slate-800">
              <tr>
                <th className="text-left p-1.5">Round</th>
                <th className="text-left p-1.5">Action</th>
                <th className="text-right p-1.5">Target</th>
                <th className="text-left p-1.5">Conf</th>
                <th className="text-left p-1.5">Src</th>
                <th className="text-right p-1.5">Lat</th>
                <th className="text-right p-1.5">Actual</th>
                <th className="text-center p-1.5">W/L</th>
                <th className="text-left p-1.5">Reasoning</th>
              </tr>
            </thead>
            <tbody>
              {data.recent.map((r: any) => (
                <tr key={r.id} className="border-b border-slate-800/50">
                  <td className="p-1.5 font-mono text-slate-400">#{r.round_id}</td>
                  <td className="p-1.5 font-semibold">{r.action}</td>
                  <td className="p-1.5 text-right font-mono">{r.target == null ? '—' : `${Number(r.target).toFixed(2)}x`}</td>
                  <td className="p-1.5 text-slate-400">{r.confidence ?? '—'}</td>
                  <td className="p-1.5"><span className={r.source === 'ai' ? 'text-emerald-400' : 'text-blue-400'}>{r.source}</span></td>
                  <td className="p-1.5 text-right text-slate-400 font-mono">{r.latency_ms ?? '—'}</td>
                  <td className="p-1.5 text-right font-mono">{r.actual == null ? '⏳' : `${Number(r.actual).toFixed(2)}x`}</td>
                  <td className="p-1.5 text-center">
                    {r.outcome == null ? <span className="text-slate-500">—</span>
                      : r.outcome === 'WIN' ? <span className="text-emerald-400 font-bold">✓ WIN</span>
                      : r.outcome === 'SKIP_WIN' ? <span className="text-emerald-400 font-bold">SKIP✓</span>
                      : r.outcome === 'SKIP_LOSS' ? <span className="text-red-400 font-bold">SKIP✗</span>
                      : <span className="text-red-400 font-bold">✗ LOSS</span>}
                  </td>
                  <td className="p-1.5 text-slate-400 max-w-xs truncate" title={r.reasoning ?? ''}>{r.reasoning ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div className="text-xs text-slate-500 text-center pt-2">
        Window: last {data.window_hours}h • Data: {data.summary.total} predictions loaded
      </div>
    </main>
  );
}

function Stat({ label, value, sub, tone = 'neutral' }: { label: string; value: string | number; sub?: string; tone?: 'good' | 'bad' | 'neutral' }) {
  const color = tone === 'good' ? 'text-emerald-400' : tone === 'bad' ? 'text-red-400' : 'text-white';
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-lg p-4">
      <div className="text-xs uppercase tracking-wide text-slate-400">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${color}`}>{value}</div>
      {sub && <div className="text-xs text-slate-500 mt-0.5">{sub}</div>}
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-lg p-4">
      <div className="text-sm font-semibold mb-3 text-slate-200">{title}</div>
      {children}
    </div>
  );
}

function BucketTable({ rows, showWin = true }: { rows: Bucket[]; showWin?: boolean }) {
  if (!rows.length) return <div className="text-xs text-slate-500 py-2">No data yet</div>;
  const max = Math.max(...rows.map(r => r.total));
  return (
    <div className="space-y-1.5">
      {rows.map(r => (
        <div key={r.key} className="text-xs">
          <div className="flex justify-between mb-0.5">
            <span className="font-medium text-slate-300 truncate">{r.key}</span>
            <span className="text-slate-400 font-mono ml-2 shrink-0">
              {r.total}
              {showWin && r.verified > 0 && (
                <span className={`ml-2 ${r.win_rate! >= 55 ? 'text-emerald-400' : r.win_rate! >= 45 ? 'text-slate-400' : 'text-red-400'}`}>
                  {r.win_rate}% ({r.wins}/{r.verified})
                </span>
              )}
            </span>
          </div>
          <div className="h-1.5 bg-slate-800 rounded overflow-hidden">
            <div className="h-full bg-cyan-600" style={{ width: `${(r.total / max) * 100}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}
