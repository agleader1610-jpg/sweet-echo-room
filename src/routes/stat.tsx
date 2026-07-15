import { createFileRoute } from "@tanstack/react-router";
import { useServerFn } from "@tanstack/react-start";
import { useCallback, useEffect, useState } from "react";
import { getStats, isStatsUnlocked, lockStats, unlockStats } from "@/lib/stats.functions";

export const Route = createFileRoute("/stat")({
  head: () => ({
    meta: [
      { title: "Admin Stats" },
      { name: "robots", content: "noindex, nofollow" },
    ],
  }),
  component: StatPage,
});

type Stats = Awaited<ReturnType<typeof getStats>>;

function StatPage() {
  const fetchStats = useServerFn(getStats);
  const unlock = useServerFn(unlockStats);
  const lock = useServerFn(lockStats);
  const checkUnlocked = useServerFn(isStatsUnlocked);

  const [token, setToken] = useState("");
  const [unlocked, setUnlocked] = useState<boolean | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const loadStats = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await fetchStats();
      setStats(r);
    } catch {
      setError("Session expired. Please unlock again.");
      setUnlocked(false);
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, [fetchStats]);

  // Check session on mount (server-side cookie check)
  useEffect(() => {
    checkUnlocked()
      .then((r) => setUnlocked(r.unlocked))
      .catch(() => setUnlocked(false));
  }, [checkUnlocked]);

  useEffect(() => {
    if (unlocked) void loadStats();
  }, [unlocked, loadStats]);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const value = token.trim();
    if (!value || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const r = await unlock({ data: { token: value } });
      if (r.ok) {
        setToken(""); // wipe token from memory immediately
        setUnlocked(true);
      } else {
        setError("Invalid token.");
        setToken("");
      }
    } catch {
      setError("Server error.");
    } finally {
      setSubmitting(false);
    }
  };

  const onSignOut = async () => {
    try {
      await lock();
    } catch {
      /* ignore */
    }
    setUnlocked(false);
    setStats(null);
  };

  if (unlocked === null) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        Loading…
      </div>
    );
  }

  if (!unlocked) {
    return (
      <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
        <h1 className="text-2xl font-bold text-foreground">Admin Access</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Enter the admin token to view visitor stats. The token is verified on the
          server and never stored in your browser.
        </p>
        <form onSubmit={onSubmit} className="mt-6 space-y-3" autoComplete="off">
          <input
            type="password"
            name="admin-token"
            autoFocus
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="Admin token"
            autoComplete="off"
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground outline-none focus:ring-2 focus:ring-primary"
          />
          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-md bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-60"
          >
            {submitting ? "Verifying…" : "Unlock"}
          </button>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </form>
      </div>
    );
  }

  if (loading || !stats) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        Loading stats…
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-foreground">Visitor Stats</h1>
        <button
          onClick={onSignOut}
          className="rounded-md border border-input px-3 py-1.5 text-xs font-medium hover:bg-accent"
        >
          Sign out
        </button>
      </div>

      <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard label="Total visits" value={stats.totalVisits} />
        <StatCard label="Last 24h" value={stats.last24h} />
        <StatCard label="Last 7 days" value={stats.last7d} />
        <StatCard label="Unique IPs" value={stats.uniqueIps} />
      </div>

      <section className="mt-8">
        <h2 className="text-lg font-semibold text-foreground">Top IPs</h2>
        <div className="mt-3 overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted text-left text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-3 py-2">IP</th>
                <th className="px-3 py-2 text-right">Visits</th>
              </tr>
            </thead>
            <tbody>
              {stats.topIps.map((row) => (
                <tr key={row.ip} className="border-t border-border">
                  <td className="px-3 py-2 font-mono text-xs">{row.ip}</td>
                  <td className="px-3 py-2 text-right">{row.count}</td>
                </tr>
              ))}
              {stats.topIps.length === 0 && (
                <tr>
                  <td colSpan={2} className="px-3 py-6 text-center text-muted-foreground">
                    No visits yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="mt-8">
        <h2 className="text-lg font-semibold text-foreground">Recent visits (last 100)</h2>
        <div className="mt-3 overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted text-left text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-3 py-2">Time</th>
                <th className="px-3 py-2">IP</th>
                <th className="px-3 py-2">Country</th>
                <th className="px-3 py-2">Path</th>
                <th className="px-3 py-2">User Agent</th>
              </tr>
            </thead>
            <tbody>
              {stats.recent.map((r) => (
                <tr key={r.id} className="border-t border-border">
                  <td className="whitespace-nowrap px-3 py-2 text-xs">
                    {new Date(r.created_at).toLocaleString()}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">{r.ip ?? "—"}</td>
                  <td className="px-3 py-2 text-xs">{r.country ?? "—"}</td>
                  <td className="px-3 py-2 text-xs">{r.path ?? "—"}</td>
                  <td className="max-w-xs truncate px-3 py-2 text-xs text-muted-foreground" title={r.user_agent ?? ""}>
                    {r.user_agent ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-bold text-foreground">{value.toLocaleString()}</div>
    </div>
  );
}
