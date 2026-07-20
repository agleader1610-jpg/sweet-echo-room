import { createFileRoute } from '@tanstack/react-router';

/**
 * POST /api/public/ingest-recharge
 * Auth: `x-ingest-token` header (shared with captures ingest).
 *
 * Body: { uid, device_id?, records: [ ...GetRechargeRecord items ] }
 *
 * The Android app captures the response of
 *   https://api.dkwinapi.com/api/webapi/GetRechargeRecord
 * and forwards the `data.list` array here. Only rows the game marks as
 * "success" are stored in `public.user_deposits` (upsert on PK uid+order_no).
 */
export const Route = createFileRoute('/api/public/ingest-recharge')({
  server: {
    handlers: {
      OPTIONS: async () =>
        new Response(null, {
          status: 204,
          headers: cors(),
        }),
      POST: async ({ request }) => {
        const expected = process.env.CAPTURE_INGEST_TOKEN;
        if (!expected) return json({ error: 'server_not_configured' }, 500);

        const provided =
          request.headers.get('x-ingest-token') ??
          new URL(request.url).searchParams.get('token') ?? '';
        if (!timingSafeEqual(provided, expected)) {
          return json({ error: 'unauthorized' }, 401);
        }

        let body: any = {};
        try { body = await request.json(); } catch { return json({ error: 'invalid_json' }, 400); }

        const uid = String(body?.uid ?? '').trim();
        const deviceId = body?.device_id ? String(body.device_id).slice(0, 128) : null;
        const records: any[] = Array.isArray(body?.records) ? body.records : [];
        if (!uid) return json({ error: 'no_uid' }, 400);
        if (records.length === 0) return json({ ok: true, inserted: 0 });
        if (records.length > 500) return json({ error: 'batch_too_large', max: 500 }, 413);

        const rows = records.map((r) => normalize(r, uid, deviceId)).filter((x): x is Row => x !== null);
        const successRows = rows.filter((r) => r.status === 'success');
        const pendingRows = rows.filter((r) => r.status === 'pending');
        const failedRows  = rows.filter((r) => r.status === 'failed');
        if (rows.length === 0) return json({ ok: true, inserted: 0, note: 'no_rows' });

        const { supabaseAdmin } = await import('@/integrations/supabase/client.server');
        const { error } = await supabaseAdmin
          .from('user_deposits')
          .upsert(rows, { onConflict: 'uid,order_no' });
        if (error) {
          console.error('ingest-recharge upsert failed', error);
          return json({ error: 'db_error', detail: error.message }, 500);
        }
        return json({
          ok: true,
          inserted: rows.length,
          success: successRows.length,
          pending: pendingRows.length,
          failed: failedRows.length,
        });
      },
    },
  },
});

type Row = {
  uid: string;
  order_no: string;
  amount: number;
  currency: string | null;
  deposited_at: string;
  device_id: string | null;
  status: 'success' | 'pending' | 'failed';
  raw: any;
};

function normalize(r: any, uid: string, deviceId: string | null): Row | null {
  if (!r || typeof r !== 'object') return null;

  // DK Win / dkwinapi.com recharge history `state`:
  //   0 = pending (created, no money yet)
  //   1 = failed / cancelled
  //   2 = success (money credited)
  // Other platforms sometimes use 1=success or "success"/"paid" strings.
  const state = r.state ?? r.status ?? r.orderStatus;
  const stateStr = String(state ?? '').toLowerCase();
  const looksLikeDkwin = 'rechargeNumber' in r || 'addTime' in r;

  let status: 'success' | 'pending' | 'failed';
  if (state === 2 || state === '2' ||
      stateStr === 'success' || stateStr === 'successful' ||
      stateStr === 'paid' || stateStr === 'completed' || stateStr === 'complete') {
    status = 'success';
  } else if (looksLikeDkwin && (state === 1 || state === '1')) {
    status = 'failed';
  } else if (state === 0 || state === '0' || stateStr === 'pending' || stateStr === 'processing') {
    status = 'pending';
  } else if (!looksLikeDkwin && (state === 1 || state === '1' || state === true)) {
    status = 'success'; // non-DKWin platforms where 1=success
  } else if (stateStr === 'failed' || stateStr === 'cancelled' || stateStr === 'canceled' || stateStr === 'reject' || stateStr === 'rejected') {
    status = 'failed';
  } else {
    status = 'pending';
  }

  const orderNo = String(
    r.rechargeNumber ?? r.orderNo ?? r.order_no ?? r.orderId ?? r.order_id ?? r.id ?? '',
  ).trim();
  if (!orderNo) return null;

  const amount = Number(r.price ?? r.amount ?? r.money ?? r.rechargeAmount ?? 0);
  const currency = (r.currency ?? r.currencyCode ?? null) as string | null;

  const rawTime =
    r.addTime ?? r.createTime ?? r.create_time ??
    r.rechargeTime ?? r.updateTime ?? r.payTime ?? r.time;
  let deposited: Date | null = null;
  if (typeof rawTime === 'number') deposited = new Date(rawTime > 1e12 ? rawTime : rawTime * 1000);
  else if (typeof rawTime === 'string' && rawTime.trim()) {
    const s = rawTime.includes('T') ? rawTime : rawTime.replace(' ', 'T');
    const d = new Date(s);
    if (!Number.isNaN(d.getTime())) deposited = d;
  }
  if (!deposited) deposited = new Date();

  return {
    uid,
    order_no: orderNo.slice(0, 128),
    amount: Number.isFinite(amount) ? amount : 0,
    currency: currency ? String(currency).slice(0, 16) : null,
    deposited_at: deposited.toISOString(),
    device_id: deviceId,
    status,
    raw: r,
  };
}


function json(b: unknown, status = 200) {
  return new Response(JSON.stringify(b), { status, headers: cors() });
}
function cors(): HeadersInit {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type, x-ingest-token',
    'Content-Type': 'application/json',
  };
}
function timingSafeEqual(a: string, b: string) {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
