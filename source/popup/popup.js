import { labelsArray } from "../modules/lib.js";
import { getSettings, updateSettings, getState } from "../attentionos/core/storage.js";

// ─── ReelKill stats (reels watched today + daily limit) ────────────────────

async function loadAOSStats() {
  try {
    const state = await getState();
    const settings = await getSettings();

    const reels = state.reels_watched_today || 0;
    const limit = settings.daily_limit;

    const reelsTodayEl = document.getElementById("aos-reels-today");
    const reelsLimitEl = document.getElementById("aos-reels-limit");
    const reelsProgressEl = document.getElementById("aos-reels-progress");
    const limitInput = document.getElementById("aos-daily-limit");
    const stepperBtns = document.querySelectorAll(".rk-stepper-btn");

    if (reelsTodayEl) {
      reelsTodayEl.textContent = reels;
      reelsTodayEl.classList.toggle("rk-stat-warn", reels >= limit);
    }
    if (reelsLimitEl) {
      reelsLimitEl.textContent = limit;
    }
    if (reelsProgressEl) {
      const pct = limit > 0 ? Math.min(100, (reels / limit) * 100) : 0;
      reelsProgressEl.style.width = `${pct}%`;
    }
    if (limitInput) {
      limitInput.value = limit;
      limitInput.disabled = false;
      stepperBtns.forEach((btn) => {
        btn.disabled = false;
      });
    }
  } catch (err) {
    console.error("[ReelKill popup] loadAOSStats failed:", err);
  }
}

// ─── Stepper (daily limit +/−) ────────────────────────────────────────────

function initStepper() {
  const stepperBtns = document.querySelectorAll(".rk-stepper-btn");
  const input = document.getElementById("aos-daily-limit");

  stepperBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!input || input.disabled) return;
      const step = parseInt(btn.dataset.step, 10);
      const current = parseInt(input.value, 10) || 0;
      const next = Math.max(1, Math.min(50, current + step));
      input.value = next;
    });
  });
}

// ─── Dashboard link (icon button in header) ────────────────────────────────

function initDashboardLink() {
  const btn = document.getElementById("aos-open-dashboard");
  if (!btn) return;
  btn.addEventListener("click", (e) => {
    e.preventDefault();
    chrome.tabs.create({
      url: chrome.runtime.getURL("attentionos/ui/dashboard.html"),
    });
  });
}

// ─── Block-list options (chrome.storage.sync) ──────────────────────────────

const saveOptions = async () => {
  const options = {};

  for (const label of labelsArray) {
    const element = document.getElementById(label);
    if (element !== null) {
      options[label] = element.checked;
    }
  }

  const limitInput = document.getElementById("aos-daily-limit");
  if (limitInput && !limitInput.disabled) {
    const newLimit = parseInt(limitInput.value, 10);
    if (Number.isFinite(newLimit) && newLimit > 0 && newLimit <= 50) {
      await updateSettings({ daily_limit: newLimit });
    }
  }

  chrome.storage.sync.set(options, () => {
    showStatus("Saved");
    loadAOSStats();
  });
};

const restoreOptions = () => {
  chrome.storage.sync.get(labelsArray, (items) => {
    for (const key of Object.keys(items)) {
      const el = document.getElementById(key);
      if (el) el.checked = items[key];
    }
  });
};

function showStatus(text) {
  const status = document.getElementById("status");
  if (!status) return;
  status.textContent = text;
  status.classList.add("rk-status-show");
  clearTimeout(showStatus._t);
  showStatus._t = setTimeout(() => {
    status.textContent = "";
    status.classList.remove("rk-status-show");
  }, 2000);
}

// ─── Boot ─────────────────────────────────────────────────────────────────

document.addEventListener("DOMContentLoaded", () => {
  loadAOSStats();
  restoreOptions();
  initStepper();
  initDashboardLink();
});

document.getElementById("save").addEventListener("click", saveOptions);

// ─── Tab navigation ────────────────────────────────────────────────────────

const tabs = document.querySelectorAll("[data-tab-target]");
const tabContents = document.querySelectorAll("[data-tab-content]");

tabs.forEach((tab) => {
  tab.addEventListener("click", () => {
    const target = document.querySelector(tab.dataset.tabTarget);
    if (!target) return;
    tabContents.forEach((tc) => tc.classList.remove("active"));
    tabs.forEach((t) => {
      t.classList.remove("active");
      t.setAttribute("aria-selected", "false");
    });
    tab.classList.add("active");
    tab.setAttribute("aria-selected", "true");
    target.classList.add("active");
  });
});
