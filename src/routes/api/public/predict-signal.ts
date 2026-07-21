import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';
import { computeLocalSignal, computeRiskSignal, type Signal } from '@/lib/local-prediction-logic';

// Defaults used only if DB config is missing. Live values come from public.ai_config
// (editable via /admin), cached in memory for 60s so we don't hit the DB every request.
const DEFAULT_MODEL = 'google/gemini-3-flash-preview';
const DEFAULT_TIMEOUT_MS = 4000;
const DEFAULT_SYS_NORMAL = 'You are an Aviator crash-game analyst with persistent MEMORY of your own past signals. You must use both recent crash history and your prior prediction outcomes before choosing the NEXT round signal. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=100 chars"}. If SKIP, target=null.';
const DEFAULT_SYS_RISK = 'You are an Aviator crash-game analyst in RISK-RECOVERY mode. Read the market AND your own past signals; pick a recovery target that fits both. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=120 chars"}.';

type AiCfg = { model: string; timeoutMs: number; sysNormal: string; sysRisk: string };
let cfgCache: { at: number; cfg: AiCfg } | null = null;
const CFG_TTL_MS = 60_000;

async function loadAiConfig(supa: ReturnType<typeof supaClient>): Promise<AiCfg> {
  const now = Date.now();
  if (cfgCache && now - cfgCache.at < CFG_TTL_MS) return cfgCache.cfg;
  try {
    const { data } = await supa.from('ai_config').select('model,timeout_ms,sys_normal,sys_risk').eq('id', 1).maybeSingle();
    const cfg: AiCfg = {
      model: (data?.model as string) || DEFAULT_MODEL,
      timeoutMs: Number(data?.timeout_ms) || DEFAULT_TIMEOUT_MS,
      sysNormal: (data?.sys_normal as string) || DEFAULT_SYS_NORMAL,
      sysRisk: (data?.sys_risk as string) || DEFAULT_SYS_RISK,
    };
    cfgCache = { at: now, cfg };
    return cfg;
  } catch {
    return { model: DEFAULT_MODEL, timeoutMs: DEFAULT_TIMEOUT_MS, sysNormal: DEFAULT_SYS_NORMAL, sysRisk: DEFAULT_SYS_RISK };
  }
}

export function invalidateAiConfigCache() { cfgCache = null; }

type Mode = 'normal' | 'risk';

interface Body {
  device_id?: string;
  mode?: string;
  history?: Array<{ round_id: number; multiplier: number }>;
}

type PriorSignal = {
  id: number;
  round_id: number;
  target_multiplier: number | null;
  action: string;
  confidence: string | null;
  source: string | null;
  reasoning: string | null;
  result: { actual_multiplier: number; outcome: string } | null;
};

type MemoryStats = {
  priorStr: string;
  lossStreak: number;
  wins: number;
  losses: number;
  accuracy: number;
  skipWins: number;
  skipLosses: number;
  skipAccuracy: number;
  pending: number;
  memoryCount: number;
};

