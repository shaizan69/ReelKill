/**
 * AttentionOS — Reel Tracker
 *
 * Detects individual reel views on instagram.com/reels/* using a
 * multi-signal architecture:
 *
 *  1. MutationObserver — watches for new reel containers entering the DOM.
 *  2. IntersectionObserver (threshold 0.3) — tracks viewport visibility.
 *  3. <video> playing event — fallback when IntersectionObserver doesn't fire.
 *
 * A reel counts as "watched" when it has been visible for ≥2 continuous
 * seconds, triggered by either the intersection path or the playing-event path.
 * This filters out rapid scrolling and partial impressions.
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
const INTERSECTION_RATIO = 0.3;     // 30% in viewport (more forgiving)
const SESSION_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes of inactivity → session end

// ─── Module State ────────────────────────────────────────────────────────────

let mutationObs = null;
let intersectionObs = null;
let trackedElements = new WeakMap();  // element → { timerId, entryTime, reelId, counted, playingBound }
let sessionTimeoutId = null;
let isActive = false;
let _idCounter = 0;                   // Monotonic fallback for reel IDs

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

  // Clean up playing event listeners on tracked elements
  trackedElements.forEach((meta, el) => {
    if (meta.playingBound) {
      el.removeEventListener('playing', meta.playingBound, true);
    }
    if (meta.timerId) {
      clearTimeout(meta.timerId);
    }
  });
  trackedElements = new WeakMap();

  if (sessionTimeoutId) {
    clearTimeout(sessionTimeoutId);
    sessionTimeoutId = null;
  }

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
 * Scans a DOM subtree for actual reel video elements and registers them
 * with the IntersectionObserver + playing event listener.
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
  // that links to /reel/
  const article = videoEl.closest('article');
  if (!article) return false;

  return article.querySelector('a[href*="/reel"]') !== null;
}

/**
 * Registers a single video element with the IntersectionObserver
 * and a playing-event fallback. Extracts the reel ID and stores
 * metadata in the WeakMap.
 */
function _observeVideo(videoEl) {
  if (!intersectionObs) return;
  if (trackedElements.has(videoEl)) return; // Already tracked

  const reelId = _extractReelId(videoEl);

  const meta = {
    timerId: null,
    entryTime: null,
    reelId: reelId,
    counted: false,
    playingBound: null,
  };

  trackedElements.set(videoEl, meta);

  // Path A: IntersectionObserver
  intersectionObs.observe(videoEl);

  // Path B: playing event fallback — catches reels the observer misses
  const onPlaying = () => {
    if (meta.counted || meta.timerId) return;
    meta.entryTime = Date.now();
    meta.timerId = setTimeout(() => {
      _onReelViewed(videoEl, meta);
    }, VIEW_THRESHOLD_MS);
  };
  meta.playingBound = onPlaying;
  videoEl.addEventListener('playing', onPlaying, true);

}

// ─── IntersectionObserver Callback ───────────────────────────────────────────

function _onIntersection(entries) {
  for (const entry of entries) {
    const meta = trackedElements.get(entry.target);
    if (!meta || meta.counted) continue;

    if (entry.isIntersecting && entry.intersectionRatio >= INTERSECTION_RATIO) {
      // Reel entered viewport — start the 2-second timer
      if (!meta.timerId) {
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

  // Clean up playing listener
  if (meta.playingBound) {
    videoEl.removeEventListener('playing', meta.playingBound, true);
    meta.playingBound = null;
  }

  const watchDuration = Math.round((Date.now() - meta.entryTime) / 1000) || 2;

  const state = await getState();
  const now = new Date().toISOString();

  // Ensure session is active
  let sessionId = state.current_session_id;
  if (!sessionId) {
    sessionId = generateSessionId();
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
    console.warn('[RK] Failed to notify service worker:', err.message);
  }

  // Reset session inactivity timeout
  _resetSessionTimeout();


}

// ─── Reel ID Extraction ─────────────────────────────────────────────────────

/**
 * Extracts a reel identifier from the DOM context around a video element.
 *
 * On /reels/ page: URL path is definitive (each page = one reel).
 * On feed: tries article links and data attributes.
 * Last resort: positional fingerprint (unique per DOM element).
 */
function _extractReelId(videoEl) {
  const path = window.location.pathname;

  // Strategy 1: URL path — definitive on /reels/ pages
  const pathMatch = path.match(/\/reels?\/([A-Za-z0-9_-]+)/);
  if (pathMatch) {
    // On /reels/ page, URL is always the right ID (each page = one reel)
    if (path.startsWith('/reels')) {
      return pathMatch[1];
    }
    // On feed, only use URL if there's a single video (avoid collisions)
    const allVideos = document.querySelectorAll('video');
    if (allVideos.length <= 1) {
      return pathMatch[1];
    }
  }

  // Strategy 2: Ancestor with data attributes
  let ancestor = videoEl.closest('[data-media-id]');
  if (ancestor) return ancestor.getAttribute('data-media-id');

  // Strategy 3: Article container with reel link
  ancestor = videoEl.closest('article');
  if (ancestor) {
    const link = ancestor.querySelector('a[href*="/reel"]');
    if (link) {
      const linkMatch = link.href.match(/\/reels?\/([A-Za-z0-9_-]+)/);
      if (linkMatch) return linkMatch[1];
    }
  }

  // Strategy 4: Any nearby link to a reel
  const parent = videoEl.parentElement;
  if (parent) {
    const link = parent.querySelector('a[href*="/reel"]');
    if (link) {
      const linkMatch = link.href.match(/\/reels?\/([A-Za-z0-9_-]+)/);
      if (linkMatch) return linkMatch[1];
    }
  }

  // Strategy 5: Video src hash (blob URLs are unique per video)
  const src = videoEl.src || videoEl.querySelector('source')?.src || '';
  if (src) {
    return _simpleHash(src);
  }

  // Strategy 6: Article positional fingerprint
  if (ancestor) {
    const allArticles = Array.from(document.querySelectorAll('article'));
    const idx = allArticles.indexOf(ancestor);
    if (idx >= 0) {
      const fingerprint = ancestor.innerHTML.slice(0, 200);
      return _simpleHash(`article:${idx}:${fingerprint}`);
    }
  }

  // Last resort: monotonic counter — never returns the same ID twice
  return 'reel_' + (++_idCounter);
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
