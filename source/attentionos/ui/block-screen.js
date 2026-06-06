/**
 * AttentionOS — Hard Block Screen (UI)
 *
 * Nuclear option. When the daily limit is hit, this screen replaces the
 * Instagram feed entirely:
 *
 *  - REMOVES [role="main"] content (not just display:none)
 *  - Injects a full-viewport block screen with 24h countdown
 *  - No close button. No dismiss. No override.
 *  - A MutationObserver prevents Instagram from re-rendering the feed
 *    by immediately removing any new [role="main"] content while active.
 *  - Persists across SPA navigations within Instagram.
 *
 * This module runs in the content script context.
 */

// ─── Constants ───────────────────────────────────────────────────────────────

const BLOCK_SCREEN_ID = 'aos-hard-block-screen';
const TIMER_INTERVAL_MS = 1000;

// ─── State ───────────────────────────────────────────────────────────────────

let timerInterval = null;
let feedGuard = null; // MutationObserver that prevents feed re-rendering

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Injects the hard block screen. Replaces the feed DOM.
 * Idempotent — safe to call multiple times.
 *
 * @param {string} expiresISO — ISO timestamp when the 24h block ends
 * @param {number} reelsWatched — total reels watched when block triggered
 * @param {number} dailyLimit — the limit that was exceeded
 */
function showBlockScreen(expiresISO, reelsWatched, dailyLimit) {
  // Prevent duplicate injection
  if (document.getElementById(BLOCK_SCREEN_ID)) {
    _updateTimer(expiresISO);
    return;
  }

  // REMOVE the feed (not hide — remove)
  _removeFeedContent();

  // Inject styles
  const style = document.createElement('style');
  style.setAttribute('data-attentionos', 'block-styles');
  style.textContent = _getStyles();
  document.head.appendChild(style);

  // Create block screen
  const screen = document.createElement('div');
  screen.id = BLOCK_SCREEN_ID;
  screen.setAttribute('data-attentionos', 'hard-block');
  screen.innerHTML = _buildHTML(expiresISO, reelsWatched, dailyLimit);

  document.body.appendChild(screen);

  // Start countdown timer
  _startTimer(expiresISO);

  // Start feed guard — prevent Instagram from re-injecting content
  _startFeedGuard();

  console.log('[AttentionOS] Hard block screen injected');
}

/**
 * Removes the block screen. Only called when the 24h block expires.
 */
function removeBlockScreen() {
  const screen = document.getElementById(BLOCK_SCREEN_ID);
  if (screen) screen.remove();

  const style = document.querySelector('style[data-attentionos="block-styles"]');
  if (style) style.remove();

  if (timerInterval) {
    clearInterval(timerInterval);
    timerInterval = null;
  }

  if (feedGuard) {
    feedGuard.disconnect();
    feedGuard = null;
  }

  console.log('[AttentionOS] Hard block screen removed');
}

/**
 * Checks if the block screen is currently displayed.
 * @returns {boolean}
 */
function isBlockScreenActive() {
  return !!document.getElementById(BLOCK_SCREEN_ID);
}

// ─── Feed Removal ────────────────────────────────────────────────────────────

/**
 * Aggressively removes feed content. Uses multiple selectors to catch
 * Instagram's various DOM structures.
 */
function _removeFeedContent() {
  // Primary target: main content container
  const main = document.querySelector('[role="main"]');
  if (main) {
    // Empty it rather than removing the container (Instagram expects it to exist)
    main.innerHTML = '';
    main.style.display = 'none';
  }

  // Also hide any articles (feed posts)
  document.querySelectorAll('article').forEach((el) => {
    el.style.display = 'none';
  });
}

/**
 * MutationObserver that watches for Instagram trying to re-render feed
 * content and immediately removes it. This is critical because Instagram's
 * SPA will try to re-inject content on navigation events.
 */
function _startFeedGuard() {
  if (feedGuard) feedGuard.disconnect();

  feedGuard = new MutationObserver((mutations) => {
    // Don't touch our own block screen
    if (!document.getElementById(BLOCK_SCREEN_ID)) return;

    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType !== Node.ELEMENT_NODE) continue;

        // If Instagram re-injects main content, nuke it
        if (
          node.getAttribute?.('role') === 'main' ||
          node.querySelector?.('[role="main"]')
        ) {
          const mainEl = node.getAttribute?.('role') === 'main'
            ? node
            : node.querySelector('[role="main"]');
          if (mainEl) {
            mainEl.innerHTML = '';
            mainEl.style.display = 'none';
          }
        }

        // Kill any new articles
        if (node.tagName === 'ARTICLE') {
          node.style.display = 'none';
        }
        const articles = node.querySelectorAll?.('article');
        if (articles) {
          articles.forEach((a) => (a.style.display = 'none'));
        }
      }
    }
  });

  feedGuard.observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
  });
}

