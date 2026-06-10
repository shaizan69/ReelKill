/**
 * AttentionOS — Storage Module
 * 
 * Central data layer. Every other module reads/writes through these helpers.
 * Uses chrome.storage.local exclusively (Antigram uses chrome.storage.sync — no collision).
 * 
 * All keys are prefixed with "aos_" to namespace clearly.
 * 
 * Design decisions:
 *  - Single get/set per logical operation for atomicity.
 *  - Settings loosening is queued (applies after 24h). Tightening is immediate.
 *  - Events older than 30 days are pruned on every write to keep storage lean.
 *  - All timestamps are UTC ISO 8601 strings.
 */

// ─── Storage Keys ────────────────────────────────────────────────────────────

const KEYS = {
  SETTINGS: 'aos_settings',
  STATE: 'aos_state',
  EVENTS: 'aos_events',
  DAILY_SUMMARIES: 'aos_daily_summaries',
  PENDING_SETTINGS: 'aos_pending_settings',
};

// ─── Defaults ────────────────────────────────────────────────────────────────

const DEFAULT_SETTINGS = {
  daily_limit: 30,               // Hard ceiling: 50. User picks during onboarding.
  binge_threshold_reels: 15,     // Reels in window to trigger cooldown
  binge_window_minutes: 10,      // Sliding window size
  cooldown_base_seconds: 300,    // 5 minutes (1st cooldown)
  friction_threshold_pct: 0.8,   // Friction fires at 80% of daily limit
};

const DEFAULT_STATE = {
  reels_watched_today: 0,
  cooldown_active: false,
  cooldown_expires: null,        // ISO string or null
  cooldown_count_today: 0,
  hard_block_active: false,
  hard_block_expires: null,      // ISO string or null
  current_session_id: null,
  current_session_start: null,   // ISO string or null
  last_reel_view_at: null,       // ISO string — used for session timeout
  friction_shown_this_session: false,
  block_day_start: null,         // ISO string — rolling 24h anchor for daily counter
};

// ─── Low-Level Helpers ───────────────────────────────────────────────────────

/**
 * Reads one or more keys from chrome.storage.local.
 * @param {string|string[]} keys
 * @returns {Promise<Object>}
 */
function storageGet(keys) {
  return new Promise((resolve) => {
    chrome.storage.local.get(keys, resolve);
  });
}

/**
 * Writes an object to chrome.storage.local (atomic for all keys in the object).
 * @param {Object} data
 * @returns {Promise<void>}
 */
function storageSet(data) {
  return new Promise((resolve) => {
    chrome.storage.local.set(data, resolve);
  });
}

// ─── Initialization ──────────────────────────────────────────────────────────

/**
 * Initializes storage with defaults. Non-destructive — preserves existing data.
 * Call on extension install and on every service worker startup.
 */
async function initialize() {
  const existing = await storageGet([
    KEYS.SETTINGS,
    KEYS.STATE,
    KEYS.EVENTS,
    KEYS.DAILY_SUMMARIES,
  ]);

  const updates = {};

  if (!existing[KEYS.SETTINGS]) {
    updates[KEYS.SETTINGS] = { ...DEFAULT_SETTINGS };
  }
  if (!existing[KEYS.STATE]) {
    updates[KEYS.STATE] = { ...DEFAULT_STATE };
  }
  if (!existing[KEYS.EVENTS]) {
    updates[KEYS.EVENTS] = [];
  }
  if (!existing[KEYS.DAILY_SUMMARIES]) {
    updates[KEYS.DAILY_SUMMARIES] = {};
  }

  if (Object.keys(updates).length > 0) {
    await storageSet(updates);
  }
}

// ─── Settings ────────────────────────────────────────────────────────────────

/**
 * Returns current settings object.
 * @returns {Promise<Object>}
 */
async function getSettings() {
  const data = await storageGet(KEYS.SETTINGS);
  return data[KEYS.SETTINGS] || { ...DEFAULT_SETTINGS };
}

