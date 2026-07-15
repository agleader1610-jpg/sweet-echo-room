CREATE TABLE public.app_config (
  id INT PRIMARY KEY DEFAULT 1,
  min_version INT NOT NULL DEFAULT 1,
  latest_version INT NOT NULL DEFAULT 1,
  latest_version_name TEXT NOT NULL DEFAULT '1.0',
  apk_url TEXT NOT NULL DEFAULT '',
  update_message TEXT NOT NULL DEFAULT 'নতুন version এসেছে। অনুগ্রহ করে update করুন।',
  force_update BOOLEAN NOT NULL DEFAULT true,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT single_row CHECK (id = 1)
);

GRANT SELECT ON public.app_config TO anon;
GRANT SELECT ON public.app_config TO authenticated;
GRANT ALL ON public.app_config TO service_role;

ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public read app_config" ON public.app_config FOR SELECT TO anon, authenticated USING (true);

INSERT INTO public.app_config (id, min_version, latest_version, latest_version_name, apk_url, force_update)
VALUES (1, 1, 1, '1.0', '', false);
