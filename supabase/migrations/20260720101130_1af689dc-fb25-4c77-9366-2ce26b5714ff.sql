
-- Backfill user_deposits from previously captured GetRechargeRecord responses.
-- DK Win: state=2 = success. rechargeNumber = order id. price = amount. addTime = timestamp.
INSERT INTO public.user_deposits (uid, order_no, amount, currency, deposited_at, device_id, raw, captured_at)
SELECT
  (rec->>'userID')::text                                         AS uid,
  (rec->>'rechargeNumber')                                       AS order_no,
  COALESCE(NULLIF(rec->>'price','')::numeric, 0)                 AS amount,
  NULL                                                           AS currency,
  to_timestamp(rec->>'addTime', 'YYYY-MM-DD HH24:MI:SS') AT TIME ZONE 'UTC' AS deposited_at,
  NULL                                                           AS device_id,
  rec                                                            AS raw,
  now()                                                          AS captured_at
FROM (
  SELECT jsonb_array_elements(c.res_body::jsonb->'data'->'list') AS rec
  FROM public.captures c
  WHERE c.url ILIKE '%GetRechargeRecord%'
    AND c.res_body IS NOT NULL
    AND LEFT(c.res_body,1) = '{'
) t
WHERE (rec->>'state') = '2'
  AND (rec->>'rechargeNumber') IS NOT NULL
  AND (rec->>'userID') IS NOT NULL
ON CONFLICT (uid, order_no) DO NOTHING;