// ─── HTML Builder ────────────────────────────────────────────────────────────

function _buildHTML(expiresISO, reelsWatched, dailyLimit) {
  const remaining = _formatTimeRemaining(expiresISO);
  const reels = Number.isFinite(reelsWatched) ? reelsWatched : 0;
  const limit = Number.isFinite(dailyLimit) && dailyLimit > 0 ? dailyLimit : null;

  let subtitle;
  if (limit == null) {
    subtitle = `You've reached your daily reel limit.`;
  } else if (reels === 0) {
    subtitle = `Your 24-hour cooldown from reaching the
      <strong>${limit}</strong>-reel daily limit is still in effect.`;
  } else {
    subtitle = `You've watched <strong>${reels}</strong> reel${reels === 1 ? '' : 's'} \u2014
      your limit of <strong>${limit}</strong> has been reached.`;
  }

  return `
    <div class="aos-block-container">
      <div class="aos-block-glow"></div>

      <div class="aos-block-content">
        <div class="aos-block-icon">
          <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            <line x1="9" y1="9" x2="15" y2="15"/>
            <line x1="15" y1="9" x2="9" y2="15"/>
          </svg>
        </div>

        <h1 class="aos-block-title">Daily Limit Reached</h1>

        <p class="aos-block-subtitle">${subtitle}</p>

        <div class="aos-block-timer-section">
          <p class="aos-block-timer-label">Instagram resumes in</p>
          <div class="aos-block-timer" id="aos-block-timer">${remaining}</div>
        </div>

        <div class="aos-block-divider"></div>

        <div class="aos-block-suggestions">
          <p class="aos-block-suggestions-title">Use this time to:</p>
          <div class="aos-block-suggestion-grid">
            <div class="aos-block-suggestion">
              <span class="aos-block-suggestion-emoji">📚</span>
              <span class="aos-block-suggestion-text">Read a book</span>
            </div>
            <div class="aos-block-suggestion">
              <span class="aos-block-suggestion-emoji">🚶</span>
              <span class="aos-block-suggestion-text">Go for a walk</span>
            </div>
            <div class="aos-block-suggestion">
              <span class="aos-block-suggestion-emoji">💤</span>
              <span class="aos-block-suggestion-text">Rest your eyes</span>
            </div>
            <div class="aos-block-suggestion">
              <span class="aos-block-suggestion-emoji">🎯</span>
              <span class="aos-block-suggestion-text">Focus on goals</span>
            </div>
          </div>
        </div>

        <p class="aos-block-footer">
          AttentionOS is protecting your focus. This block cannot be dismissed.
        </p>
      </div>
    </div>
  `;
}

// ─── Timer ───────────────────────────────────────────────────────────────────

function _startTimer(expiresISO) {
  if (timerInterval) clearInterval(timerInterval);

  timerInterval = setInterval(() => {
    const timerEl = document.getElementById('aos-block-timer');
    if (!timerEl) {
      clearInterval(timerInterval);
      timerInterval = null;
      return;
    }

    const diff = new Date(expiresISO).getTime() - Date.now();

    if (diff <= 0) {
      removeBlockScreen();
      // Notify service worker
      try {
        chrome.runtime.sendMessage({ type: 'AOS_HARD_BLOCK_EXPIRED_CLIENT' });
      } catch (_) {}
      return;
    }

    timerEl.textContent = _formatMs(diff);
  }, TIMER_INTERVAL_MS);
}

function _updateTimer(expiresISO) {
  _startTimer(expiresISO);
}

function _formatTimeRemaining(expiresISO) {
  const diff = new Date(expiresISO).getTime() - Date.now();
  if (diff <= 0) return '00:00:00';
  return _formatMs(diff);
}

