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
  const session = await useSession<GateSession>(sessionConfig());
  if (!session.data.unlocked) return { unauthorized: true as const };
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('app_config')
    .select('min_version,latest_version,latest_version_name,apk_url,force_update,update_message,updated_at')
    .eq('id', 1)
    .maybeSingle();
  if (error) throw error;
  return {
    unauthorized: false as const,
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

// ---------- License Gate ----------

export const getLicenseSettings = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data: cfg } = await supabaseAdmin
    .from('app_config')
    .select('required_parent_invite_code, lock_message')
    .eq('id', 1)
    .maybeSingle();
  const { data: uids } = await supabaseAdmin
    .from('licensed_uids')
    .select('uid, note, created_at')
    .order('created_at', { ascending: false })
    .limit(500);
  return {
    required_parent_invite_code: cfg?.required_parent_invite_code ?? '82824101415',
    lock_message: cfg?.lock_message ?? '',
    uids: uids ?? [],
  };
});

export const updateLicenseSettings = createServerFn({ method: 'POST' })
  .inputValidator((d: { required_parent_invite_code: string; lock_message: string }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('app_config')
      .update({
        required_parent_invite_code: String(data.required_parent_invite_code || '').trim(),
        lock_message: String(data.lock_message || '').trim(),
        updated_at: new Date().toISOString(),
      })
      .eq('id', 1);
    if (error) throw error;
    return { ok: true };
  });

export const addLicensedUid = createServerFn({ method: 'POST' })
  .inputValidator((d: { uid: string; note?: string }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const uid = String(data.uid || '').trim();
    if (!uid) throw new Error('UID required');
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('licensed_uids')
      .upsert({ uid, note: data.note ? String(data.note).slice(0, 500) : null });
    if (error) throw error;
    return { ok: true };
  });

export const removeLicensedUid = createServerFn({ method: 'POST' })
  .inputValidator((d: { uid: string }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('licensed_uids')
      .delete()
      .eq('uid', String(data.uid || '').trim());
    if (error) throw error;
    return { ok: true };
  });

// ---------- Deposit Gate (per-game) ----------

const DEPOSIT_GAMES = ['aviator', 'wingo', 'wingo_auto'] as const;
type DepositGame = (typeof DEPOSIT_GAMES)[number];
function normGame(v: unknown): DepositGame {
  const s = String(v ?? '').toLowerCase();
  return (DEPOSIT_GAMES as readonly string[]).includes(s) ? (s as DepositGame) : 'aviator';
}

export const getDepositSettings = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const cols =
    'aviator_lifetime_min_deposits, aviator_recency_days, aviator_recency_min_deposits,' +
    ' wingo_lifetime_min_deposits, wingo_recency_days, wingo_recency_min_deposits,' +
    ' wingo_auto_lifetime_min_deposits, wingo_auto_recency_days, wingo_auto_recency_min_deposits';
  const { data: cfg } = await supabaseAdmin
    .from('app_config').select(cols).eq('id', 1).maybeSingle();
  const { data: rules } = await supabaseAdmin
    .from('user_deposit_rules')
    .select('id, uid, game, rule_type, days_required, lifetime_min_deposits, recency_days, recency_min_deposits, note, updated_at')
    .order('updated_at', { ascending: false })
    .limit(500);
  const { data: recent } = await supabaseAdmin
    .from('deposit_gate_log')
    .select('uid, game, allowed, reason, rule_source, required_days, lifetime_count, recent_count, last_deposit_at, checked_at')
    .order('checked_at', { ascending: false })
    .limit(50);
  const c: any = cfg ?? {};
  const globals = DEPOSIT_GAMES.reduce((acc, g) => {
    acc[g] = {
      lifetime_min: Number(c[`${g}_lifetime_min_deposits`]) || 0,
      recency_days: Number(c[`${g}_recency_days`]) || 0,
      recency_min:  Math.max(1, Number(c[`${g}_recency_min_deposits`]) || 1),
    };
    return acc;
  }, {} as Record<DepositGame, { lifetime_min: number; recency_days: number; recency_min: number }>);
  return { globals, rules: rules ?? [], recent: recent ?? [] };
});

