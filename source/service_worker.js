/**
 * Root Service Worker
 *
 * Combines Antigram's original handlers with AttentionOS background module.
 * MV3 service workers must register all event listeners synchronously at
 * the top level — they cannot be added lazily inside async callbacks.
 */

import {
  onStartup as aosStartup,
  onInstalled as aosInstalled,
  onMessage as aosMessage,
  onAlarm as aosAlarm,
  onTabRemoved as aosTabRemoved,
  onTabUpdated as aosTabUpdated,
} from './attentionos/background/service-worker.js';

// ─── Antigram: Uninstall feedback ────────────────────────────────────────────
const FEEDBACK_FORM_URL = "https://tally.so/r/mK8kd7?agent=chrome";

// ─── Install / Update ────────────────────────────────────────────────────────
chrome.runtime.onInstalled.addListener((details) => {
  // Antigram original
  chrome.runtime.setUninstallURL(FEEDBACK_FORM_URL);

  // AttentionOS initialization
  aosInstalled(details);
});

// ─── Service Worker Startup ──────────────────────────────────────────────────
chrome.runtime.onStartup.addListener(() => {
  aosStartup();
});

// Also run startup logic on script evaluation (covers wake-from-idle)
aosStartup();

// ─── Message Routing ─────────────────────────────────────────────────────────
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Only handle AttentionOS messages (prefixed with AOS_)
  if (message.type && message.type.startsWith('AOS_')) {
    aosMessage(message, sender).then(sendResponse);
    return true; // Async response
  }
  // Non-AOS messages pass through (for future Antigram use)
  return false;
});

// ─── Alarm Routing ───────────────────────────────────────────────────────────
chrome.alarms.onAlarm.addListener((alarm) => {
  // Only handle AttentionOS alarms (prefixed with aos_)
  if (alarm.name.startsWith('aos_')) {
    aosAlarm(alarm);
  }
});

// ─── Tab Lifecycle ───────────────────────────────────────────────────────────
chrome.tabs.onRemoved.addListener((tabId) => {
  aosTabRemoved(tabId);
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  aosTabUpdated(tabId, changeInfo, tab);
});
