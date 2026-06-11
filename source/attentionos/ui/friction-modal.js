/**
 * AttentionOS — Friction Modal (UI)
 *
 * Soft warning modal shown when the user approaches their daily limit
 * (default: 80% threshold). The modal is informational, not blocking —
 * the user can dismiss it and continue.
 *
 * Every interaction is logged:
 *  - friction_shown: the modal appeared
 *  - friction_dismissed: the user clicked "continue"
 *
 * This module runs in the content script context.
 */

// ─── Constants ───────────────────────────────────────────────────────────────

const MODAL_ID = 'aos-friction-modal';

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Shows the friction modal.
 * Idempotent — will not inject if already present.
 *
 * @param {number} reelsWatched — current reel count
 * @param {number} dailyLimit — user's daily limit
 * @param {Function} [onDismiss] — callback when user dismisses the modal
 */
function showFrictionModal(reelsWatched, dailyLimit, onDismiss) {
  // Prevent duplicate injection
  if (document.getElementById(MODAL_ID)) return;

  // Inject styles
  const style = document.createElement('style');
  style.setAttribute('data-attentionos', 'friction-styles');
  style.textContent = _getStyles();
  document.head.appendChild(style);

  // Create modal overlay
  const overlay = document.createElement('div');
  overlay.id = MODAL_ID;
  overlay.setAttribute('data-attentionos', 'friction');
  overlay.innerHTML = _buildHTML(reelsWatched, dailyLimit);

  document.body.appendChild(overlay);

  // Wire up dismiss button
  const dismissBtn = document.getElementById('aos-friction-dismiss');
  if (dismissBtn) {
    dismissBtn.addEventListener('click', () => {
      _removeModal();

      // Notify background
      try {
        chrome.runtime.sendMessage({ type: 'AOS_FRICTION_DISMISSED' });
      } catch (_) {}

      if (onDismiss) onDismiss();
    });
  }

}

/**
 * Removes the friction modal if present.
 */
function removeFrictionModal() {
  _removeModal();
}

/**
 * Checks if the friction modal is currently displayed.
 * @returns {boolean}
 */
function isFrictionModalActive() {
  return !!document.getElementById(MODAL_ID);
}

// ─── Internal ────────────────────────────────────────────────────────────────

function _removeModal() {
  const overlay = document.getElementById(MODAL_ID);
  if (overlay) overlay.remove();

  const style = document.querySelector('style[data-attentionos="friction-styles"]');
  if (style) style.remove();
}

// ─── HTML Builder ────────────────────────────────────────────────────────────

function _buildHTML(reelsWatched, dailyLimit) {
  const remaining = dailyLimit - reelsWatched;
  const progressPct = Math.min((reelsWatched / dailyLimit) * 100, 100);

  return `
    <div class="aos-friction-backdrop">
      <div class="aos-friction-card">
        <div class="aos-friction-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/>
            <line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
        </div>

        <h2 class="aos-friction-title">Heads Up</h2>

        <p class="aos-friction-message">
          You've watched <strong>${reelsWatched}</strong> of your
          <strong>${dailyLimit}</strong> reels for today.
          ${remaining > 0
            ? `Only <strong>${remaining}</strong> left before your daily limit kicks in.`
            : `You've reached your limit.`}
        </p>

        <div class="aos-friction-progress">
          <div class="aos-friction-progress-bar">
            <div class="aos-friction-progress-fill" style="width: ${progressPct}%"></div>
          </div>
          <span class="aos-friction-progress-label">${reelsWatched} / ${dailyLimit}</span>
        </div>

        <button id="aos-friction-dismiss" class="aos-friction-btn">
          I understand, continue
        </button>

        <p class="aos-friction-footnote">
          This warning won't appear again this session.
        </p>
      </div>
    </div>
  `;
}

// ─── Styles ──────────────────────────────────────────────────────────────────

function _getStyles() {
  return `
    #${MODAL_ID} {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      z-index: 999998;
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
        Helvetica, Arial, sans-serif;
      animation: aos-friction-fade-in 0.3s ease-out;
      -webkit-font-smoothing: antialiased;
      -moz-osx-font-smoothing: grayscale;
    }

    #${MODAL_ID} *,
    #${MODAL_ID} *::before,
    #${MODAL_ID} *::after {
      box-sizing: border-box;
      margin: 0;
      font-family: inherit;
      line-height: 1.4;
    }

    @keyframes aos-friction-fade-in {
      from { opacity: 0; }
      to { opacity: 1; }
    }

    .aos-friction-backdrop {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      background: rgba(0, 0, 0, 0.75);
      backdrop-filter: blur(8px);
      -webkit-backdrop-filter: blur(8px);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
    }

    .aos-friction-card {
      position: relative;
      background: #1a1a2e;
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 20px;
      padding: 36px 32px 28px;
      max-width: 380px;
      width: 100%;
      text-align: center;
      box-shadow: 0 24px 48px rgba(0, 0, 0, 0.4);
      animation: aos-friction-slide-up 0.35s ease-out;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    @keyframes aos-friction-slide-up {
      from { transform: translateY(20px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    .aos-friction-icon {
      color: #f59e0b;
      margin: 0 0 20px 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
    }

    .aos-friction-icon svg {
      display: block;
    }

    .aos-friction-title {
      font-size: 24px;
      font-weight: 700;
      color: #ffffff;
      margin: 0 0 14px 0;
      line-height: 1.25;
      letter-spacing: -0.3px;
    }

    .aos-friction-message {
      font-size: 15px;
      color: rgba(255, 255, 255, 0.65);
      line-height: 1.55;
      margin: 0 0 24px 0;
    }

    .aos-friction-message strong {
      color: #ffffff;
      font-weight: 600;
    }

    .aos-friction-progress {
      width: 100%;
      margin: 0 0 24px 0;
    }

    .aos-friction-progress-bar {
      height: 6px;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 3px;
      overflow: hidden;
      margin: 0 0 8px 0;
    }

    .aos-friction-progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #f59e0b, #ef4444);
      border-radius: 3px;
      transition: width 0.5s ease;
    }

    .aos-friction-progress-label {
      display: block;
      font-size: 13px;
      color: rgba(255, 255, 255, 0.4);
      font-variant-numeric: tabular-nums;
      line-height: 1.4;
    }

    .aos-friction-btn {
      display: inline-block;
      padding: 12px 28px;
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 12px;
      color: rgba(255, 255, 255, 0.85);
      font-size: 14px;
      font-weight: 500;
      line-height: 1.3;
      cursor: pointer;
      transition: all 0.2s ease;
      margin: 0 0 14px 0;
      font-family: inherit;
    }

    .aos-friction-btn:hover {
      background: rgba(255, 255, 255, 0.12);
      border-color: rgba(255, 255, 255, 0.25);
      color: #ffffff;
    }

    .aos-friction-footnote {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.3);
      margin: 0;
      line-height: 1.4;
    }
  `;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  showFrictionModal,
  removeFrictionModal,
  isFrictionModalActive,
};