export const updateDepositGlobal = createServerFn({ method: 'POST' })
  .inputValidator((d: {
    game: string;
    lifetime_min: number;
    recency_days: number;
    recency_min: number;
  }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const game = normGame(data.game);
    const lifetimeMin = Math.max(0, Math.min(100, Math.floor(Number(data.lifetime_min) || 0)));
    const recencyDays = Math.max(0, Math.min(365, Math.floor(Number(data.recency_days) || 0)));
    const recencyMin  = Math.max(1, Math.min(100, Math.floor(Number(data.recency_min) || 1)));
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const patch: Record<string, unknown> = {
      updated_at: new Date().toISOString(),
      [`${game}_lifetime_min_deposits`]: lifetimeMin,
      [`${game}_recency_days`]: recencyDays,
      [`${game}_recency_min_deposits`]: recencyMin,
    };
    const { error } = await supabaseAdmin.from('app_config').update(patch as never).eq('id', 1);
    if (error) throw error;
    return { ok: true, game, lifetime_min: lifetimeMin, recency_days: recencyDays, recency_min: recencyMin };
  });

export const upsertDepositRule = createServerFn({ method: 'POST' })
  .inputValidator((d: {
    uid: string;
    game?: string | null;
    rule_type: 'unlimited' | 'days' | 'blocked';
    lifetime_min?: number;
    recency_days?: number;
    recency_min?: number;
    note?: string;
  }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const uid = String(data.uid || '').trim();
    if (!uid) throw new Error('UID required');
    if (!['unlimited', 'days', 'blocked'].includes(data.rule_type)) throw new Error('invalid rule_type');
    const game = data.game ? normGame(data.game) : null; // null = applies to all games
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

    const row: Record<string, unknown> = {
      uid,
      game,
      rule_type: data.rule_type,
      note: data.note ? String(data.note).slice(0, 500) : null,
      updated_at: new Date().toISOString(),
    };
    if (data.rule_type === 'days') {
      row.lifetime_min_deposits = Math.max(0, Math.min(100, Math.floor(Number(data.lifetime_min) || 0)));
      row.recency_days = Math.max(0, Math.min(365, Math.floor(Number(data.recency_days) || 0)));
      row.recency_min_deposits = Math.max(1, Math.min(100, Math.floor(Number(data.recency_min) || 1)));
      row.days_required = row.recency_days; // legacy mirror
    } else {
      row.lifetime_min_deposits = null;
      row.recency_days = null;
      row.recency_min_deposits = null;
      row.days_required = 0;
    }

    // Manual "upsert by (uid, game)" — Supabase upsert can't target the
    // partial expression index we created (COALESCE(game,'*')).
    const q = supabaseAdmin.from('user_deposit_rules').select('id').eq('uid', uid);
    const { data: existing } = game === null
      ? await q.is('game', null).maybeSingle()
      : await q.eq('game', game).maybeSingle();

    if (existing?.id) {
      const { error } = await supabaseAdmin
        .from('user_deposit_rules').update(row as never).eq('id', existing.id);
      if (error) throw error;
    } else {
      const { error } = await supabaseAdmin.from('user_deposit_rules').insert(row as never);
      if (error) throw error;
    }
    return { ok: true };
  });

export const removeDepositRule = createServerFn({ method: 'POST' })
  .inputValidator((d: { id?: number; uid?: string; game?: string | null }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    if (data.id) {
      const { error } = await supabaseAdmin.from('user_deposit_rules').delete().eq('id', data.id);
      if (error) throw error;
      return { ok: true };
    }
    const uid = String(data.uid || '').trim();
    if (!uid) throw new Error('id or uid required');
    let q = supabaseAdmin.from('user_deposit_rules').delete().eq('uid', uid);
    if (data.game === null || data.game === undefined) q = q.is('game', null);
    else q = q.eq('game', normGame(data.game));
    const { error } = await q;
    if (error) throw error;
    return { ok: true };
  });

// ---------- Prediction Analytics ----------


type Row = {
  id: number;
  device_id: string | null;
  round_id: number;
  target_multiplier: number | null;
  action: string;
  confidence: string | null;
  source: string;
  reasoning: string | null;
  ai_model: string | null;
  latency_ms: number | null;
  created_at: string;
  result: { actual_multiplier: number; outcome: string } | null;
};

