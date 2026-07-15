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
ALTER TABLE public.visits ENABLE ROW LEVEL SECURITY;

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
  ON public.captures FOR SELECT USING (false);
CREATE INDEX captures_created_at_idx ON public.captures (created_at DESC);
CREATE INDEX captures_device_id_idx ON public.captures (device_id);
CREATE INDEX captures_host_idx ON public.captures (host);

CREATE TABLE public.user_snapshots (
  id BIGSERIAL PRIMARY KEY,
  device_id TEXT,
  session_id TEXT,
  host TEXT,
  source_url TEXT,
  uid TEXT,
  user_name TEXT,
  nick_name TEXT,
  phone TEXT,
  balance NUMERIC,
  amount_of_code NUMERIC,
  raw JSONB,
  captured_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON public.user_snapshots (device_id, captured_at DESC);
CREATE INDEX ON public.user_snapshots (uid);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_snapshots TO authenticated;
GRANT ALL ON public.user_snapshots TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.user_snapshots_id_seq TO authenticated, service_role;
ALTER TABLE public.user_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY "No direct client access to user_snapshots"
  ON public.user_snapshots FOR SELECT USING (false);

CREATE OR REPLACE FUNCTION public.extract_user_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  j JSONB; d JSONB;
  v_uid TEXT; v_user TEXT; v_nick TEXT; v_phone TEXT;
  v_balance NUMERIC; v_code NUMERIC;
BEGIN
  IF NEW.res_body IS NULL OR length(NEW.res_body) < 2 OR left(NEW.res_body,1) <> '{' THEN
    RETURN NEW;
  END IF;
  BEGIN j := NEW.res_body::jsonb;
  EXCEPTION WHEN others THEN RETURN NEW; END;

  d := COALESCE(j->'data', j);
  IF d IS NULL OR jsonb_typeof(d) <> 'object' THEN RETURN NEW; END IF;

  v_uid   := COALESCE(d->>'userId', d->>'uid', d->>'user_id', d->>'memberId');
  v_user  := COALESCE(d->>'userName', d->>'username', d->>'account', d->>'loginName');
  v_nick  := COALESCE(d->>'nickName', d->>'nickname', d->>'name');
  v_phone := COALESCE(d->>'phone', d->>'mobile', d->>'phoneNumber');
  BEGIN v_balance := COALESCE(NULLIF(d->>'amount','')::numeric, NULLIF(d->>'balance','')::numeric, NULLIF(d->>'money','')::numeric); EXCEPTION WHEN others THEN v_balance := NULL; END;
  BEGIN v_code := NULLIF(d->>'amountofCode','')::numeric; EXCEPTION WHEN others THEN v_code := NULL; END;

  IF v_uid IS NULL AND v_user IS NULL AND v_nick IS NULL AND v_balance IS NULL THEN
    RETURN NEW;
  END IF;

  INSERT INTO public.user_snapshots(
    device_id, session_id, host, source_url,
    uid, user_name, nick_name, phone, balance, amount_of_code, raw, captured_at
  ) VALUES (
    NEW.device_id, NEW.session_id, NEW.host, NEW.url,
    v_uid, v_user, v_nick, v_phone, v_balance, v_code,
    d, COALESCE(NEW.captured_at, NEW.created_at)
  );
  RETURN NEW;
END;
$$;

CREATE TRIGGER captures_extract_snapshot
  AFTER INSERT ON public.captures
  FOR EACH ROW EXECUTE FUNCTION public.extract_user_snapshot();

REVOKE EXECUTE ON FUNCTION public.extract_user_snapshot() FROM PUBLIC, anon, authenticated;