export const Route = createFileRoute('/api/public/predict-signal')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      POST: async ({ request }) => {
        const started = Date.now();
        let body: Body;
        try { body = (await request.json()) as Body; } catch { return json({ ok: false, error: 'bad_json' }, 400); }
        const deviceId = (body.device_id ?? '').slice(0, 128);
        const mode: Mode = (String(body.mode ?? 'normal').toLowerCase() === 'risk') ? 'risk' : 'normal';
        const history = (body.history ?? []).slice(0, 30).filter(h => typeof h.round_id === 'number' && typeof h.multiplier === 'number');
        if (history.length < 3) return json({ ok: false, error: 'need_history' }, 200);

        const supa = supaClient();
        // Persist latest round (best-effort; do not block on failure)
        const newest = history[0];
        supa.from('round_history').insert({
          device_id: deviceId,
          round_id: newest.round_id,
          multiplier: newest.multiplier,
        }).then(() => {}, () => {});

        // Fetch recent predictions + outcomes so AI has real memory of its own W/L, targets, and pending calls.
        const priorRes = await supa
          .from('predictions')
          .select('id,round_id,target_multiplier,action,confidence,source,reasoning,prediction_results(actual_multiplier,outcome)')
          .eq('device_id', deviceId)
          .order('created_at', { ascending: false })
          .limit(15);
        const priors = ((priorRes.data ?? []) as any[]).map((p): PriorSignal => {
          const result = Array.isArray(p.prediction_results) && p.prediction_results.length
            ? p.prediction_results[0]
            : null;
          return {
            id: Number(p.id),
            round_id: Number(p.round_id),
            target_multiplier: p.target_multiplier == null ? null : Number(p.target_multiplier),
            action: String(p.action ?? 'BET'),
            confidence: p.confidence == null ? null : String(p.confidence),
            source: p.source == null ? null : String(p.source),
            reasoning: p.reasoning == null ? null : String(p.reasoning),
            result: result ? { actual_multiplier: Number(result.actual_multiplier), outcome: normalizeOutcome(result.outcome) } : null,
          };
        });
        const memory = summarizeMemory(priors);

        // Try AI first
        const cfg = await loadAiConfig(supa);
        let signal: Signal | null = null;
        let source: 'ai' | 'local' = 'local';
        let aiModel: string | null = null;
        let aiError: string | null = null;
        let aiRaw: string | null = null;
        let promptTokens: number | null = null;
        let completionTokens: number | null = null;
        let totalTokens: number | null = null;
        let costCredits: number | null = null;
        try {
          const r = await callAI(history, memory, mode, cfg);
          signal = r.signal;
          aiRaw = r.raw;
          source = 'ai';
          aiModel = cfg.model;
          promptTokens = r.promptTokens;
          completionTokens = r.completionTokens;
          totalTokens = r.totalTokens;
          costCredits = estimateCost(cfg.model, promptTokens, completionTokens);
        } catch (e: any) {
          aiError = (e?.message ?? 'ai_failed').slice(0, 500);
          signal = mode === 'risk'
            ? computeRiskSignal(history, memory.lossStreak)
            : applyMemoryToLocal(computeLocalSignal(history), memory);
          source = 'local';
        }


        const runningRoundId = newest.round_id + 1;
        const latency = Date.now() - started;

        // Persist prediction
        const ins = await supa.from('predictions').insert({
          device_id: deviceId,
          round_id: runningRoundId,
          target_multiplier: signal.target,
          action: signal.action,
          confidence: signal.confidence,
          source,
          reasoning: signal.reasoning,
          ai_model: aiModel,
          latency_ms: latency,
          prompt_tokens: promptTokens,
          completion_tokens: completionTokens,
          total_tokens: totalTokens,
          cost_credits: costCredits,
          raw: { history_len: history.length, memory, ai_error: aiError, ai_raw: aiRaw },
        }).select('id').maybeSingle();

        return json({
          ok: true,
          prediction_id: ins.data?.id ?? null,
          round_id: runningRoundId,
          history_newest_round_id: newest.round_id,
          history_newest_multiplier: newest.multiplier,
          history_len: history.length,
          mode,
          target: signal.target,
          action: signal.action,
          confidence: signal.confidence,
          reasoning: signal.reasoning,
          source,
          model: aiModel,
          latency_ms: latency,
          ai_raw: aiRaw,
          ai_error: aiError,
          prompt_tokens: promptTokens,
          completion_tokens: completionTokens,
          total_tokens: totalTokens,
          cost_credits: costCredits,
          memory_count: memory.memoryCount,
          memory_loss_streak: memory.lossStreak,
          memory_accuracy: memory.accuracy,
          memory_skip_accuracy: memory.skipAccuracy,
          memory_pending: memory.pending,
        }, 200);
      },
    },
  },
});

