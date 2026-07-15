
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

DO $$
DECLARE r RECORD; j JSONB; d JSONB;
BEGIN
  FOR r IN SELECT * FROM public.captures WHERE res_body IS NOT NULL AND left(res_body,1)='{' LOOP
    BEGIN j := r.res_body::jsonb; EXCEPTION WHEN others THEN CONTINUE; END;
    d := COALESCE(j->'data', j);
    IF d IS NULL OR jsonb_typeof(d) <> 'object' THEN CONTINUE; END IF;
    IF NOT (d ? 'userId' OR d ? 'userName' OR d ? 'nickName' OR d ? 'amount' OR d ? 'balance') THEN CONTINUE; END IF;
    BEGIN
      INSERT INTO public.user_snapshots(device_id, session_id, host, source_url, uid, user_name, nick_name, phone, balance, amount_of_code, raw, captured_at)
      VALUES (
        r.device_id, r.session_id, r.host, r.url,
        COALESCE(d->>'userId', d->>'uid', d->>'user_id', d->>'memberId'),
        COALESCE(d->>'userName', d->>'username', d->>'account', d->>'loginName'),
        COALESCE(d->>'nickName', d->>'nickname', d->>'name'),
        COALESCE(d->>'phone', d->>'mobile', d->>'phoneNumber'),
        NULLIF(d->>'amount','')::numeric,
        NULLIF(d->>'amountofCode','')::numeric,
        d, COALESCE(r.captured_at, r.created_at)
      );
    EXCEPTION WHEN others THEN CONTINUE; END;
  END LOOP;
END $$;
