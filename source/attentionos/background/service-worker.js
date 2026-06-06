/**
 * AttentionOS — Background Service Worker Module
 *
 * Central coordinator running in the extension's MV3 service worker context.
 * This module is imported by the root service_worker.js.
 *
 * Responsibilities:
 *  1. Startup: prune old events, validate state, check pending alarms.
 *  2. Message handling: process events from content scripts.
 *  3. Alarm handling: cooldown/block expiry, pending settings.
 *  4. Tab coordination: ensure only one tracker per Instagram origin.
 *  5. Multi-tab messaging: broadcast UI commands to all Instagram tabs.
 *
 * No external network requests. Everything local.
 */

import { initialize, pruneOldEvents, applyPendingSettings, getState, getSettings, updateState, getDailySummary, updateDailySummary, todayUTC, logEvent, currentLocalHour } from '../core/storage.js';
import { checkBinge, triggerCooldown, onCooldownExpiry, checkCooldownCountReset } from '../core/cooldown.js';
import { checkBudget, triggerHardBlock, onHardBlockExpiry, recountReels } from '../core/budget.js';
import { shouldShowFriction, onFrictionShown, onFrictionDismissed } from '../core/friction.js';
import { checkInterventions } from '../core/intervention.js';

// ─── Module State ────────────────────────────────────────────────────────────
// In-memory only. Lost on service worker restart (which is fine — we
// re-derive from storage on startup).

let activeTrackerTabId = null;

// ─── Startup ─────────────────────────────────────────────────────────────────

/**
 * Called when the service worker starts (on install, or on wake from idle).
 * Performs maintenance tasks.
 */
async function onStartup() {
  console.log('[AttentionOS SW] Starting up...');

  // Initialize storage with defaults (non-destructive)
  await initialize();

  // Prune events older than 30 days
  const pruned = await pruneOldEvents();
  if (pruned > 0) {
    console.log(`[AttentionOS SW] Pruned ${pruned} old events`);
  }

  // Check if any pending settings should be applied
  const settingsApplied = await applyPendingSettings();
  if (settingsApplied) {
    console.log('[AttentionOS SW] Applied pending settings');
  }

  // Reset cooldown count if no cooldowns in 24h
  await checkCooldownCountReset();

  // Recount reels to ensure counter is accurate
  await recountReels();

  // Validate active block/cooldown state
  await _validateActiveState();

  console.log('[AttentionOS SW] Startup complete');
}

/**
 * Validates that active blocks/cooldowns haven't silently expired
 * while the service worker was asleep.
 */
async function _validateActiveState() {
  const state = await getState();

  // Check hard block expiry
  if (state.hard_block_active && state.hard_block_expires) {
    const now = new Date().toISOString();
    if (now >= state.hard_block_expires) {
      await onHardBlockExpiry();
      await _broadcastToInstagramTabs({ type: 'AOS_REMOVE_BLOCK' });
    }
  }

  // Check cooldown expiry
  if (state.cooldown_active && state.cooldown_expires) {
    const now = new Date().toISOString();
    if (now >= state.cooldown_expires) {
      const result = await onCooldownExpiry();
      await _broadcastToInstagramTabs({
        type: 'AOS_REMOVE_COOLDOWN',
        payload: { hardBlockTakeover: result.hardBlockTakeover },
      });
    }
  }
}

// ─── Install Handler ─────────────────────────────────────────────────────────

/**
 * Called on extension install/update. Initializes storage and sets up
 * recurring alarm for maintenance.
 */
async function onInstalled(details) {
  console.log(`[AttentionOS SW] Installed (reason: ${details.reason})`);

  await initialize();

  // Set up a periodic alarm for maintenance (check pending settings, etc.)
  // Runs every 5 minutes.
  chrome.alarms.create('aos_maintenance', {
    periodInMinutes: 5,
  });
}

// ─── Message Handling ────────────────────────────────────────────────────────

/**
 * Handles messages from content scripts.
 * Returns a response object (async).
 */
async function onMessage(message, sender) {
  const tabId = sender?.tab?.id;

  switch (message.type) {
    case 'AOS_REEL_VIEWED':
      return await _handleReelViewed(message.payload, tabId);

    case 'AOS_TRACKER_REGISTER':
      return _handleTrackerRegister(tabId);

    case 'AOS_TRACKER_UNREGISTER':
      return _handleTrackerUnregister(tabId);

    case 'AOS_FRICTION_DISMISSED':
      return await _handleFrictionDismissed();

    case 'AOS_SESSION_END':
      return await _handleSessionEnd(message.payload);

    case 'AOS_COOLDOWN_EXPIRED_CLIENT':
      // Client-side timer detected cooldown expiry
      return await _handleCooldownExpiryFromClient();

    case 'AOS_HARD_BLOCK_EXPIRED_CLIENT':
      // Client-side timer detected hard block expiry
      return await _handleHardBlockExpiryFromClient();

    case 'AOS_GET_STATUS':
      // Content script requesting current status
      return await _handleGetStatus();

    default:
      return { ok: false, error: 'unknown message type' };
  }
}

