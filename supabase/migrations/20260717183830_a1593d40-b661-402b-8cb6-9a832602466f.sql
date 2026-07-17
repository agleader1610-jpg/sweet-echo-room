
ALTER TABLE public.app_config
  ADD COLUMN IF NOT EXISTS required_parent_invite_code TEXT NOT NULL DEFAULT '82824101415',
  ADD COLUMN IF NOT EXISTS lock_message TEXT NOT NULL DEFAULT '🔒 এই signal system শুধু আমাদের team member-দের জন্য। আমাদের invite code দিয়ে account তৈরি করে Debug (Browser) tab থেকে login করুন।';

CREATE TABLE IF NOT EXISTS public.licensed_uids (
  uid TEXT PRIMARY KEY,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT ALL ON public.licensed_uids TO service_role;
ALTER TABLE public.licensed_uids ENABLE ROW LEVEL SECURITY;
