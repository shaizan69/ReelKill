/**
 * AttentionOS — Reels Blur (UI)
 *
 * Soft intervention. Replaces the full-page hard-block screen and full-screen
 * cooldown overlay with a non-destructive blur of reel elements:
 *
 *  - Reel items in the main feed (articles with a <video> or /reel/ link) are
 *    blurred and made non-interactive.
 *  - On the /reels/ path, the entire main area is blurred.
 *  - On every page, all <video> elements are paused and src-stripped so
 *    nothing can play.
 *  - A floating badge at the top of the viewport shows the current
 *    intervention type, a countdown, and reels-watched / daily-limit.
 *  - Posts (articles with no <video> and no /reel/ link) remain visible.
 *  - The user can still navigate, browse photos, message, etc.
 *
 * Survives SPA navigations via a MutationObserver that re-scans for new
 * reel elements and re-blurs them as they appear.
 *
 * Idempotent — safe to call applyReelsBlur() multiple times.
 * This module runs in the content script context.
 */

const STYLE_ID = 'aos-reels-blur-styles';
const BADGE_ID = 'aos-reels-blur-badge';
const COVER_CLASS = 'aos-blur-cover';
const BLURRED_ATTR = 'data-aos-blurred';
const PAUSED_ATTR = 'data-aos-paused';

let state = null; // { type: 'block' | 'cooldown', expires, reelsWatched, dailyLimit }
let observer = null;
let badgeTimer = null;
let scanTimer = null;

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Activates the reels-blur intervention. Reels are blurred, videos paused,
 * a floating badge is shown.
 *
 * @param {{type: 'block'|'cooldown', expires: string, reelsWatched: number, dailyLimit: number}} newState
 */
function applyReelsBlur(newState) {
  state = newState;

  _injectStyles();
  _mountBadge();
  _scanAndBlur();
  _startObserver();

}

/**
 * Deactivates the intervention. Removes all blur covers, restores videos,
 * removes the badge, stops the observer.
 */
function removeReelsBlur() {
  if (!state) return;

  state = null;

  _stopObserver();
  _unmountBadge();
  _unblurAll();
  _restoreVideos();

  const style = document.getElementById(STYLE_ID);
  if (style) style.remove();

}

/**
 * @returns {boolean} true if intervention is currently active
 */
function isReelsBlurActive() {
  return state !== null;
}

/**
 * @returns {object|null} current state
 */
function getReelsBlurState() {
  return state;
}

// ─── Styles ──────────────────────────────────────────────────────────────────

