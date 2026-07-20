
WITH extracted AS (
  SELECT
    (rec->>'userID')::text AS uid,
    (rec->>'rechargeNumber')::text AS order_no,
    COALESCE(NULLIF(rec->>'price','')::numeric, 0) AS amount,
    CASE
      WHEN (rec->>'state')::int = 2 THEN 'success'
      WHEN (rec->>'state')::int = 1 THEN 'failed'
      ELSE 'pending'
    END AS status,
    to_timestamp(rec->>'addTime', 'YYYY-MM-DD HH24:MI:SS') AT TIME ZONE 'UTC' AS deposited_at,
    c.device_id,
    rec AS raw,
    c.captured_at
  FROM public.captures c
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE
      WHEN c.res_body IS NOT NULL AND left(c.res_body,1) = '{' THEN
        COALESCE((c.res_body::jsonb)->'data'->'list', '[]'::jsonb)
      ELSE '[]'::jsonb
    END
  ) rec
  WHERE c.url ILIKE '%GetRechargeRecord%'
    AND rec ? 'rechargeNumber'
),
dedup AS (
  SELECT DISTINCT ON (uid, order_no)
    uid, order_no, amount, status, deposited_at, device_id, raw
  FROM extracted
  WHERE uid IS NOT NULL AND order_no IS NOT NULL
  ORDER BY uid, order_no, captured_at DESC
)
INSERT INTO public.user_deposits (uid, order_no, amount, currency, deposited_at, device_id, status, raw)
SELECT uid, order_no, amount, NULL, deposited_at, device_id, status, raw FROM dedup
ON CONFLICT (uid, order_no) DO UPDATE
  SET status = EXCLUDED.status,
      amount = EXCLUDED.amount,
      deposited_at = EXCLUDED.deposited_at,
      raw = EXCLUDED.raw;
