/**
 * AttentionOS — Content Main (Orchestrator)
 *
 * This is the content-side coordinator. It runs in the Instagram page context
 * (loaded via dynamic import from content-loader.js).
 *
 * Responsibilities:
 *  1. On init: check for active hard block or cooldown → apply reels-blur.
 *  2. If no block/cooldown: register with service worker for tracker assignment.
 *  3. Listen for messages from service worker (apply blur, remove blur, etc.)
 *  4. Handle page visibility changes (pause/resume tracking).
 *
 * The intervention style is "soft blur" — reels and videos are blurred and
 * paused, but the Instagram page itself remains navigable and posts are
 * still visible. This module delegates rendering to:
 *  - tracker.js for reel detection
 *  - reels-blur.js for the blur UI (replaces the old hard-block screen)
 *  - friction-modal.js for the 80% soft warning
 *  - Service worker for budget/cooldown decisions
 */

import { startTracking, stopTracking, endSession } from './core/tracker.js';
import {
  applyReelsBlur,
  removeReelsBlur,
  isReelsBlurActive,
} from './ui/reels-blur.js';
import { showFrictionModal, removeFrictionModal } from './ui/friction-modal.js';
import { getState, getSettings, initialize } from './core/storage.js';

// ─── State ───────────────────────────────────────────────────────────────────

let isInitialized = false;
let isTrackerActive = false;

// ─── Init ────────────────────────────────────────────────────────────────────

/**
 * Main entry point. Called from content-loader.js.
 */
async function init() {
  if (isInitialized) return;
  isInitialized = true;

  await initialize();

  // Apply intervention if any is active. Hard block takes priority over cooldown.
  const blockHandled = await _checkActiveBlock();
  if (blockHandled) {
    _setupMessageListener();
    return;
  }

  const cooldownHandled = await _checkActiveCooldown();
  if (cooldownHandled) {
    _setupMessageListener();
    return;
  }

  // No active intervention — register as tracker and listen for messages.
  _setupMessageListener();
  _registerTracker();

  window.addEventListener('beforeunload', _onBeforeUnload);
  document.addEventListener('visibilitychange', _onVisibilityChange);

}

// ─── Intervention Checks ─────────────────────────────────────────────────────

/**
 * Checks if a hard block is active. If so, applies the blur intervention.
 * @returns {Promise<boolean>} true if block is active
 */
async function _checkActiveBlock() {
  const state = await getState();

  if (!state.hard_block_active || !state.hard_block_expires) return false;

  const now = new Date().toISOString();
  if (now >= state.hard_block_expires) {
    try {
      chrome.runtime.sendMessage({ type: 'AOS_HARD_BLOCK_EXPIRED_CLIENT' });
    } catch (_) {}
    return false;
  }

  const settings = await getSettings();
  _applyBlur({
    type: 'block',
    expires: state.hard_block_expires,
    reelsWatched: state.reels_watched_today,
    dailyLimit: settings.daily_limit,
  });

  return true;
}

/**
 * Checks if a cooldown is active. If so, applies the blur intervention.
 * @returns {Promise<boolean>} true if cooldown is active
 */
async function _checkActiveCooldown() {
  const state = await getState();

  if (!state.cooldown_active || !state.cooldown_expires) return false;

  const now = new Date().toISOString();
  if (now >= state.cooldown_expires) {
    try {
      chrome.runtime.sendMessage({ type: 'AOS_COOLDOWN_EXPIRED_CLIENT' });
    } catch (_) {}
    return false;
  }

  const settings = await getSettings();
  _applyBlur({
    type: 'cooldown',
    expires: state.cooldown_expires,
    reelsWatched: state.reels_watched_today,
    dailyLimit: settings.daily_limit,
  });

  return true;
}

function _applyBlur(blurState) {
  applyReelsBlur(blurState);
  if (isTrackerActive) {
    isTrackerActive = false;
    stopTracking();
  }
}

function _removeBlur() {
  removeReelsBlur();
}

// ─── Message Handling ────────────────────────────────────────────────────────

function _setupMessageListener() {
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    _handleMessage(message).then(sendResponse);
    return true; // Async response
  });
}

async function _handleMessage(message) {
  switch (message.type) {
    // ── Tracker Assignment ─────────────────────────────────────────
    case 'AOS_TRACKER_ASSIGNED':
      if (!isTrackerActive && !isReelsBlurActive()) {
        isTrackerActive = true;
        await startTracking();
      }
      return { ok: true };

    case 'AOS_TRACKER_REVOKED':
      if (isTrackerActive) {
        isTrackerActive = false;
        stopTracking();
      }
      return { ok: true };

    // ── Cooldown ───────────────────────────────────────────────────
    case 'AOS_SHOW_COOLDOWN':
      _applyBlur({
        type: 'cooldown',
        expires: message.payload.expires,
        reelsWatched: message.payload.reelsWatched,
        dailyLimit: message.payload.dailyLimit,
      });
      return { ok: true };

    case 'AOS_REMOVE_COOLDOWN':
      _removeBlur();
      if (message.payload?.hardBlockTakeover) {
        const state = await getState();
        const settings = await getSettings();
        _applyBlur({
          type: 'block',
          expires: state.hard_block_expires,
          reelsWatched: state.reels_watched_today,
          dailyLimit: settings.daily_limit,
        });
      } else {
        if (isTrackerActive) await startTracking();
        else _registerTracker();
      }
      return { ok: true };

    // ── Hard Block ─────────────────────────────────────────────────
    case 'AOS_SHOW_BLOCK':
      _applyBlur({
        type: 'block',
        expires: message.payload.expires,
        reelsWatched: message.payload.reelsWatched,
        dailyLimit: message.payload.dailyLimit,
      });
      return { ok: true };

    case 'AOS_REMOVE_BLOCK':
      _removeBlur();
      _registerTracker();
      return { ok: true };

    // ── Friction ───────────────────────────────────────────────────
    case 'AOS_SHOW_FRICTION':
      showFrictionModal(
        message.payload.reelsWatched,
        message.payload.dailyLimit
      );
      return { ok: true };

    // ── Pattern-based interventions (banners/reminders) ───────────
    case 'AOS_INTERVENTIONS':
      // Log only for now; future phases will add banner UI
      return { ok: true };

    default:
      return { ok: false, error: 'unknown message type' };
  }
}

// ─── Tracker Registration ────────────────────────────────────────────────────

function _registerTracker() {
  try {
    chrome.runtime.sendMessage(
      { type: 'AOS_TRACKER_REGISTER' },
      (response) => {
        if (chrome.runtime.lastError) {
          console.warn(
            '[AttentionOS] Could not register tracker:',
            chrome.runtime.lastError.message
          );
          return;
        }

        if (response?.assigned) {
          isTrackerActive = true;
          startTracking();
        }
      }
    );
  } catch (err) {
    console.warn('[AttentionOS] Tracker registration failed:', err.message);
  }
}

// ─── Lifecycle ───────────────────────────────────────────────────────────────

function _onBeforeUnload() {
  if (isTrackerActive) {
    try {
      chrome.runtime.sendMessage({ type: 'AOS_TRACKER_UNREGISTER' });
    } catch (_) {}

    endSession();
    stopTracking();
  }
}

function _onVisibilityChange() {
  if (!isTrackerActive) return;

  if (document.hidden) {
    // Tab hidden — IntersectionObserver naturally stops counting
    // because nothing is in viewport.
  }
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export { init };
