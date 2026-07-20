CREATE TABLE IF NOT EXISTS public.deposit_gate_log (
  id BIGSERIAL PRIMARY KEY,
  uid TEXT,
  device_id TEXT,
  allowed BOOLEAN NOT NULL,
  reason TEXT NOT NULL,
  rule_source TEXT NOT NULL,
  required_days INTEGER,
  last_deposit_at TIMESTAMPTZ,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_deposit_gate_log_checked_at ON public.deposit_gate_log (checked_at DESC);
CREATE INDEX IF NOT EXISTS idx_deposit_gate_log_uid ON public.deposit_gate_log (uid, checked_at DESC);
GRANT ALL ON public.deposit_gate_log TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.deposit_gate_log_id_seq TO service_role;
ALTER TABLE public.deposit_gate_log ENABLE ROW LEVEL SECURITY;