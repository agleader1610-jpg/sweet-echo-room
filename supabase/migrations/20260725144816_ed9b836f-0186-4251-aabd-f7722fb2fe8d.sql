
CREATE TABLE public.wingo_signal_cache (
  issue_id TEXT PRIMARY KEY,
  game_code TEXT NOT NULL DEFAULT 'WinGo_30S',
  pick TEXT NOT NULL,
  confidence TEXT NOT NULL DEFAULT 'MEDIUM',
  reasoning TEXT,
  source TEXT NOT NULL DEFAULT 'ai',
  ai_model TEXT,
  ai_error TEXT,
  credits_exhausted BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON public.wingo_signal_cache TO anon, authenticated;
GRANT ALL ON public.wingo_signal_cache TO service_role;
ALTER TABLE public.wingo_signal_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY "read wingo signal cache" ON public.wingo_signal_cache FOR SELECT TO anon, authenticated USING (true);
CREATE POLICY "insert wingo signal cache" ON public.wingo_signal_cache FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE INDEX idx_wingo_signal_cache_created ON public.wingo_signal_cache (created_at DESC);
