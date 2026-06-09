/**
 * AttentionOS — Reel Tracker
 *
 * Detects individual reel views on instagram.com/reels/* using a two-observer
 * architecture:
 *
 *  1. MutationObserver — watches for new reel containers entering the DOM.
 *  2. IntersectionObserver (threshold 0.5) — tracks viewport visibility.
 *
 * A reel counts as "watched" only when it has been ≥50% visible for ≥2 continuous
 * seconds. This filters out rapid scrolling and partial impressions.
 *
 * Fires events to storage and notifies the service worker for budget/cooldown
 * checks after every confirmed view.
 *
 * Multi-tab safety: on init, sends TRACKER_REGISTER to the service worker.
 * If another tab already owns the tracker, this instance stays dormant.
 */

import {
  logEvent,
  getState,
  updateState,
  generateSessionId,
  todayUTC,
  currentLocalHour,
} from './storage.js';

// ─── Configuration ───────────────────────────────────────────────────────────

const VIEW_THRESHOLD_MS = 2000;     // 2 seconds of continuous visibility
const INTERSECTION_RATIO = 0.5;     // 50% in viewport
const SESSION_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes of inactivity → session end

// ─── Module State ────────────────────────────────────────────────────────────
// Kept in-memory per content-script lifetime. Survives SPA navigations but not
// tab reloads (which is correct — service worker is the source of truth).

let mutationObs = null;
let intersectionObs = null;
let trackedElements = new WeakMap();  // element → { timerId, entryTime, reelId }
let seenReelIds = new Set();          // Prevent double-counting within a session
let sessionTimeoutId = null;
let isActive = false;

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Starts the tracker. Call from content-main.js after confirming this tab
 * is the active tracker via the service worker.
 */
async function startTracking() {
  if (isActive) return;
  isActive = true;

  // Ensure we have a session
  const state = await getState();
  if (!state.current_session_id) {
    const sessionId = generateSessionId();
    const now = new Date().toISOString();
    await updateState({
      current_session_id: sessionId,
      current_session_start: now,
    });
    await logEvent({
      event: 'session_start',
      session_id: sessionId,
      timestamp: now,
      day: todayUTC(),
      hour: currentLocalHour(),
    });
  }

  // Set up IntersectionObserver
  intersectionObs = new IntersectionObserver(_onIntersection, {
    threshold: INTERSECTION_RATIO,
  });

  // Set up MutationObserver — watch for new video/reel containers
  mutationObs = new MutationObserver(_onMutation);
  mutationObs.observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
  });

  // Scan existing DOM for reels already present
  _scanForReels(document);

  console.log('[AttentionOS] Tracker started');
}

/**
 * Stops the tracker. Cleans up all observers and timers.
 */
function stopTracking() {
  if (!isActive) return;
  isActive = false;

  if (mutationObs) {
    mutationObs.disconnect();
    mutationObs = null;
  }

  if (intersectionObs) {
    intersectionObs.disconnect();
    intersectionObs = null;
  }

  // Clear any pending view timers
  // (WeakMap entries will be GC'd when elements are removed)

  if (sessionTimeoutId) {
    clearTimeout(sessionTimeoutId);
    sessionTimeoutId = null;
  }

  console.log('[AttentionOS] Tracker stopped');
}

/**
 * Ends the current session (logs event, clears session state).
 * Called on tab unload or after SESSION_TIMEOUT_MS of inactivity.
 */
async function endSession() {
  const state = await getState();
  if (!state.current_session_id) return;

  const now = new Date().toISOString();
  await logEvent({
    event: 'session_end',
    session_id: state.current_session_id,
    timestamp: now,
    day: todayUTC(),
    hour: currentLocalHour(),
  });

  await updateState({
    current_session_id: null,
    current_session_start: null,
    friction_shown_this_session: false,
  });

  seenReelIds.clear();
}

// ─── MutationObserver Callback ───────────────────────────────────────────────

function _onMutation(mutations) {
  if (!isActive) return;

  for (const mutation of mutations) {
    for (const node of mutation.addedNodes) {
      if (node.nodeType !== Node.ELEMENT_NODE) continue;
      _scanForReels(node);
    }
  }
}

