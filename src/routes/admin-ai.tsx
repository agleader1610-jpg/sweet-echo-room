import { createFileRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { useServerFn } from '@tanstack/react-start';
import { adminLogin, adminStatus, getAiConfig, updateAiConfig } from '@/lib/admin-gate.functions';

export const Route = createFileRoute('/admin-ai')({
  head: () => ({
    meta: [
      { title: 'AI Config — Admin' },
      { name: 'description', content: 'Live-edit Aviator AI model and prompts without rebuilding the app.' },
      { name: 'robots', content: 'noindex, nofollow' },
    ],
  }),
  component: AdminAiPage,
});

function AdminAiPage() {
  const status = useServerFn(adminStatus);
  const login = useServerFn(adminLogin);
  const load = useServerFn(getAiConfig);
  const save = useServerFn(updateAiConfig);

  const [ready, setReady] = useState(false);
  const [unlocked, setUnlocked] = useState(false);
  const [pw, setPw] = useState('');
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState('');

  const [model, setModel] = useState('google/gemini-3-flash-preview');
  const [timeoutMs, setTimeoutMs] = useState(4000);
  const [sysNormal, setSysNormal] = useState('');
  const [sysRisk, setSysRisk] = useState('');
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const s = await status();
      setUnlocked(!!s.unlocked);
      setReady(true);
      if (s.unlocked) await refresh();
    })();
     
  }, []);

  async function refresh() {
    const r = await load();
    const c = r.config as any;
    setModel(c.model ?? 'google/gemini-3-flash-preview');
    setTimeoutMs(Number(c.timeout_ms) || 4000);
    setSysNormal(c.sys_normal ?? '');
    setSysRisk(c.sys_risk ?? '');
    setUpdatedAt(c.updated_at ?? null);
  }

  async function doLogin(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    const r = await login({ data: { password: pw } });
    if (r.ok) { setUnlocked(true); await refresh(); }
    else setErr('ভুল password');
  }

  async function doSave() {
    setSaving(true); setMsg('');
    try {
      await save({ data: { model, timeout_ms: timeoutMs, sys_normal: sysNormal, sys_risk: sysRisk } });
      setMsg('✅ Saved — নতুন config ৬০ সেকেন্ডের মধ্যে সব request-এ live হবে।');
      await refresh();
    } catch (e: any) {
      setMsg('❌ ' + (e?.message ?? 'save failed'));
    } finally {
      setSaving(false);
    }
  }

  if (!ready) return <div className="min-h-screen bg-slate-950 text-slate-200 grid place-items-center">Loading…</div>;

  if (!unlocked) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-200 grid place-items-center p-4">
        <form onSubmit={doLogin} className="w-full max-w-sm bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
          <h1 className="text-xl font-semibold">Admin Login</h1>
          <input
            type="password"
            autoComplete="current-password"
            value={pw}
            onChange={(e) => setPw(e.target.value)}
            className="w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2"
            placeholder="Password"
          />
          {err && <p className="text-red-400 text-sm">{err}</p>}
          <button className="w-full rounded-lg bg-cyan-500 text-slate-950 font-semibold py-2">Unlock</button>
        </form>
      </div>
    );
  }

  const models = [
    'google/gemini-3-flash-preview',
    'google/gemini-3.1-flash-lite',
    'google/gemini-3.5-flash',
    'google/gemini-2.5-flash',
    'google/gemini-2.5-flash-lite',
    'google/gemini-2.5-pro',
    'openai/gpt-5.4-nano',
    'openai/gpt-5.4-mini',
    'openai/gpt-5.4',
    'openai/gpt-5-mini',
    'openai/gpt-5-nano',
  ];

  return (
    <div className="min-h-screen bg-slate-950 text-slate-200 p-4 md:p-8">
      <div className="max-w-4xl mx-auto space-y-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">🤖 AI Live Config</h1>
            <p className="text-sm text-slate-400">Model & prompts এখান থেকে বদলাও — APK rebuild লাগবে না। Cache TTL ৬০ সেকেন্ড।</p>
          </div>
          {updatedAt && <span className="text-xs text-slate-500">Last saved: {new Date(updatedAt).toLocaleString()}</span>}
        </header>

        <section className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
          <div className="grid md:grid-cols-2 gap-4">
            <label className="block">
              <span className="text-sm text-slate-400">Model</span>
              <input
                list="model-list"
                value={model}
                onChange={(e) => setModel(e.target.value)}
                className="mt-1 w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2 font-mono text-sm"
              />
              <datalist id="model-list">
                {models.map(m => <option key={m} value={m} />)}
              </datalist>
            </label>
            <label className="block">
              <span className="text-sm text-slate-400">Timeout (ms) — 1500–30000</span>
              <input
                type="number"
                min={1500}
                max={30000}
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(Number(e.target.value))}
                className="mt-1 w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2"
              />
            </label>
          </div>

          <label className="block">
            <span className="text-sm text-slate-400">System Prompt — NORMAL mode</span>
            <textarea
              value={sysNormal}
              onChange={(e) => setSysNormal(e.target.value)}
              rows={8}
              className="mt-1 w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2 text-sm font-mono"
            />
          </label>

          <label className="block">
            <span className="text-sm text-slate-400">System Prompt — RISK (RECOVERY) mode</span>
            <textarea
              value={sysRisk}
              onChange={(e) => setSysRisk(e.target.value)}
              rows={10}
              className="mt-1 w-full rounded-lg bg-slate-800 border border-slate-700 px-3 py-2 text-sm font-mono"
            />
          </label>

          <div className="flex items-center gap-3">
            <button
              onClick={doSave}
              disabled={saving}
              className="rounded-lg bg-cyan-500 text-slate-950 font-semibold px-5 py-2 disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save & Go Live'}
            </button>
            <button
              onClick={refresh}
              className="rounded-lg border border-slate-700 px-4 py-2 text-sm"
            >
              Reload
            </button>
            {msg && <span className="text-sm">{msg}</span>}
          </div>
        </section>

        <p className="text-xs text-slate-500">
          Tip: model id-টা catalog থেকে নাও (`google/…` বা `openai/…`)। ভুল id দিলে prediction endpoint local fallback-এ যাবে এবং <code>ai_error</code>-এ দেখাবে।
        </p>
      </div>
    </div>
  );
}
