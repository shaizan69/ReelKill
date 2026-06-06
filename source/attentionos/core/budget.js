/**
 * AttentionOS — Budget System
 *
 * Daily limit enforcement and hard block lifecycle.
 *
 * Key rules:
 *  - Hard ceiling: 50 reels/day (cannot be set higher, ever).
 *  - Hard block lasts exactly 24 hours from trigger timestamp (rolling, not midnight).
 *  - No overrides, no snooze, no bypass.
 *  - Block screen REMOVES the feed DOM, not just hides it.
 *  - Closing/disabling the extension does NOT escape an active block.
 *
 * The daily counter uses a rolling 24-hour window anchored to the hard block
 * expiry time. On each new reel view, we recount from storage to survive
 * crashes and restarts.
 *
 * This module is called from the service worker context.
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

// ─── Daily Limit Check ──────────────────────────────────────────────────────

/**
 * Recounts reels watched in the current rolling 24-hour window from storage.
 * This is the single source of truth — never rely on an in-memory counter.
 *
 * The window is:
 *  - If a hard block was previously triggered: from (hard_block_expires - 24h) to now
 *  - Otherwise: from (now - 24h) to now
 *
 * @returns {Promise<number>} Current reel count for the rolling window
 */
async function recountReels() {
  const state = await getState();
  const now = new Date();

  let windowStart;
  if (state.hard_block_expires) {
    // Rolling window anchored to block expiry
    windowStart = new Date(
      new Date(state.hard_block_expires).getTime() - 24 * 60 * 60 * 1000
    );
    // But if the block has expired, use standard 24h window
    if (now.toISOString() >= state.hard_block_expires) {
      windowStart = new Date(now.getTime() - 24 * 60 * 60 * 1000);
    }
  } else {
    windowStart = new Date(now.getTime() - 24 * 60 * 60 * 1000);
  }

  const reelEvents = await getEventsInWindow(
    windowStart.toISOString(),
    now.toISOString(),
    'reel_viewed'
  );

  const count = reelEvents.length;

  // Sync the counter to storage for quick reads by UI modules
  await updateState({ reels_watched_today: count });

  return count;
}

/**
 * Checks the daily budget after a reel view.
 * Returns an object indicating what action (if any) should be taken.
 *
 * @returns {Promise<{action: 'none'|'friction'|'hard_block', reelsWatched: number, dailyLimit: number}>}
 */
async function checkBudget() {
  const settings = await getSettings();
  const reelsWatched = await recountReels();
  const state = await getState();

  // Already hard-blocked — no further checks
  if (state.hard_block_active) {
    return { action: 'none', reelsWatched, dailyLimit: settings.daily_limit };
  }

  // Hard block trigger
  if (reelsWatched >= settings.daily_limit) {
    return { action: 'hard_block', reelsWatched, dailyLimit: settings.daily_limit };
  }

  // Friction trigger
  const frictionThreshold = Math.floor(
    settings.daily_limit * settings.friction_threshold_pct
  );
  if (reelsWatched >= frictionThreshold && !state.friction_shown_this_session) {
    return { action: 'friction', reelsWatched, dailyLimit: settings.daily_limit };
  }

  return { action: 'none', reelsWatched, dailyLimit: settings.daily_limit };
}

// ─── Hard Block Trigger ──────────────────────────────────────────────────────

/**
 * Triggers a 24-hour hard block. Sets state, logs event, schedules alarm.
 *
 * If a cooldown is currently active, the hard block state is set but the
 * block screen won't be injected until the cooldown expires (cooldown never
 * masks a hard block — the block takes over when cooldown ends).
 *
 * @returns {Promise<{expires: string}>}
 */
async function triggerHardBlock() {
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
  const state = await getState();

  await updateState({
    hard_block_active: true,
    hard_block_expires: expiresAt,
  });

  await logEvent({
    event: 'hard_block_triggered',
    expires_at: expiresAt,
    reels_watched: state.reels_watched_today,
    timestamp: now.toISOString(),
    session_id: state.current_session_id,
    day: todayUTC(),
    hour: currentLocalHour(),
  });

  // Schedule alarm for 24h expiry
  try {
    chrome.alarms.create('aos_hard_block_expiry', {
      delayInMinutes: 24 * 60, // 24 hours
    });
  } catch (err) {
    console.error('[AttentionOS] Failed to create hard block alarm:', err);
  }

  console.log(`[AttentionOS] Hard block triggered. Expires at ${expiresAt}`);

  return { expires: expiresAt };
}

// ─── Hard Block Expiry ───────────────────────────────────────────────────────

/**
 * Handles hard block expiry. Called by the service worker's alarm handler.
 * Resets daily counters and cooldown count.
 *
 * @returns {Promise<{expired: boolean}>}
 */
async function onHardBlockExpiry() {
  const state = await getState();

  if (!state.hard_block_active) {
    return { expired: false };
  }

  const now = new Date().toISOString();

  // Verify the block has actually expired
  if (state.hard_block_expires && now < state.hard_block_expires) {
    // Not yet — reschedule
    const remaining =
      (new Date(state.hard_block_expires).getTime() - Date.now()) / 60000;
    if (remaining > 0) {
      chrome.alarms.create('aos_hard_block_expiry', {
        delayInMinutes: Math.max(remaining, 0.1),
      });
    }
    return { expired: false };
  }

  // Block has expired — reset everything
  await updateState({
    hard_block_active: false,
    hard_block_expires: null,
    reels_watched_today: 0,
    cooldown_count_today: 0,
    friction_shown_this_session: false,
  });

  console.log('[AttentionOS] Hard block expired. Counters reset.');

  return { expired: true };
}

// ─── Active Block Check ──────────────────────────────────────────────────────

/**
 * Checks if a hard block is currently active and valid.
 * Used by content scripts on page load.
 *
 * @returns {Promise<{active: boolean, expires: string|null, reelsWatched: number, dailyLimit: number}>}
 */
async function getBlockStatus() {
  const state = await getState();
  const settings = await getSettings();

  if (!state.hard_block_active || !state.hard_block_expires) {
    return {
      active: false,
      expires: null,
      reelsWatched: state.reels_watched_today,
      dailyLimit: settings.daily_limit,
    };
  }

  const now = new Date().toISOString();

  // Check if block silently expired (alarm didn't fire)
  if (now >= state.hard_block_expires) {
    await onHardBlockExpiry();
    const freshState = await getState();
    return {
      active: false,
      expires: null,
      reelsWatched: freshState.reels_watched_today,
      dailyLimit: settings.daily_limit,
    };
  }

  return {
    active: true,
    expires: state.hard_block_expires,
    reelsWatched: state.reels_watched_today,
    dailyLimit: settings.daily_limit,
  };
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  recountReels,
  checkBudget,
  triggerHardBlock,
  onHardBlockExpiry,
  getBlockStatus,
};
