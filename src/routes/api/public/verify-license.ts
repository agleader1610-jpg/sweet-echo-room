import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/verify-license
 * Body:
 *   {
 *     device_id?: string,
 *     uid?: string|null,
 *     parent_invite_code?: string|null,
 *     pkg?: string,           // package name reported by app
 *     sig_sha256?: string,    // APK signing cert SHA-256
 *     tamper_flags?: string   // comma-separated tamper detections
 *   }
 * Returns: { licensed, reason, message, required_invite_code }
 *
 * Priority:
 *   0. Any tamper_flags OR wrong package → LOCKED with troll message.
 *   1. If enforce_signature_allowlist && sig not in allowed_signature_hashes → LOCKED.
 *   2. If uid in `licensed_uids` whitelist → LICENSED.
 *   3. If parent_invite_code matches required → LICENSED.
 *   4. Else → LOCKED.
 *
 * Every attempt is logged to license_verify_log.
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
        try { body = await request.json(); } catch {}
        const uid = body?.uid ? String(body.uid).trim() : '';
        const inviteCode = body?.parent_invite_code ? String(body.parent_invite_code).trim() : '';
        const deviceId = body?.device_id ? String(body.device_id).slice(0, 128) : null;
        const pkg = body?.pkg ? String(body.pkg).slice(0, 128) : '';
        const sig = body?.sig_sha256 ? String(body.sig_sha256).slice(0, 128).toLowerCase() : '';
        const tamperFlags = body?.tamper_flags ? String(body.tamper_flags).slice(0, 512) : '';

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

        const { data: cfg } = await supabaseAdmin
          .from('app_config')
          .select('required_parent_invite_code, lock_message, allowed_signature_hashes, enforce_signature_allowlist')
          .eq('id', 1)
          .maybeSingle();

        const requiredInvite = (cfg?.required_parent_invite_code ?? '82824101415') as string;
        const lockMessage = (cfg?.lock_message ??
          '🔒 Locked. আমাদের team-এর invite code দিয়ে account বানিয়ে login করুন।') as string;
        const allowedSigs = ((cfg as any)?.allowed_signature_hashes ?? []) as string[];
        const enforceSig = Boolean((cfg as any)?.enforce_signature_allowlist);
        const expectedPkg = 'com.eip.netsniffer';

        const trollLock = '🤡 তুই কি ভাবছিলি হ্যাক করে বাইঁচে যাবি? Tampered/repackaged APK detect হইছে — চিরতরে LOCKED। মায়ের কোলে ফিরে যা।';

        const respond = async (licensed: boolean, reason: string, message: string) => {
          try {
            await supabaseAdmin.from('license_verify_log').insert({
              device_id: deviceId,
              uid: uid || null,
              invite: inviteCode || null,
              pkg: pkg || null,
              sig_sha256: sig || null,
              tamper_flags: tamperFlags || null,
              licensed,
              reason,
            });
          } catch {}
          return new Response(
            JSON.stringify({
              licensed,
              reason,
              message,
              required_invite_code: requiredInvite,
              device_id: deviceId,
            }),
            { headers: cors },
          );
        };

        // 0) Tamper / repackage → hard lock
        if (tamperFlags) return respond(false, 'tampered', trollLock);
        if (pkg && pkg !== expectedPkg) return respond(false, 'pkg_renamed', trollLock);

        // 1) Signature allowlist
        if (enforceSig && allowedSigs.length > 0) {
          const norm = allowedSigs.map((s) => String(s).toLowerCase().trim()).filter(Boolean);
          if (!sig || !norm.includes(sig)) {
            return respond(false, 'sig_mismatch', trollLock);
          }
        }

        // 2) UID whitelist
        if (uid) {
          const { data: row } = await supabaseAdmin
            .from('licensed_uids')
            .select('uid')
            .eq('uid', uid)
            .maybeSingle();
          if (row) return respond(true, 'uid_whitelisted', '');
        }

        // 3) Invite code match
        if (inviteCode && inviteCode === requiredInvite) {
          return respond(true, 'invite_match', '');
        }

        // 4) LOCKED
        return respond(false, uid || inviteCode ? 'mismatch' : 'no_login', lockMessage);
      },
    },
  },
});
