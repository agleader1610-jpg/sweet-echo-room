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
