// Local prediction logic — mirrors the Kotlin PredictionTracker adaptive rules.
// Used as a fallback whenever the AI call fails or credits run out.

export interface Signal {
  action: 'BET' | 'STRONG BET' | 'SKIP';
  target: number | null;
  confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  reasoning: string;
}

interface Round { round_id: number; multiplier: number; }

export function computeLocalSignal(history: Round[]): Signal {
  const all = history.map(r => r.multiplier);
  if (all.length < 3) {
    return { action: 'BET', target: 1.5, confidence: 'LOW', reasoning: 'not enough history — safe default.' };
  }
  const recent = all.slice(0, 15);
  const last5 = all.slice(0, 5);
  const avgRecent = recent.reduce((a, b) => a + b, 0) / recent.length;
  const avgLast5 = last5.reduce((a, b) => a + b, 0) / last5.length;
  const heat5x = recent.filter(x => x >= 5).length;
  const heat2x = recent.filter(x => x >= 2).length;
  let lowStreak = 0;
  for (const x of all) { if (x < 2) lowStreak++; else break; }
  let highStreak = 0;
  for (const x of all) { if (x >= 2) highStreak++; else break; }
  const droughtIdx = all.findIndex(x => x >= 10);
  const drought = droughtIdx < 0 ? all.length : droughtIdx;
  const lastX = all[0];
  const prevX = all[1] ?? 0;

  if (lastX >= 10) return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: 'big spike just landed — cool-down.' };
  if (lastX >= 3 && prevX >= 3) return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: 'two high crashes in a row — reversal likely.' };
  if (lowStreak >= 8) return { action: 'STRONG BET', target: 3.0, confidence: 'HIGH', reasoning: `${lowStreak} straight pinks — big recovery overdue.` };
  if (lowStreak >= 5) return { action: 'STRONG BET', target: 2.0, confidence: 'HIGH', reasoning: `${lowStreak} pinks in a row — reversal building.` };
  if (lowStreak >= 3) return { action: 'BET', target: 1.7, confidence: 'MEDIUM', reasoning: 'cold streak — moderate rebound.' };
  if (drought >= 25) return { action: 'STRONG BET', target: 5.0, confidence: 'HIGH', reasoning: `${drought} rounds since 10x — mega-spike zone.` };
  if (drought >= 15) return { action: 'BET', target: 2.5, confidence: 'MEDIUM', reasoning: 'long drought — spike window opening.' };
  if (avgRecent > 3.5 || heat5x >= 4) return { action: 'BET', target: 1.3, confidence: 'MEDIUM', reasoning: 'market running hot — take small & fast.' };
  if (avgRecent < 1.6 && heat2x <= 4) return { action: 'BET', target: 1.5, confidence: 'MEDIUM', reasoning: 'quiet market — safe quick win.' };
  if (highStreak >= 4) return { action: 'SKIP', target: null, confidence: 'MEDIUM', reasoning: `${highStreak} greens in a row — crash-back due.` };
  if (avgLast5 >= 1.6 && avgLast5 <= 2.4) return { action: 'BET', target: 1.8, confidence: 'MEDIUM', reasoning: 'balanced trend — steady climb.' };
  return { action: 'BET', target: 1.6, confidence: 'LOW', reasoning: 'neutral read — conservative play.' };
}

/**
 * RISK MODE — recovery-oriented martingale-style signal.
 *
 * Philosophy: on flat-stake play, 2 losses at ~2x targets leave the user
 * ~2 units down. Risk mode escalates the NEXT target so a single win
 * recovers the loss AND locks in profit. Formula (stake=1 each round):
 *   required_target(streak) = losses + 1 + 0.15  (0.15 = profit buffer)
 *   loss 0 → 1.6-1.8x   (normal fast win)
 *   loss 1 → 2.15x       (recover 1 unit + profit)
 *   loss 2 → 3.15x       (recover 2 units + profit)
 *   loss 3 → 4.15x       (recover 3 units + profit)
 *   loss 4+ → 1-round SKIP then reset (prevent runaway)
 *
 * Also biases toward BET over SKIP so users get more actionable signals,
 * and only skips on truly dangerous frames (fresh 15x+ spike or 4+ losses).
 */
export function computeRiskSignal(history: Round[], lossStreak: number): Signal {
  const all = history.map(r => r.multiplier);
  if (all.length < 3) {
    return { action: 'BET', target: 1.7, confidence: 'LOW', reasoning: 'risk-mode default — quick recovery bet.' };
  }
  const lastX = all[0];
  const prevX = all[1] ?? 0;

  // Extreme cool-down — even risk mode respects a mega-spike just landed.
  if (lastX >= 15) {
    return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: `${lastX.toFixed(2)}x spike — 1 round cool-down.` };
  }
  // Runaway guard — 4+ consecutive losses = pause to reset psychology & bankroll.
  if (lossStreak >= 4) {
    return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: `${lossStreak} losses in a row — cool-down then reset stake.` };
  }

  // Recovery ladder. Base target = losses + 1.15 so a single hit clears
  // the deficit and locks small profit at flat stake.
  const recoveryTarget =
    lossStreak === 0 ? 1.7 :
    lossStreak === 1 ? 2.15 :
    lossStreak === 2 ? 3.15 :
    /* 3 */             4.15;

  const recent = all.slice(0, 15);
  const heat5x = recent.filter(x => x >= 5).length;
  const heat2x = recent.filter(x => x >= 2).length;
  let lowStreak = 0;
  for (const x of all) { if (x < 2) lowStreak++; else break; }
  const droughtIdx = all.findIndex(x => x >= 10);
  const drought = droughtIdx < 0 ? all.length : droughtIdx;

  // Momentum boost: long drought or long pink streak → widen target slightly
  // upward (never below recoveryTarget so recovery stays intact).
  let target = recoveryTarget;
  if (lossStreak === 0 && (lowStreak >= 5 || drought >= 20)) target = 2.3;
  if (lossStreak === 0 && (lowStreak >= 8 || drought >= 30)) target = 3.0;

  // Hot market → tighten target down slightly (still >= recovery floor)
  if (heat5x >= 4 && lossStreak <= 1) target = Math.max(recoveryTarget - 0.15, 1.5);

  // Confidence scales with how favorable the setup is.
  const conf: Signal['confidence'] =
    (lowStreak >= 5 || drought >= 20 || lossStreak >= 2) ? 'HIGH' :
    (heat2x <= 5 || lossStreak === 1) ? 'MEDIUM' : 'LOW';

  const action: Signal['action'] = lossStreak >= 2 ? 'STRONG BET' : 'BET';

  // Slight bias against back-to-back high crashes — small target trim
  // BUT do not go below recovery floor.
  const adjusted = (lastX >= 5 && prevX >= 5) ? Math.max(target - 0.2, recoveryTarget) : target;

  const reason =
    lossStreak === 0 ? 'risk-mode fresh entry — small quick win.' :
    `risk-mode recovery — clear ${lossStreak} loss${lossStreak > 1 ? 'es' : ''} + profit at ${adjusted.toFixed(2)}x.`;

  return { action, target: Number(adjusted.toFixed(2)), confidence: conf, reasoning: reason };
}
