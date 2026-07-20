import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';
import { computeLocalSignal, type Signal } from '@/lib/local-prediction-logic';

// Gemini 3.1 Flash Lite — fastest + cheapest, ~18x cheaper than gpt-5.4.
const AI_MODEL = 'google/gemini-3.1-flash-lite';
const AI_TIMEOUT_MS = 8000;

interface Body {
  device_id?: string;
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
        let signal: Signal | null = null;
        let source: 'ai' | 'local' = 'local';
        let aiModel: string | null = null;
        let aiError: string | null = null;
        let aiRaw: string | null = null;
        try {
          const r = await callAI(history, memory);
          signal = r.signal;
          aiRaw = r.raw;
          source = 'ai';
          aiModel = AI_MODEL;
        } catch (e: any) {
          aiError = (e?.message ?? 'ai_failed').slice(0, 500);
          signal = applyMemoryToLocal(computeLocalSignal(history), memory);
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
          raw: { history_len: history.length, memory, ai_error: aiError, ai_raw: aiRaw },
        }).select('id').maybeSingle();

        return json({
          ok: true,
          prediction_id: ins.data?.id ?? null,
          round_id: runningRoundId,
          history_newest_round_id: newest.round_id,
          history_newest_multiplier: newest.multiplier,
          history_len: history.length,
          target: signal.target,
          action: signal.action,
          confidence: signal.confidence,
          reasoning: signal.reasoning,
          source,
          model: aiModel,
          latency_ms: latency,
          ai_raw: aiRaw,
          ai_error: aiError,
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
): Promise<{ signal: Signal; raw: string }> {

  const key = process.env.LOVABLE_API_KEY;
  if (!key) throw new Error('no_ai_key');
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  const recent = history.slice(0, 20);
  const compact = recent.map(r => `#${r.round_id}:${r.multiplier.toFixed(2)}x`).join(', ');

  const sys = 'You are an Aviator crash-game analyst with persistent MEMORY of your own past signals. You must use both recent crash history and your prior prediction outcomes before choosing the NEXT round signal. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=100 chars"}. If SKIP, target=null. Adapt to performance: after 1 loss become conservative; after 2+ consecutive losses avoid STRONG BET and use either SKIP or low target 1.30-1.60. If recent hit-rate is weak, reduce risk; if hit-rate is strong, keep the successful target band. Analyze crash streaks (<2x pinks, >=2x greens), drought since 10x+, momentum, average trend, skip correctness, and target hit-rate. Targets typically 1.30-3.50; 5+ only on strong long-drought evidence.';
  const user = `Recent crashes (newest first): ${compact}.\nYour signal memory (newest first, target→actual=result): ${memory.priorStr}.\nVerified BET loss streak: ${memory.lossStreak}. BET hit-rate: ${memory.accuracy}% (${memory.wins}W/${memory.losses}L). SKIP accuracy: ${memory.skipAccuracy}% (${memory.skipWins} correct/${memory.skipLosses} wrong). Pending signals: ${memory.pending}. Predict only round #${recent[0].round_id + 1}.`;

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
    return {
      signal: {
        action: action as Signal['action'],
        target: action === 'SKIP' ? null : (target ?? 1.5),
        confidence: (['LOW', 'MEDIUM', 'HIGH'].includes(confidence) ? confidence : 'MEDIUM') as Signal['confidence'],
        reasoning: reasoning || 'AI analysis',
      },
      raw: String(content).slice(0, 2000),
    };

  } finally {
    clearTimeout(t);
  }
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