function _injectStyles() {
  if (document.getElementById(STYLE_ID)) return;

  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = `
    [${BLURRED_ATTR}] {
      filter: blur(40px) saturate(0.6) !important;
      pointer-events: none !important;
      user-select: none !important;
      -webkit-user-select: none !important;
      overflow: hidden;
      position: relative !important;
    }

    .${COVER_CLASS} {
      position: absolute;
      inset: 0;
      z-index: 999;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-direction: column;
      gap: 6px;
      padding: 12px;
      background: rgba(0, 0, 0, 0.55);
      backdrop-filter: blur(24px);
      -webkit-backdrop-filter: blur(24px);
      color: #ffffff;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
        Helvetica, Arial, sans-serif;
      text-align: center;
      pointer-events: auto;
      cursor: default;
      box-sizing: border-box;
    }

    .${COVER_CLASS} *,
    .${COVER_CLASS} *::before,
    .${COVER_CLASS} *::after {
      box-sizing: border-box;
    }

    .aos-cover-icon {
      font-size: 28px;
      line-height: 1;
      opacity: 0.9;
      margin: 0;
    }

    .aos-cover-text {
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 1px;
      text-transform: uppercase;
      opacity: 0.9;
      margin: 0;
      line-height: 1.2;
    }

    #${BADGE_ID} {
      position: fixed;
      top: 16px;
      left: 50%;
      transform: translateX(-50%);
      z-index: 2147483646;
      max-width: calc(100vw - 32px);
      animation: aos-badge-slide-in 0.4s cubic-bezier(0.16, 1, 0.3, 1);
      pointer-events: none;
    }

    @keyframes aos-badge-slide-in {
      from { opacity: 0; transform: translate(-50%, -20px); }
      to   { opacity: 1; transform: translate(-50%, 0); }
    }

    .aos-badge-inner {
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 14px 20px;
      background: rgba(15, 15, 15, 0.92);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border: 1px solid rgba(255, 255, 255, 0.12);
      border-radius: 16px;
      box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5),
                  0 2px 8px rgba(0, 0, 0, 0.3);
      color: #ffffff;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
        Helvetica, Arial, sans-serif;
      pointer-events: auto;
      min-width: 320px;
    }

    .aos-badge-icon {
      font-size: 28px;
      line-height: 1;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 40px;
      height: 40px;
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.06);
    }

    .aos-badge-body {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      flex: 1;
    }

    .aos-badge-title {
      font-size: 11px;
      font-weight: 800;
      letter-spacing: 1.2px;
      text-transform: uppercase;
      margin: 0;
      line-height: 1.2;
      display: inline-block;
    }

    .aos-badge-subtitle {
      font-size: 11px;
      color: rgba(255, 255, 255, 0.55);
      margin: 0;
      line-height: 1.2;
    }

    .aos-badge-timer {
      font-size: 20px;
      font-weight: 800;
      font-variant-numeric: tabular-nums;
      letter-spacing: 1.5px;
      line-height: 1.15;
      margin: 0;
      display: inline-block;
    }

    .aos-badge-progress {
      font-size: 10px;
      color: rgba(255, 255, 255, 0.45);
      font-variant-numeric: tabular-nums;
      margin: 0;
      line-height: 1.2;
      letter-spacing: 0.3px;
    }

    .aos-badge-close {
      background: none;
      border: none;
      color: rgba(255, 255, 255, 0.4);
      font-size: 18px;
      line-height: 1;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 6px;
      transition: background 0.15s, color 0.15s;
      flex-shrink: 0;
      font-family: inherit;
    }

    .aos-badge-close:hover {
      background: rgba(255, 255, 255, 0.08);
      color: rgba(255, 255, 255, 0.8);
    }

    @media (max-width: 480px) {
      #${BADGE_ID} { top: 8px; }
      .aos-badge-inner {
        min-width: 0;
        width: 100%;
        padding: 10px 14px;
        gap: 10px;
      }
      .aos-badge-icon { font-size: 22px; width: 34px; height: 34px; }
      .aos-badge-timer { font-size: 18px; }
    }
  `;
  document.head.appendChild(s);
}

// ─── Scan & Blur ─────────────────────────────────────────────────────────────

function _scanAndBlur() {
  const path = window.location.pathname;
  if (path.startsWith('/reels') || path.startsWith('/reel/')) {
    _blurReelsPage();
  } else {
    _blurFeedReels();
  }
  _blurStandaloneVideos();
}

function _blurFeedReels() {
  const articles = document.querySelectorAll('article');
  articles.forEach((article) => {
    if (_isReel(article)) {
      _blurElement(article);
    }
  });
}

function _isReel(node) {
  if (node.querySelector('video')) return true;
  if (node.querySelector('a[href*="/reel/"]')) return true;
  if (node.querySelector('a[href*="/reels/"]')) return true;
  return false;
}

function _blurReelsPage() {
  const main =
    document.querySelector('[role="main"]') ||
    document.querySelector('main') ||
    document.querySelector('section[role="main"]');
  if (main) _blurElement(main);
}

function _blurStandaloneVideos() {
  document.querySelectorAll('video').forEach((v) => {
    if (v.closest(`[${BLURRED_ATTR}]`)) return;
    const container =
      v.closest('article') ||
      v.closest('[role="main"]') ||
      v.closest('main') ||
      v.parentElement;
    if (container) _blurElement(container);
  });
}

function _blurElement(el) {
  if (!el || el.hasAttribute(BLURRED_ATTR)) return;

  el.setAttribute(BLURRED_ATTR, 'true');

  el.querySelectorAll('video').forEach((v) => {
    try { v.pause(); } catch (_) {}
    v.setAttribute(PAUSED_ATTR, 'true');
    try { v.removeAttribute('src'); v.load(); } catch (_) {}
  });

  const cover = document.createElement('div');
  cover.className = COVER_CLASS;
  cover.innerHTML =
    '<div class="aos-cover-icon">\u23F8</div>' +
    '<div class="aos-cover-text">Reels paused</div>';
  el.appendChild(cover);
}

function _unblurAll() {
  document.querySelectorAll(`[${BLURRED_ATTR}]`).forEach((el) => {
    el.removeAttribute(BLURRED_ATTR);
    el.querySelectorAll(`.${COVER_CLASS}`).forEach((c) => c.remove());
  });
}

function _restoreVideos() {
  document.querySelectorAll(`video[${PAUSED_ATTR}]`).forEach((v) => {
    v.removeAttribute(PAUSED_ATTR);
  });
}

