import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';

// Public read-only endpoint the Android app polls at launch to decide
// whether to force an update. Fail-open: if anything goes wrong, we
// return null so the app continues normally.
export const Route = createFileRoute('/api/public/app-version')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      GET: async () => {
        try {
          const url = process.env.SUPABASE_URL!;
          const key = process.env.SUPABASE_PUBLISHABLE_KEY!;
          const supa = createClient(url, key, {
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
          const { data, error } = await supa
            .from('app_config')
            .select('min_version,latest_version,latest_version_name,apk_url,update_message,force_update,updated_at')
            .eq('id', 1)
            .maybeSingle();
          if (error || !data) {
            return json({ ok: false }, 200);
          }
          return json({ ok: true, ...data }, 200);
        } catch (e) {
          return json({ ok: false }, 200);
        }
      },
    },
  },
});

function cors(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type',
    'Cache-Control': 'no-store',
  };
}
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...cors() },
  });
}
