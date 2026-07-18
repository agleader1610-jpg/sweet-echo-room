## লক্ষ্য

WinGo game-এ auto-bet panel — signal অনুযায়ী নিজে নিজে bet দেবে। সব traffic user-এর ফোনের browser থেকেই যাবে, VPS/relay-এর কোনো ছোঁয়া থাকবে না।

## Approach: Path A — WebView JS Injection

Browser tab-এ যে WebView-এ game খোলা, ওটার ভিতরেই JavaScript inject করে page-এর নিজস্ব bet function call করব। এতে:

- Signature/random/timestamp page নিজেই generate করবে (crack করতে হবে না)
- সব request phone-এর real IP + cookie + user-agent দিয়ে যাবে
- VPS/backend-এ কোনো call নেই — সম্পূর্ণ on-device
- Detection risk কম (normal user click-এর মতোই দেখাবে)

## যা যা তৈরি হবে

### 1. `AutoBetEngine.kt` (নতুন)
- Signal source: existing `WinGoStore` + `PredictionTracker` (Round ID + BIG/SMALL lock)
- State machine: IDLE → ARMED → PLACING → WAIT_RESULT → RESOLVED
- Rules engine:
  - Base amount (৳1 / ৳10 / ৳100 / ৳1000)
  - Multiplier (x1 / x5 / x10 / x50 / x100)
  - Strategy: Flat / Martingale (loss হলে double) / Anti-Martingale
  - Stop-loss (নির্দিষ্ট loss হলে auto-off)
  - Target profit (নির্দিষ্ট profit হলে auto-off)
  - Max rounds cap
  - Skip low-confidence signals option

### 2. `BetInjector.kt` (নতুন)
WebView-এ JS execute করার helper। দুইটা mode:

**Mode 1 — DOM click simulation (safest):**
- BIG/SMALL button element খুঁজে click event fire করে
- Amount + multiplier button click করে
- Confirm button click করে

**Mode 2 — Direct fetch fallback:**
- Page যদি global signing function expose করে থাকে (`window.Md5`, `window.sign`), সেটা call করে fetch দিয়ে bet post করি — এখনো WebView-এর ভিতরে, তাই cookie/IP phone-এর

Round-এর প্রথম injection-এ probe script চালিয়ে dynamically detect করব কোনটা কাজ করে।

### 3. `fragment_wingo.xml` — Auto-Bet Panel UI
নতুন card যোগ হবে uppermost section-এ:
```text
┌─ 🤖 AUTO-BET PANEL ─────────────────┐
│ Master Switch:        [ OFF ]       │
│ Base amount:  [৳1] [৳10] [৳100]     │
│ Multiplier:   [x1] [x5] [x10] [x50] │
│ Strategy:     [Flat|Martingale|AntiM]│
│ Stop-loss:    [ ৳500  ]              │
│ Target win:   [ ৳1000 ]              │
│ Max rounds:   [  50   ]              │
├─ LIVE STATS ────────────────────────┤
│ Placed: 12   Won: 7   Lost: 5       │
│ P/L: +৳340   Streak: W2             │
│ Next bet: BIG · ৳20  (Round …420)   │
└──────────────────────────────────────┘
```

### 4. `BrowserFragment.kt` update
- Auto-bet ON হলে browser tab-এ game URL auto-load (ar-lottery01.com/WinGo)
- Bet placement সময় small toast: "🤖 Auto-bet: BIG ৳20 placed"
- Bet result detect করার জন্য existing `CaptureUploader`/`SniffingWebViewClient`-এর data ব্যবহার

### 5. Safety layers
- Manual override — user যেকোনো সময় switch OFF করলে current round শেষ হলে থামবে
- Balance guard — GetBalance response থেকে balance parse করে, insufficient হলে auto-stop
- Duplicate prevention — একই `issueNumber`-এ দুবার bet করবে না (lock in SharedPreferences)
- Server-error backoff — 3 consecutive fail হলে 60s cooldown

### 6. Local history (already exists)
`PredictionTracker` extended করে auto-bet actual outcome + P/L save হবে — SharedPreferences-এ, on-device only।

## VPS/Backend থেকে যা যা বাদ

- ❌ কোনো `/api/public/*` call auto-bet flow-এ নেই
- ❌ Bet decision কোনো cloud AI-তে যাবে না — WinGo signal (BIG/SMALL) locally generate
- ❌ Round data upload বন্ধ থাকবে auto-bet ON থাকা অবস্থায় (optional toggle)
- ✅ শুধু existing version-check + license-check reachability check হবে (already cached 12h)

## Version

`versionCode = 62`, `versionName = "1.0.62-autobet"`

## যা technical section

- `evaluateJavascript()` async — Handler post করে result parse
- Injected JS-এ `MutationObserver` বসাব bet result popup detect করার জন্য
- Signal lock: `PredictionTracker.getLockedSignal(issueNumber)` — যা আগেই আছে
- Martingale sequence store: `SharedPreferences` key `wingo_autobet_seq`
- Result parsing: existing `captures` table না, direct WebView network hook দিয়ে on-device
