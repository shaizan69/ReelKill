/**
 * AttentionOS — Analytics Aggregator
 *
 * Derives daily summaries from raw events. This is the bridge between the
 * raw event log and the dashboard's metrics.
 *
 * Summaries are computed on-demand (when the dashboard loads or a session ends)
 * and cached in chrome.storage.local under daily_summaries[date].
 *
 * Each summary contains:
 *  - reels_watched: total reel views that day
 *  - time_spent_seconds: cumulative watch duration
 *  - sessions: number of distinct sessions
 *  - cooldowns_triggered: how many cooldowns fired
 *  - hard_block_hit: whether the hard block triggered
 *  - attention_score: 0-100 composite score (computed by score.js)
 */

import {
  getEventsInWindow,
  getAllEvents,
  getDailySummary,
  updateDailySummary,
} from '../core/storage.js';

import { computeAttentionScore } from './score.js';

// ─── Summary Generation ──────────────────────────────────────────────────────

/**
 * Computes and persists the daily summary for a given date.
 * Scans all events from that UTC day (00:00:00 to 23:59:59.999).
 *
 * @param {string} dateStr — "YYYY-MM-DD"
 * @returns {Promise<Object>} The computed summary
 */
async function computeDailySummary(dateStr) {
  const dayStart = `${dateStr}T00:00:00.000Z`;
  const dayEnd = `${dateStr}T23:59:59.999Z`;

  // Fetch all events for this day
  const allDayEvents = await getEventsInWindow(dayStart, dayEnd);

  // ── Reel views ─────────────────────────────────────────────────
  const reelViews = allDayEvents.filter((e) => e.event === 'reel_viewed');
  const reelsWatched = reelViews.length;
  const timeSpentSeconds = reelViews.reduce(
    (sum, e) => sum + (e.watch_duration || 2),
    0
  );

  // ── Sessions ───────────────────────────────────────────────────
  const sessionStarts = allDayEvents.filter((e) => e.event === 'session_start');
  const sessions = sessionStarts.length;

  // ── Cooldowns ──────────────────────────────────────────────────
  const cooldowns = allDayEvents.filter((e) => e.event === 'cooldown_triggered');
  const cooldownsTriggered = cooldowns.length;

  // ── Hard block ─────────────────────────────────────────────────
  const hardBlocks = allDayEvents.filter((e) => e.event === 'hard_block_triggered');
  const hardBlockHit = hardBlocks.length > 0;

  // ── Friction ───────────────────────────────────────────────────
  const frictionShown = allDayEvents.filter((e) => e.event === 'friction_shown').length;
  const frictionDismissed = allDayEvents.filter((e) => e.event === 'friction_dismissed').length;

  // ── Attention score ────────────────────────────────────────────
  const attentionScore = await computeAttentionScore(dateStr, {
    reelsWatched,
    cooldownsTriggered,
    hardBlockHit,
    frictionShown,
    frictionDismissed,
    reelViews, // Pass raw views for late-night detection
  });

  const summary = {
    reels_watched: reelsWatched,
    time_spent_seconds: timeSpentSeconds,
    sessions,
    cooldowns_triggered: cooldownsTriggered,
    hard_block_hit: hardBlockHit,
    friction_shown: frictionShown,
    friction_dismissed: frictionDismissed,
    attention_score: attentionScore,
  };

  // Persist
  await updateDailySummary(dateStr, summary);

  return summary;
}

// ─── Weekly Report ───────────────────────────────────────────────────────────

/**
 * Generates a weekly report from daily summaries.
 * Covers the 7-day window ending on `endDateStr` (inclusive).
 *
 * @param {string} endDateStr — "YYYY-MM-DD" (typically today)
 * @returns {Promise<Object>}
 */
