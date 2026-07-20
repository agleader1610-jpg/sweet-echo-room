
# Deposit Gate System

Login-এর পর user-কে দিনে একবার RechargeHistory page-এ force redirect করে `GetRechargeRecord` API capture করব, successful deposits server-এ upload করে rule অনুযায়ী Aviator/WinGo/AutoBet unlock করব। Browser tab সবসময় খোলা থাকবে যাতে user deposit করতে পারে।

## Backend (Lovable Cloud)

**নতুন tables** (migration):
- `user_deposits(uid, order_no, amount, deposited_at, captured_at)` — PK (uid, order_no); server GetRechargeRecord capture থেকে successful rows insert করে।
- `user_deposit_rules(uid PK, rule_type, days_required, note, updated_at)` — rule_type: `unlimited` | `days` | `blocked`।
- `deposit_gate_log(id, uid, allowed, reason, checked_at)` — audit।
- `app_config`-এ কলাম add: `global_deposit_days_required int default 0` (0 = deposit check off; N = শেষ N দিনে অন্তত ১টা successful deposit লাগবে)।

**নতুন server routes** (`src/routes/api/public/`):
- `ingest-recharge.ts` (POST) — body: `{ uid, device_id, records[] }`, `state=success` (বা equivalent) rows insert; token auth (reuse `CAPTURE_INGEST_TOKEN`)।
- `check-deposit-gate.ts` (POST) — body: `{ uid }` → returns `{ allowed, reason, rule, last_deposit_at, required_days }`। Order: per-UID override → global rule → allow।

**Admin panel** (`src/routes/admin.tsx`):
- Global rule slider (0 = off, 1/2/3/5/7/30 days)।
- Per-UID override table: UID, type (unlimited/days N/blocked), note — add/edit/delete।
- Recent deposit-gate log।

## Android app

**নতুন**: `DepositGate.kt` (LicenseGate-এর ধাঁচে):
- `state` LiveData — `ALLOWED | BLOCKED | UNKNOWN` + reason।
- `tryExtract(entry)`:
  - Login response হলে uid mark করে `needsDailyRedirect` set + `MainActivity`-কে redirect signal।
  - URL contains `GetRechargeRecord` হলে records parse করে `/api/public/ingest-recharge`-এ POST + তারপর `check-deposit-gate` call।
- Cache: SharedPreferences-এ per-UID last check (12h TTL) — offline unlock smooth।

**MainActivity/BrowserFragment**:
- Login detect হওয়ার পর দিনে একবার (uid+date key) `loadUrl("https://dkwin8.com/#/wallet/RechargeHistory")` force redirect; পরে user free।

**Fragment gate** (Aviator, WinGo, WinGoAutoBet):
- `LicenseGate` OK হলে তারপর `DepositGate` check — blocked হলে existing `LicenseLockOverlay`-এর similar overlay দেখাবে "আজকের deposit history পাওয়া যায়নি। DK Win tab-এ গিয়ে deposit করুন" message সহ।
- Browser tab untouched।

**SniffingWebViewClient** already সব response capture করে; `CaptureStore.add()` থেকে `DepositGate.tryExtract()` call add করব (LicenseGate-এর পাশে)।

Version bump → **81** (`1.0.81-deposit-gate`)।

## Out of scope
- Failed/pending deposit count (successful only)।
- Amount threshold (later যোগ করা যাবে)।
- Browser lock (user সবসময় deposit করতে পারবে)।