/**
 * Scans a DOM subtree for **actual reel** video elements and registers them
 * with the IntersectionObserver.  Only videos that belong to a reel context
 * (article with a /reel/ link, anything on /reels/ page) are tracked.
 * Regular posts, stories, and IGTV are ignored.
 */
function _scanForReels(root) {
  const candidates = root.querySelectorAll
    ? root.querySelectorAll('video')
    : [];

  // Also check if root itself is a video
  if (root.tagName === 'VIDEO') {
    if (_isReelVideo(root)) _observeVideo(root);
  }

  for (const video of candidates) {
    if (_isReelVideo(video)) {
      _observeVideo(video);
    }
  }
}

/**
 * Returns true if the <video> element is part of a reel.
 * Matches the same logic used by reels-blur.js.
 */
function _isReelVideo(videoEl) {
  // On the dedicated /reels/* page, every visible video is a reel
  const path = window.location.pathname;
  if (path.startsWith('/reels')) return true;

  // On the feed / explore, the video must live inside an <article>
  // that links to /reel/…
  const article = videoEl.closest('article');
  if (!article) return false;

  return article.querySelector('a[href*="/reel"]') !== null;
}

/**
 * Registers a single video element with the IntersectionObserver.
 * Extracts the reel ID and stores metadata in the WeakMap.
 */
function _observeVideo(videoEl) {
  if (!intersectionObs) return;
  if (trackedElements.has(videoEl)) return; // Already tracked

  const reelId = _extractReelId(videoEl);

  trackedElements.set(videoEl, {
    timerId: null,
    entryTime: null,
    reelId: reelId,
    counted: false,
  });

  intersectionObs.observe(videoEl);
}

// ─── IntersectionObserver Callback ───────────────────────────────────────────

function _onIntersection(entries) {
  for (const entry of entries) {
    const meta = trackedElements.get(entry.target);
    if (!meta) continue;

    if (entry.isIntersecting && entry.intersectionRatio >= INTERSECTION_RATIO) {
      // Reel entered viewport at ≥50% — start the 2-second timer
      if (!meta.timerId && !meta.counted) {
        meta.entryTime = Date.now();
        meta.timerId = setTimeout(() => {
          _onReelViewed(entry.target, meta);
        }, VIEW_THRESHOLD_MS);
      }
    } else {
      // Reel left viewport — cancel pending timer
      if (meta.timerId) {
        clearTimeout(meta.timerId);
        meta.timerId = null;
        meta.entryTime = null;
      }
    }
  }
}

// ─── Reel Viewed Handler ─────────────────────────────────────────────────────

async function _onReelViewed(videoEl, meta) {
  if (meta.counted) return;
  meta.counted = true;
  meta.timerId = null;

  // Prevent double-counting same reel in this session
  if (seenReelIds.has(meta.reelId)) return;
  seenReelIds.add(meta.reelId);

  // Calculate watch duration — at minimum VIEW_THRESHOLD_MS,
  // but could be longer if we add duration tracking later.
  const watchDuration = Math.round((Date.now() - meta.entryTime) / 1000) || 2;

  const state = await getState();
  const now = new Date().toISOString();

  // Ensure session is active
  let sessionId = state.current_session_id;
  if (!sessionId) {
    sessionId = generateSessionId();
    const sessionStart = now;
    await updateState({
      current_session_id: sessionId,
      current_session_start: sessionStart,
    });
    await logEvent({
      event: 'session_start',
      session_id: sessionId,
      timestamp: now,
      day: todayUTC(),
      hour: currentLocalHour(),
    });
  }

  // Build the event
  const event = {
    event: 'reel_viewed',
    reel_id: meta.reelId,
    watch_duration: watchDuration,
    timestamp: now,
    session_id: sessionId,
    day: todayUTC(),
    hour: currentLocalHour(),
  };

  // Log to storage
  await logEvent(event);

  // Update last reel view timestamp for session timeout tracking
  await updateState({ last_reel_view_at: now });

  // Notify service worker — it will run budget + cooldown checks
  try {
    chrome.runtime.sendMessage({
      type: 'AOS_REEL_VIEWED',
      payload: event,
    });
  } catch (err) {
    // Service worker may be inactive; it will reconcile on next wake
    console.warn('[AttentionOS] Failed to notify service worker:', err.message);
  }

  // Reset session inactivity timeout
  _resetSessionTimeout();

  console.log(`[AttentionOS] Reel viewed: ${meta.reelId} (${watchDuration}s)`);
}

