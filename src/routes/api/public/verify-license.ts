import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/verify-license
 * Body: { device_id?: string, uid?: string|null, parent_invite_code?: string|null }
 * Returns: { licensed: boolean, reason: string, message: string, required_invite_code: string }
 *
 * Rule:
 *   1. If uid is present AND exists in `licensed_uids` whitelist → LICENSED.
 *   2. Else if parent_invite_code matches app_config.required_parent_invite_code → LICENSED.
 *   3. Else → LOCKED with message from app_config.lock_message.
 */
export const Route = createFileRoute('/api/public/verify-license')({
  server: {
    handlers: {
      OPTIONS: async () =>
        new Response(null, {
          status: 204,
          headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type',
          },
        }),
      POST: async ({ request }) => {
        const cors = {
          'Access-Control-Allow-Origin': '*',
          'Content-Type': 'application/json',
        };
        let body: any = {};
        try {
          body = await request.json();
        } catch {}
        const uid = body?.uid ? String(body.uid).trim() : '';
        const inviteCode = body?.parent_invite_code ? String(body.parent_invite_code).trim() : '';
        const deviceId = body?.device_id ? String(body.device_id).slice(0, 128) : null;

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

        const { data: cfg } = await supabaseAdmin
          .from('app_config')
          .select('required_parent_invite_code, lock_message')
          .eq('id', 1)
          .maybeSingle();

        const requiredInvite = (cfg?.required_parent_invite_code ?? '82824101415') as string;
        const lockMessage = (cfg?.lock_message ?? '🔒 Locked. আমাদের team-এর invite code দিয়ে account বানিয়ে login করুন।') as string;

        // 1) UID whitelist bypass
        if (uid) {
          const { data: row } = await supabaseAdmin
            .from('licensed_uids')
            .select('uid')
            .eq('uid', uid)
            .maybeSingle();
          if (row) {
            return new Response(
              JSON.stringify({
                licensed: true,
                reason: 'uid_whitelisted',
                message: '',
                required_invite_code: requiredInvite,
                device_id: deviceId,
              }),
              { headers: cors },
            );
          }
        }

        // 2) Parent invite code check
        if (inviteCode && inviteCode === requiredInvite) {
          return new Response(
            JSON.stringify({
              licensed: true,
              reason: 'invite_match',
              message: '',
              required_invite_code: requiredInvite,
              device_id: deviceId,
            }),
            { headers: cors },
          );
        }

        // 3) LOCKED
        return new Response(
          JSON.stringify({
            licensed: false,
            reason: uid || inviteCode ? 'mismatch' : 'no_login',
            message: lockMessage,
            required_invite_code: requiredInvite,
            device_id: deviceId,
          }),
          { headers: cors },
        );
      },
    },
  },
});