// ─── Reel Viewed Handler ─────────────────────────────────────────────────────

async function _handleReelViewed(eventPayload, senderTabId) {
  // Increment the daily summary counter
  const today = todayUTC();
  const state = await getState();

  // Recount from storage (crash-safe)
  const reelsWatched = await recountReels();

  // Update daily summary
  await updateDailySummary(today, {
    reels_watched: reelsWatched,
    time_spent_seconds:
      ((await _getDailySummaryField(today, 'time_spent_seconds')) || 0) +
      (eventPayload.watch_duration || 2),
  });

  // 1. Check budget (daily limit + friction)
  const budgetResult = await checkBudget();

  if (budgetResult.action === 'hard_block') {
    // If we're in a cooldown, set the hard block but don't show it yet
    // (cooldown finishes first, then hard block takes over)
    const blockResult = await triggerHardBlock();

    if (state.cooldown_active) {
      // Hard block is set in state but UI deferred until cooldown expires
      console.log('[AttentionOS SW] Hard block queued behind active cooldown');
    } else {
      // Show hard block immediately
      await _broadcastToInstagramTabs({
        type: 'AOS_SHOW_BLOCK',
        payload: {
          expires: blockResult.expires,
          reelsWatched: budgetResult.reelsWatched,
          dailyLimit: budgetResult.dailyLimit,
        },
      });
    }

    return { ok: true, action: 'hard_block' };
  }

  // 2. Check binge (cooldown)
  const isBinging = await checkBinge();

  if (isBinging) {
    const cooldownResult = await triggerCooldown();

    await _broadcastToInstagramTabs({
      type: 'AOS_SHOW_COOLDOWN',
      payload: {
        expires: cooldownResult.expires,
        reelsWatched: budgetResult.reelsWatched,
        dailyLimit: budgetResult.dailyLimit,
      },
    });

    // Update daily summary
    await updateDailySummary(today, {
      cooldowns_triggered:
        ((await _getDailySummaryField(today, 'cooldowns_triggered')) || 0) + 1,
    });

    return { ok: true, action: 'cooldown' };
  }

  // 3. Check friction
  if (budgetResult.action === 'friction') {
    const settings = await getSettings();
    const showFriction = await shouldShowFriction(
      budgetResult.reelsWatched,
      budgetResult.dailyLimit,
      settings.friction_threshold_pct
    );

    if (showFriction) {
      await onFrictionShown(budgetResult.reelsWatched, budgetResult.dailyLimit);

      // Send to the tab that sent the reel view (or broadcast)
      if (senderTabId) {
        _sendToTab(senderTabId, {
          type: 'AOS_SHOW_FRICTION',
          payload: {
            reelsWatched: budgetResult.reelsWatched,
            dailyLimit: budgetResult.dailyLimit,
          },
        });
      }

      return { ok: true, action: 'friction' };
    }
  }

  // 4. Check intervention patterns (non-blocking — returns descriptors)
  const interventionResult = await checkInterventions(eventPayload);
  if (interventionResult.interventions.length > 0) {
    console.log('[AttentionOS SW] Interventions triggered:', interventionResult.interventions.map(i => i.type));
    // Deliver interventions to the sender tab
    // For now, log them. Phase 3 will add banner/notification UI.
    if (senderTabId) {
      _sendToTab(senderTabId, {
        type: 'AOS_INTERVENTIONS',
        payload: { interventions: interventionResult.interventions },
      });
    }
  }

  return { ok: true, action: 'none' };
}

// ─── Tracker Registration ────────────────────────────────────────────────────

function _handleTrackerRegister(tabId) {
  if (!tabId) return { assigned: false };

  if (activeTrackerTabId === null || activeTrackerTabId === tabId) {
    activeTrackerTabId = tabId;
    return { assigned: true };
  }

  // Check if the current tracker tab still exists
  chrome.tabs.get(activeTrackerTabId, (tab) => {
    if (chrome.runtime.lastError || !tab) {
      // Previous tracker tab is gone — reassign
      activeTrackerTabId = tabId;
      _sendToTab(tabId, { type: 'AOS_TRACKER_ASSIGNED' });
    }
  });

  return { assigned: false };
}

function _handleTrackerUnregister(tabId) {
  if (activeTrackerTabId === tabId) {
    activeTrackerTabId = null;

    // Try to reassign to another Instagram tab
    _reassignTracker();
  }
  return { ok: true };
}

/**
 * Finds another open Instagram tab and assigns it as the tracker.
 */
function _reassignTracker() {
  chrome.tabs.query(
    { url: ['https://*.instagram.com/*', 'https://instagram.com/*'] },
    (tabs) => {
      if (chrome.runtime.lastError || !tabs || tabs.length === 0) return;

      // Pick the first active/visible tab, or any tab
      const candidate =
        tabs.find((t) => t.active) || tabs.find((t) => !t.discarded) || tabs[0];

      if (candidate && candidate.id !== activeTrackerTabId) {
        activeTrackerTabId = candidate.id;
        _sendToTab(candidate.id, { type: 'AOS_TRACKER_ASSIGNED' });
        console.log(`[AttentionOS SW] Tracker reassigned to tab ${candidate.id}`);
      }
    }
  );
}

