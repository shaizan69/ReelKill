/**
 * AttentionOS — Intervention Engine
 *
 * Pattern detection that runs after every event. Detects concerning behavioral
 * patterns and returns a list of interventions that should be shown to the user.
 *
 * Patterns detected:
 *  1. Rapid binge: 20 reels in 15 minutes → "Take a break" banner
 *  2. Frequent opens: Instagram opened 10+ times today → usage warning in popup
 *  3. Budget near-exhaustion: 90%+ of daily limit used → escalated friction
 *  4. Cooldown cluster: 3+ cooldowns in one day → end-of-session summary
 *  5. Late-night scrolling: Reels after 11 PM → gentle reminder
 *
 * This module is called from the service worker after events are logged.
 * It returns intervention descriptors — the service worker decides how
 * to deliver them (banner, popup badge, notification, etc.)
 */

import {
  getEventsInWindow,
  getState,
  getSettings,
  todayUTC,
  currentLocalHour,
} from './storage.js';

// ─── Intervention Types ──────────────────────────────────────────────────────

const INTERVENTION = {
  TAKE_A_BREAK: 'take_a_break',
  FREQUENT_OPENS: 'frequent_opens',
  BUDGET_CRITICAL: 'budget_critical',
  COOLDOWN_CLUSTER: 'cooldown_cluster',
  LATE_NIGHT: 'late_night',
};

// ─── Main Check ──────────────────────────────────────────────────────────────

/**
 * Runs all pattern detectors and returns a list of triggered interventions.
 * Called by the service worker after every event.
 *
 * @param {Object} event — the event that was just logged
 * @returns {Promise<{interventions: Object[]}>}
 */
async function checkInterventions(event) {
  const interventions = [];

  // Only run pattern checks on reel_viewed events (most patterns key off views)
  if (event.event === 'reel_viewed') {
    const results = await Promise.all([
      _checkRapidBinge(),
      _checkBudgetCritical(),
      _checkLateNight(event),
    ]);

    for (const result of results) {
      if (result) interventions.push(result);
    }
  }

  // These checks run on any event type
  if (event.event === 'session_start') {
    const frequentOpens = await _checkFrequentOpens();
    if (frequentOpens) interventions.push(frequentOpens);
  }

  if (event.event === 'cooldown_triggered') {
    const cluster = await _checkCooldownCluster();
    if (cluster) interventions.push(cluster);
  }

  return { interventions };
}

// ─── Pattern Detectors ───────────────────────────────────────────────────────

/**
 * Pattern 1: Rapid binge — 20 reels in the last 15 minutes.
 * This is MORE aggressive than the cooldown threshold (15 reels / 10 min)
 * and fires a softer "take a break" banner instead of a hard cooldown.
 *
 * It only fires if a cooldown hasn't already been triggered (avoiding duplicate).
 */
async function _checkRapidBinge() {
  const state = await getState();
  if (state.cooldown_active) return null; // Cooldown already handling this

  const now = new Date();
  const windowStart = new Date(now.getTime() - 15 * 60 * 1000).toISOString();
  const windowEnd = now.toISOString();

  const reelEvents = await getEventsInWindow(windowStart, windowEnd, 'reel_viewed');

  if (reelEvents.length >= 20) {
    return {
      type: INTERVENTION.TAKE_A_BREAK,
      severity: 'warning',
      message: 'You\'ve watched 20+ reels in the last 15 minutes. Consider taking a break.',
      data: { reelCount: reelEvents.length, windowMinutes: 15 },
    };
  }

  return null;
}

/**
 * Pattern 2: Frequent opens — Instagram opened 10+ times today.
 * Detected by counting session_start events for today.
 */
async function _checkFrequentOpens() {
  const today = todayUTC();
  const dayStart = `${today}T00:00:00.000Z`;
  const dayEnd = `${today}T23:59:59.999Z`;

  const sessionStarts = await getEventsInWindow(dayStart, dayEnd, 'session_start');

  if (sessionStarts.length >= 10) {
    return {
      type: INTERVENTION.FREQUENT_OPENS,
      severity: 'info',
      message: `You've opened Instagram ${sessionStarts.length} times today. That's a lot of context switching.`,
      data: { openCount: sessionStarts.length },
    };
  }

  return null;
}

/**
 * Pattern 3: Budget near-exhaustion — 90%+ of daily limit used.
 * More urgent than the friction threshold (default 80%).
 */
async function _checkBudgetCritical() {
  const state = await getState();
  const settings = await getSettings();

  if (state.hard_block_active) return null; // Already blocked

  const ratio = state.reels_watched_today / settings.daily_limit;

  if (ratio >= 0.9 && ratio < 1.0) {
    const remaining = settings.daily_limit - state.reels_watched_today;
    return {
      type: INTERVENTION.BUDGET_CRITICAL,
      severity: 'critical',
      message: `Only ${remaining} reel${remaining === 1 ? '' : 's'} left before your daily limit. Make them count.`,
      data: {
        reelsWatched: state.reels_watched_today,
        dailyLimit: settings.daily_limit,
        remaining,
      },
    };
  }

  return null;
}

/**
 * Pattern 4: Cooldown cluster — 3+ cooldowns triggered today.
 * Suggests the user should consider ending their session entirely.
 */
async function _checkCooldownCluster() {
  const state = await getState();

  if (state.cooldown_count_today >= 3) {
    return {
      type: INTERVENTION.COOLDOWN_CLUSTER,
      severity: 'warning',
      message: `You've triggered ${state.cooldown_count_today} cooldowns today. This might be a good time to put the phone down.`,
      data: { cooldownCount: state.cooldown_count_today },
    };
  }

  return null;
}

/**
 * Pattern 5: Late-night scrolling — viewing reels after 11 PM.
 * Uses the LOCAL hour from the event.
 */
async function _checkLateNight(event) {
  const hour = event.hour != null ? event.hour : currentLocalHour();

  if (hour >= 23 || hour < 5) {
    return {
      type: INTERVENTION.LATE_NIGHT,
      severity: 'info',
      message: 'It\'s late. Scrolling now will impact your sleep quality.',
      data: { hour },
    };
  }

  return null;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  checkInterventions,
  INTERVENTION,
};
