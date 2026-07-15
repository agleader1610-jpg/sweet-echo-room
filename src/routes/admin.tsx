import { createFileRoute } from '@tanstack/react-router';
import { useServerFn } from '@tanstack/react-start';
import { useEffect, useState } from 'react';
import {
  adminLogin,
  adminLogout,
  adminStatus,
  getAppConfig,
  updateAppConfig,
} from '@/lib/admin-gate.functions';

export const Route = createFileRoute('/admin')({
  head: () => ({ meta: [{ title: 'Admin Panel — App Version Control' }, { name: 'robots', content: 'noindex' }] }),
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
          <p className="text-sm text-slate-400 mb-6">App version control</p>
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
  const load = useServerFn(getAppConfig);
  const save = useServerFn(updateAppConfig);
  const [cfg, setCfg] = useState<any>(null);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState('');

  useEffect(() => { load().then(setCfg).catch(() => setCfg({})); }, [load]);

  async function onSave(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true); setMsg('');
    try {
      await save({ data: {
        min_version: Number(cfg.min_version) || 1,
        latest_version: Number(cfg.latest_version) || 1,
        latest_version_name: String(cfg.latest_version_name || '1.0'),
        apk_url: String(cfg.apk_url || ''),
        update_message: String(cfg.update_message || ''),
        force_update: !!cfg.force_update,
      }});
      setMsg('✅ Saved — সব app এখন নতুন rule পাবে');
    } catch (e: any) {
      setMsg('❌ ' + (e?.message || 'Save failed'));
    } finally { setSaving(false); }
  }

  if (!cfg) return <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center">Loading config…</div>;

  return (
    <div className="min-h-screen bg-slate-950 text-white">
      <header className="border-b border-slate-800 bg-slate-900/50 backdrop-blur">
        <div className="max-w-2xl mx-auto px-4 py-4 flex justify-between items-center">
          <h1 className="text-xl font-bold">App Version Control</h1>
          <button onClick={onLogout} className="text-sm text-slate-400 hover:text-white">Logout</button>
        </div>
      </header>
      <main className="max-w-2xl mx-auto px-4 py-8">
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-4 mb-6 text-sm">
          <b>কীভাবে কাজ করে:</b> App প্রতি launch-এ এই config check করে। যদি app-এর <code className="bg-slate-800 px-1 rounded">versionCode</code> <code className="bg-slate-800 px-1 rounded">min_version</code>-এর চেয়ে ছোট হয় এবং <b>Force update</b> on থাকে, তাহলে app block screen দেখাবে ও APK link দিবে।
        </div>

        <form onSubmit={onSave} className="space-y-5 bg-slate-900 border border-slate-800 rounded-xl p-6">
          <Field label="Minimum required version (integer)" hint="এর নিচের versionCode-এর app block হবে">
            <input type="number" value={cfg.min_version ?? 1} onChange={(e) => setCfg({ ...cfg, min_version: e.target.value })} className={inputCls} />
          </Field>
          <Field label="Latest version code" hint="বর্তমান latest APK-এর versionCode">
            <input type="number" value={cfg.latest_version ?? 1} onChange={(e) => setCfg({ ...cfg, latest_version: e.target.value })} className={inputCls} />
          </Field>
          <Field label="Latest version name" hint="যেমন 1.2, 2.0.1">
            <input type="text" value={cfg.latest_version_name ?? '1.0'} onChange={(e) => setCfg({ ...cfg, latest_version_name: e.target.value })} className={inputCls} />
          </Field>
          <Field label="APK download URL" hint="Users এখান থেকে নতুন APK download করবে (GitHub release / Drive / server link)">
            <input type="url" value={cfg.apk_url ?? ''} onChange={(e) => setCfg({ ...cfg, apk_url: e.target.value })} placeholder="https://..." className={inputCls} />
          </Field>
          <Field label="Update message (users দেখবে)">
            <textarea value={cfg.update_message ?? ''} onChange={(e) => setCfg({ ...cfg, update_message: e.target.value })} rows={3} className={inputCls} />
          </Field>
          <label className="flex items-center gap-3 cursor-pointer">
            <input type="checkbox" checked={!!cfg.force_update} onChange={(e) => setCfg({ ...cfg, force_update: e.target.checked })} className="w-5 h-5 accent-cyan-500" />
            <div>
              <div className="font-medium">Force update chalu</div>
              <div className="text-xs text-slate-400">On থাকলে পুরনো app block হবে। Off রাখলে কিছুই হবে না।</div>
            </div>
          </label>

          <div className="flex items-center gap-3 pt-2">
            <button disabled={saving} type="submit" className="rounded-md bg-cyan-600 hover:bg-cyan-500 disabled:opacity-50 px-5 py-2 font-medium">
              {saving ? 'Saving…' : 'Save & Push to all apps'}
            </button>
            {msg && <span className="text-sm">{msg}</span>}
          </div>
        </form>
      </main>
    </div>
  );
}

const inputCls = 'w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500';

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1">{label}</label>
      {hint && <p className="text-xs text-slate-400 mb-1.5">{hint}</p>}
      {children}
    </div>
  );
}
