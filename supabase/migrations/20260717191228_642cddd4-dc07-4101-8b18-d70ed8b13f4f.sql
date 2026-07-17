
ALTER TABLE public.app_config
  ADD COLUMN IF NOT EXISTS allowed_signature_hashes text[] DEFAULT '{}'::text[],
  ADD COLUMN IF NOT EXISTS enforce_signature_allowlist boolean DEFAULT false;

CREATE TABLE IF NOT EXISTS public.license_verify_log (
  id bigserial PRIMARY KEY,
  device_id text,
  uid text,
  invite text,
  pkg text,
  sig_sha256 text,
  tamper_flags text,
  licensed boolean NOT NULL,
  reason text,
  created_at timestamptz NOT NULL DEFAULT now()
);
GRANT ALL ON public.license_verify_log TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.license_verify_log_id_seq TO service_role;
ALTER TABLE public.license_verify_log ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS license_verify_log_created_idx
  ON public.license_verify_log (created_at DESC);
