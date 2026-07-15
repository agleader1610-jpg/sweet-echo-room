import { createFileRoute } from '@tanstack/react-router';
import { useServerFn } from '@tanstack/react-start';
import { useEffect, useState } from 'react';
import {
  adminLogin,
  adminLogout,
  adminStatus,
  getAdminDashboard,
  publishUpdate,
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
          <p className="text-sm text-slate-400 mb-6">App update control</p>
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

type Build = { runNumber: number; tag: string; apkUrl: string; publishedAt: string; name: string };

function Dashboard({ onLogout }: { onLogout: () => void }) {
  const load = useServerFn(getAdminDashboard);
  const save = useServerFn(publishUpdate);
  const [builds, setBuilds] = useState<Build[] | null>(null);
  const [minVersion, setMinVersion] = useState<number>(1);
  const [forceUpdate, setForceUpdate] = useState<boolean>(true);
  const [message, setMessage] = useState<string>('');
  const [savedMin, setSavedMin] = useState<number>(1);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState('');

  async function refresh() {
    const r = await load();
    setBuilds(r.builds);
    setMinVersion(r.config.min_version ?? 1);
    setSavedMin(r.config.min_version ?? 1);
    setForceUpdate(!!r.config.force_update);
    setMessage(r.config.update_message ?? '');
  }

  useEffect(() => { refresh().catch(() => setBuilds([])); }, []);

  async function onPublish(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true); setMsg('');
    try {
      await save({ data: {
        min_version: Number(minVersion) || 1,
        force_update: forceUpdate,
        update_message: message,
      }});
      setSavedMin(Number(minVersion) || 1);
      setMsg('✅ Published — সব পুরনো app এখন update prompt দেখবে');
    } catch (e: any) {
      setMsg('❌ ' + (e?.message || 'Failed'));
    } finally { setSaving(false); }
  }

  if (!builds) return <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center">Loading…</div>;

  const latest = builds[0];
  const dirty = minVersion !== savedMin;

  return (
    <div className="min-h-screen bg-slate-950 text-white">
      <header className="border-b border-slate-800 bg-slate-900/50 backdrop-blur">
        <div className="max-w-2xl mx-auto px-4 py-4 flex justify-between items-center">
          <h1 className="text-xl font-bold">App Update Control</h1>
          <button onClick={onLogout} className="text-sm text-slate-400 hover:text-white">Logout</button>
        </div>
      </header>

      <main className="max-w-2xl mx-auto px-4 py-8 space-y-6">
        {/* Latest APK card */}
        <section className="bg-slate-900 border border-slate-800 rounded-xl p-6">
          <div className="text-xs uppercase tracking-wide text-slate-400 mb-1">GitHub থেকে সর্বশেষ build</div>
          {latest ? (
            <>
              <div className="text-2xl font-bold mb-2">Build #{latest.runNumber}</div>
              <a href={`/api/public/download-apk/${latest.runNumber}`} target="_blank" rel="noreferrer"
                 className="inline-flex items-center gap-2 text-cyan-400 hover:text-cyan-300 text-sm break-all">
                📥 Download APK
              </a>
              <div className="text-xs text-slate-500 mt-2">
                Published: {new Date(latest.publishedAt).toLocaleString()}
              </div>
            </>
          ) : (
            <div className="text-slate-400 text-sm">
              কোনো GitHub release পাওয়া যায়নি। আগে code push করে APK build হতে দিন।
            </div>
          )}
          <button onClick={() => { setBuilds(null); refresh(); }}
                  className="mt-3 text-xs text-slate-400 hover:text-white">
            🔄 Refresh builds list
          </button>
        </section>

        {/* Publish card */}
        <form onSubmit={onPublish} className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-5">
          <div>
            <label className="block text-sm font-medium mb-1">Minimum required build</label>
            <p className="text-xs text-slate-400 mb-2">
              এর নিচের build চালানো user-দের update prompt দেখানো হবে। সাধারণত latest select করুন।
            </p>
            <select
              value={minVersion}
              onChange={(e) => setMinVersion(Number(e.target.value))}
              className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500"
            >
              {builds.length === 0 && <option value={1}>Build #1 (default)</option>}
              {builds.map((b) => (
                <option key={b.runNumber} value={b.runNumber}>
                  Build #{b.runNumber} {b.runNumber === latest?.runNumber ? '(latest)' : ''} — {new Date(b.publishedAt).toLocaleDateString()}
                </option>
              ))}
            </select>
            <div className="mt-1.5 text-xs">
              বর্তমানে saved: <b>Build #{savedMin}</b>
              {dirty && <span className="text-amber-400 ml-2">● unsaved</span>}
            </div>
          </div>

          <label className="flex items-start gap-3 cursor-pointer">
            <input type="checkbox" checked={forceUpdate}
                   onChange={(e) => setForceUpdate(e.target.checked)}
                   className="w-5 h-5 mt-0.5 accent-cyan-500" />
            <div>
              <div className="font-medium">Force update চালু</div>
              <div className="text-xs text-slate-400">On থাকলে পুরনো build block হবে। Off রাখলে কেউ কিছুই দেখবে না।</div>
            </div>
          </label>

          <div>
            <label className="block text-sm font-medium mb-1">Update message (users দেখবে)</label>
            <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={3}
                      className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500" />
          </div>

          <div className="flex items-center gap-3 pt-2">
            <button disabled={saving} type="submit"
                    className="rounded-md bg-cyan-600 hover:bg-cyan-500 disabled:opacity-50 px-6 py-2.5 font-semibold">
              {saving ? 'Publishing…' : '🚀 Publish update'}
            </button>
            {msg && <span className="text-sm">{msg}</span>}
          </div>
        </form>

        <div className="bg-slate-900/50 border border-slate-800 rounded-lg p-4 text-xs text-slate-400 leading-relaxed">
          <b className="text-slate-300">কীভাবে কাজ করে:</b><br />
          1. Code push করলে GitHub Actions নতুন APK build করে (Build #1, #2, #3…)।<br />
          2. প্রতি APK-এর ভেতরে ওই build number automatic বসে যায়।<br />
          3. App launch-এ এই server-কে জিজ্ঞেস করে। আপনি এখানে "Minimum required" যেটা set করবেন — এর নিচের সব build-এর user block screen দেখবে ও Download button-এ latest APK পাবে।<br />
          4. Rollback করতে চাইলে minimum আবার আগের build-এ নামিয়ে Publish করুন।
        </div>
      </main>
    </div>
  );
}
