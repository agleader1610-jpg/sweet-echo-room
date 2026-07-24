// Smart fallback prediction — used when AI is unavailable (credits exhausted,
// timeout, rate limit). Uses BOTH recent crash history AND the device's own
// past signal outcomes (memory) to make a data-driven signal, instead of
// the pure-heuristic computeLocalSignal/computeRiskSignal fallbacks.
//
// Philosophy: mimic what the AI does — look at market state + prior W/L
// pattern, pick target that historically worked in similar conditions.

import type { Signal } from './local-prediction-logic';
import { computeLocalSignal, computeRiskSignal } from './local-prediction-logic';

interface Round { round_id: number; multiplier: number; }

export interface PriorForSmart {
  target_multiplier: number | null;
  action: string;
  result: { actual_multiplier: number; outcome: string } | null;
}

export interface SmartMemory {
  priors: PriorForSmart[];
  lossStreak: number;
  wins: number;
  losses: number;
  accuracy: number;
  // Global cross-device intelligence (mined from all AI signals + outcomes)
  globalTarget?: number | null;   // best-performing target under current market
  globalReason?: string;          // human-readable why (e.g. "global:warm wr=62% best~1.6x")
  globalWinRate?: number;         // overall global BET win rate
  globalSampleSize?: number;      // how many verified global signals mined
}


/**
 * Analyze history + prior signal outcomes to produce a data-driven signal.
 * Returns null if there isn't enough memory to be useful; caller should then
 * fall back to pure heuristic (computeLocalSignal / computeRiskSignal).
 */