function targetBucket(t: number | null): string {
  if (t == null) return 'skip';
  if (t < 1.5) return '1.0-1.5x';
  if (t < 2) return '1.5-2x';
  if (t < 3) return '2-3x';
  if (t < 5) return '3-5x';
  if (t < 10) return '5-10x';
  return '10x+';
}
function crashBucket(m: number): string {
  if (m < 1.5) return '1.0-1.5x (pink)';
  if (m < 2) return '1.5-2x';
  if (m < 3) return '2-3x';
  if (m < 5) return '3-5x';
  if (m < 10) return '5-10x';
  return '10x+ (blue)';
}

function normalizeOutcome(v: unknown): string {
  const s = String(v ?? '').toLowerCase();
  if (s === 'win') return 'WIN';
  if (s === 'loss') return 'LOSS';
  if (s === 'skip_win') return 'SKIP_WIN';
  if (s === 'skip_loss') return 'SKIP_LOSS';
  return String(v ?? '').toUpperCase();
}

function outcomeIsCorrect(outcome: string): boolean {
  const o = normalizeOutcome(outcome);
  return o === 'WIN' || o === 'SKIP_WIN';
}

function agg(rows: Row[], key: (r: Row) => string) {
  const map = new Map<string, { total: number; verified: number; wins: number; latencySum: number; latencyN: number }>();
  for (const r of rows) {
    const k = key(r);
    if (!map.has(k)) map.set(k, { total: 0, verified: 0, wins: 0, latencySum: 0, latencyN: 0 });
    const b = map.get(k)!;
    b.total++;
    if (r.latency_ms != null) { b.latencySum += r.latency_ms; b.latencyN++; }
    if (r.result) {
      b.verified++;
      if (outcomeIsCorrect(r.result.outcome)) b.wins++;
    }
  }
  return Array.from(map.entries()).map(([k, v]) => ({
    key: k,
    total: v.total,
    verified: v.verified,
    wins: v.wins,
    win_rate: v.verified ? Math.round((v.wins / v.verified) * 1000) / 10 : null,
    avg_latency_ms: v.latencyN ? Math.round(v.latencySum / v.latencyN) : null,
  })).sort((a, b) => b.total - a.total);
}

export const getPredictionAnalytics = createServerFn({ method: 'GET' })
  .inputValidator((d: { hours?: number } | undefined) => ({ hours: Math.min(720, Math.max(1, Number(d?.hours) || 24)) }))
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const since = new Date(Date.now() - data.hours * 3600_000).toISOString();

    const { data: preds, error } = await supabaseAdmin
      .from('predictions')
      .select('id,device_id,round_id,target_multiplier,action,confidence,source,reasoning,ai_model,latency_ms,created_at,prediction_results(actual_multiplier,outcome)')
      .gte('created_at', since)
      .order('created_at', { ascending: false })
      .limit(2000);
    if (error) throw error;

    const rows: Row[] = (preds ?? []).map((p: any) => ({
      id: p.id,
      device_id: p.device_id,
      round_id: Number(p.round_id),
      target_multiplier: p.target_multiplier == null ? null : Number(p.target_multiplier),
      action: p.action,
      confidence: p.confidence,
      source: p.source,
      reasoning: p.reasoning,
      ai_model: p.ai_model,
      latency_ms: p.latency_ms,
      created_at: p.created_at,
      result: Array.isArray(p.prediction_results) && p.prediction_results.length
        ? { actual_multiplier: Number(p.prediction_results[0].actual_multiplier), outcome: normalizeOutcome(p.prediction_results[0].outcome) }
        : null,
    }));

    const verified = rows.filter(r => r.result);
    const wins = verified.filter(r => outcomeIsCorrect(r.result!.outcome)).length;
    const latencies = rows.map(r => r.latency_ms).filter((n): n is number => n != null).sort((a, b) => a - b);
    const p50 = latencies[Math.floor(latencies.length * 0.5)] ?? null;
    const p95 = latencies[Math.floor(latencies.length * 0.95)] ?? null;

    // Crash distribution (from actual results)
    const crashDist = agg(verified, r => crashBucket(r.result!.actual_multiplier));

    // Best/worst target scenarios: bucket by (action + target range)
    const scenario = agg(verified, r => `${r.action} @ ${targetBucket(r.target_multiplier)}`);

    return {
      unauthorized: false as const,
      window_hours: data.hours,
      summary: {
        total: rows.length,
        verified: verified.length,
        pending: rows.length - verified.length,
        wins,
        losses: verified.length - wins,
        win_rate: verified.length ? Math.round((wins / verified.length) * 1000) / 10 : null,
        latency_p50_ms: p50,
        latency_p95_ms: p95,
      },
      by_source: agg(rows, r => r.source),
      by_action: agg(rows, r => r.action),
      by_confidence: agg(rows, r => r.confidence ?? 'UNKNOWN'),
      by_target: agg(rows, r => targetBucket(r.target_multiplier)),
      by_model: agg(rows.filter(r => r.ai_model), r => r.ai_model!),
      crash_distribution: crashDist,
      best_scenarios: scenario.filter(s => s.verified >= 3).sort((a, b) => (b.win_rate ?? 0) - (a.win_rate ?? 0)).slice(0, 8),
      worst_scenarios: scenario.filter(s => s.verified >= 3).sort((a, b) => (a.win_rate ?? 0) - (b.win_rate ?? 0)).slice(0, 8),
      recent: rows.slice(0, 30).map(r => ({
        id: r.id,
        round_id: r.round_id,
        action: r.action,
        target: r.target_multiplier,
        confidence: r.confidence,
        source: r.source,
        latency_ms: r.latency_ms,
        reasoning: r.reasoning,
        actual: r.result?.actual_multiplier ?? null,
        outcome: r.result?.outcome ?? null,
        created_at: r.created_at,
      })),
    };
  });

