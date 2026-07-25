import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';

/**
 * WinGo shared AI signal endpoint.
 *
 * One AI call per PERIOD (issue_id) across ALL devices. Result is cached in
 * public.wingo_signal_cache so every user opening WinGo/AutoBet gets the
 * exact same pick. If AI fails or credits are gone, we return a random pick
 * with credits_exhausted=true so the app can show its "update notice" banner
 * and stop after the next win.
 */

const AI_MODEL = 'google/gemini-3-flash-preview';
const AI_TIMEOUT_MS = 5000;

type Body = {
  device_id?: string;
  game_code?: string;
  issue_id?: string;               // target period (the one we predict)
  mode?: string;                   // 'ai' | 'server'   (server = random)
  history?: Array<{ issue: string; number: number }>;
};

export const Route = createFileRoute('/api/public/wingo-signal')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      POST: async ({ request }) => {
        const started = Date.now();
        let body: Body;
        try { body = (await request.json()) as Body; } catch { return json({ ok: false, error: 'bad_json' }, 400); }

        const issueId = (body.issue_id ?? '').trim().slice(0, 64);
        const gameCode = (body.game_code ?? 'WinGo_30S').trim().slice(0, 32);
        const modeRaw = (body.mode ?? 'ai').trim().toLowerCase();
        const mode: 'ai' | 'server' = modeRaw === 'server' ? 'server' : 'ai';
        const history = (body.history ?? [])
          .filter(h => typeof h.issue === 'string' && typeof h.number === 'number' && h.number >= 0 && h.number <= 9)
          .slice(0, 10);

        if (!issueId) return json({ ok: false, error: 'need_issue_id' }, 200);
        // Server (random) mode doesn't need history; AI mode does.
        if (mode === 'ai' && history.length < 3) return json({ ok: false, error: 'need_history' }, 200);

        const supa = supaClient();

        // 1) Shared cache lookup — keyed by (issue_id, mode) so AI-mode users
        //    and Server-mode users each get a consistent shared pick per period.
        const cached = await supa
          .from('wingo_signal_cache')
          .select('issue_id, mode, pick, confidence, reasoning, source, ai_model, ai_error, credits_exhausted, created_at')
          .eq('issue_id', issueId)
          .eq('mode', mode)
          .maybeSingle();

        if (cached.data) {
          return json({
            ok: true,
            cached: true,
            issue_id: issueId,
            mode,
            pick: cached.data.pick,
            confidence: cached.data.confidence,
            reasoning: cached.data.reasoning ?? '',
            source: cached.data.source,
            model: cached.data.ai_model,
            ai_error: cached.data.ai_error,
            credits_exhausted: cached.data.credits_exhausted,
            latency_ms: Date.now() - started,
          }, 200);
        }

        // 2) First device for this (period, mode) — build the signal.
        let pick = 'BIG';
        let confidence = 'MEDIUM';
        let reasoning = '';
        let source: 'ai' | 'random' | 'server' = 'random';
        let aiError: string | null = null;
        let creditsExhausted = false;

        if (mode === 'server') {
          // Pure random shared pick — no AI call, no credit usage.
          pick = Math.random() < 0.5 ? 'BIG' : 'SMALL';
          confidence = 'MEDIUM';
          reasoning = 'Server random signal।';
          source = 'server';
        } else {
          // Feedback loop: pull previous AI signals for the exact issues in history,
          // compare pick vs actual, and pass hit-rate + last 5 outcomes to the model.
          const outcomes = await buildOutcomeFeedback(supa, history);
          try {
            const ai = await callAI(history, outcomes);
            pick = ai.pick;
            confidence = ai.confidence;
            reasoning = ai.reasoning;
            source = 'ai';
          } catch (e: any) {
            aiError = (e?.message ?? 'ai_failed').slice(0, 200);
            creditsExhausted = /402|payment|credit|quota|insufficient/i.test(aiError ?? '');
            pick = Math.random() < 0.5 ? 'BIG' : 'SMALL';
            confidence = 'MEDIUM';
            reasoning = 'Signal ready।';
            source = 'random';
          }
        }

        // 3) Persist. Composite PK (issue_id, mode) — upsert on that conflict.
        const upsert = await supa
          .from('wingo_signal_cache')
          .upsert({
            issue_id: issueId,
            mode,
            game_code: gameCode,
            pick,
            confidence,
            reasoning,
            source,
            ai_model: source === 'ai' ? AI_MODEL : null,
            ai_error: aiError,
            credits_exhausted: creditsExhausted,
          }, { onConflict: 'issue_id,mode' })
          .select('pick, confidence, reasoning, source, ai_model, ai_error, credits_exhausted')
          .maybeSingle();

        const final = upsert.data ?? { pick, confidence, reasoning, source, ai_model: source === 'ai' ? AI_MODEL : null, ai_error: aiError, credits_exhausted: creditsExhausted };

        return json({
          ok: true,
          cached: false,
          issue_id: issueId,
          mode,
          pick: final.pick,
          confidence: final.confidence,
          reasoning: final.reasoning ?? '',
          source: final.source,
          model: final.ai_model,
          ai_error: final.ai_error,
          credits_exhausted: final.credits_exhausted,
          latency_ms: Date.now() - started,
        }, 200);
      },
    },
  },
});


