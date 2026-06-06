/**
 * AttentionOS — Cooldown Overlay (UI)
 *
 * Full-screen overlay injected into Instagram's DOM during active cooldowns.
 *
 * Features:
 *  - Covers entire viewport with a semi-opaque dark overlay
 *  - Countdown timer (MM:SS) running against wall clock
 *  - Progress display: reels_watched / daily_limit
 *  - Persists across tab close/reopen (reads expiry from chrome.storage.local)
 *  - Auto-removes itself when cooldown expires
 *  - Idempotent injection (checks for existing overlay)
 *  - Feed is set to display:none while active
 *  - Styles are scoped to avoid CSS conflicts
 *
 * This module runs in the content script context.
 */

// ─── Constants ───────────────────────────────────────────────────────────────

const OVERLAY_ID = 'aos-cooldown-overlay';
const TIMER_INTERVAL_MS = 1000;

// ─── State ───────────────────────────────────────────────────────────────────

let timerInterval = null;

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Injects the cooldown overlay into the page.
 * Idempotent — if overlay already exists, updates it instead.
 *
 * @param {string} expiresISO — ISO timestamp when cooldown ends
 * @param {number} reelsWatched — current reel count
 * @param {number} dailyLimit — user's daily limit
 */
function showCooldownOverlay(expiresISO, reelsWatched, dailyLimit) {
  // Prevent duplicate injection
  if (document.getElementById(OVERLAY_ID)) {
    _updateOverlayData(expiresISO, reelsWatched, dailyLimit);
    return;
  }

  // Hide the feed
  _hideFeed();

  // Create overlay
  const overlay = document.createElement('div');
  overlay.id = OVERLAY_ID;
  overlay.setAttribute('data-attentionos', 'cooldown');

  // Inject styles
  const style = document.createElement('style');
  style.setAttribute('data-attentionos', 'cooldown-styles');
  style.textContent = _getStyles();
  document.head.appendChild(style);

  // Build overlay content
  overlay.innerHTML = _buildHTML(expiresISO, reelsWatched, dailyLimit);

  document.body.appendChild(overlay);

  // Start countdown timer
  _startTimer(expiresISO);

  console.log('[AttentionOS] Cooldown overlay injected');
}

/**
 * Removes the cooldown overlay and restores the feed.
 */
function removeCooldownOverlay() {
  const overlay = document.getElementById(OVERLAY_ID);
  if (overlay) {
    overlay.remove();
  }

  // Remove injected styles
  const style = document.querySelector('style[data-attentionos="cooldown-styles"]');
  if (style) {
    style.remove();
  }

  // Stop timer
  if (timerInterval) {
    clearInterval(timerInterval);
    timerInterval = null;
  }

  // Restore feed
  _showFeed();

  console.log('[AttentionOS] Cooldown overlay removed');
}

/**
 * Checks if the cooldown overlay is currently displayed.
 * @returns {boolean}
 */
function isCooldownOverlayActive() {
  return !!document.getElementById(OVERLAY_ID);
}

// ─── HTML Builder ────────────────────────────────────────────────────────────

function _buildHTML(expiresISO, reelsWatched, dailyLimit) {
  const remaining = _formatTimeRemaining(expiresISO);
  const progressPct = Math.min((reelsWatched / dailyLimit) * 100, 100);

  return `
    <div class="aos-cooldown-container">
      <div class="aos-cooldown-icon">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <polyline points="12 6 12 12 16 14"/>
        </svg>
      </div>

      <h1 class="aos-cooldown-title">Take a Breather</h1>

      <p class="aos-cooldown-subtitle">
        You've been scrolling fast. Let's slow down for a moment.
      </p>

      <div class="aos-cooldown-timer" id="aos-cooldown-timer">
        ${remaining}
      </div>
      <p class="aos-cooldown-timer-label">until you can continue</p>

      <div class="aos-cooldown-progress-container">
        <div class="aos-cooldown-progress-bar">
          <div class="aos-cooldown-progress-fill" style="width: ${progressPct}%"></div>
        </div>
        <p class="aos-cooldown-progress-text">
          <span id="aos-cooldown-watched">${reelsWatched}</span> / ${dailyLimit} reels today
        </p>
      </div>

      <p class="aos-cooldown-message">
        This cooldown is protecting your attention. It will end automatically.
      </p>
    </div>
  `;
}

// ─── Timer ───────────────────────────────────────────────────────────────────

function _startTimer(expiresISO) {
  if (timerInterval) {
    clearInterval(timerInterval);
  }

  timerInterval = setInterval(() => {
    const timerEl = document.getElementById('aos-cooldown-timer');
    if (!timerEl) {
      clearInterval(timerInterval);
      timerInterval = null;
      return;
    }

    const now = Date.now();
    const expires = new Date(expiresISO).getTime();
    const diff = expires - now;

    if (diff <= 0) {
      // Cooldown expired — remove overlay
      removeCooldownOverlay();
      // Notify service worker
      try {
        chrome.runtime.sendMessage({ type: 'AOS_COOLDOWN_EXPIRED_CLIENT' });
      } catch (_) {}
      return;
    }

    timerEl.textContent = _formatMs(diff);
  }, TIMER_INTERVAL_MS);
}