export const exportPredictionsCsv = createServerFn({ method: 'GET' })
  .inputValidator((d: { hours?: number } | undefined) => ({ hours: Math.min(720, Math.max(1, Number(d?.hours) || 168)) }))
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const since = new Date(Date.now() - data.hours * 3600_000).toISOString();
    const { data: rows, error } = await supabaseAdmin
      .from('predictions')
      .select('id,round_id,device_id,action,target_multiplier,confidence,source,ai_model,latency_ms,reasoning,created_at,prediction_results(actual_multiplier,outcome)')
      .gte('created_at', since)
      .order('created_at', { ascending: false })
      .limit(10000);
    if (error) throw error;
    const header = 'id,round_id,device_id,action,target,confidence,source,model,latency_ms,actual,outcome,reasoning,created_at';
    const esc = (v: any) => {
      if (v == null) return '';
      const s = String(v).replace(/"/g, '""');
      return /[",\n]/.test(s) ? `"${s}"` : s;
    };
    const lines = (rows ?? []).map((r: any) => {
      const res = Array.isArray(r.prediction_results) && r.prediction_results[0];
      return [r.id, r.round_id, r.device_id, r.action, r.target_multiplier, r.confidence, r.source, r.ai_model, r.latency_ms, res?.actual_multiplier ?? '', res?.outcome ?? '', r.reasoning, r.created_at].map(esc).join(',');
    });
    return { csv: [header, ...lines].join('\n'), count: rows?.length ?? 0 };
  });

// ---------- Notifications ----------

export const listNotificationsAdmin = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('notifications')
    .select('id, target_uid, title, body, created_at')
    .order('created_at', { ascending: false })
    .limit(200);
  if (error) throw error;
  return { items: data ?? [] };
});

export const createNotification = createServerFn({ method: 'POST' })
  .inputValidator((d: { title: string; body: string; target_uid?: string | null }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const title = String(data.title || '').trim().slice(0, 120);
    const body = String(data.body || '').trim().slice(0, 2000);
    const target_uid = data.target_uid ? String(data.target_uid).trim().slice(0, 64) : null;
    if (!title || !body) throw new Error('Title and body required');
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('notifications')
      .insert({ title, body, target_uid: target_uid || null });
    if (error) throw error;
    return { ok: true };
  });

export const deleteNotification = createServerFn({ method: 'POST' })
  .inputValidator((d: { id: string }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('notifications')
      .delete()
      .eq('id', String(data.id));
    if (error) throw error;
    return { ok: true };
  });


// ---- Usage stats (devices & users) ----------------------------------------

