ALTER TABLE public.user_deposits ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'success';
CREATE INDEX IF NOT EXISTS user_deposits_uid_status_idx ON public.user_deposits(uid, status);