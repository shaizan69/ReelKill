/**
 * AttentionOS — Friction Layer
 *
 * Soft warnings at configurable thresholds (default: 80% of daily limit).
 * Shows a modal overlay with a message and a confirm button.
 * Every friction event is logged: both the showing and whether it was dismissed.
 *
 * Friction is shown at most once per session. If the user dismisses it,
 * that decision is logged and they continue — no enforcement action.
 *
 * This module provides the logic; actual UI injection is in
 * ui/friction-modal.js. This module is called from the service worker.
 */

import {
  getState,
  updateState,
  logEvent,
  todayUTC,
  currentLocalHour,
} from './storage.js';

// ─── Friction Check ──────────────────────────────────────────────────────────

/**
 * Determines if friction should be shown based on current reel count
 * and session state.
 *
 * @param {number} reelsWatched — current reel count in the rolling window
 * @param {number} dailyLimit — user's daily limit
 * @param {number} frictionPct — threshold percentage (e.g. 0.8)
 * @returns {Promise<boolean>}
 */
async function shouldShowFriction(reelsWatched, dailyLimit, frictionPct) {
  const state = await getState();

  // Only show once per session
  if (state.friction_shown_this_session) return false;

  // Don't show during cooldown or hard block
  if (state.cooldown_active || state.hard_block_active) return false;

  const threshold = Math.floor(dailyLimit * frictionPct);
  return reelsWatched >= threshold;
}

// ─── Friction Events ─────────────────────────────────────────────────────────

/**
 * Logs that friction was shown and marks the session so it won't show again.
 *
 * @param {number} reelsWatched
 * @param {number} dailyLimit
 */
async function onFrictionShown(reelsWatched, dailyLimit) {
  const state = await getState();

  await updateState({ friction_shown_this_session: true });

  await logEvent({
    event: 'friction_shown',
    reels_watched: reelsWatched,
    daily_limit: dailyLimit,
    timestamp: new Date().toISOString(),
    session_id: state.current_session_id,
    day: todayUTC(),
    hour: currentLocalHour(),
  });

  console.log(
    `[AttentionOS] Friction shown: ${reelsWatched}/${dailyLimit} reels`
  );
}

/**
 * Logs that the user dismissed the friction modal (chose to continue).
 */
async function onFrictionDismissed() {
  const state = await getState();

  await logEvent({
    event: 'friction_dismissed',
    timestamp: new Date().toISOString(),
    session_id: state.current_session_id,
    day: todayUTC(),
    hour: currentLocalHour(),
  });

  console.log('[AttentionOS] Friction dismissed — user chose to continue');
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  shouldShowFriction,
  onFrictionShown,
  onFrictionDismissed,
};