// ─── Friction Dismissed ──────────────────────────────────────────────────────

async function _handleFrictionDismissed() {
  await onFrictionDismissed();
  return { ok: true };
}

// ─── Session End ─────────────────────────────────────────────────────────────

async function _handleSessionEnd(payload) {
  const today = todayUTC();
  const currentSessions =
    (await _getDailySummaryField(today, 'sessions')) || 0;
  await updateDailySummary(today, { sessions: currentSessions + 1 });
  return { ok: true };
}

// ─── Cooldown/Block Expiry from Client ───────────────────────────────────────

async function _handleCooldownExpiryFromClient() {
  const result = await onCooldownExpiry();

  if (result.expired && result.hardBlockTakeover) {
    // Hard block takes over
    const state = await getState();
    const settings = await getSettings();
    await _broadcastToInstagramTabs({
      type: 'AOS_SHOW_BLOCK',
      payload: {
        expires: state.hard_block_expires,
        reelsWatched: state.reels_watched_today,
        dailyLimit: settings.daily_limit,
      },
    });
  }

  return { ok: true };
}

async function _handleHardBlockExpiryFromClient() {
  const result = await onHardBlockExpiry();

  if (result.expired) {
    await _broadcastToInstagramTabs({ type: 'AOS_REMOVE_BLOCK' });
  }

  return { ok: true };
}

// ─── Status ──────────────────────────────────────────────────────────────────

async function _handleGetStatus() {
  const state = await getState();
  const settings = await getSettings();
  return {
    ok: true,
    state,
    settings,
  };
}

// ─── Alarm Handling ──────────────────────────────────────────────────────────

/**
 * Handles all alarms created by AttentionOS.
 */
async function onAlarm(alarm) {
  switch (alarm.name) {
    case 'aos_cooldown_expiry': {
      const result = await onCooldownExpiry();
      if (result.expired) {
        if (result.hardBlockTakeover) {
          const state = await getState();
          const settings = await getSettings();
          await _broadcastToInstagramTabs({
            type: 'AOS_SHOW_BLOCK',
            payload: {
              expires: state.hard_block_expires,
              reelsWatched: state.reels_watched_today,
              dailyLimit: settings.daily_limit,
            },
          });
        } else {
          await _broadcastToInstagramTabs({
            type: 'AOS_REMOVE_COOLDOWN',
            payload: { hardBlockTakeover: false },
          });
        }
      }
      break;
    }

    case 'aos_hard_block_expiry': {
      const result = await onHardBlockExpiry();
      if (result.expired) {
        await _broadcastToInstagramTabs({ type: 'AOS_REMOVE_BLOCK' });
      }
      break;
    }

    case 'aos_maintenance': {
      // Periodic maintenance
      await applyPendingSettings();
      await checkCooldownCountReset();
      await _validateActiveState();
      break;
    }

    default:
      // Not our alarm — ignore
      break;
  }
}

// ─── Tab Coordination ────────────────────────────────────────────────────────

/**
 * Handles tab removal. If the tracker tab is closed, reassigns.
 */
function onTabRemoved(tabId) {
  if (tabId === activeTrackerTabId) {
    activeTrackerTabId = null;
    _reassignTracker();
  }
}

/**
 * Handles tab updates (URL changes). If the tracker tab navigates away
 * from Instagram, reassign.
 */
function onTabUpdated(tabId, changeInfo, tab) {
  if (tabId !== activeTrackerTabId) return;
  if (!changeInfo.url) return;

  const isInstagram =
    changeInfo.url.includes('instagram.com');

  if (!isInstagram) {
    activeTrackerTabId = null;
    _reassignTracker();
  }
}

// ─── Messaging Helpers ───────────────────────────────────────────────────────

/**
 * Sends a message to a specific tab.
 */
function _sendToTab(tabId, message) {
  try {
    chrome.tabs.sendMessage(tabId, message, () => {
      if (chrome.runtime.lastError) {
        // Tab may have been closed or content script not loaded
        console.warn(
          `[AttentionOS SW] Failed to send to tab ${tabId}:`,
          chrome.runtime.lastError.message
        );
      }
    });
  } catch (err) {
    console.warn(`[AttentionOS SW] sendToTab error:`, err.message);
  }
}

/**
 * Broadcasts a message to ALL open Instagram tabs.
 */
async function _broadcastToInstagramTabs(message) {
  try {
    const tabs = await chrome.tabs.query({
      url: ['https://*.instagram.com/*', 'https://instagram.com/*'],
    });

    for (const tab of tabs) {
      _sendToTab(tab.id, message);
    }
  } catch (err) {
    console.warn('[AttentionOS SW] Broadcast failed:', err.message);
  }
}

// ─── Utility ─────────────────────────────────────────────────────────────────

async function _getDailySummaryField(dateStr, field) {
  const summary = await getDailySummary(dateStr);
  return summary ? summary[field] : null;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  onStartup,
  onInstalled,
  onMessage,
  onAlarm,
  onTabRemoved,
  onTabUpdated,
};
