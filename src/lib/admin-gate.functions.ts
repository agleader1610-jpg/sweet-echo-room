import { createServerFn } from '@tanstack/react-start';
import { useSession } from '@tanstack/react-start/server';
import { createHash, timingSafeEqual } from 'node:crypto';

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

export const getAdminDashboard = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('app_config')
    .select('min_version,latest_version,latest_version_name,apk_url,force_update,update_message,updated_at')
    .eq('id', 1)
    .maybeSingle();
  if (error) throw error;
  return {
    config: data ?? {
      min_version: 1,
      latest_version: 1,
      latest_version_name: '1.0.1',
      apk_url: '',
      force_update: true,
      update_message: '',
      updated_at: null,
    },
  };
});

export const publishUpdate = createServerFn({ method: 'POST' })
  .inputValidator((d: {
    min_version: number;
    latest_version: number;
    latest_version_name: string;
    apk_url: string;
    force_update: boolean;
    update_message: string;
  }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('app_config')
      .update({
        min_version: Math.max(1, Math.floor(Number(data.min_version) || 1)),
        latest_version: Math.max(1, Math.floor(Number(data.latest_version) || 1)),
        latest_version_name: String(data.latest_version_name || '').trim(),
        apk_url: String(data.apk_url || '').trim(),
        force_update: data.force_update,
        update_message: data.update_message,
        updated_at: new Date().toISOString(),
      })
      .eq('id', 1);
    if (error) throw error;
    return { ok: true };
  });
