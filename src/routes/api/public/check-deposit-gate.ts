import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/check-deposit-gate
 * Body: { uid, device_id? }
 *
 * Returns whether the given UID currently satisfies the deposit rule.
 * Order:
 *   1. Per-UID override in `user_deposit_rules` (blocked | unlimited | days N)
 *   2. Global `app_config.global_deposit_days_required` (0 = feature off)
 *   3. Default → allowed.
 *
 * "Deposit" = a row in `user_deposits` with `deposited_at` inside the window
 * (rows are only inserted after the server confirms `state=success`).
 */
export const Route = createFileRoute('/api/public/check-deposit-gate')({
  server: {
    handlers: {
      OPTIONS: async () =>
        new Response(null, {
          status: 204,
          headers: cors(),
        }),
      POST: async ({ request }) => {
        let body: any = {};
        try { body = await request.json(); } catch {}
        const uid = String(body?.uid ?? '').trim();
        const deviceId = body?.device_id ? String(body.device_id).slice(0, 128) : null;

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

        if (!uid) {
          return respond(supabaseAdmin, {
            uid: null, deviceId, allowed: false, reason: 'no_uid',
            rule_source: 'none', required_days: null, last_deposit_at: null,
            message: '🔒 UID পাওয়া যায়নি। প্রথমে DK Win-এ login করুন।',
          });
        }

        const { data: rule } = await supabaseAdmin
          .from('user_deposit_rules')
          .select('rule_type, days_required, note')
          .eq('uid', uid)
          .maybeSingle();

        let requiredDays = 0;
        let ruleSource: 'override' | 'global' | 'none' = 'none';

        if (rule) {
          ruleSource = 'override';
          if (rule.rule_type === 'blocked') {
            const last = await lastDeposit(supabaseAdmin, uid);
            return respond(supabaseAdmin, {
              uid, deviceId, allowed: false, reason: 'override_blocked',
              rule_source: ruleSource, required_days: null, last_deposit_at: last,
              message: rule.note?.trim() || '🚫 আপনার account manually block করা হয়েছে। Admin-এর সাথে যোগাযোগ করুন।',
            });
          }
          if (rule.rule_type === 'unlimited') {
            const last = await lastDeposit(supabaseAdmin, uid);
            return respond(supabaseAdmin, {
              uid, deviceId, allowed: true, reason: 'override_unlimited',
              rule_source: ruleSource, required_days: null, last_deposit_at: last,
              message: '',
            });
          }
          requiredDays = Math.max(0, Number(rule.days_required) || 0);
        } else {
          const { data: cfg } = await supabaseAdmin
            .from('app_config')
            .select('global_deposit_days_required')
            .eq('id', 1)
            .maybeSingle();
          requiredDays = Math.max(0, Number((cfg as any)?.global_deposit_days_required) || 0);
          ruleSource = requiredDays > 0 ? 'global' : 'none';
        }

        if (requiredDays === 0) {
          const last = await lastDeposit(supabaseAdmin, uid);
          return respond(supabaseAdmin, {
            uid, deviceId, allowed: true, reason: 'no_rule',
            rule_source: ruleSource, required_days: 0, last_deposit_at: last,
            message: '',
          });
        }

        const cutoff = new Date(Date.now() - requiredDays * 86400_000).toISOString();
        const { data: recent } = await supabaseAdmin
          .from('user_deposits')
          .select('deposited_at')
          .eq('uid', uid)
          .gte('deposited_at', cutoff)
          .order('deposited_at', { ascending: false })
          .limit(1);
        const hasDeposit = (recent?.length ?? 0) > 0;
        const lastDep = recent?.[0]?.deposited_at ?? (await lastDeposit(supabaseAdmin, uid));

        return respond(supabaseAdmin, {
          uid, deviceId,
          allowed: hasDeposit,
          reason: hasDeposit ? 'deposit_ok' : 'deposit_missing',
          rule_source: ruleSource,
          required_days: requiredDays,
          last_deposit_at: lastDep,
          message: hasDeposit ? '' :
            `🔒 সর্বশেষ ${requiredDays} দিনে কোনো successful deposit পাওয়া যায়নি।\n\nSignal ব্যবহার করতে DK Win tab-এ গিয়ে deposit করুন — deposit করার পর আপনার Recharge History একবার open করলেই এখানে auto unlock হবে।`,
        });
      },
    },
  },
});

async function lastDeposit(admin: any, uid: string): Promise<string | null> {
  const { data } = await admin
    .from('user_deposits')
    .select('deposited_at')
    .eq('uid', uid)
    .order('deposited_at', { ascending: false })
    .limit(1);
  return data?.[0]?.deposited_at ?? null;
}

async function respond(admin: any, r: {
  uid: string | null; deviceId: string | null;
  allowed: boolean; reason: string; rule_source: string;
  required_days: number | null; last_deposit_at: string | null; message: string;
}) {
  try {
    await admin.from('deposit_gate_log').insert({
      uid: r.uid, device_id: r.deviceId,
      allowed: r.allowed, reason: r.reason, rule_source: r.rule_source,
      required_days: r.required_days, last_deposit_at: r.last_deposit_at,
    });
  } catch {}
  return new Response(JSON.stringify({
    allowed: r.allowed,
    reason: r.reason,
    rule_source: r.rule_source,
    required_days: r.required_days,
    last_deposit_at: r.last_deposit_at,
    message: r.message,
  }), { headers: cors() });
}

function cors(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type',
    'Content-Type': 'application/json',
  };
}