export const getUsageStats = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

  const now = Date.now();
  const day = new Date(now - 24 * 3600 * 1000).toISOString();
  const week = new Date(now - 7 * 24 * 3600 * 1000).toISOString();
  const month = new Date(now - 30 * 24 * 3600 * 1000).toISOString();

  // Pull recent-ish capture rows for device aggregation. Limit protects DB.
  const { data: capRows } = await supabaseAdmin
    .from('captures')
    .select('device_id, created_at')
    .not('device_id', 'is', null)
    .gte('created_at', month)
    .order('created_at', { ascending: false })
    .limit(50000);

  const deviceLast = new Map<string, string>();
  const deviceCount = new Map<string, number>();
  for (const r of capRows ?? []) {
    const d = r.device_id as string;
    if (!d) continue;
    if (!deviceLast.has(d)) deviceLast.set(d, r.created_at as string);
    deviceCount.set(d, (deviceCount.get(d) ?? 0) + 1);
  }

  // Total distinct devices across all time (via count trick on captures).
  const { count: totalCaptures } = await supabaseAdmin
    .from('captures')
    .select('*', { count: 'exact', head: true });

  const active24h = Array.from(deviceLast.values()).filter((t) => t >= day).length;
  const active7d = Array.from(deviceLast.values()).filter((t) => t >= week).length;
  const active30d = deviceLast.size;

  const recentDevices = Array.from(deviceLast.entries())
    .map(([device_id, last_seen]) => ({
      device_id,
      last_seen,
      captures: deviceCount.get(device_id) ?? 0,
    }))
    .sort((a, b) => (a.last_seen < b.last_seen ? 1 : -1))
    .slice(0, 100);

  // Users (identified by uid extracted from snapshots)
  const { data: snapRows } = await supabaseAdmin
    .from('user_snapshots')
    .select('uid, nick_name, user_name, phone, balance, device_id, captured_at, created_at')
    .not('uid', 'is', null)
    .order('created_at', { ascending: false })
    .limit(20000);

  const userMap = new Map<string, {
    uid: string;
    nick_name: string | null;
    user_name: string | null;
    phone: string | null;
    balance: number | null;
    device_id: string | null;
    last_seen: string;
  }>();
  for (const r of snapRows ?? []) {
    const uid = r.uid as string;
    if (!uid) continue;
    const last = (r.captured_at as string) ?? (r.created_at as string);
    const existing = userMap.get(uid);
    if (!existing || existing.last_seen < last) {
      userMap.set(uid, {
        uid,
        nick_name: (r.nick_name as string | null) ?? existing?.nick_name ?? null,
        user_name: (r.user_name as string | null) ?? existing?.user_name ?? null,
        phone: (r.phone as string | null) ?? existing?.phone ?? null,
        balance: (r.balance as number | null) ?? existing?.balance ?? null,
        device_id: (r.device_id as string | null) ?? existing?.device_id ?? null,
        last_seen: last,
      });
    }
  }

  const users = Array.from(userMap.values())
    .sort((a, b) => (a.last_seen < b.last_seen ? 1 : -1))
    .slice(0, 200);

  const userLastSeens = Array.from(userMap.values()).map((u) => u.last_seen);
  const users24h = userLastSeens.filter((t) => t >= day).length;
  const users7d = userLastSeens.filter((t) => t >= week).length;

  return {
    devices: {
      total: deviceLast.size, // devices seen in last 30d window
      active24h,
      active7d,
      active30d,
      totalCaptures: totalCaptures ?? 0,
    },
    users: {
      total: userMap.size,
      active24h: users24h,
      active7d: users7d,
    },
    recentDevices,
    recentUsers: users,
  };
});

/**
 * AI usage stats: total predictions, AI vs local counts, token spend, and
 * approximate credit cost — for the admin AI Usage panel.
 */
