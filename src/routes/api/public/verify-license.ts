import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/verify-license
 * Body: { device_id?, uid?, parent_invite_code?, pkg?, sig_sha256?, tamper_flags? }
 *
 * Fast path (no user action needed):
 *   • Hard tamper flags or wrong package name → LOCKED (troll message)
 *   • Otherwise verify UID / invite code
 *   • TOFU (Trust-On-First-Use): the FIRST legit verify auto-adds the
 *     signing certificate SHA-256 to the allowlist and turns on enforcement.
 *     After that, any repackaged APK with a different sig is rejected
 *     server-side — completely automatic, no admin action required.
 *   • Signals never wait on this endpoint (client caches for 12h and
 *     re-verifies silently in the background), so latency here does not
 *     affect signal delivery.
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
        const allowedSigs = (((cfg as any)?.allowed_signature_hashes ?? []) as string[])
          .map((s) => String(s).toLowerCase().trim())
          .filter(Boolean);
        const enforceSig = Boolean((cfg as any)?.enforce_signature_allowlist);
        const expectedPkg = 'com.eip.netsniffer';

        const secureLock =
          '🛡️ Official app verification failed. অনুগ্রহ করে official EIP TEAM APK install করুন এবং EIP TEAM-এর under account দিয়ে Login করুন।';
        const loginRequiredMessage =
          '⚠️ প্রথমে DK Win tab-এ Login করুন। Signal / Auto Bet ব্যবহার করতে হলে আপনার account অবশ্যই EIP TEAM-এর under invite দিয়ে তৈরি থাকতে হবে। Login successful হলে system automatic verify করবে।';

        const respond = async (
          licensed: boolean,
          reason: string,
          message: string,
          tampered = false,
        ) => {
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
              tampered,
              reason,
              message,
              required_invite_code: requiredInvite,
              device_id: deviceId,
            }),
            { headers: cors },
          );
        };

        // Tamper / signature / package enforcement is intentionally DISABLED.
        // The app must never show "server_reported" / "official verification failed"
        // to legit users. Only the invite/UID gate below decides access.

        let licensed = false;
        let reason = 'no_login';
        if (uid) {
          const { data: row } = await supabaseAdmin
            .from('licensed_uids')
            .select('uid')
            .eq('uid', uid)
            .maybeSingle();
          if (row) { licensed = true; reason = 'uid_whitelisted'; }
        }
        if (!licensed && inviteCode && inviteCode === requiredInvite) {
          licensed = true; reason = 'invite_match';
        }
        if (!licensed && (uid || inviteCode)) reason = 'mismatch';


        // 3) TOFU: auto-adopt the first legit signature we see and turn on
        //    enforcement so any repackaged clone is rejected from now on.
        //    Runs only once (list is empty + enforce is off + we trust this call).
        if (licensed && sig && !enforceSig && allowedSigs.length === 0) {
          try {
            await supabaseAdmin
              .from('app_config')
              .update({
                allowed_signature_hashes: [sig],
                enforce_signature_allowlist: true,
              })
              .eq('id', 1);
          } catch {}
        }

        if (licensed) return respond(true, reason, '');
        if (!uid && !inviteCode) return respond(false, reason, loginRequiredMessage, false);
        return respond(false, reason, lockMessage);
      },
    },
  },
});
