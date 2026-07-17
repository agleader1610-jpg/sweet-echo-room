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
