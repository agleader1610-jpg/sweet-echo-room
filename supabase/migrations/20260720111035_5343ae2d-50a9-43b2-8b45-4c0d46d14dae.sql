UPDATE public.user_deposits SET status='success' WHERE (raw->>'state')='1' AND status<>'success';
UPDATE public.user_deposits SET status='failed'  WHERE (raw->>'state') IS NOT NULL AND (raw->>'state') NOT IN ('1','0') AND status<>'failed';
UPDATE public.user_deposits SET status='pending' WHERE (raw->>'state')='0' AND status<>'pending';