export const getAiUsageStats = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

  const now = Date.now();
  const d1 = new Date(now - 24 * 3600_000).toISOString();
  const d7 = new Date(now - 7 * 24 * 3600_000).toISOString();

  // Pull last 7d of predictions (id, source, model, tokens, cost, created_at, device_id)
  const { data: rows7d } = await supabaseAdmin
    .from('predictions')
    .select('id,source,ai_model,prompt_tokens,completion_tokens,total_tokens,cost_credits,latency_ms,created_at,device_id')
    .gte('created_at', d7)
    .order('created_at', { ascending: false })
    .limit(20000);

  const rows = (rows7d ?? []) as Array<{
    id: number; source: string | null; ai_model: string | null;
    prompt_tokens: number | null; completion_tokens: number | null; total_tokens: number | null;
    cost_credits: number | null; latency_ms: number | null; created_at: string; device_id: string | null;
  }>;

  type Bucket = { count: number; ai: number; local: number; promptTok: number; completionTok: number; cost: number };
  const empty = (): Bucket => ({ count: 0, ai: 0, local: 0, promptTok: 0, completionTok: 0, cost: 0 });

  const day = empty();
  const week = empty();
  const byModel: Record<string, Bucket> = {};
  const byDeviceCost: Record<string, { count: number; cost: number }> = {};
  const perDay: Record<string, Bucket> = {};

  for (const r of rows) {
    const isAi = r.source === 'ai';
    const inT = r.prompt_tokens ?? 0;
    const outT = r.completion_tokens ?? 0;
    const cost = Number(r.cost_credits ?? 0);
    const t = new Date(r.created_at).getTime();
    const dayKey = r.created_at.slice(0, 10);

    const add = (b: Bucket) => {
      b.count++; if (isAi) b.ai++; else b.local++;
      b.promptTok += inT; b.completionTok += outT; b.cost += cost;
    };
    add(week);
    if (t >= now - 24 * 3600_000) add(day);
    const dayBucket = perDay[dayKey] ?? (perDay[dayKey] = empty()); add(dayBucket);

    const mkey = r.ai_model ?? (isAi ? 'unknown' : 'local');
    const mb = byModel[mkey] ?? (byModel[mkey] = empty()); add(mb);

    if (r.device_id) {
      const dv = byDeviceCost[r.device_id] ?? (byDeviceCost[r.device_id] = { count: 0, cost: 0 });
      dv.count++; dv.cost += cost;
    }
  }

  const topDevices = Object.entries(byDeviceCost)
    .map(([device_id, v]) => ({ device_id, count: v.count, cost: Number(v.cost.toFixed(4)) }))
    .sort((a, b) => b.cost - a.cost).slice(0, 15);

  const dailySeries = Object.entries(perDay)
    .map(([date, b]) => ({
      date, count: b.count, ai: b.ai, local: b.local,
      promptTok: b.promptTok, completionTok: b.completionTok,
      cost: Number(b.cost.toFixed(4)),
    }))
    .sort((a, b) => a.date < b.date ? -1 : 1);

  const round = (b: Bucket) => ({
    count: b.count, ai: b.ai, local: b.local,
    promptTok: b.promptTok, completionTok: b.completionTok, totalTok: b.promptTok + b.completionTok,
    cost: Number(b.cost.toFixed(4)),
  });

  return {
    last24h: round(day),
    last7d: round(week),
    byModel: Object.entries(byModel).map(([model, b]) => ({ model, ...round(b) }))
      .sort((a, b) => b.count - a.count),
    topDevices,
    dailySeries,
    recent: rows.slice(0, 30).map(r => ({
      id: r.id,
      created_at: r.created_at,
      source: r.source,
      model: r.ai_model,
      device_id: r.device_id,
      prompt_tokens: r.prompt_tokens,
      completion_tokens: r.completion_tokens,
      cost_credits: r.cost_credits,
      latency_ms: r.latency_ms,
    })),
  };
});


// ==================== AI Config (remote, no rebuild needed) ====================

export const getAiConfig = createServerFn({ method: 'GET' }).handler(async () => {
  await requireUnlocked();
  const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
  const { data, error } = await supabaseAdmin
    .from('ai_config')
    .select('model,timeout_ms,sys_normal,sys_risk,updated_at')
    .eq('id', 1)
    .maybeSingle();
  if (error) throw error;
  return { config: data ?? { model: 'google/gemini-3-flash-preview', timeout_ms: 4000, sys_normal: '', sys_risk: '', updated_at: null } };
});

export const updateAiConfig = createServerFn({ method: 'POST' })
  .inputValidator((d: { model: string; timeout_ms: number; sys_normal: string; sys_risk: string }) => d)
  .handler(async ({ data }) => {
    await requireUnlocked();
    const model = String(data.model || '').slice(0, 120).trim();
    const timeout_ms = Math.max(1500, Math.min(30000, Math.floor(Number(data.timeout_ms) || 4000)));
    const sys_normal = String(data.sys_normal || '').slice(0, 8000);
    const sys_risk = String(data.sys_risk || '').slice(0, 8000);
    if (!model) throw new Error('model_required');
    const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
    const { error } = await supabaseAdmin
      .from('ai_config')
      .upsert({ id: 1, model, timeout_ms, sys_normal, sys_risk, updated_at: new Date().toISOString() });
    if (error) throw error;
    return { ok: true as const };
  });
