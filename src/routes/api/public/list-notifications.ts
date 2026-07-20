import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/list-notifications
 * Body: { uid?: string, since?: string(iso) }
 * Returns broadcast notifications (target_uid IS NULL) plus any targeted to
 * the caller's uid. Newest first. Public read (safe columns only).
 */
export const Route = createFileRoute('/api/public/list-notifications')({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: cors() }),
      POST: async ({ request }) => {
        let body: any = {};
        try { body = await request.json(); } catch {}
        const uid = body?.uid ? String(body.uid).trim().slice(0, 64) : null;

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
        let q = supabaseAdmin
          .from('notifications')
          .select('id, title, body, target_uid, created_at')
          .order('created_at', { ascending: false })
          .limit(50);
        q = uid ? q.or(`target_uid.is.null,target_uid.eq.${uid}`) : q.is('target_uid', null);
        const { data, error } = await q;
        if (error) {
          return new Response(JSON.stringify({ items: [] }), { headers: cors() });
        }
        return new Response(JSON.stringify({ items: data ?? [] }), { headers: cors() });
      },
    },
  },
});

function cors(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type',
    'Content-Type': 'application/json',
  };
}