// ─── Reel ID Extraction ─────────────────────────────────────────────────────

/**
 * Extracts a reel identifier from the DOM context around a video element.
 * Tries multiple strategies since Instagram's DOM changes frequently.
 *
 * Priority:
 *  1. URL path segment: /reels/<id>/
 *  2. Closest ancestor with a data attribute containing an ID
 *  3. Closest <a> linking to /reel/<id>/ or /reels/<id>/
 *  4. Video src hash (fallback — unique enough per reel)
 */
function _extractReelId(videoEl) {
  // Strategy 1: URL path
  const pathMatch = window.location.pathname.match(/\/reels?\/([A-Za-z0-9_-]+)/);
  if (pathMatch) {
    // On the single-reel page, this is definitive.
    // On the feed, multiple reels share the URL, so we still try other strategies.
    // If there's only one video visible, use the URL.
    const allVideos = document.querySelectorAll('video');
    if (allVideos.length <= 1) {
      return pathMatch[1];
    }
  }

  // Strategy 2: Ancestor with data attributes
  let ancestor = videoEl.closest('[data-media-id]');
  if (ancestor) return ancestor.getAttribute('data-media-id');

  // Try aria-label or other identifying attributes on parent containers
  ancestor = videoEl.closest('article');
  if (ancestor) {
    const link = ancestor.querySelector('a[href*="/reel"]');
    if (link) {
      const linkMatch = link.href.match(/\/reels?\/([A-Za-z0-9_-]+)/);
      if (linkMatch) return linkMatch[1];
    }
  }

  // Strategy 3: Any nearby link to a reel
  const parent = videoEl.parentElement;
  if (parent) {
    const link = parent.querySelector('a[href*="/reel"]');
    if (link) {
      const linkMatch = link.href.match(/\/reels?\/([A-Za-z0-9_-]+)/);
      if (linkMatch) return linkMatch[1];
    }
  }

  // Strategy 4: Fallback — hash of video src + position in DOM
  const src = videoEl.src || videoEl.querySelector('source')?.src || '';
  if (src) {
    return _simpleHash(src);
  }

  // Last resort — use the closest <article> container identity, or a
  // positional fingerprint from the DOM (avoids random-per-scroll).
  const article = videoEl.closest('article');
  if (article) {
    // Use the article's position in the document + a short stable hash
    // of its first 200 chars of innerHTML (changes on re-render, so
    // dedup still works for the same article instance).
    const allArticles = Array.from(document.querySelectorAll('article'));
    const idx = allArticles.indexOf(article);
    if (idx >= 0) {
      const fingerprint = article.innerHTML.slice(0, 200);
      return _simpleHash(`article:${idx}:${fingerprint}`);
    }
  }

  return 'unknown_reel';
}

/**
 * Simple FNV-1a-inspired hash for short strings (video URLs).
 * Not cryptographic — just needs to be consistent within a session.
 */
function _simpleHash(str) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  return 'reel_' + hash.toString(16);
}

// ─── Session Timeout ─────────────────────────────────────────────────────────

function _resetSessionTimeout() {
  if (sessionTimeoutId) {
    clearTimeout(sessionTimeoutId);
  }
  sessionTimeoutId = setTimeout(async () => {
    console.log('[AttentionOS] Session timeout — ending session');
    await endSession();

    // Notify service worker
    try {
      const state = await getState();
      chrome.runtime.sendMessage({
        type: 'AOS_SESSION_END',
        payload: {
          session_id: state.current_session_id,
          timestamp: new Date().toISOString(),
        },
      });
    } catch (_) {
      // Service worker may be sleeping
    }
  }, SESSION_TIMEOUT_MS);
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  startTracking,
  stopTracking,
  endSession,
};