async function generateWeeklyReport(endDateStr) {
  const days = _getDateRange(endDateStr, 7);
  const summaries = [];

  for (const day of days) {
    let summary = await getDailySummary(day);
    if (!summary) {
      // Compute it if missing
      summary = await computeDailySummary(day);
    }
    summaries.push({ date: day, ...summary });
  }

  // ── Totals ─────────────────────────────────────────────────────
  const totalReels = summaries.reduce((s, d) => s + d.reels_watched, 0);
  const totalTime = summaries.reduce((s, d) => s + d.time_spent_seconds, 0);
  const totalSessions = summaries.reduce((s, d) => s + d.sessions, 0);
  const totalCooldowns = summaries.reduce((s, d) => s + d.cooldowns_triggered, 0);
  const hardBlockDays = summaries.filter((d) => d.hard_block_hit).length;

  // ── Best / Worst day ───────────────────────────────────────────
  const withScores = summaries.filter((d) => d.attention_score != null);
  const bestDay = withScores.length > 0
    ? withScores.reduce((a, b) => (a.attention_score >= b.attention_score ? a : b))
    : null;
  const worstDay = withScores.length > 0
    ? withScores.reduce((a, b) => (a.attention_score <= b.attention_score ? a : b))
    : null;

  // ── Danger window (hour with most reels) ───────────────────────
  // This requires hourly data — we'll compute it from raw events
  const dangerHour = await _computeDangerHour(days);

  // ── Average attention score ────────────────────────────────────
  const avgScore = withScores.length > 0
    ? Math.round(
        withScores.reduce((s, d) => s + d.attention_score, 0) / withScores.length
      )
    : null;

  return {
    period: { start: days[0], end: days[days.length - 1] },
    days: summaries,
    totals: {
      reels_watched: totalReels,
      time_spent_seconds: totalTime,
      sessions: totalSessions,
      cooldowns_triggered: totalCooldowns,
      hard_block_days: hardBlockDays,
    },
    best_day: bestDay ? { date: bestDay.date, score: bestDay.attention_score } : null,
    worst_day: worstDay ? { date: worstDay.date, score: worstDay.attention_score } : null,
    danger_hour: dangerHour,
    avg_attention_score: avgScore,
  };
}

// ─── Streak Tracker ──────────────────────────────────────────────────────────

/**
 * Computes the current streak: consecutive days within daily limit and
 * no hard blocks, counting backwards from today.
 *
 * @param {string} todayStr — "YYYY-MM-DD"
 * @returns {Promise<{current: number, best: number}>}
 */
async function computeStreak(todayStr) {
  // Look back up to 365 days
  const days = _getDateRange(todayStr, 365);
  let current = 0;
  let best = 0;
  let streakBroken = false;

  for (const day of days.reverse()) {
    const summary = await getDailySummary(day);

    if (!summary) {
      // No data for this day — could mean they didn't use Instagram
      // Count it as a "good" day (not using is better than overusing)
      if (!streakBroken) current++;
      continue;
    }

    const isGoodDay = !summary.hard_block_hit;

    if (isGoodDay) {
      if (!streakBroken) current++;
      // Track running streak for best calculation
    } else {
      streakBroken = true;
    }
  }

  // For "best" streak, scan all summaries
  // Simplified: just return current for now, full history scan in future
  best = Math.max(current, best);

  return { current, best };
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Returns an array of date strings going back `numDays` from `endDateStr`.
 * Oldest first, newest last.
 *
 * @param {string} endDateStr — "YYYY-MM-DD"
 * @param {number} numDays
 * @returns {string[]}
 */
function _getDateRange(endDateStr, numDays) {
  const dates = [];
  const end = new Date(endDateStr + 'T00:00:00Z');

  for (let i = numDays - 1; i >= 0; i--) {
    const d = new Date(end.getTime() - i * 24 * 60 * 60 * 1000);
    dates.push(d.toISOString().slice(0, 10));
  }

  return dates;
}

/**
 * Finds the hour of day (0-23) with the most reel views across a date range.
 * @param {string[]} days — array of "YYYY-MM-DD"
 * @returns {Promise<{hour: number, count: number}|null>}
 */
async function _computeDangerHour(days) {
  const hourBuckets = new Array(24).fill(0);

  for (const day of days) {
    const dayStart = `${day}T00:00:00.000Z`;
    const dayEnd = `${day}T23:59:59.999Z`;
    const events = await getEventsInWindow(dayStart, dayEnd, 'reel_viewed');

    for (const e of events) {
      const hour = e.hour != null ? e.hour : new Date(e.timestamp).getHours();
      hourBuckets[hour]++;
    }
  }

  const maxCount = Math.max(...hourBuckets);
  if (maxCount === 0) return null;

  const maxHour = hourBuckets.indexOf(maxCount);
  return { hour: maxHour, count: maxCount };
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  computeDailySummary,
  generateWeeklyReport,
  computeStreak,
};
