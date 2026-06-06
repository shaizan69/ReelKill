/**
 * AttentionOS — Cooldown System
 *
 * Binge detection via sliding window algorithm:
 *  - Reads actual event timestamps from storage (not in-memory counters).
 *  - Default: 15 reels in any rolling 10-minute window triggers a cooldown.
 *  - Check fires after EVERY reel view.
 *
 * Cooldown escalation (resets every 24h rolling):
 *  - 1st: 5 min   (300s)
 *  - 2nd: 10 min  (600s)
 *  - 3rd: 20 min  (1200s)
 *  - 4th+: 30 min (1800s)
 *
 * State is persisted in chrome.storage.local so cooldowns survive tab
 * close/reopen and extension restarts.
 *
 * This module is designed to be called from the service worker context
 * (has access to chrome.alarms and chrome.tabs).
 */

import {
  getSettings,
  getState,
  updateState,
  getEventsInWindow,
  logEvent,
  todayUTC,
  currentLocalHour,
} from './storage.js';

// ─── Escalation Tiers ────────────────────────────────────────────────────────
// Multipliers applied to cooldown_base_seconds based on cooldown count.
// 1st = 1x, 2nd = 2x, 3rd = 4x, 4th+ = 6x

const ESCALATION_MULTIPLIERS = [1, 2, 4, 6];

/**
 * Returns the cooldown duration in seconds for the given cooldown number
 * (1-indexed: 1st cooldown, 2nd cooldown, etc.)
 */
function getCooldownDuration(cooldownNumber, baseSeconds) {
  const tier = Math.min(cooldownNumber, ESCALATION_MULTIPLIERS.length) - 1;
  const multiplier = ESCALATION_MULTIPLIERS[Math.max(0, tier)];
  return baseSeconds * multiplier;
}

// ─── Binge Detection ─────────────────────────────────────────────────────────

/**
 * Checks whether the user is currently bingeing by examining the sliding
 * window of reel_viewed events.
 *
 * IMPORTANT: Uses event timestamps from storage, NOT in-memory state.
 * This ensures tab crashes don't reset the window.
 *
 * @returns {Promise<boolean>} true if cooldown should be triggered
 */
async function checkBinge() {
  const state = await getState();

  // Don't trigger a new cooldown if one is already active
  if (state.cooldown_active) return false;

  // Don't trigger cooldown if hard block is active
  if (state.hard_block_active) return false;

  const settings = await getSettings();
  const now = new Date();
  const windowStart = new Date(
    now.getTime() - settings.binge_window_minutes * 60 * 1000
  ).toISOString();
  const windowEnd = now.toISOString();

  const reelEvents = await getEventsInWindow(
    windowStart,
    windowEnd,
    'reel_viewed'
  );

  return reelEvents.length >= settings.binge_threshold_reels;
}

// ─── Cooldown Trigger ────────────────────────────────────────────────────────

/**
 * Triggers a cooldown. Sets state, logs event, schedules alarm.
 * Called by the service worker when checkBinge() returns true.
 *
 * @returns {Promise<{duration: number, expires: string}>}
 */
async function triggerCooldown() {
  const state = await getState();
  const settings = await getSettings();

  // Determine which cooldown number this is (1-indexed)
  const cooldownNumber = (state.cooldown_count_today || 0) + 1;
  const duration = getCooldownDuration(cooldownNumber, settings.cooldown_base_seconds);

  const now = new Date();
  const expiresAt = new Date(now.getTime() + duration * 1000).toISOString();

  // Persist cooldown state
  await updateState({
    cooldown_active: true,
    cooldown_expires: expiresAt,
    cooldown_count_today: cooldownNumber,
  });

  // Log the event
  await logEvent({
    event: 'cooldown_triggered',
    cooldown_number: cooldownNumber,
    duration_seconds: duration,
    expires_at: expiresAt,
    timestamp: now.toISOString(),
    session_id: state.current_session_id,
    day: todayUTC(),
    hour: currentLocalHour(),
  });

  // Schedule alarm for expiry
  // chrome.alarms expects delay in minutes (minimum ~1 minute granularity).
  // For short cooldowns, we use a combination: set alarm for ceiling(duration)
  // and do a precise check in the alarm handler.
  const delayMinutes = Math.max(duration / 60, 0.1); // chrome.alarms allows fractional minutes >= 0.1
  try {
    chrome.alarms.create('aos_cooldown_expiry', {
      delayInMinutes: delayMinutes,
    });
  } catch (err) {
    console.error('[AttentionOS] Failed to create cooldown alarm:', err);
  }

  console.log(
    `[AttentionOS] Cooldown #${cooldownNumber} triggered: ${duration}s, expires at ${expiresAt}`
  );

  return { duration, expires: expiresAt };
}