/**
 * Updates settings. All changes are queued and take effect at the next
 * 24-hour session reset (24h after the first reel was watched).
 *
 * If no session is active (block_day_start is null), changes apply
 * immediately since there's no active session to protect.
 *
 * daily_limit is hard-capped at 50.
 *
 * @param {Object} partial — key/value pairs to update
 * @returns {Promise<{queued: Object|null}>}
 */
async function updateSettings(partial) {
  const data = await storageGet([KEYS.SETTINGS, KEYS.PENDING_SETTINGS, KEYS.STATE]);
  const current = data[KEYS.SETTINGS] || { ...DEFAULT_SETTINGS };
  const pending = data[KEYS.PENDING_SETTINGS] || null;
  const state = data[KEYS.STATE] || {};

  const queued = {};
  let hasQueued = false;

  for (const [key, value] of Object.entries(partial)) {
    if (!(key in DEFAULT_SETTINGS)) continue;

    let safeValue = value;

    // Hard ceiling enforcement
    if (key === 'daily_limit') {
      safeValue = Math.min(Math.max(1, Math.floor(safeValue)), 50);
    }

    if (safeValue !== current[key]) {
      queued[key] = safeValue;
      hasQueued = true;
    }
  }

  if (!hasQueued) {
    return { queued: null };
  }

  const updates = {};

  if (state.block_day_start) {
    // Session active — queue changes to apply at next reset
    const windowEnd = new Date(
      new Date(state.block_day_start).getTime() + 24 * 60 * 60 * 1000
    );
    updates[KEYS.PENDING_SETTINGS] = {
      changes: { ...(pending?.changes || {}), ...queued },
      applies_at: windowEnd.toISOString(),
    };
  } else {
    // No active session — apply immediately
    updates[KEYS.SETTINGS] = { ...current, ...queued };
  }

  if (Object.keys(updates).length > 0) {
    await storageSet(updates);
  }

  return {
    queued: hasQueued ? queued : null,
  };
}

/**
 * Applies pending settings if the current 24h session window has reset.
 *
 * The reset is detected by checking if `block_day_start` has moved past
 * the `applies_at` timestamp — meaning a new session started after the
 * pending changes were queued.
 *
 * Also falls back to a time-based check (now >= applies_at) for the case
 * where block_day_start was cleared (e.g. hard block expiry).
 *
 * @returns {Promise<boolean>} true if settings were applied
 */
async function applyPendingSettings() {
  const data = await storageGet([KEYS.SETTINGS, KEYS.PENDING_SETTINGS, KEYS.STATE]);
  const pending = data[KEYS.PENDING_SETTINGS];
  const state = data[KEYS.STATE] || {};

  if (!pending || !pending.applies_at) return false;

  const appliesAt = new Date(pending.applies_at);
  const now = new Date();

  let shouldApply = false;

  if (state.block_day_start) {
    // Session active — apply if the current window started AFTER the target time
    const windowStart = new Date(state.block_day_start);
    shouldApply = windowStart >= appliesAt;
  } else {
    // No active session — apply if the target time has passed
    shouldApply = now >= appliesAt;
  }

  if (!shouldApply) return false;

  const current = data[KEYS.SETTINGS] || { ...DEFAULT_SETTINGS };
  const merged = { ...current, ...pending.changes };

  // Enforce hard ceiling again just in case
  merged.daily_limit = Math.min(Math.max(1, Math.floor(merged.daily_limit)), 50);

  await storageSet({
    [KEYS.SETTINGS]: merged,
    [KEYS.PENDING_SETTINGS]: null,
  });

  return true;
}

// ─── State ───────────────────────────────────────────────────────────────────

/**
 * Returns current state object.
 * @returns {Promise<Object>}
 */
async function getState() {
  const data = await storageGet(KEYS.STATE);
  return data[KEYS.STATE] || { ...DEFAULT_STATE };
}

/**
 * Atomically updates state. Merges partial into existing state.
 * @param {Object} partial
 * @returns {Promise<Object>} The full updated state
 */
async function updateState(partial) {
  const data = await storageGet(KEYS.STATE);
  const current = data[KEYS.STATE] || { ...DEFAULT_STATE };
  const updated = { ...current, ...partial };
  await storageSet({ [KEYS.STATE]: updated });
  return updated;
}