function _formatMs(ms) {
  const totalSeconds = Math.ceil(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

// ─── Styles ──────────────────────────────────────────────────────────────────

function _getStyles() {
  return `
    #${BLOCK_SCREEN_ID} {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      z-index: 9999999;
      background: #0a0a0a;
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
        Helvetica, Arial, sans-serif;
      color: #ffffff;
      animation: aos-block-fade-in 0.6s ease-out;
      overflow-y: auto;
      overflow-x: hidden;
      -webkit-font-smoothing: antialiased;
      -moz-osx-font-smoothing: grayscale;
    }

    #${BLOCK_SCREEN_ID} *,
    #${BLOCK_SCREEN_ID} *::before,
    #${BLOCK_SCREEN_ID} *::after {
      box-sizing: border-box;
      margin: 0;
      font-family: inherit;
      line-height: 1.4;
    }

    @keyframes aos-block-fade-in {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    .aos-block-container {
      position: relative;
      width: 100%;
      min-height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 40px 20px;
    }

    .aos-block-glow {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      width: 500px;
      height: 500px;
      max-width: 90vw;
      max-height: 90vw;
      border-radius: 50%;
      background: radial-gradient(circle, rgba(239, 68, 68, 0.15) 0%, transparent 70%);
      animation: aos-glow-pulse 4s ease-in-out infinite;
      pointer-events: none;
    }

    @keyframes aos-glow-pulse {
      0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 0.5; }
      50% { transform: translate(-50%, -50%) scale(1.2); opacity: 0.8; }
    }

    .aos-block-content {
      position: relative;
      text-align: center;
      max-width: 480px;
      width: 100%;
      padding: 16px;
      z-index: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    .aos-block-icon {
      color: #ef4444;
      margin-bottom: 28px;
      animation: aos-shield-breathe 3s ease-in-out infinite;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
    }

    .aos-block-icon svg {
      display: block;
    }

    @keyframes aos-shield-breathe {
      0%, 100% { transform: scale(1); }
      50% { transform: scale(1.08); }
    }

    .aos-block-title {
      font-size: 36px;
      font-weight: 800;
      line-height: 1.25;
      margin: 0 0 16px 0;
      padding: 4px 2px;
      letter-spacing: -0.5px;
      color: #ef4444;
      background: linear-gradient(135deg, #ef4444, #dc2626, #b91c1c);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
      display: inline-block;
    }

    .aos-block-subtitle {
      font-size: 16px;
      color: rgba(255, 255, 255, 0.6);
      margin: 0 0 40px 0;
      line-height: 1.6;
      max-width: 420px;
    }

    .aos-block-subtitle strong {
      color: #ffffff;
      font-weight: 600;
    }

    .aos-block-timer-section {
      margin-bottom: 36px;
      width: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    .aos-block-timer-label {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.4);
      text-transform: uppercase;
      letter-spacing: 3px;
      margin: 0 0 14px 0;
      line-height: 1.4;
    }

    .aos-block-timer {
      font-size: 64px;
      font-weight: 800;
      font-variant-numeric: tabular-nums;
      letter-spacing: 4px;
      line-height: 1.15;
      margin: 0;
      padding: 6px 4px;
      color: #ffffff;
      background: linear-gradient(180deg, #ffffff 0%, rgba(255,255,255,0.6) 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
      display: inline-block;
    }

    .aos-block-divider {
      width: 60px;
      height: 2px;
      background: linear-gradient(90deg, transparent, rgba(239, 68, 68, 0.5), transparent);
      margin: 0 auto 32px auto;
      border-radius: 1px;
    }

    .aos-block-suggestions {
      margin-bottom: 36px;
      width: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    .aos-block-suggestions-title {
      font-size: 14px;
      color: rgba(255, 255, 255, 0.45);
      margin: 0 0 16px 0;
      line-height: 1.4;
    }

    .aos-block-suggestion-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
      width: 100%;
      max-width: 360px;
      margin: 0 auto;
    }

    .aos-block-suggestion {
      display: flex;
      align-items: center;
      justify-content: flex-start;
      gap: 10px;
      padding: 14px 16px;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      font-size: 14px;
      line-height: 1.3;
      color: rgba(255, 255, 255, 0.65);
      transition: background 0.2s, border-color 0.2s, color 0.2s;
      text-align: left;
      min-height: 48px;
    }

    .aos-block-suggestion:hover {
      background: rgba(255, 255, 255, 0.08);
      border-color: rgba(255, 255, 255, 0.15);
      color: rgba(255, 255, 255, 0.85);
    }

    .aos-block-suggestion-emoji {
      font-size: 20px;
      line-height: 1;
      flex-shrink: 0;
    }

    .aos-block-suggestion-text {
      flex: 1;
      min-width: 0;
    }

    .aos-block-footer {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.25);
      margin: 0;
      line-height: 1.5;
      max-width: 360px;
    }

    @media (max-width: 480px) {
      .aos-block-title { font-size: 28px; }
      .aos-block-timer { font-size: 48px; letter-spacing: 2px; }
      .aos-block-suggestion-grid { grid-template-columns: 1fr; }
    }
  `;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  showBlockScreen,
  removeBlockScreen,
  isBlockScreenActive,
};
