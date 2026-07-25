
ALTER TABLE public.wingo_signal_cache ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'ai';
ALTER TABLE public.wingo_signal_cache DROP CONSTRAINT IF EXISTS wingo_signal_cache_pkey;
ALTER TABLE public.wingo_signal_cache ADD PRIMARY KEY (issue_id, mode);
