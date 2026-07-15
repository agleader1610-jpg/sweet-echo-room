import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';

// Public endpoint the Android app hits at launch.
// It returns only the manually saved update config from the admin panel.
// Fail-open: any error → { ok: false } so the app continues normally.
export const Route = createFileRoute('/api/public/app-version')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      GET: async ({ request }) => {
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

          const cfgRes = await supa
            .from('app_config')
            .select('min_version,latest_version,latest_version_name,apk_url,update_message,force_update,updated_at')
            .eq('id', 1)
            .maybeSingle();

          if (cfgRes.error || !cfgRes.data) return json({ ok: false }, 200);

          return json({
            ok: true,
            min_version: cfgRes.data.min_version,
            force_update: cfgRes.data.force_update,
            update_message: cfgRes.data.update_message,
            latest_version: cfgRes.data.latest_version,
            latest_version_name: cfgRes.data.latest_version_name,
            apk_url: cfgRes.data.apk_url,
            updated_at: cfgRes.data.updated_at,
          }, 200);
        } catch {
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
