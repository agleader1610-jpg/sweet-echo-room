## লক্ষ্য

Aviator round শেষে instant AI signal → app-এ real-time show → সব data database-এ save → AI credit শেষ হলে automatic local logic fallback।

## Architecture

```text
Android App (BrowserFragment/AviatorStore)
   │  crash detect → POST /api/public/predict
   ▼
TanStack Server Route  ──► Supabase (round_history, predictions, session_stats)
   │
   ├─► Lovable AI Gateway (Gemini 3 Flash) ──► signal
   │                                              │
   └─► IF AI fails/402 → local logic engine ──────┤
                                                  ▼
   Response {signal, target, confidence, source: "ai"|"local", reasoning}
                                                  │
Android app displays instantly on AviatorFragment + BrowserFragment overlay
```

WebSocket not strictly needed — HTTPS POST after each crash detect হলে latency 300-800ms, যেটা next round শুরু হওয়ার আগেই signal দিয়ে দিবে। এতে existing STREAM_SECRET/relay infrastructure-এ ঝামেলা কম, cost কম, reliability বেশি।

## Model Choice

**Gemini 3 Flash** (`google/gemini-3-flash-preview`) — fastest, cheapest, multimodal ভবিষ্যতে video analysis-ও করতে পারবে (তোমার mentioned "video analysis" plan-এর জন্য perfect)।

## Database (নতুন 3 tables)

1. **round_history** — প্রতি crash: `round_id, multiplier, crashed_at, device_id`
2. **predictions** — প্রতি signal: `round_id, target, action, confidence, source(ai/local), reasoning, ai_model, created_at`
3. **prediction_results** — verify হওয়ার পর: `prediction_id, actual_multiplier, outcome(win/loss/skip), profit_ratio`

সব table-এ RLS: anon read+insert (device_id filter), service_role full।

## Server Route: `/api/public/predict-signal`

Input: `{ device_id, history: [{round_id, multiplier}...last 20] }`

Flow:
1. History save → `round_history` table
2. AI call try করে (Gemini 3 Flash, 5s timeout, structured JSON output)
3. AI fail/timeout/402 হলে → local logic engine (existing PredictionTracker logic port করা)
4. Signal `predictions` table-এ save
5. Return signal + `source` field

Local logic engine: current Kotlin-এর 9 scenario logic TypeScript-এ port — heat detection, cold streak, 10x+ skip, recovery bet ইত্যাদি।

## Verification endpoint: `/api/public/verify-result`

পরবর্তী crash-এর পর app call করবে — actual multiplier দিবে, `prediction_results` table update হবে, W/L stats accurate থাকবে।

## Android Changes

- **AviatorStore.kt**: crash detect হলে `PredictionClient.fetchSignal(history)` call → callback-এ AI/local signal UI-তে push
- **PredictionClient.kt** (নতুন): OkHttp POST to server route, JSON parse, fallback to local `PredictionTracker` যদি network fail
- **AviatorFragment.kt**: signal card-এ নতুন badge — "🤖 AI" (green) বা "⚙ LOGIC" (blue) source দেখাবে + AI reasoning text (2 lines)
- **BrowserFragment.kt**: overlay-এ same signal source badge

## Fallback Strategy

Server side: AI 402/429/timeout → immediately local logic run → return with `source: "local"` (app কখনো blank signal পাবে না)।
Client side: server unreachable → Kotlin-এর local `PredictionTracker` fallback → `source: "offline"`।

তিন-layer resilience: AI → server logic → offline logic।

## Files touched

- Migration: 3 new tables + RLS + grants
- New: `src/routes/api/public/predict-signal.ts`, `src/routes/api/public/verify-result.ts`, `src/lib/local-prediction-logic.ts`
- Android new: `PredictionClient.kt`
- Android edit: `AviatorStore.kt`, `AviatorFragment.kt`, `BrowserFragment.kt`, `fragment_aviator.xml` (source badge + reasoning text)
- Version bump: **31**

## Cost Estimate

Gemini 3 Flash: প্রতি round ~500 input tokens + 100 output ≈ **0.03 credit/round**। ঘণ্টায় ~120 rounds = 3.6 credits/hour। Testing session (4 hours) ≈ 15 credits।

## Confirmation

শুরু করব? Approve করলে সব একসাথে ship করব — migration + server routes + Android update সহ v1.0.31 build।