async function callAI(
  history: Array<{ round_id: number; multiplier: number }>,
  memory: MemoryStats,
  mode: Mode,
): Promise<{ signal: Signal; raw: string; promptTokens: number | null; completionTokens: number | null; totalTokens: number | null }> {

  const key = process.env.LOVABLE_API_KEY;
  if (!key) throw new Error('no_ai_key');
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  const recent = history.slice(0, 20);
  const compact = recent.map(r => `#${r.round_id}:${r.multiplier.toFixed(2)}x`).join(', ');

  const sysNormal = 'You are an Aviator crash-game analyst with persistent MEMORY of your own past signals. You must use both recent crash history and your prior prediction outcomes before choosing the NEXT round signal. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=100 chars"}. If SKIP, target=null. Adapt to performance: after 1 loss become conservative; after 2+ consecutive losses avoid STRONG BET and use either SKIP or low target 1.30-1.60. If recent hit-rate is weak, reduce risk; if hit-rate is strong, keep the successful target band. Analyze crash streaks (<2x pinks, >=2x greens), drought since 10x+, momentum, average trend, skip correctness, and target hit-rate. Targets typically 1.30-3.50; 5+ only on strong long-drought evidence.';

  const sysRisk = 'You are an Aviator crash-game analyst in RISK-RECOVERY mode. You have persistent MEMORY of every past signal you gave (target, actual crash, win/loss). NO fixed martingale ladder — you must READ THE MARKET (recent crashes, pink/green streaks, drought since 10x+, momentum, average trend) TOGETHER with your own prior signals and their outcomes, and choose a recovery target that a smart human trader would pick to clear prior losses AND lock small profit on the very next winning round. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=120 chars"}. Guidelines: (1) Give BET signals aggressively — SKIP only when the market clearly says danger (e.g. a huge >=15x just landed, or 4+ of your own recent BETs lost in a row and the pattern hasn\'t shifted). (2) After losses, raise the target intelligently based on how many units you must recover AND what the market actually supports — do not blindly double. If the market looks weak, prefer a slightly lower but still recovery-positive target with STRONG BET confidence; if the market looks primed (long pink streak, long drought), aim higher. (3) After wins, reset to a fresh conservative target. (4) Always justify in reasoning why THIS target fits BOTH the market read AND the loss/win history. Reasoning may be short Bengali or English.';
  const sys = mode === 'risk' ? sysRisk : sysNormal;
  const userNormal = `Recent crashes (newest first): ${compact}.\nYour signal memory (newest first, target→actual=result): ${memory.priorStr}.\nVerified BET loss streak: ${memory.lossStreak}. BET hit-rate: ${memory.accuracy}% (${memory.wins}W/${memory.losses}L). SKIP accuracy: ${memory.skipAccuracy}% (${memory.skipWins} correct/${memory.skipLosses} wrong). Pending signals: ${memory.pending}. Predict only round #${recent[0].round_id + 1}.`;
  const userRisk = `MODE: RISK-RECOVERY (market-aware, NOT martingale).\nRecent crashes (newest first): ${compact}.\nYour signal memory (newest first, target→actual=result): ${memory.priorStr}.\nVerified BET loss streak L=${memory.lossStreak}. BET hit-rate: ${memory.accuracy}% (${memory.wins}W/${memory.losses}L). SKIP accuracy: ${memory.skipAccuracy}%. Pending: ${memory.pending}. Predict only round #${recent[0].round_id + 1}. Read the market AND your own past signals — pick a recovery target that fits BOTH, not a fixed ladder.`;
  const user = mode === 'risk' ? userRisk : userNormal;

  try {
    const res = await fetch('https://ai.gateway.lovable.dev/v1/chat/completions', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${key}`,
      },
      body: JSON.stringify({
        model: AI_MODEL,
        messages: [
          { role: 'system', content: sys },
          { role: 'user', content: user },
        ],
        response_format: { type: 'json_object' },
      }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`ai_${res.status}: ${text.slice(0, 100)}`);
    }
    const data = await res.json() as any;
    const content = data?.choices?.[0]?.message?.content;
    if (!content) throw new Error('ai_empty');
    const parsed = JSON.parse(content);
    const action = String(parsed.action ?? 'BET').toUpperCase();
    const target = parsed.target == null ? null : Number(parsed.target);
    const confidence = String(parsed.confidence ?? 'MEDIUM').toUpperCase();
    const reasoning = String(parsed.reasoning ?? '').slice(0, 140);
    if (!['BET', 'STRONG BET', 'SKIP'].includes(action)) throw new Error('ai_bad_action');
    if (target !== null && (!isFinite(target) || target < 1.01 || target > 100)) throw new Error('ai_bad_target');
    const usage = data?.usage ?? {};
    return {
      signal: {
        action: action as Signal['action'],
        target: action === 'SKIP' ? null : (target ?? 1.5),
        confidence: (['LOW', 'MEDIUM', 'HIGH'].includes(confidence) ? confidence : 'MEDIUM') as Signal['confidence'],
        reasoning: reasoning || 'AI analysis',
      },
      raw: String(content).slice(0, 2000),
      promptTokens: typeof usage.prompt_tokens === 'number' ? usage.prompt_tokens : null,
      completionTokens: typeof usage.completion_tokens === 'number' ? usage.completion_tokens : null,
      totalTokens: typeof usage.total_tokens === 'number' ? usage.total_tokens : null,
    };

  } finally {
    clearTimeout(t);
  }
}

// Estimate cost in Lovable credits from token counts. Rates are approximations
// derived from observed AI Gateway logs; used only for display.
function estimateCost(model: string, promptTok: number | null, completionTok: number | null): number | null {
  if (promptTok == null && completionTok == null) return null;
  const pt = promptTok ?? 0;
  const ct = completionTok ?? 0;
  // Credits per 1K tokens (approximate, per model)
  const rates: Record<string, { in: number; out: number }> = {
    'openai/gpt-5.4': { in: 0.025, out: 0.070 },
    'openai/gpt-5.4-mini': { in: 0.005, out: 0.020 },
    'openai/gpt-5.4-nano': { in: 0.001, out: 0.004 },
    'google/gemini-3-flash-preview': { in: 0.0015, out: 0.006 },
    'google/gemini-3.1-flash-lite': { in: 0.0008, out: 0.003 },
    'google/gemini-3.5-flash': { in: 0.002, out: 0.008 },
  };
  const r = rates[model] ?? { in: 0.01, out: 0.03 };
  return Number((((pt / 1000) * r.in) + ((ct / 1000) * r.out)).toFixed(6));
}


function applyMemoryToLocal(signal: Signal, memory: MemoryStats): Signal {
  if (memory.lossStreak >= 2) {
    return {
      ...signal,
      action: signal.action === 'SKIP' ? 'SKIP' : 'BET',
      target: signal.action === 'SKIP' ? null : Math.min(signal.target ?? 1.5, 1.5),
      confidence: signal.confidence === 'HIGH' ? 'MEDIUM' : signal.confidence,
      reasoning: `memory: ${memory.lossStreak} losses — safer target.`,
    };
  }
  if (memory.lossStreak === 1 && signal.target != null && signal.target > 1.8) {
    return { ...signal, target: 1.6, reasoning: 'memory: last signal lost — reduced target.' };
  }
  return signal;
}

function normalizeOutcome(v: unknown): string {
  const s = String(v ?? '').toLowerCase();
  if (s === 'win') return 'WIN';
  if (s === 'loss') return 'LOSS';
  if (s === 'skip_win') return 'SKIP_WIN';
  if (s === 'skip_loss') return 'SKIP_LOSS';
  return String(v ?? '').toUpperCase();
}

function summarizeMemory(priors: PriorSignal[]): MemoryStats {
  const priorStr = priors.length
    ? priors.map((p) => {
        const tgt = p.target_multiplier == null ? 'SKIP' : `T${p.target_multiplier.toFixed(2)}x`;
        const actual = p.result ? p.result.actual_multiplier.toFixed(2) : '?';
        const outcome = p.result?.outcome ?? 'PENDING';
        const tag = outcome === 'WIN' ? 'WIN'
          : outcome === 'LOSS' ? 'LOSS'
          : outcome === 'SKIP_WIN' ? 'SKIP✓'
          : outcome === 'SKIP_LOSS' ? 'SKIP✗'
          : 'PENDING';
        return `#${p.round_id}[${p.action}:${tgt}→${actual}x=${tag}]`;
      }).join(', ')
    : 'none';

  const verifiedBets = priors.filter(p => p.result?.outcome === 'WIN' || p.result?.outcome === 'LOSS');
  let lossStreak = 0;
  for (const p of priors) {
    const outcome = p.result?.outcome;
    if (outcome == null || outcome === 'SKIP_WIN' || outcome === 'SKIP_LOSS') continue;
    if (outcome === 'LOSS') lossStreak++;
    else break;
  }
  const wins = verifiedBets.filter(p => p.result?.outcome === 'WIN').length;
  const losses = verifiedBets.filter(p => p.result?.outcome === 'LOSS').length;
  const skipWins = priors.filter(p => p.result?.outcome === 'SKIP_WIN').length;
  const skipLosses = priors.filter(p => p.result?.outcome === 'SKIP_LOSS').length;
  const accuracy = wins + losses > 0 ? Math.round((wins / (wins + losses)) * 100) : 0;
  const skipAccuracy = skipWins + skipLosses > 0 ? Math.round((skipWins / (skipWins + skipLosses)) * 100) : 0;
  return {
    priorStr,
    lossStreak,
    wins,
    losses,
    accuracy,
    skipWins,
    skipLosses,
    skipAccuracy,
    pending: priors.filter(p => !p.result).length,
    memoryCount: priors.length,
  };
}

function supaClient() {
  const url = process.env.SUPABASE_URL!;
  const key = process.env.SUPABASE_PUBLISHABLE_KEY!;
  return createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: {
      fetch: (input, init) => {
        const h = new Headers(init?.headers);
        if (key.startsWith('sb_') && h.get('Authorization') === `Bearer ${key}`) h.delete('Authorization');
        h.set('apikey', key);
        return fetch(input, { ...init, headers: h });
      },
    },
  });
}
function cors(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type',
    'Cache-Control': 'no-store',
  };
}
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...cors() } });
}
