import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';
import { computeLocalSignal, type Signal } from '@/lib/local-prediction-logic';

// Upgraded to GPT-5.4 with priority tier — best reasoning + low latency.
// Fast mode (service_tier: priority) supported per ai-models-chat catalog.
const AI_MODEL = 'openai/gpt-5.4';
const AI_TIMEOUT_MS = 8000;

interface Body {
  device_id?: string;
  history?: Array<{ round_id: number; multiplier: number }>;
}

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

        // Fetch recent prediction outcomes so AI has memory of prior W/L
        const priorRes = await supa
          .from('prediction_results')
          .select('round_id,target_multiplier,actual_multiplier,outcome')
          .eq('device_id', deviceId)
          .order('round_id', { ascending: false })
          .limit(10);
        const priors = (priorRes.data ?? []) as Array<{ round_id: number; target_multiplier: number | null; actual_multiplier: number; outcome: string }>;

        // Try AI first
        let signal: Signal | null = null;
        let source: 'ai' | 'local' = 'local';
        let aiModel: string | null = null;
        let aiError: string | null = null;
        let aiRaw: string | null = null;
        try {
          const r = await callAI(history, priors);
          signal = r.signal;
          aiRaw = r.raw;
          source = 'ai';
          aiModel = AI_MODEL;
        } catch (e: any) {
          aiError = (e?.message ?? 'ai_failed').slice(0, 500);
          signal = computeLocalSignal(history);
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
          raw: { history_len: history.length, ai_error: aiError, ai_raw: aiRaw },
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
        }, 200);
      },
    },
  },
});

async function callAI(history: Array<{ round_id: number; multiplier: number }>): Promise<{ signal: Signal; raw: string }> {

  const key = process.env.LOVABLE_API_KEY;
  if (!key) throw new Error('no_ai_key');
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  const recent = history.slice(0, 20);
  const compact = recent.map(r => `#${r.round_id}:${r.multiplier.toFixed(2)}x`).join(', ');
  const sys = 'You are an Aviator crash-game analyst. Given the last rounds (newest first), predict the NEXT round. Only output JSON: {"action":"BET"|"STRONG BET"|"SKIP","target":number|null,"confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=100 chars"}. If SKIP, target=null. Base analysis on: streaks of low (<2x) or high crashes, drought since 10x+, momentum, average trend. Be decisive but conservative — targets typically 1.3-3.5, occasionally 5+ if long drought.';
  const user = `Recent rounds (newest first): ${compact}. Predict next.`;
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
        service_tier: 'priority',
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
        target: action === 'SKIP' ? null : target,
        confidence: (['LOW', 'MEDIUM', 'HIGH'].includes(confidence) ? confidence : 'MEDIUM') as Signal['confidence'],
        reasoning: reasoning || 'AI analysis',
      },
      raw: String(content).slice(0, 2000),
    };

  } finally {
    clearTimeout(t);
  }
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
