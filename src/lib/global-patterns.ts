// Global pattern miner — reads AI-generated signals + their outcomes across
// ALL devices from public.predictions + public.prediction_results, and mines
// patterns that historically worked. Cached for 5 minutes to avoid hammering
// the DB. Used both by the AI prompt (so it sees "what won globally") and by
// the smart fallback (so it can pick targets that historically won under
// similar market conditions, even without the AI).

import type { SupabaseClient } from '@supabase/supabase-js';

export interface TargetBucketStat {
  range: string;         // e.g. "1.3-1.6"
  min: number;
  max: number;
  attempts: number;
  wins: number;
  winRate: number;       // 0..100
}

export interface MarketBucketStat {
  bucket: string;        // e.g. "cold" | "warm" | "hot" | "spike"
  attempts: number;
  wins: number;
  winRate: number;       // 0..100
  bestTarget: number | null;   // median winning target inside this bucket
}

export interface GlobalPatterns {
  sampleSize: number;
  overallWinRate: number;        // BET win rate across all AI signals
  skipAccuracy: number;          // SKIP correctness across all AI SKIPs
  targetBuckets: TargetBucketStat[];
  marketBuckets: Record<string, MarketBucketStat>;
  bestTargetOverall: number | null;
  worstTargetOverall: number | null;
  summary: string;               // compact text for AI prompt
}

const CACHE_TTL_MS = 5 * 60_000;
let cache: { at: number; data: GlobalPatterns } | null = null;

export function invalidateGlobalPatternsCache() { cache = null; }

const TARGET_BUCKETS: Array<[number, number, string]> = [
  [1.01, 1.3, '1.01-1.3'],
  [1.3, 1.6, '1.3-1.6'],
  [1.6, 2.0, '1.6-2.0'],
  [2.0, 2.5, '2.0-2.5'],
  [2.5, 3.5, '2.5-3.5'],
  [3.5, 5.0, '3.5-5.0'],
  [5.0, 100, '5.0+'],
];

function marketBucketOf(lastMult: number | null): string {
  if (lastMult == null) return 'unknown';
  if (lastMult >= 10) return 'spike';
  if (lastMult >= 3) return 'hot';
  if (lastMult >= 1.6) return 'warm';
  return 'cold';
}

export async function getGlobalPatterns(supa: SupabaseClient): Promise<GlobalPatterns> {
  const now = Date.now();
  if (cache && now - cache.at < CACHE_TTL_MS) return cache.data;

  // Pull recent AI signals + their outcomes. Limit to a rolling window so
  // stats stay relevant to current market behavior.
  // We select last ~1000 verified results (approx last few days).
  const { data, error } = await supa
    .from('prediction_results')
    .select('actual_multiplier,outcome,target_multiplier,round_id,predictions!inner(source,action,target_multiplier)')
    .in('outcome', ['WIN', 'LOSS', 'SKIP_WIN', 'SKIP_LOSS'])
    .order('created_at', { ascending: false })
    .limit(1500);

  if (error || !data) {
    const empty = emptyPatterns();
    cache = { at: now, data: empty };
    return empty;
  }

  type Row = {
    actual_multiplier: number;
    outcome: string;
    target_multiplier: number | null;
    predictions: { source: string | null; action: string | null; target_multiplier: number | null } | null;
  };
  const rows = data as unknown as Row[];

  // BET signals (any source), verified WIN/LOSS
  const bets = rows.filter(r => (r.outcome === 'WIN' || r.outcome === 'LOSS')
    && r.predictions?.action !== 'SKIP'
    && (r.predictions?.target_multiplier ?? r.target_multiplier) != null);
  const skips = rows.filter(r => r.outcome === 'SKIP_WIN' || r.outcome === 'SKIP_LOSS');

  const wins = bets.filter(r => r.outcome === 'WIN').length;
  const overallWinRate = bets.length ? Math.round((wins / bets.length) * 100) : 0;
  const skipWins = skips.filter(r => r.outcome === 'SKIP_WIN').length;
  const skipAccuracy = skips.length ? Math.round((skipWins / skips.length) * 100) : 0;

  // Target buckets — win rate per target range
  const targetBuckets: TargetBucketStat[] = TARGET_BUCKETS.map(([mn, mx, label]) => {
    const inB = bets.filter(r => {
      const t = r.predictions?.target_multiplier ?? r.target_multiplier ?? 0;
      return t >= mn && t < mx;
    });
    const w = inB.filter(r => r.outcome === 'WIN').length;
    return {
      range: label,
      min: mn,
      max: mx,
      attempts: inB.length,
      wins: w,
      winRate: inB.length ? Math.round((w / inB.length) * 100) : 0,
    };
  });

  // Market buckets — grouped by ACTUAL crash bucket of the round we bet on
  const marketBuckets: Record<string, MarketBucketStat> = {};
  const marketWinTargets: Record<string, number[]> = {};
  for (const r of bets) {
    const bucket = marketBucketOf(r.actual_multiplier);
    if (!marketBuckets[bucket]) {
      marketBuckets[bucket] = { bucket, attempts: 0, wins: 0, winRate: 0, bestTarget: null };
      marketWinTargets[bucket] = [];
    }
    marketBuckets[bucket].attempts++;
    if (r.outcome === 'WIN') {
      marketBuckets[bucket].wins++;
      const t = r.predictions?.target_multiplier ?? r.target_multiplier;
      if (t != null) marketWinTargets[bucket].push(t);
    }
  }
  for (const b of Object.values(marketBuckets)) {
    b.winRate = b.attempts ? Math.round((b.wins / b.attempts) * 100) : 0;
    const ts = marketWinTargets[b.bucket].sort((a, z) => a - z);
    b.bestTarget = ts.length ? Number(ts[Math.floor(ts.length / 2)].toFixed(2)) : null;
  }

  // Best/worst target overall (must have enough attempts to matter)
  const meaningful = targetBuckets.filter(b => b.attempts >= 10);
  const best = meaningful.slice().sort((a, b) => b.winRate - a.winRate)[0];
  const worst = meaningful.slice().sort((a, b) => a.winRate - b.winRate)[0];
  const bestTargetOverall = best ? Number(((best.min + best.max) / 2).toFixed(2)) : null;
  const worstTargetOverall = worst ? Number(((worst.min + worst.max) / 2).toFixed(2)) : null;

  const summary = buildSummary(bets.length, overallWinRate, skipAccuracy, targetBuckets, marketBuckets);

  const result: GlobalPatterns = {
    sampleSize: bets.length,
    overallWinRate,
    skipAccuracy,
    targetBuckets,
    marketBuckets,
    bestTargetOverall,
    worstTargetOverall,
    summary,
  };
  cache = { at: now, data: result };
  return result;
}

