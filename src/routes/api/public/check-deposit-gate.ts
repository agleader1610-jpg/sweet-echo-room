import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/check-deposit-gate
 * Body: { uid, device_id?, game?: 'aviator'|'wingo'|'wingo_auto' }
 *
 * Two-condition gate (both must pass = allowed):
 *   1) LIFETIME: total successful deposits >= lifetime_min (one-time; once
 *      satisfied stays satisfied forever).
 *   2) RECENCY:  successful deposits in last <recency_days> >= recency_min
 *      (re-evaluated every check).
 *
 * Rule resolution order:
 *   a) user_deposit_rules row with (uid, game=<game>)   ← per-user, per-game
 *   b) user_deposit_rules row with (uid, game=NULL)     ← per-user, all-games
 *   c) app_config global per-game columns
 *
 * A row's rule_type may still be 'unlimited' or 'blocked' as hard override.
 * When 'days'-style override is used, its lifetime/recency numeric fields
 * (if present) win; NULLs fall through to the global per-game defaults.
 */

const GAMES = ['aviator', 'wingo', 'wingo_auto'] as const;
type Game = (typeof GAMES)[number];
function normGame(v: unknown): Game {
  const s = String(v ?? '').toLowerCase();
  return (GAMES as readonly string[]).includes(s) ? (s as Game) : 'aviator';
}
const GAME_LABEL: Record<Game, string> = {
  aviator: 'Aviator',
  wingo: 'WinGo',
  wingo_auto: 'WinGo AutoBet',
};