// ─── Cooldown Expiry ─────────────────────────────────────────────────────────

/**
 * Handles cooldown expiry. Called by the service worker's alarm handler.
 *
 * Checks whether:
 *  - The cooldown has actually expired (wall-clock comparison).
 *  - A hard block should take over (daily limit hit during cooldown).
 *
 * @returns {Promise<{expired: boolean, hardBlockTakeover: boolean}>}
 */
async function onCooldownExpiry() {
  const state = await getState();

  if (!state.cooldown_active) {
    return { expired: false, hardBlockTakeover: false };
  }

  const now = new Date().toISOString();

  // Verify the cooldown has actually expired (wall-clock check)
  if (now < state.cooldown_expires) {
    // Not yet — reschedule. This handles cases where the alarm fired early
    // due to chrome.alarms' minimum granularity.
    const remaining =
      (new Date(state.cooldown_expires).getTime() - Date.now()) / 60000;
    if (remaining > 0) {
      chrome.alarms.create('aos_cooldown_expiry', {
        delayInMinutes: Math.max(remaining, 0.1),
      });
    }
    return { expired: false, hardBlockTakeover: false };
  }

  // Cooldown has expired
  await updateState({
    cooldown_active: false,
    cooldown_expires: null,
  });

  // Check if hard block should take over
  // (daily limit hit during cooldown → hard block activates now)
  const hardBlockTakeover = state.hard_block_active && state.hard_block_expires;

  console.log(
    `[AttentionOS] Cooldown expired. Hard block takeover: ${hardBlockTakeover}`
  );

  return { expired: true, hardBlockTakeover };
}

// ─── Rolling 24h Reset ───────────────────────────────────────────────────────

/**
 * Checks if the cooldown_count_today should be reset.
 * Resets when there are no cooldown_triggered events in the last 24 hours.
 *
 * Called by the service worker on startup and periodically.
 */
async function checkCooldownCountReset() {
  const now = new Date();
  const windowStart = new Date(
    now.getTime() - 24 * 60 * 60 * 1000
  ).toISOString();

  const cooldownEvents = await getEventsInWindow(
    windowStart,
    now.toISOString(),
    'cooldown_triggered'
  );

  if (cooldownEvents.length === 0) {
    const state = await getState();
    if (state.cooldown_count_today > 0) {
      await updateState({ cooldown_count_today: 0 });
      console.log('[AttentionOS] Cooldown count reset (no cooldowns in 24h)');
    }
  }
}

// ─── Active Cooldown Check ───────────────────────────────────────────────────

/**
 * Checks if a cooldown is currently active and valid (not expired).
 * Used by content scripts on page load to decide whether to show the overlay.
 *
 * @returns {Promise<{active: boolean, expires: string|null, reelsWatched: number, dailyLimit: number}>}
 */
async function getCooldownStatus() {
  const state = await getState();
  const settings = await getSettings();

  if (!state.cooldown_active || !state.cooldown_expires) {
    return {
      active: false,
      expires: null,
      reelsWatched: state.reels_watched_today,
      dailyLimit: settings.daily_limit,
    };
  }

  const now = new Date().toISOString();

  // Check if the cooldown has silently expired (alarm didn't fire)
  if (now >= state.cooldown_expires) {
    await onCooldownExpiry();
    return {
      active: false,
      expires: null,
      reelsWatched: state.reels_watched_today,
      dailyLimit: settings.daily_limit,
    };
  }

  return {
    active: true,
    expires: state.cooldown_expires,
    reelsWatched: state.reels_watched_today,
    dailyLimit: settings.daily_limit,
  };
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  checkBinge,
  triggerCooldown,
  onCooldownExpiry,
  checkCooldownCountReset,
  getCooldownStatus,
  getCooldownDuration,
};
