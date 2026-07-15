import { createServerFn } from '@tanstack/react-start';
import { useSession } from '@tanstack/react-start/server';
import { createHash, timingSafeEqual } from 'node:crypto';
import { fetchBuildsFromGithub, type Build } from '@/lib/github-releases';

type GateSession = { unlocked?: boolean };

function sessionConfig() {
  return {
    password: process.env.SESSION_SECRET!,
    name: 'admin-gate',
    maxAge: 60 * 60 * 24 * 7,
    cookie: { httpOnly: true, secure: true, sameSite: 'lax' as const, path: '/' },
  };
}

function matches(input: string, expected: string): boolean {
  const a = createHash('sha256').update(input, 'utf8').digest();
  const b = createHash('sha256').update(expected, 'utf8').digest();
  return timingSafeEqual(a, b);
}

async function requireUnlocked() {
  const session = await useSession<GateSession>(sessionConfig());
  if (!session.data.unlocked) throw new Error('Unauthorized');
  return session;
}

export const adminLogin = createServerFn({ method: 'POST' })
  .inputValidator((data: { password: string }) => data)
  .handler(async ({ data }) => {
    const expected = process.env.SITE_PASSWORD;
    if (!expected) throw new Error('SITE_PASSWORD not set');
    if (!matches(data.password, expected)) return { ok: false as const };
    const session = await useSession<GateSession>(sessionConfig());
    await session.update({ unlocked: true });
    return { ok: true as const };
  });

export const adminLogout = createServerFn({ method: 'POST' }).handler(async () => {
  const session = await useSession<GateSession>(sessionConfig());
  await session.clear();
  return { ok: true as const };
});

export const adminStatus = createServerFn({ method: 'GET' }).handler(async () => {
  const session = await useSession<GateSession>(sessionConfig());
  return { unlocked: !!session.data.unlocked };
});

// Returns the current config + live list of GitHub build releases.
// Admin picks a `min_version` from the dropdown; no typing needed.
export const getAdminDashboard = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('app_config')
    .select('min_version,force_update,update_message,updated_at')
    .eq('id', 1)
    .maybeSingle();
  if (error) throw error;
  const builds: Build[] = await fetchBuildsFromGithub().catch(() => []);
  return {
    config: data ?? { min_version: 1, force_update: true, update_message: '', updated_at: null },
    builds,
  };
});

export const publishUpdate = createServerFn({ method: 'POST' })
  .inputValidator((d: {
    min_version: number;
    force_update: boolean;
    update_message: string;
  }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    // Keep the legacy latest_version / apk_url columns in sync so anything
    // still reading them stays consistent, even though the public endpoint
    // now pulls those from GitHub live.
    const builds = await fetchBuildsFromGithub().catch(() => []);
    const latest = builds[0];
    const { error } = await supabaseAdmin
      .from('app_config')
      .update({
        min_version: data.min_version,
        force_update: data.force_update,
        update_message: data.update_message,
        latest_version: latest?.runNumber ?? data.min_version,
        latest_version_name: latest ? `1.0.${latest.runNumber}` : '1.0',
        apk_url: latest ? `/api/public/download-apk/${latest.runNumber}` : '',
        updated_at: new Date().toISOString(),
      })
      .eq('id', 1);
    if (error) throw error;
    return { ok: true };
  });