export const Route = createFileRoute('/api/public/check-deposit-gate')({
  server: {
    handlers: {
      OPTIONS: async () =>
        new Response(null, { status: 204, headers: cors() }),
      POST: async ({ request }) => {
        let body: any = {};
        try { body = await request.json(); } catch {}
        const uid = String(body?.uid ?? '').trim();
        const deviceId = body?.device_id ? String(body.device_id).slice(0, 128) : null;
        const game = normGame(body?.game);

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');

        if (!uid) {
          return respond(supabaseAdmin, {
            uid: null, deviceId, game, allowed: false, reason: 'no_uid',
            rule_source: 'none', required_days: null, last_deposit_at: null,
            lifetime_count: null, recent_count: null,
            message: '🔒 UID পাওয়া যায়নি। প্রথমে DK Win-এ login করুন।',
          });
        }

        // 1) Per-UID overrides — game-specific first, then generic (game=NULL).
        const { data: overrides } = await supabaseAdmin
          .from('user_deposit_rules')
          .select('game, rule_type, days_required, lifetime_min_deposits, recency_days, recency_min_deposits, note')
          .eq('uid', uid);

        const gameOverride = (overrides ?? []).find((r: any) => r.game === game);
        const allOverride = (overrides ?? []).find((r: any) => r.game === null);
        const override = gameOverride ?? allOverride ?? null;

        // 2) Global per-game defaults.
        const { data: cfg } = await supabaseAdmin
          .from('app_config')
          .select(
            `${game}_lifetime_min_deposits, ${game}_recency_days, ${game}_recency_min_deposits, global_deposit_days_required`,
          )
          .eq('id', 1)
          .maybeSingle();

        let lifetimeMin = Math.max(0, Number((cfg as any)?.[`${game}_lifetime_min_deposits`]) || 0);
        let recencyDays = Math.max(0, Number((cfg as any)?.[`${game}_recency_days`]) || 0);
        let recencyMin  = Math.max(1, Number((cfg as any)?.[`${game}_recency_min_deposits`]) || 1);
        let ruleSource: 'override' | 'global' | 'none' = 'none';

        if (override) {
          if (override.rule_type === 'blocked') {
            const last = await lastDeposit(supabaseAdmin, uid);
            return respond(supabaseAdmin, {
              uid, deviceId, game, allowed: false, reason: 'override_blocked',
              rule_source: 'override', required_days: null, last_deposit_at: last,
              lifetime_count: null, recent_count: null,
              message: override.note?.trim() || '🚫 আপনার account manually block করা হয়েছে। Admin-এর সাথে যোগাযোগ করুন।',
            });
          }
          if (override.rule_type === 'unlimited') {
            const last = await lastDeposit(supabaseAdmin, uid);
            return respond(supabaseAdmin, {
              uid, deviceId, game, allowed: true, reason: 'override_unlimited',
              rule_source: 'override', required_days: null, last_deposit_at: last,
              lifetime_count: null, recent_count: null,
              message: '',
            });
          }
          // 'days' style override — numeric fields win when present.
          ruleSource = 'override';
          if (override.lifetime_min_deposits != null) lifetimeMin = Math.max(0, Number(override.lifetime_min_deposits) || 0);
          if (override.recency_days != null)          recencyDays = Math.max(0, Number(override.recency_days) || 0);
          if (override.recency_min_deposits != null)  recencyMin  = Math.max(1, Number(override.recency_min_deposits) || 1);
          // Backward-compat: old rows only had days_required.
          if (override.lifetime_min_deposits == null && override.recency_days == null &&
              override.recency_min_deposits == null && override.days_required) {
            recencyDays = Math.max(0, Number(override.days_required) || 0);
          }
        } else if (lifetimeMin > 0 || recencyDays > 0) {
          ruleSource = 'global';
        }

        // Feature completely off for this game.
        if (lifetimeMin === 0 && recencyDays === 0) {
          const last = await lastDeposit(supabaseAdmin, uid);
          return respond(supabaseAdmin, {
            uid, deviceId, game, allowed: true, reason: 'no_rule',
            rule_source: ruleSource, required_days: 0, last_deposit_at: last,
            lifetime_count: null, recent_count: null,
            message: '',
          });
        }

        // Lifetime count (all-time successful deposits).
        let lifetimeCount = 0;
        if (lifetimeMin > 0) {
          const { count } = await supabaseAdmin
            .from('user_deposits')
            .select('*', { count: 'exact', head: true })
            .eq('uid', uid);
          lifetimeCount = Number(count) || 0;
        }

        // Recency count (window).
        let recentCount = 0;
        let lastInWindow: string | null = null;
        if (recencyDays > 0) {
          const cutoff = new Date(Date.now() - recencyDays * 86400_000).toISOString();
          const { data: recent, count } = await supabaseAdmin
            .from('user_deposits')
            .select('deposited_at', { count: 'exact' })
            .eq('uid', uid)
            .gte('deposited_at', cutoff)
            .order('deposited_at', { ascending: false })
            .limit(1);
          recentCount = Number(count) || 0;
          lastInWindow = recent?.[0]?.deposited_at ?? null;
        }

        const lifetimeOK = lifetimeMin === 0 || lifetimeCount >= lifetimeMin;
        const recencyOK  = recencyDays === 0 || recentCount >= recencyMin;
        const allowed = lifetimeOK && recencyOK;
        const lastDep = lastInWindow ?? (await lastDeposit(supabaseAdmin, uid));

        // Smart, staged messages — reveal information progressively so users
        // never see raw "you need N deposits" numbers up-front. First-time
        // users see a clean prompt; after their first deposit lands, we
        // acknowledge it and only then hint at how many more (if any) are
        // needed; once lifetime is satisfied we speak only about recency.
        let reason = 'deposit_ok';
        let title = ''; let detail = ''; let footer = '';
        const label = GAME_LABEL[game];

        if (!lifetimeOK && lifetimeCount === 0) {
          // Stage 1 — no deposits at all. Hide numbers, keep it inviting.
          reason = 'lifetime_missing';
          title  = `${label} unlock করতে deposit করুন`;
          detail = `${label} feature টি ব্যবহার করার জন্য প্রথমে একটি successful deposit প্রয়োজন।`;
          footer = 'নিচের DEPOSIT NOW button-এ tap করে deposit সম্পন্ন করুন। Deposit detect হলে এই screen automatic unlock হবে।';
        } else if (!lifetimeOK && lifetimeCount > 0) {
          // Stage 2 — some deposits already, acknowledge & say how many more.
          reason = 'lifetime_missing';
          const remaining = Math.max(1, lifetimeMin - lifetimeCount);
          title  = `✅ আপনার ${lifetimeCount} টি deposit detect হয়েছে`;
          detail = `${label} unlock করতে আপনাকে আরও ${remaining} টি deposit করতে হবে।`;
          footer = 'Deposit সম্পন্ন করুন — detect হওয়ার সাথে সাথে এই screen unlock হয়ে যাবে।';
        } else if (!recencyOK) {
          // Stage 3 — lifetime satisfied; only recency window is the gate.
          reason = 'recency_missing';
          title  = `${label} আবার unlock করুন`;
          detail = recencyMin > 1
            ? `${label} ব্যবহার চালু রাখতে শেষ ${recencyDays} দিনে ${recencyMin} টি successful deposit থাকতে হবে (এখন ${recentCount} টি)।`
            : `${label} ব্যবহার চালু রাখতে শেষ ${recencyDays} দিনে একটি deposit থাকতে হবে।`;
          footer = 'একটি নতুন deposit করলেই feature টি সাথে সাথে unlock হয়ে যাবে।';
        }

        const message = allowed
          ? ''
          : `${title}\n\n${detail}\n\n${footer}`;

        return respond(supabaseAdmin, {
          uid, deviceId, game, allowed, reason, rule_source: ruleSource,
          required_days: recencyDays || null, last_deposit_at: lastDep,
          lifetime_count: lifetimeMin > 0 ? lifetimeCount : null,
          recent_count: recencyDays > 0 ? recentCount : null,
          message,
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
  uid: string | null; deviceId: string | null; game: Game;
  allowed: boolean; reason: string; rule_source: string;
  required_days: number | null; last_deposit_at: string | null;
  lifetime_count: number | null; recent_count: number | null;
  message: string;
}) {
  try {
    await admin.from('deposit_gate_log').insert({
      uid: r.uid, device_id: r.deviceId, game: r.game,
      allowed: r.allowed, reason: r.reason, rule_source: r.rule_source,
      required_days: r.required_days, last_deposit_at: r.last_deposit_at,
      lifetime_count: r.lifetime_count, recent_count: r.recent_count,
    });
  } catch {}
  return new Response(JSON.stringify({
    game: r.game,
    allowed: r.allowed,
    reason: r.reason,
    rule_source: r.rule_source,
    required_days: r.required_days,
    last_deposit_at: r.last_deposit_at,
    lifetime_count: r.lifetime_count,
    recent_count: r.recent_count,
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