// ─── MutationObserver ────────────────────────────────────────────────────────

function _startObserver() {
  if (observer) return;

  observer = new MutationObserver((muts) => {
    if (!state) return;

    let needsScan = false;
    for (const m of muts) {
      for (const node of m.addedNodes) {
        if (node.nodeType !== 1) continue;
        if (
          node.matches?.('article, video, [role="main"], main') ||
          node.querySelector?.('article, video, [role="main"], main')
        ) {
          needsScan = true;
          break;
        }
      }
      if (needsScan) break;
    }

    if (needsScan) {
      clearTimeout(scanTimer);
      scanTimer = setTimeout(() => {
        if (state) _scanAndBlur();
      }, 150);
    }
  });

  observer.observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
  });
}

function _stopObserver() {
  if (observer) {
    observer.disconnect();
    observer = null;
  }
  if (scanTimer) {
    clearTimeout(scanTimer);
    scanTimer = null;
  }
}

// ─── Badge ───────────────────────────────────────────────────────────────────

function _mountBadge() {
  if (document.getElementById(BADGE_ID)) {
    _updateBadge();
    return;
  }

  const badge = document.createElement('div');
  badge.id = BADGE_ID;
  badge.innerHTML = _badgeHTML();
  document.body.appendChild(badge);

  const closeBtn = badge.querySelector('.aos-badge-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      badge.style.display = 'none';
    });
  }

  _startBadgeTimer();
}

function _unmountBadge() {
  if (badgeTimer) {
    clearInterval(badgeTimer);
    badgeTimer = null;
  }
  const b = document.getElementById(BADGE_ID);
  if (b) b.remove();
}

function _badgeHTML() {
  if (!state) return '';

  const remaining = _formatRemaining(state.expires);
  const watched = Number.isFinite(state.reelsWatched) ? state.reelsWatched : 0;
  const limit =
    Number.isFinite(state.dailyLimit) && state.dailyLimit > 0
      ? state.dailyLimit
      : 0;
  const isBlock = state.type === 'block';

  const title = isBlock ? 'Daily Limit Reached' : 'Cooldown Active';
  const subtitle = isBlock
    ? 'Reels paused. Resumes in'
    : 'Take a breather. Resumes in';
  const iconChar = isBlock ? '\u{1F6D1}' : '\u23F8';
  const palette = isBlock
    ? 'linear-gradient(135deg, #ef4444 0%, #dc2626 50%, #b91c1c 100%)'
    : 'linear-gradient(135deg, #f59e0b 0%, #d97706 50%, #b45309 100%)';

  const limitLabel = limit > 0 ? ` \u00B7 ${watched} / ${limit} today` : '';

  return `
    <div class="aos-badge-inner">
      <div class="aos-badge-icon">${iconChar}</div>
      <div class="aos-badge-body">
        <div class="aos-badge-title" style="background:${palette};-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;display:inline-block;">${title}</div>
        <div class="aos-badge-subtitle">${subtitle}</div>
        <div class="aos-badge-timer" id="aos-badge-timer" style="background:${palette};-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;display:inline-block;">${remaining}</div>
        <div class="aos-badge-progress">${watched} reels today${limitLabel}</div>
      </div>
      <button class="aos-badge-close" type="button" aria-label="Dismiss badge">\u00D7</button>
    </div>
  `;
}

function _updateBadge() {
  const b = document.getElementById(BADGE_ID);
  if (!b || !state) return;

  b.innerHTML = _badgeHTML();

  const closeBtn = b.querySelector('.aos-badge-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      b.style.display = 'none';
    });
  }
}

function _startBadgeTimer() {
  if (badgeTimer) clearInterval(badgeTimer);

  badgeTimer = setInterval(() => {
    if (!state) {
      _unmountBadge();
      return;
    }

    const diff = new Date(state.expires).getTime() - Date.now();
    if (diff <= 0) {
      removeReelsBlur();
      try {
        chrome.runtime.sendMessage({ type: 'AOS_REELS_BLUR_EXPIRED' });
      } catch (_) {}
      return;
    }

    const timerEl = document.getElementById('aos-badge-timer');
    if (timerEl) {
      timerEl.textContent = _formatRemaining(state.expires);
    }
  }, 1000);
}

function _formatRemaining(iso) {
  const diff = new Date(iso).getTime() - Date.now();
  if (!Number.isFinite(diff) || diff <= 0) return '00:00:00';
  const total = Math.floor(diff / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) {
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  applyReelsBlur,
  removeReelsBlur,
  isReelsBlurActive,
  getReelsBlurState,
};