async function buildOutcomeFeedback(
  supa: ReturnType<typeof supaClient>,
  history: Array<{ issue: string; number: number }>,
): Promise<{ hits: number; total: number; recent: string }> {
  if (history.length === 0) return { hits: 0, total: 0, recent: '' };
  const issues = history.map(h => h.issue).slice(0, 10);
  const { data } = await supa
    .from('wingo_signal_cache')
    .select('issue_id, pick, source')
    .in('issue_id', issues)
    .eq('mode', 'ai');
  const rows = (data ?? []).filter(r => r.source === 'ai');
  const byIssue = new Map(rows.map(r => [r.issue_id, r.pick as string]));
  let hits = 0, total = 0;
  const pairs: string[] = [];
  for (const h of history) {
    const p = byIssue.get(h.issue);
    if (!p) continue;
    total++;
    const actual = h.number >= 5 ? 'BIG' : 'SMALL';
    const win = p === actual;
    if (win) hits++;
    if (pairs.length < 5) pairs.push(`${p}→${actual}(${win ? 'W' : 'L'})`);
  }
  return { hits, total, recent: pairs.join(',') };
}

async function callAI(
  history: Array<{ issue: string; number: number }>,
  outcomes: { hits: number; total: number; recent: string },
): Promise<{ pick: string; confidence: string; reasoning: string }> {
  const key = process.env.LOVABLE_API_KEY;
  if (!key) throw new Error('no_ai_key');
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  const compact = history.map(h => `${h.number}${h.number >= 5 ? 'B' : 'S'}`).join(',');
  const feedback = outcomes.total > 0
    ? `\nRecent AI hit-rate: ${outcomes.hits}/${outcomes.total} (${Math.round(100 * outcomes.hits / outcomes.total)}%). Last picks vs actual: ${outcomes.recent}. If losing streak, adapt strategy (flip bias or increase mean-reversion weight).`
    : '';

  const sys = `WinGo BIG/SMALL predictor. Analyze streak, imbalance, alternation; pick BIG or SMALL for NEXT round.
Output ONLY JSON: {"pick":"BIG"|"SMALL","confidence":"LOW"|"MEDIUM"|"HIGH","reasoning":"<=80 chars"}`;

  const user = `Last 10 (newest→oldest, B≥5 S<5): ${compact}.${feedback}`;

  try {
    const res = await fetch('https://ai.gateway.lovable.dev/v1/chat/completions', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        'Lovable-API-Key': key,
        'X-Lovable-AIG-SDK': 'manual-fetch',
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
      throw new Error(`ai_${res.status}: ${text.slice(0, 120)}`);
    }
    const data = await res.json() as any;
    const content = data?.choices?.[0]?.message?.content;
    if (!content) throw new Error('ai_empty');
    const parsed = JSON.parse(content);
    const pick = String(parsed.pick ?? '').toUpperCase();
    if (pick !== 'BIG' && pick !== 'SMALL') throw new Error('ai_bad_pick');
    const confRaw = String(parsed.confidence ?? 'MEDIUM').toUpperCase();
    const confidence = ['LOW', 'MEDIUM', 'HIGH'].includes(confRaw) ? confRaw : 'MEDIUM';
    const reasoning = String(parsed.reasoning ?? '').slice(0, 140);
    return { pick, confidence, reasoning: reasoning || 'AI analysis' };
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
