
-- Deposit Gate System schema

-- 1. user_deposits: captured successful recharges
CREATE TABLE public.user_deposits (
  uid text NOT NULL,
  order_no text NOT NULL,
  amount numeric NOT NULL DEFAULT 0,
  currency text,
  deposited_at timestamptz NOT NULL,
  captured_at timestamptz NOT NULL DEFAULT now(),
  device_id text,
  raw jsonb,
  PRIMARY KEY (uid, order_no)
);
CREATE INDEX idx_user_deposits_uid_time ON public.user_deposits (uid, deposited_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_deposits TO authenticated;
GRANT ALL ON public.user_deposits TO service_role;
ALTER TABLE public.user_deposits ENABLE ROW LEVEL SECURITY;
CREATE POLICY "service role manages deposits" ON public.user_deposits
  FOR ALL TO service_role USING (true) WITH CHECK (true);

-- 2. user_deposit_rules: per-UID overrides
CREATE TABLE public.user_deposit_rules (
  uid text PRIMARY KEY,
  rule_type text NOT NULL CHECK (rule_type IN ('unlimited','days','blocked')),
  days_required integer NOT NULL DEFAULT 0,
  note text,
  updated_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_deposit_rules TO authenticated;
GRANT ALL ON public.user_deposit_rules TO service_role;
ALTER TABLE public.user_deposit_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY "service role manages deposit rules" ON public.user_deposit_rules
  FOR ALL TO service_role USING (true) WITH CHECK (true);

-- 3. deposit_gate_log: audit
CREATE TABLE public.deposit_gate_log (
  id bigserial PRIMARY KEY,
  uid text,
  device_id text,
  allowed boolean NOT NULL,
  reason text,
  rule_source text,
  required_days integer,
  last_deposit_at timestamptz,
  checked_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_deposit_gate_log_time ON public.deposit_gate_log (checked_at DESC);
GRANT SELECT, INSERT ON public.deposit_gate_log TO authenticated;
GRANT ALL ON public.deposit_gate_log TO service_role;
ALTER TABLE public.deposit_gate_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY "service role manages gate log" ON public.deposit_gate_log
  FOR ALL TO service_role USING (true) WITH CHECK (true);

-- 4. app_config: add global rule column
ALTER TABLE public.app_config
  ADD COLUMN IF NOT EXISTS global_deposit_days_required integer NOT NULL DEFAULT 0;
