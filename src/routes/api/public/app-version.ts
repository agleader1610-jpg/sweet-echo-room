import { createFileRoute } from '@tanstack/react-router';
import { createClient } from '@supabase/supabase-js';
import { fetchBuildsFromGithub } from '@/lib/github-releases';

// Public endpoint the Android app hits at launch. Merges:
//  - admin-controlled gate (min_version, force_update, update_message) from DB
//  - live latest APK info (latest_version + apk_url) from GitHub Releases
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

          const [cfgRes, builds] = await Promise.all([
            supa
              .from('app_config')
              .select('min_version,update_message,force_update,updated_at')
              .eq('id', 1)
              .maybeSingle(),
            fetchBuildsFromGithub().catch(() => []),
          ]);

          if (cfgRes.error || !cfgRes.data) return json({ ok: false }, 200);
          const latest = builds[0];
          const origin = new URL(request.url).origin;

          return json({
            ok: true,
            min_version: cfgRes.data.min_version,
            force_update: cfgRes.data.force_update,
            update_message: cfgRes.data.update_message,
            latest_version: latest?.runNumber ?? cfgRes.data.min_version,
            latest_version_name: latest ? `1.0.${latest.runNumber}` : '',
            apk_url: latest ? `${origin}/api/public/download-apk/${latest.runNumber}` : '',
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
