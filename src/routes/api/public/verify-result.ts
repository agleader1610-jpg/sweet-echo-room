import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';

interface Body {
  device_id?: string;
  prediction_id?: number;
  round_id?: number;
  actual_multiplier?: number;
  target_multiplier?: number | null;
}

export const Route = createFileRoute('/api/public/verify-result')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      POST: async ({ request }) => {
        let body: Body;
        try { body = (await request.json()) as Body; } catch { return json({ ok: false, error: 'bad_json' }, 400); }
        const roundId = Number(body.round_id);
        const actual = Number(body.actual_multiplier);
        if (!isFinite(roundId) || !isFinite(actual)) return json({ ok: false, error: 'bad_input' }, 400);
        const deviceId = (body.device_id ?? '').slice(0, 128);
        const target = body.target_multiplier == null ? null : Number(body.target_multiplier);
        // Determine outcome: target null → SKIP verify (correct if actual<2, wrong if actual>=2); else win if actual>=target.
        let outcome: string;
        if (target == null) outcome = actual < 2 ? 'SKIP_WIN' : 'SKIP_LOSS';
        else outcome = actual >= target ? 'WIN' : 'LOSS';

        const supa = supaClient();
        await supa.from('prediction_results').insert({
          prediction_id: body.prediction_id ?? null,
          device_id: deviceId,
          round_id: roundId,
          target_multiplier: target,
          actual_multiplier: actual,
          outcome,
        });
        return json({ ok: true, outcome }, 200);
      },
    },
  },
});

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
