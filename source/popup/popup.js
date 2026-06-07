import { labelsArray } from "../modules/lib.js";
import { getSettings, updateSettings, getState } from "../attentionos/core/storage.js";

// ─── ReelKill stats (reels watched today + daily limit) ────────────────────

async function loadAOSStats() {
  try {
    const state = await getState();
    const settings = await getSettings();

    const reelsTodayEl = document.getElementById("aos-reels-today");
    if (reelsTodayEl) {
      const reels = state.reels_watched_today || 0;
      reelsTodayEl.textContent = reels;
      reelsTodayEl.style.color =
        reels >= settings.daily_limit ? "var(--red)" : "var(--white)";
    }

    const limitInput = document.getElementById("aos-daily-limit");
    if (limitInput) {
      limitInput.value = settings.daily_limit;
      limitInput.disabled = false;
      limitInput.placeholder = "";
    }
  } catch (err) {
    console.error("[ReelKill popup] loadAOSStats failed:", err);
  }
}

document.addEventListener("DOMContentLoaded", () => {
  loadAOSStats();
  restoreOptions();
});

// Dashboard links (header nav + the big button under Reel Limit)
function _openDashboard(e) {
  if (e) e.preventDefault();
  chrome.tabs.create({
    url: chrome.runtime.getURL("attentionos/ui/dashboard.html"),
  });
}

const dashboardLink = document.getElementById("aos-dashboard-link");
if (dashboardLink) {
  dashboardLink.addEventListener("click", _openDashboard);
}

const openDashboardBtn = document.getElementById("aos-open-dashboard");
if (openDashboardBtn) {
  openDashboardBtn.addEventListener("click", _openDashboard);
}

// ─── Antigram-style block-list options (chrome.storage.sync) ────────────────

const saveOptions = async () => {
  const options = {};

  for (const label of labelsArray) {
    const element = document.getElementById(label);
    if (element !== null) {
      options[label] = element.checked;
    }
  }

  // Save daily reel limit (chrome.storage.local)
  const limitInput = document.getElementById("aos-daily-limit");
  if (limitInput && !limitInput.disabled) {
    const newLimit = parseInt(limitInput.value, 10);
    if (Number.isFinite(newLimit) && newLimit > 0 && newLimit <= 50) {
      await updateSettings({ daily_limit: newLimit });
    }
  }

  chrome.storage.sync.set(options, () => {
    const status = document.getElementById("status");
    status.textContent = "Saved! ✌️ Refresh to apply";
    const intervalId = setInterval(() => {
      status.textContent = "";
      clearInterval(intervalId);
    }, 2000);
    console.log(chrome.storage.sync.get(options));

    // Re-render the reels-today indicator with the fresh limit
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

document.getElementById("save").addEventListener("click", saveOptions);

// ─── Tab navigation ─────────────────────────────────────────────────────────

const tabs = document.querySelectorAll("[data-tab-target]");
const tabContents = document.querySelectorAll("[data-tab-content]");
tabs.forEach((tab) => {
  tab.addEventListener("click", () => {
    const target = document.querySelector(tab.dataset.tabTarget);
    tabContents.forEach((tabContent) => tabContent.classList.remove("active"));
    tabs.forEach((tab) => tab.classList.remove("active"));

    tab.classList.add("active");
    target.classList.add("active");
  });
});
