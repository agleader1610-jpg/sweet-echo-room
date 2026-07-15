
CREATE TABLE public.visits (
  id BIGSERIAL PRIMARY KEY,
  ip TEXT,
  user_agent TEXT,
  path TEXT,
  referer TEXT,
  country TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX visits_created_at_idx ON public.visits (created_at DESC);
CREATE INDEX visits_ip_idx ON public.visits (ip);

GRANT ALL ON public.visits TO service_role;
-- No anon/authenticated grants: only server-side admin code reads/writes.

ALTER TABLE public.visits ENABLE ROW LEVEL SECURITY;
-- No policies: all access goes through service_role in server functions.
