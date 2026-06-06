import { labelsArray } from "../modules/lib.js";

// Saving and loading options from storage
const saveOptions = () => {
  const options = {};

  for (const label of labelsArray) {
    const element = document.getElementById(label);
    if (element !== null) {
      options[label] = element.checked;
    }
  }

  const onSet = () => {
    const status = document.getElementById("status");
    status.textContent = "Saved! ✌️ Refresh to apply";
    const intervalId = setInterval(() => {
      status.textContent = "";
      clearInterval(intervalId);
    }, 2000);
    console.log(chrome.storage.sync.get(options));
  };

  chrome.storage.sync.set(options, onSet);
};

const restoreOptions = () => {
  chrome.storage.sync.get(labelsArray, (items) => {
    for (const key of Object.keys(items)) {
      document.getElementById(key).checked = items[key];
    }
  });
};

document.addEventListener("DOMContentLoaded", restoreOptions);
document.getElementById("save").addEventListener("click", saveOptions);

// Managing tab navigation
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

// AttentionOS Dashboard link (Header nav)
const dashboardLink = document.getElementById("aos-dashboard-link");
if (dashboardLink) {
  dashboardLink.addEventListener("click", (e) => {
    e.preventDefault();
    chrome.tabs.create({
      url: chrome.runtime.getURL("attentionos/ui/dashboard.html"),
    });
  });
}

// AttentionOS Dashboard button (Main banner icon)
const dashboardBtn = document.getElementById("aos-dashboard-btn");
if (dashboardBtn) {
  dashboardBtn.addEventListener("click", (e) => {
    e.preventDefault();
    chrome.tabs.create({
      url: chrome.runtime.getURL("attentionos/ui/dashboard.html"),
    });
  });
}

// --- AttentionOS Limit Configuration ---
import { getSettings, updateSettings, getState } from "../attentionos/core/storage.js";

async function loadAOSStats() {
  try {
    const state = await getState();
    const settings = await getSettings();

    const reelsTodayEl = document.getElementById("aos-reels-today");
    if (reelsTodayEl) {
      const reels = state.reels_watched_today || 0;
      reelsTodayEl.textContent = reels;
      reelsTodayEl.style.color =
        reels >= settings.daily_limit ? "#ef4444" : "#f59e0b";
    }

    const limitInput = document.getElementById("aos-daily-limit");
    if (limitInput) {
      limitInput.value = settings.daily_limit;
      limitInput.disabled = false;
      limitInput.placeholder = "";
    }
  } catch (err) {
    console.error("[AttentionOS popup] loadAOSStats failed:", err);
  }
}

const aosSaveBtn = document.getElementById("aos-save-btn");
if (aosSaveBtn) {
  aosSaveBtn.addEventListener("click", async (e) => {
    e.preventDefault();
    const limitInput = document.getElementById("aos-daily-limit");
    if (!limitInput || limitInput.disabled) return;

    const newLimit = parseInt(limitInput.value, 10);
    if (Number.isFinite(newLimit) && newLimit > 0) {
      await updateSettings({ daily_limit: newLimit });
      const statusEl = document.getElementById("aos-status");
      if (statusEl) {
        statusEl.textContent = "Limit Saved ✓";
        setTimeout(() => { statusEl.textContent = ""; }, 2000);
      }
      loadAOSStats();
    }
  });
}

loadAOSStats();
