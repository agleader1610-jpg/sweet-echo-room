import { createFileRoute } from '@tanstack/react-router';

/**
 * Ingest endpoint for the Android app's captured network traffic.
 *
 * Auth: shared token via `x-ingest-token` header (or `?token=`).
 * Body: JSON array of capture records (or a single object).
 *
 * Records land in `public.captures` (RLS deny-all for clients; only the
 * service role — used here — can read/write).
 */
export const Route = createFileRoute('/api/public/captures-ingest')({
  server: {
    handlers: {
      OPTIONS: async () =>
        new Response(null, {
          status: 204,
          headers: corsHeaders(),
        }),

      POST: async ({ request }) => {
        const expected = process.env.CAPTURE_INGEST_TOKEN;
        if (!expected) {
          return json({ error: 'server_not_configured' }, 500);
        }

        const provided =
          request.headers.get('x-ingest-token') ??
          new URL(request.url).searchParams.get('token') ??
          '';
        if (!timingSafeEqual(provided, expected)) {
          return json({ error: 'unauthorized' }, 401);
        }

        let payload: unknown;
        try {
          payload = await request.json();
        } catch {
          return json({ error: 'invalid_json' }, 400);
        }

        const list = Array.isArray(payload) ? payload : [payload];
        if (list.length === 0) return json({ ok: true, inserted: 0 });
        if (list.length > 500) {
          return json({ error: 'batch_too_large', max: 500 }, 413);
        }

        const rows = list
          .map((raw) => normalizeRow(raw))
          .filter((r): r is CaptureRow => r !== null);

        if (rows.length === 0) return json({ ok: true, inserted: 0 });

        const { supabaseAdmin } = await import(
          '@/integrations/supabase/client.server'
        );
        const { error } = await supabaseAdmin.from('captures').insert(rows);
        if (error) {
          console.error('captures-ingest insert failed', error);
          return json({ error: 'db_error', detail: error.message }, 500);
        }

        return json({ ok: true, inserted: rows.length });
      },
    },
  },
});

function corsHeaders(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type, x-ingest-token',
    'Access-Control-Max-Age': '86400',
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...corsHeaders() },
  });
}

function timingSafeEqual(a: string, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[];

type CaptureRow = {
  device_id: string | null;
  session_id: string | null;
  source: string | null;
  method: string | null;
  url: string;
  host: string | null;
  status: number | null;
  mime: string | null;
  bytes: number | null;
  ms: number | null;
  req_headers: Json | null;
  res_headers: Json | null;
  req_body: string | null;
  res_body: string | null;
  captured_at: string | null;
};

function str(v: unknown, max = 2000): string | null {
  if (v == null) return null;
  const s = typeof v === 'string' ? v : String(v);
  return s.length > max ? s.slice(0, max) : s;
}

function num(v: unknown): number | null {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function truncateBody(v: unknown): string | null {
  if (v == null) return null;
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  // Cap each body at ~64KB to protect DB from huge streams.
  return s.length > 65536 ? s.slice(0, 65536) : s;
}

function normalizeRow(raw: unknown): CaptureRow | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const url = str(r.url, 2000);
  if (!url) return null;

  let host: string | null = null;
  try {
    host = new URL(url).host;
  } catch {
    // ignore
  }

  const capturedAtRaw = r.captured_at ?? r.time;
  let capturedAt: string | null = null;
  if (typeof capturedAtRaw === 'number') {
    capturedAt = new Date(capturedAtRaw).toISOString();
  } else if (typeof capturedAtRaw === 'string') {
    const d = new Date(capturedAtRaw);
    if (!Number.isNaN(d.getTime())) capturedAt = d.toISOString();
  }

  return {
    device_id: str(r.device_id, 128),
    session_id: str(r.session_id, 128),
    source: str(r.source, 32),
    method: str(r.method, 16),
    url,
    host,
    status: num(r.status),
    mime: str(r.mime, 128),
    bytes: num(r.bytes),
    ms: num(r.ms),
    req_headers:
      r.req_headers && typeof r.req_headers === 'object'
        ? (r.req_headers as Json)
        : null,
    res_headers:
      r.res_headers && typeof r.res_headers === 'object'
        ? (r.res_headers as Json)
        : null,
    req_body: truncateBody(r.req_body),
    res_body: truncateBody(r.res_body),
    captured_at: capturedAt,
  };
}
