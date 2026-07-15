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

  if (loading) return <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center">Loading…</div>;
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
        <section className="bg-slate-900 border border-slate-800 rounded-xl p-6">
          <div className="text-xs uppercase tracking-wide text-slate-400 mb-1">Saved update target</div>
          <div className="text-2xl font-bold mb-2">Version code {latestVersion || minVersion}</div>
          {apkUrl ? (
            <a href={apkUrl} target="_blank" rel="noreferrer"
               className="inline-flex items-center gap-2 text-cyan-400 hover:text-cyan-300 text-sm break-all">
              📥 Test APK link
            </a>
          ) : (
            <div className="text-slate-400 text-sm">এখনো APK download URL দেওয়া হয়নি।</div>
          )}
          <button onClick={() => { refresh(); }}
                  className="mt-3 text-xs text-slate-400 hover:text-white">
            🔄 Refresh saved config
          </button>
        </section>

        {/* Publish card */}
        <form onSubmit={onPublish} className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-5">
          <div>
            <label className="block text-sm font-medium mb-1">Minimum required version code</label>
            <p className="text-xs text-slate-400 mb-2">
              app-এর current version code এর নিচে হলে user-কে update করতে বলবে।
            </p>
            <input
              type="number"
              min={1}
              value={minVersion}
              onChange={(e) => setMinVersion(Number(e.target.value))}
              className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500"
            />
            <div className="mt-1.5 text-xs">
              বর্তমানে saved: <b>Version code {savedMin}</b>
              {dirty && <span className="text-amber-400 ml-2">● unsaved</span>}
            </div>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-medium mb-1">Latest version code</label>
              <input
                type="number"
                min={1}
                value={latestVersion}
                onChange={(e) => setLatestVersion(Number(e.target.value))}
                className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Latest version name</label>
              <input
                value={latestName}
                onChange={(e) => setLatestName(e.target.value)}
                placeholder="1.0.15"
                className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">APK download URL</label>
            <input
              type="url"
              value={apkUrl}
              onChange={(e) => setApkUrl(e.target.value)}
              placeholder="https://example.com/APKBid.apk"
              className="w-full rounded-md bg-slate-800 border border-slate-700 px-3 py-2 outline-none focus:border-cyan-500"
            />
          </div>

          <label className="flex items-start gap-3 cursor-pointer">
            <input type="checkbox" checked={forceUpdate}
                   onChange={(e) => setForceUpdate(e.target.checked)}
                   className="w-5 h-5 mt-0.5 accent-cyan-500" />
            <div>
              <div className="font-medium">Force update চালু</div>
              <div className="text-xs text-slate-400">On থাকলে পুরনো version block হবে। Off রাখলে app normal চলবে।</div>
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
          1. APK build করার আগে app-এর version code/version name manually ঠিক করুন।<br />
          2. APK ready হলে এখানে latest version code/name এবং download URL manually save করুন।<br />
          3. App launch-এ এই saved তথ্য পড়ে। current version code minimum-এর নিচে হলে update dialog দেখাবে।<br />
          4. Rollback/disable করতে চাইলে Force update off করুন বা minimum version কমিয়ে Save করুন।
        </div>
      </main>
    </div>
  );
}
