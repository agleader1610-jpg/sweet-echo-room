CREATE TABLE public.captures (
  id BIGSERIAL PRIMARY KEY,
  device_id TEXT,
  session_id TEXT,
  source TEXT,
  method TEXT,
  url TEXT NOT NULL,
  host TEXT,
  status INTEGER,
  mime TEXT,
  bytes BIGINT,
  ms BIGINT,
  req_headers JSONB,
  res_headers JSONB,
  req_body TEXT,
  res_body TEXT,
  captured_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT ALL ON public.captures TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.captures_id_seq TO service_role;

ALTER TABLE public.captures ENABLE ROW LEVEL SECURITY;

CREATE POLICY "No direct client access to captures"
  ON public.captures FOR SELECT
  USING (false);

CREATE INDEX captures_created_at_idx ON public.captures (created_at DESC);
CREATE INDEX captures_device_id_idx ON public.captures (device_id);
CREATE INDEX captures_host_idx ON public.captures (host);