// ─── Events ──────────────────────────────────────────────────────────────────

/**
 * Appends an event to the events array. Auto-prunes entries older than 30 days.
 * @param {Object} eventObj — must contain at least { event, timestamp }
 * @returns {Promise<void>}
 */
async function logEvent(eventObj) {
  const data = await storageGet(KEYS.EVENTS);
  let events = data[KEYS.EVENTS] || [];

  // Prune old events (> 30 days)
  const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
  events = events.filter((e) => e.timestamp >= cutoff);

  events.push(eventObj);
  await storageSet({ [KEYS.EVENTS]: events });
}

/**
 * Returns all events within a time window [startISO, endISO].
 * If eventType is provided, filters to that event type only.
 * @param {string} startISO
 * @param {string} endISO
 * @param {string} [eventType]
 * @returns {Promise<Object[]>}
 */
async function getEventsInWindow(startISO, endISO, eventType) {
  const data = await storageGet(KEYS.EVENTS);
  const events = data[KEYS.EVENTS] || [];

  return events.filter((e) => {
    const inRange = e.timestamp >= startISO && e.timestamp <= endISO;
    const matchesType = eventType ? e.event === eventType : true;
    return inRange && matchesType;
  });
}

/**
 * Returns ALL events (for analytics, debugging). Respects 30-day window.
 * @returns {Promise<Object[]>}
 */
async function getAllEvents() {
  const data = await storageGet(KEYS.EVENTS);
  return data[KEYS.EVENTS] || [];
}

/**
 * Prunes events older than 30 days. Called on service worker startup.
 * @returns {Promise<number>} Number of events pruned
 */
async function pruneOldEvents() {
  const data = await storageGet(KEYS.EVENTS);
  const events = data[KEYS.EVENTS] || [];
  const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
  const pruned = events.filter((e) => e.timestamp >= cutoff);
  const removed = events.length - pruned.length;

  if (removed > 0) {
    await storageSet({ [KEYS.EVENTS]: pruned });
  }

  return removed;
}

// ─── Daily Summaries ─────────────────────────────────────────────────────────

/**
 * Returns the summary for a specific date.
 * @param {string} dateStr — "YYYY-MM-DD"
 * @returns {Promise<Object|null>}
 */
async function getDailySummary(dateStr) {
  const data = await storageGet(KEYS.DAILY_SUMMARIES);
  const summaries = data[KEYS.DAILY_SUMMARIES] || {};
  return summaries[dateStr] || null;
}

/**
 * Updates (merges) the summary for a specific date.
 * @param {string} dateStr — "YYYY-MM-DD"
 * @param {Object} partial
 * @returns {Promise<void>}
 */
async function updateDailySummary(dateStr, partial) {
  const data = await storageGet(KEYS.DAILY_SUMMARIES);
  const summaries = data[KEYS.DAILY_SUMMARIES] || {};
  const existing = summaries[dateStr] || {
    reels_watched: 0,
    time_spent_seconds: 0,
    sessions: 0,
    cooldowns_triggered: 0,
    hard_block_hit: false,
    attention_score: 0,
  };

  summaries[dateStr] = { ...existing, ...partial };
  await storageSet({ [KEYS.DAILY_SUMMARIES]: summaries });
}

// ─── Utility ─────────────────────────────────────────────────────────────────

/**
 * Generates a UUID v4 for session IDs.
 * @returns {string}
 */
function generateSessionId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Returns today's date as "YYYY-MM-DD" in UTC.
 * @returns {string}
 */
function todayUTC() {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Returns current hour (0-23) in the user's local timezone.
 * Used only for heatmap bucketing — all other time logic is UTC.
 * @returns {number}
 */
function currentLocalHour() {
  return new Date().getHours();
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  KEYS,
  DEFAULT_SETTINGS,
  DEFAULT_STATE,
  initialize,
  getSettings,
  updateSettings,
  applyPendingSettings,
  getState,
  updateState,
  logEvent,
  getEventsInWindow,
  getAllEvents,
  pruneOldEvents,
  getDailySummary,
  updateDailySummary,
  generateSessionId,
  todayUTC,
  currentLocalHour,
};
