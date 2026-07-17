-- Round history: every confirmed crash
CREATE TABLE public.round_history (
  id bigserial PRIMARY KEY,
  device_id text,
  round_id bigint NOT NULL,
  multiplier numeric NOT NULL,
  crashed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX round_history_device_round_idx ON public.round_history (device_id, round_id DESC);
CREATE INDEX round_history_round_idx ON public.round_history (round_id DESC);
GRANT SELECT, INSERT ON public.round_history TO anon;
GRANT SELECT, INSERT ON public.round_history TO authenticated;
GRANT ALL ON public.round_history TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.round_history_id_seq TO anon, authenticated, service_role;
ALTER TABLE public.round_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "anon insert round history" ON public.round_history FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY "anon read round history" ON public.round_history FOR SELECT TO anon USING (true);

-- Predictions: every AI or local signal emitted
CREATE TABLE public.predictions (
  id bigserial PRIMARY KEY,
  device_id text,
  round_id bigint NOT NULL,
  target_multiplier numeric,
  action text NOT NULL,
  confidence text,
  source text NOT NULL,
  reasoning text,
  ai_model text,
  latency_ms integer,
  raw jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX predictions_round_idx ON public.predictions (round_id DESC);
CREATE INDEX predictions_device_idx ON public.predictions (device_id, created_at DESC);
GRANT SELECT, INSERT ON public.predictions TO anon;
GRANT SELECT, INSERT ON public.predictions TO authenticated;
GRANT ALL ON public.predictions TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.predictions_id_seq TO anon, authenticated, service_role;
ALTER TABLE public.predictions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "anon insert predictions" ON public.predictions FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY "anon read predictions" ON public.predictions FOR SELECT TO anon USING (true);

-- Prediction results: verification after the round crashes
CREATE TABLE public.prediction_results (
  id bigserial PRIMARY KEY,
  prediction_id bigint REFERENCES public.predictions(id) ON DELETE CASCADE,
  device_id text,
  round_id bigint NOT NULL,
  target_multiplier numeric,
  actual_multiplier numeric NOT NULL,
  outcome text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX prediction_results_round_idx ON public.prediction_results (round_id DESC);
CREATE INDEX prediction_results_device_idx ON public.prediction_results (device_id, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON public.prediction_results TO anon;
GRANT SELECT, INSERT, UPDATE ON public.prediction_results TO authenticated;
GRANT ALL ON public.prediction_results TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.prediction_results_id_seq TO anon, authenticated, service_role;
ALTER TABLE public.prediction_results ENABLE ROW LEVEL SECURITY;
CREATE POLICY "anon insert results" ON public.prediction_results FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY "anon read results" ON public.prediction_results FOR SELECT TO anon USING (true);