import { createServerFn } from "@tanstack/react-start";
import { getRequestHeader, getRequestIP, useSession } from "@tanstack/react-start/server";

// ---- Visit logging ---------------------------------------------------------

// Public: fire-and-forget visit logging. No user input trusted; we only take
// the path from the client and read IP/UA/referer from request headers.
export const logVisit = createServerFn({ method: "POST" })
  .inputValidator((data: { path?: string }) => ({
    path: typeof data?.path === "string" ? data.path.slice(0, 500) : null,
  }))
  .handler(async ({ data }) => {
    try {
      const ip =
        getRequestIP({ xForwardedFor: true }) ??
        getRequestHeader("cf-connecting-ip") ??
        null;
      const userAgent = getRequestHeader("user-agent") ?? null;
      const referer = getRequestHeader("referer") ?? null;
      const country = getRequestHeader("cf-ipcountry") ?? null;

      const { supabaseAdmin } = await import("@/integrations/supabase/client.server");
      await supabaseAdmin.from("visits").insert({
        ip,
        user_agent: userAgent ? userAgent.slice(0, 500) : null,
        path: data.path,
        referer: referer ? referer.slice(0, 500) : null,
        country,
      });
    } catch (err) {
      console.error("logVisit failed", err);
    }
    return { ok: true };
  });

// ---- Admin session (encrypted httpOnly cookie) -----------------------------

type StatsSession = { unlocked?: boolean; iat?: number };

function sessionConfig() {
  const password = process.env.STATS_SESSION_SECRET;
  if (!password || password.length < 32) {
    throw new Error("STATS_SESSION_SECRET not configured");
  }
  return {
    password,
    name: "stat-session",
    maxAge: 60 * 60 * 8, // 8 hours
    cookie: {
      httpOnly: true,
      secure: true,
      sameSite: "lax" as const,
      path: "/",
    },
  };
}

function tokenMatches(input: string): boolean {
  const expected = process.env.ADMIN_STATS_TOKEN;
  if (!expected || !input) return false;
  if (input.length !== expected.length) return false;
  let mismatch = 0;
  for (let i = 0; i < input.length; i++) {
    mismatch |= input.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return mismatch === 0;
}

// Exchange admin token for a session cookie. Token is never stored in the
// browser — only the encrypted, httpOnly session cookie is.
export const unlockStats = createServerFn({ method: "POST" })
  .inputValidator((data: { token: string }) => ({ token: String(data?.token ?? "") }))
  .handler(async ({ data }) => {
    if (!tokenMatches(data.token)) {
      // Small delay to slow down brute-force from the same client.
      await new Promise((r) => setTimeout(r, 400));
      return { ok: false as const };
    }
    const session = await useSession<StatsSession>(sessionConfig());
    await session.update({ unlocked: true, iat: Math.floor(Date.now() / 1000) });
    return { ok: true as const };
  });

export const lockStats = createServerFn({ method: "POST" }).handler(async () => {
  const session = await useSession<StatsSession>(sessionConfig());
  await session.clear();
  return { ok: true as const };
});

export const isStatsUnlocked = createServerFn({ method: "GET" }).handler(async () => {
  const session = await useSession<StatsSession>(sessionConfig());
  return { unlocked: session.data.unlocked === true };
});

async function requireUnlocked() {
  const session = await useSession<StatsSession>(sessionConfig());
  if (!session.data.unlocked) throw new Error("Unauthorized");
}

// getStats now reads the session cookie — client never sends a token.
export const getStats = createServerFn({ method: "POST" }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import("@/integrations/supabase/client.server");

  const now = new Date();
  const dayAgo = new Date(now.getTime() - 24 * 3600 * 1000).toISOString();
  const weekAgo = new Date(now.getTime() - 7 * 24 * 3600 * 1000).toISOString();

  const [
    { count: totalVisits },
    { count: last24h },
    { count: last7d },
    { data: recent },
    { data: allIps },
  ] = await Promise.all([
    supabaseAdmin.from("visits").select("*", { count: "exact", head: true }),
    supabaseAdmin.from("visits").select("*", { count: "exact", head: true }).gte("created_at", dayAgo),
    supabaseAdmin.from("visits").select("*", { count: "exact", head: true }).gte("created_at", weekAgo),
    supabaseAdmin
      .from("visits")
      .select("id, ip, user_agent, path, referer, country, created_at")
      .order("created_at", { ascending: false })
      .limit(100),
    supabaseAdmin.from("visits").select("ip").not("ip", "is", null).limit(10000),
  ]);

  const uniqueIps = new Set((allIps ?? []).map((r) => r.ip)).size;

  const ipCounts = new Map<string, number>();
  for (const row of allIps ?? []) {
    if (!row.ip) continue;
    ipCounts.set(row.ip, (ipCounts.get(row.ip) ?? 0) + 1);
  }
  const topIps = Array.from(ipCounts.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([ip, count]) => ({ ip, count }));

  return {
    totalVisits: totalVisits ?? 0,
    last24h: last24h ?? 0,
    last7d: last7d ?? 0,
    uniqueIps,
    recent: recent ?? [],
    topIps,
  };
});