export function computeSmartSignal(
  history: Round[],
  memory: SmartMemory,
  mode: 'normal' | 'risk',
): Signal {
  const all = history.map(r => r.multiplier);
  if (all.length < 3) {
    return mode === 'risk'
      ? computeRiskSignal(history, memory.lossStreak)
      : computeLocalSignal(history);
  }

  const lastX = all[0];
  const prevX = all[1] ?? 0;

  // Hard cool-down — spike just landed, matches AI behavior
  if (lastX >= 12) {
    return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: `smart: ${lastX.toFixed(2)}x spike — cool-down.` };
  }
  if (mode === 'normal' && lastX >= 3 && prevX >= 3) {
    return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: 'smart: back-to-back high crashes — reversal likely.' };
  }

  const recent = all.slice(0, 15);
  const avgRecent = recent.reduce((a, b) => a + b, 0) / recent.length;
  const heat5x = recent.filter(x => x >= 5).length;
  let lowStreak = 0;
  for (const x of all) { if (x < 2) lowStreak++; else break; }
  const droughtIdx = all.findIndex(x => x >= 10);
  const drought = droughtIdx < 0 ? all.length : droughtIdx;

  // ---- memory-driven target selection ----
  // Look at prior BET signals that HIT (WIN) and pick a target near the
  // median of those winning targets, biased by current market state.
  const wonBets = memory.priors.filter(
    p => p.result?.outcome === 'WIN' && p.target_multiplier != null && p.action !== 'SKIP',
  );
  const lostBets = memory.priors.filter(
    p => p.result?.outcome === 'LOSS' && p.target_multiplier != null && p.action !== 'SKIP',
  );

  let memoryTarget: number | null = null;
  if (wonBets.length >= 3) {
    const wins = wonBets.map(p => p.target_multiplier!).sort((a, b) => a - b);
    memoryTarget = wins[Math.floor(wins.length / 2)]; // median winning target
  }

  // If losing targets skew high, memory says be more conservative
  let losingAvg: number | null = null;
  if (lostBets.length >= 3) {
    losingAvg = lostBets.reduce((s, p) => s + p.target_multiplier!, 0) / lostBets.length;
  }

  // ---- RISK MODE ----
  if (mode === 'risk') {
    if (memory.lossStreak >= 4) {
      return { action: 'SKIP', target: null, confidence: 'HIGH', reasoning: `smart: ${memory.lossStreak} losses — reset stake.` };
    }
    // Recovery ladder anchored in what actually won for this device
    const base = memory.lossStreak === 0 ? 1.7 :
                 memory.lossStreak === 1 ? 2.15 :
                 memory.lossStreak === 2 ? 3.15 : 4.15;
    let target = base;
    if (memoryTarget != null && memory.lossStreak === 0) {
      // Blend memory median with base, keep in sensible band
      target = Math.max(1.4, Math.min(3.0, (memoryTarget + base) / 2));
    }
    if (losingAvg != null && target > losingAvg - 0.15 && memory.lossStreak <= 1) {
      target = Math.max(1.5, losingAvg - 0.2); // avoid the loss zone
    }
    if (lowStreak >= 6 || drought >= 25) target = Math.max(target, 2.5);
    const conf: Signal['confidence'] =
      memory.accuracy >= 55 && memory.wins + memory.losses >= 5 ? 'HIGH' :
      memory.wins + memory.losses >= 3 ? 'MEDIUM' : 'LOW';
    const action: Signal['action'] = memory.lossStreak >= 2 ? 'STRONG BET' : 'BET';
    return {
      action,
      target: Number(target.toFixed(2)),
      confidence: conf,
      reasoning: `smart-risk: memory ${memory.accuracy}% (${memory.wins}W/${memory.losses}L), streak ${memory.lossStreak}.`,
    };
  }

  // ---- NORMAL MODE ----
  // Long cold streak → recovery bet, biased by memory-known winning target
  if (lowStreak >= 5) {
    const t = memoryTarget && memoryTarget >= 1.8 ? Math.min(memoryTarget, 3.0) : 2.0;
    return { action: 'STRONG BET', target: Number(t.toFixed(2)), confidence: 'HIGH', reasoning: `smart: ${lowStreak} pinks — memory target ${t.toFixed(2)}x.` };
  }
  if (drought >= 20) {
    return { action: 'BET', target: 2.5, confidence: 'MEDIUM', reasoning: `smart: ${drought}r since 10x — spike window.` };
  }

  // Hot market — small quick target
  if (avgRecent > 3.5 || heat5x >= 4) {
    return { action: 'BET', target: 1.3, confidence: 'MEDIUM', reasoning: 'smart: hot market — small & fast.' };
  }

  // Default — blend GLOBAL winning target (all-devices AI wisdom) with
  // this device's own memory median. Global has priority when meaningful.
  let target = 1.6;
  const gt = memory.globalTarget;
  const gSample = memory.globalSampleSize ?? 0;
  if (gt != null && gSample >= 30) {
    // Global winner in this market bucket, clamped to a sane band
    target = Math.max(1.3, Math.min(gt, 3.0));
    if (memoryTarget != null) {
      // small pull toward the device's own winners
      target = Number(((target * 2 + memoryTarget) / 3).toFixed(2));
    }
  } else if (memoryTarget != null) {
    target = Math.max(1.4, Math.min(memoryTarget, 2.2));
  }
  if (losingAvg != null && Math.abs(target - losingAvg) < 0.15) {
    target = Math.max(1.4, losingAvg - 0.2);
  }
  if (lowStreak >= 3) target = Math.max(target, 1.7);

  const hasGlobal = gt != null && gSample >= 30;
  const conf: Signal['confidence'] =
    hasGlobal && (memory.globalWinRate ?? 0) >= 55 ? 'HIGH' :
    memory.accuracy >= 55 && memory.wins + memory.losses >= 5 ? 'HIGH' :
    (hasGlobal || memory.wins + memory.losses >= 3) ? 'MEDIUM' : 'LOW';

  const reasoning = hasGlobal
    ? `smart: ${memory.globalReason} + me ${memory.accuracy}% → ${target.toFixed(2)}x.`
    : (memory.wins + memory.losses >= 3
        ? `smart: memory ${memory.accuracy}% (${memory.wins}W/${memory.losses}L) → ${target.toFixed(2)}x.`
        : `smart: warming up — ${target.toFixed(2)}x safe.`);

  return {
    action: 'BET',
    target: Number(target.toFixed(2)),
    confidence: conf,
    reasoning: reasoning.slice(0, 140),
  };
}