function buildSummary(
  n: number,
  winRate: number,
  skipAcc: number,
  targets: TargetBucketStat[],
  markets: Record<string, MarketBucketStat>,
): string {
  if (n === 0) return 'no global history yet';
  const tParts = targets
    .filter(b => b.attempts >= 5)
    .map(b => `${b.range}=${b.winRate}%(${b.wins}/${b.attempts})`)
    .join(' ');
  const mParts = Object.values(markets)
    .filter(b => b.attempts >= 5)
    .map(b => `${b.bucket}=${b.winRate}%${b.bestTarget != null ? `,best~${b.bestTarget}x` : ''}`)
    .join(' ');
  return `n=${n} BET-wr=${winRate}% SKIP-acc=${skipAcc}% | targets: ${tParts || 'n/a'} | markets: ${mParts || 'n/a'}`;
}

function emptyPatterns(): GlobalPatterns {
  return {
    sampleSize: 0,
    overallWinRate: 0,
    skipAccuracy: 0,
    targetBuckets: [],
    marketBuckets: {},
    bestTargetOverall: null,
    worstTargetOverall: null,
    summary: 'no global history yet',
  };
}

/**
 * Given the current market state, return the best target from global winners
 * in that market bucket. Used by smart fallback.
 */
export function pickTargetFromGlobal(
  patterns: GlobalPatterns,
  marketState: { lastMultiplier: number; avgRecent: number; lowStreak: number; drought: number },
): { target: number | null; reason: string } {
  if (patterns.sampleSize < 20) return { target: null, reason: 'thin-global' };

  // Choose market bucket from CURRENT last crash — we're betting on the next round
  // matching a similar bucket pattern.
  let bucket: string;
  if (marketState.lowStreak >= 5 || marketState.drought >= 20) bucket = 'warm'; // reversal expected
  else if (marketState.avgRecent >= 3.5) bucket = 'warm';                        // hot cooling
  else if (marketState.lastMultiplier < 1.5) bucket = 'warm';
  else if (marketState.lastMultiplier >= 3) bucket = 'cold';                     // just crashed high → often cold
  else bucket = 'warm';

  const mb = patterns.marketBuckets[bucket];
  if (mb && mb.attempts >= 8 && mb.bestTarget != null) {
    return { target: mb.bestTarget, reason: `global:${bucket} wr=${mb.winRate}% best~${mb.bestTarget}x` };
  }

  // fall back to overall best-performing target bucket
  const meaningful = patterns.targetBuckets.filter(b => b.attempts >= 10);
  const best = meaningful.slice().sort((a, b) => b.winRate - a.winRate)[0];
  if (best) {
    const mid = Number(((best.min + best.max) / 2).toFixed(2));
    return { target: mid, reason: `global:best-bucket ${best.range}=${best.winRate}%` };
  }
  return { target: null, reason: 'no-global-signal' };
}