function _formatTimeRemaining(expiresISO) {
  const diff = new Date(expiresISO).getTime() - Date.now();
  if (diff <= 0) return '00:00';
  return _formatMs(diff);
}

function _formatMs(ms) {
  const totalSeconds = Math.ceil(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

// ─── Update (re-use existing overlay) ────────────────────────────────────────

function _updateOverlayData(expiresISO, reelsWatched, dailyLimit) {
  const watchedEl = document.getElementById('aos-cooldown-watched');
  if (watchedEl) watchedEl.textContent = reelsWatched;

  _startTimer(expiresISO);
}

// ─── Feed Visibility ─────────────────────────────────────────────────────────

function _hideFeed() {
  const main = document.querySelector('[role="main"]');
  if (main) main.style.display = 'none';

  // Also try the feed-specific container
  const articles = document.querySelectorAll('article');
  articles.forEach((a) => (a.style.display = 'none'));
}

function _showFeed() {
  const main = document.querySelector('[role="main"]');
  if (main) main.style.display = '';

  const articles = document.querySelectorAll('article');
  articles.forEach((a) => (a.style.display = ''));
}

// ─── Styles ──────────────────────────────────────────────────────────────────

function _getStyles() {
  return `
    #${OVERLAY_ID} {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      z-index: 999999;
      background: rgba(0, 0, 0, 0.92);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
        Helvetica, Arial, sans-serif;
      color: #ffffff;
      animation: aos-fade-in 0.4s ease-out;
      overflow-y: auto;
      overflow-x: hidden;
      -webkit-font-smoothing: antialiased;
      -moz-osx-font-smoothing: grayscale;
    }

    #${OVERLAY_ID} *,
    #${OVERLAY_ID} *::before,
    #${OVERLAY_ID} *::after {
      box-sizing: border-box;
      margin: 0;
      font-family: inherit;
      line-height: 1.4;
    }

    @keyframes aos-fade-in {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    .aos-cooldown-container {
      text-align: center;
      max-width: 420px;
      width: 100%;
      padding: 40px 24px;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    .aos-cooldown-icon {
      color: #ef4444;
      margin-bottom: 24px;
      animation: aos-pulse 2s ease-in-out infinite;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
    }

    .aos-cooldown-icon svg {
      display: block;
    }

    @keyframes aos-pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.7; transform: scale(1.05); }
    }

    .aos-cooldown-title {
      font-size: 32px;
      font-weight: 700;
      line-height: 1.25;
      margin: 0 0 12px 0;
      padding: 4px 2px;
      letter-spacing: -0.5px;
      color: #ef4444;
      background: linear-gradient(135deg, #ef4444, #dc2626, #b91c1c);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
      display: inline-block;
    }

    .aos-cooldown-subtitle {
      font-size: 16px;
      color: rgba(255, 255, 255, 0.7);
      margin: 0 0 32px 0;
      line-height: 1.5;
      max-width: 360px;
    }

    .aos-cooldown-timer {
      font-size: 72px;
      font-weight: 800;
      font-variant-numeric: tabular-nums;
      letter-spacing: 4px;
      line-height: 1.15;
      margin: 0 0 8px 0;
      padding: 6px 4px;
      color: #ef4444;
      background: linear-gradient(180deg, #ef4444 0%, #b91c1c 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
      display: inline-block;
    }

    .aos-cooldown-timer-label {
      font-size: 13px;
      color: rgba(255, 255, 255, 0.5);
      margin: 0 0 36px 0;
      text-transform: uppercase;
      letter-spacing: 2px;
      line-height: 1.4;
    }

    .aos-cooldown-progress-container {
      margin: 0 0 28px 0;
      width: 100%;
      max-width: 320px;
    }

    .aos-cooldown-progress-bar {
      height: 6px;
      background: rgba(255, 255, 255, 0.15);
      border-radius: 3px;
      overflow: hidden;
      margin: 0 0 10px 0;
    }

    .aos-cooldown-progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #ef4444, #dc2626);
      border-radius: 3px;
      transition: width 0.5s ease;
    }

    .aos-cooldown-progress-text {
      font-size: 13px;
      color: rgba(255, 255, 255, 0.55);
      margin: 0;
      line-height: 1.4;
      font-variant-numeric: tabular-nums;
    }

    .aos-cooldown-message {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.35);
      margin: 0;
      line-height: 1.5;
      max-width: 320px;
    }

    @media (max-width: 480px) {
      .aos-cooldown-title { font-size: 26px; }
      .aos-cooldown-timer { font-size: 56px; letter-spacing: 2px; }
    }
  `;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  showCooldownOverlay,
  removeCooldownOverlay,
  isCooldownOverlayActive,
};
