ALTER TABLE public.predictions
  ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER,
  ADD COLUMN IF NOT EXISTS completion_tokens INTEGER,
  ADD COLUMN IF NOT EXISTS total_tokens INTEGER,
  ADD COLUMN IF NOT EXISTS cost_credits NUMERIC(12, 6);

CREATE INDEX IF NOT EXISTS idx_predictions_created_at_source
  ON public.predictions (created_at DESC, source);

CREATE INDEX IF NOT EXISTS idx_predictions_device_created
  ON public.predictions (device_id, created_at DESC);