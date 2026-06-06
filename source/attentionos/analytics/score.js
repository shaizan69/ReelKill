/**
 * AttentionOS — Attention Score Calculator
 *
 * Computes a 0-100 composite score that measures how well the user
 * managed their attention on a given day. Higher = better self-control.
 *
 * Scoring breakdown:
 *  - Within daily limit (no hard block):     30 points
 *  - No cooldowns triggered:                 20 points
 *  - No late-night scrolling (after 11 PM):  20 points
 *  - Week-over-week improvement:             20 points
 *  - Friction dismissed rate trending down:  10 points
 *
 * The score is intentionally generous on "good" days and punitive on
 * binge days — this creates clear emotional contrast in the dashboard.
 */

import { getSettings, getDailySummary } from '../core/storage.js';

// ─── Score Computation ───────────────────────────────────────────────────────

/**
 * Computes the attention score for a single day.
 *
 * @param {string} dateStr — "YYYY-MM-DD"
 * @param {Object} dayData — pre-computed data to avoid re-querying:
 *   - reelsWatched {number}
 *   - cooldownsTriggered {number}
 *   - hardBlockHit {boolean}
 *   - frictionShown {number}
 *   - frictionDismissed {number}
 *   - reelViews {Object[]} — raw reel_viewed events for hour analysis
 * @returns {Promise<number>} Score 0-100
 */
async function computeAttentionScore(dateStr, dayData) {
  const settings = await getSettings();
  let score = 0;

  // ── Component 1: Within daily limit (30 pts) ───────────────────
  score += _scoreDailyLimit(dayData.reelsWatched, settings.daily_limit, dayData.hardBlockHit);

  // ── Component 2: No cooldowns (20 pts) ─────────────────────────
  score += _scoreCooldowns(dayData.cooldownsTriggered);

  // ── Component 3: No late-night scrolling (20 pts) ──────────────
  score += _scoreLateNight(dayData.reelViews || []);

  // ── Component 4: Week-over-week improvement (20 pts) ───────────
  score += await _scoreImprovement(dateStr, dayData.reelsWatched);

  // ── Component 5: Friction dismissed rate (10 pts) ──────────────
  score += _scoreFrictionRate(dayData.frictionShown, dayData.frictionDismissed);

  return Math.round(Math.min(100, Math.max(0, score)));
}

// ─── Component Scorers ───────────────────────────────────────────────────────

/**
 * Component 1: Daily limit compliance (0-30 points).
 *
 * - 0 reels: 30 pts (perfect)
 * - Under 50% of limit: 28 pts
 * - Under 80% of limit: 24 pts
 * - Under 100% of limit: 18 pts
 * - At limit (hard block): 0 pts
 */
function _scoreDailyLimit(reelsWatched, dailyLimit, hardBlockHit) {
  if (hardBlockHit) return 0;
  if (reelsWatched === 0) return 30;

  const ratio = reelsWatched / dailyLimit;

  if (ratio < 0.5) return 28;
  if (ratio < 0.8) return 24;
  if (ratio < 1.0) return 18;

  return 0; // Shouldn't reach here if hardBlockHit is accurate, but safety net
}

/**
 * Component 2: Cooldown avoidance (0-20 points).
 *
 * - 0 cooldowns: 20 pts
 * - 1 cooldown: 10 pts
 * - 2 cooldowns: 5 pts
 * - 3+ cooldowns: 0 pts
 */
function _scoreCooldowns(cooldownsTriggered) {
  if (cooldownsTriggered === 0) return 20;
  if (cooldownsTriggered === 1) return 10;
  if (cooldownsTriggered === 2) return 5;
  return 0;
}

/**
 * Component 3: Late-night scrolling avoidance (0-20 points).
 *
 * "Late night" = any reel view after 11 PM (hour >= 23) or before 5 AM (hour < 5).
 *
 * - 0 late-night reels: 20 pts
 * - 1-3 late-night reels: 12 pts
 * - 4-10 late-night reels: 5 pts
 * - 11+ late-night reels: 0 pts
 */
function _scoreLateNight(reelViews) {
  const lateNightCount = reelViews.filter((e) => {
    const hour = e.hour != null ? e.hour : new Date(e.timestamp).getHours();
    return hour >= 23 || hour < 5;
  }).length;

  if (lateNightCount === 0) return 20;
  if (lateNightCount <= 3) return 12;
  if (lateNightCount <= 10) return 5;
  return 0;
}

/**
 * Component 4: Week-over-week improvement (0-20 points).
 *
 * Compares today's reel count to the same day last week.
 * - Reduced by 50%+: 20 pts
 * - Reduced by 20-50%: 15 pts
 * - Reduced by 1-20%: 10 pts
 * - Same or increased: 5 pts (baseline — no penalty for consistency)
 * - No data from last week: 10 pts (neutral)
 */
async function _scoreImprovement(dateStr, reelsToday) {
  // Get the same day last week
  const today = new Date(dateStr + 'T00:00:00Z');
  const lastWeek = new Date(today.getTime() - 7 * 24 * 60 * 60 * 1000);
  const lastWeekStr = lastWeek.toISOString().slice(0, 10);

  const lastWeekSummary = await getDailySummary(lastWeekStr);

  if (!lastWeekSummary || lastWeekSummary.reels_watched === 0) {
    return 10; // No comparison data — neutral score
  }

  const lastWeekReels = lastWeekSummary.reels_watched;
  const change = (lastWeekReels - reelsToday) / lastWeekReels;

  if (change >= 0.5) return 20;  // 50%+ reduction
  if (change >= 0.2) return 15;  // 20-50% reduction
  if (change > 0) return 10;    // Some reduction
  return 5;                      // Same or more
}

/**
 * Component 5: Friction response trend (0-10 points).
 *
 * If friction was shown, did the user dismiss it or respect it?
 * - Friction not shown (under threshold): 10 pts
 * - Shown but NOT dismissed (user stopped): 10 pts
 * - Shown and dismissed: 3 pts
 * - Multiple frictions dismissed: 0 pts (shouldn't happen per design,
 *   but safety net)
 */
function _scoreFrictionRate(frictionShown, frictionDismissed) {
  if (frictionShown === 0) return 10; // Never hit the threshold
  if (frictionDismissed === 0) return 10; // Hit threshold, chose to stop
  if (frictionDismissed === 1) return 3;
  return 0;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export { computeAttentionScore };
