/**
 * AttentionOS — Dashboard Controller
 *
 * Renders the analytics dashboard by reading from chrome.storage.local
 * and computing derived metrics via the analytics modules.
 *
 * Empty-state rules:
 *  - When the user has NO data for a metric, show "--" (never fake zeros).
 *  - When the user has 0 measured (real zero), show the actual "0".
 *  - Streak badge is hidden when there is no positive streak.
 *  - Heatmap shows an empty-state message when all buckets are 0.
 *  - Weekly chart shows an empty-state when no day has a score.
 *
 * Settings inputs start disabled with "--" placeholders, then become
 * editable + populated once the stored settings have been read.
 */

import { getSettings, updateSettings, getState, todayUTC, getAllEvents } from '../core/storage.js';
import { computeDailySummary, generateWeeklyReport, computeStreak } from '../analytics/aggregator.js';
import { generateDailyHeatmap, generateRangeHeatmap, formatHeatmapForDisplay } from '../analytics/heatmap.js';

// ─── Constants ───────────────────────────────────────────────────────────────

const REFRESH_MS = 30000;
const EMPTY = '--';

// ─── State ───────────────────────────────────────────────────────────────────

let refreshInterval = null;
let hasAnyHistory = false; // True once we've confirmed any aos_events exist
let settingsLoaded = false;

// ─── Init ────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', async () => {
  _injectSVGDefs();
  _setupTabNavigation();
  _setupSettings();
  _setupExport();

  await _refreshAll();

  refreshInterval = setInterval(_refreshAll, REFRESH_MS);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      clearInterval(refreshInterval);
      refreshInterval = null;
    } else if (!refreshInterval) {
      _refreshAll();
      refreshInterval = setInterval(_refreshAll, REFRESH_MS);
    }
  });
});

// ─── Data Refresh ────────────────────────────────────────────────────────────

async function _refreshAll() {
  try {
    // Detect whether we have any events at all (used for empty states).
    const allEvents = await getAllEvents();
    hasAnyHistory = allEvents.length > 0;

    await Promise.all([
      _renderTodayPanel(),
      _renderWeekPanel(),
      _loadSettings(),
    ]);
  } catch (err) {
    console.error('[AttentionOS Dashboard] Refresh error:', err);
  }
}

// ─── Today Panel ─────────────────────────────────────────────────────────────

async function _renderTodayPanel() {
  const today = todayUTC();
  const state = await getState();
  const settings = await getSettings();
  const summary = await computeDailySummary(today);
  const streak = await computeStreak(today);

  const hasTodayActivity =
    summary.reels_watched > 0 ||
    summary.sessions > 0 ||
    summary.cooldowns_triggered > 0;

  // ── Score Ring ──
  if (hasTodayActivity || hasAnyHistory) {
    _setScoreRing(summary.attention_score || 0, true);
  } else {
    _setScoreRing(0, false);
  }

  // ── Streak ──
  const streakBadge = document.getElementById('streak-badge');
  if (streakBadge) {
    if (streak.current > 0) {
      streakBadge.hidden = false;
      _setText('streak-count', streak.current);
    } else {
      streakBadge.hidden = true;
    }
  }

  // ── Stats ──
  _setText('stat-reels', hasTodayActivity ? summary.reels_watched : EMPTY);
  _setText('stat-time', hasTodayActivity ? _formatDuration(summary.time_spent_seconds) : EMPTY);
  _setText('stat-cooldowns', hasTodayActivity ? summary.cooldowns_triggered : EMPTY);
  _setText('stat-sessions', hasTodayActivity ? summary.sessions : EMPTY);

  _setText('stat-reels-limit', `of ${settings.daily_limit} limit`);

  // ── Budget Bar ──
  const ratio = settings.daily_limit > 0 ? summary.reels_watched / settings.daily_limit : 0;
  const fillEl = document.getElementById('budget-fill');
  if (fillEl) {
    fillEl.style.width = `${Math.min(ratio * 100, 100)}%`;
    fillEl.className = 'budget-fill';
    if (ratio >= 1) fillEl.classList.add('critical');
    else if (ratio >= settings.friction_threshold_pct) fillEl.classList.add('warning');
  }

  const frictionMark = document.getElementById('budget-friction-mark');
  if (frictionMark) {
    frictionMark.style.left = `${settings.friction_threshold_pct * 100}%`;
  }

  _setText('budget-limit-label', settings.daily_limit);

  const statusEl = document.getElementById('budget-status');
  if (statusEl) {
    if (state.hard_block_active && state.hard_block_expires) {
      statusEl.textContent = `\u{1F534} Hard block active \u2014 expires ${_formatRelativeTime(state.hard_block_expires)}`;
    } else if (!hasTodayActivity) {
      statusEl.textContent = `No reels watched yet today.`;
    } else if (ratio >= 0.9) {
      const remaining = Math.max(0, settings.daily_limit - summary.reels_watched);
      statusEl.textContent = `\u26A0\uFE0F ${remaining} reel${remaining !== 1 ? 's' : ''} remaining`;
    } else {
      statusEl.textContent = `${summary.reels_watched} of ${settings.daily_limit} reels used`;
    }
  }

  // ── Heatmap ──
  const heatmapData = await generateDailyHeatmap(today);
  const totalToday = heatmapData.reduce((s, n) => s + n, 0);
  const emptyEl = document.getElementById('heatmap-empty');
  const gridEl = document.getElementById('heatmap-grid');

  if (totalToday === 0) {
    if (gridEl) gridEl.innerHTML = '';
    if (emptyEl) emptyEl.hidden = false;
  } else {
    if (emptyEl) emptyEl.hidden = true;
    const formatted = formatHeatmapForDisplay(heatmapData);
    _renderHeatmap('heatmap-grid', formatted);
  }

  // ── Active Status ──
  _renderActiveStatus(state, settings);
}

// ─── Week Panel ──────────────────────────────────────────────────────────────

async function _renderWeekPanel() {
  const today = todayUTC();
  const report = await generateWeeklyReport(today);

  const hasWeekActivity =
    report.totals.reels_watched > 0 ||
    report.totals.sessions > 0 ||
    report.totals.cooldowns_triggered > 0;

  // ── Weekly Chart ──
  const chartEmpty = document.getElementById('weekly-chart-empty');
  const chartEl = document.getElementById('weekly-chart');

  if (!hasWeekActivity) {
    if (chartEl) chartEl.innerHTML = '';
    if (chartEmpty) chartEmpty.hidden = false;
  } else {
    if (chartEmpty) chartEmpty.hidden = true;
    _renderWeeklyChart(report.days);
  }

  // ── Stats ──
  _setText('week-reels', hasWeekActivity ? report.totals.reels_watched : EMPTY);
  _setText('week-time', hasWeekActivity ? _formatDuration(report.totals.time_spent_seconds) : EMPTY);
  _setText('week-cooldowns', hasWeekActivity ? report.totals.cooldowns_triggered : EMPTY);
  _setText('week-avg-score', report.avg_attention_score != null ? report.avg_attention_score : EMPTY);

  // ── Highlights ──
  _setText(
    'week-best-day',
    report.best_day ? `${_formatDate(report.best_day.date)} (${report.best_day.score})` : 'No data'
  );
  _setText(
    'week-worst-day',
    report.worst_day ? `${_formatDate(report.worst_day.date)} (${report.worst_day.score})` : 'No data'
  );
  _setText(
    'week-danger-hour',
    report.danger_hour ? `${_formatHour(report.danger_hour.hour)} (${report.danger_hour.count} reels)` : 'No data'
  );
  _setText('week-block-days', hasWeekActivity ? report.totals.hard_block_days : EMPTY);

  // ── Weekly Heatmap ──
  const heatmapData = await generateRangeHeatmap(today, 7);
  const total = heatmapData.reduce((s, n) => s + n, 0);
  const emptyEl = document.getElementById('weekly-heatmap-empty');
  const gridEl = document.getElementById('weekly-heatmap-grid');

  if (total === 0) {
    if (gridEl) gridEl.innerHTML = '';
    if (emptyEl) emptyEl.hidden = false;
  } else {
    if (emptyEl) emptyEl.hidden = true;
    const formatted = formatHeatmapForDisplay(heatmapData);
    _renderHeatmap('weekly-heatmap-grid', formatted);
  }
}

// ─── Renderers ───────────────────────────────────────────────────────────────

function _setScoreRing(score, hasData) {
  const el = document.getElementById('score-ring-progress');
  const valueEl = document.getElementById('score-value');
  if (!el || !valueEl) return;

  const circumference = 2 * Math.PI * 52;

  if (!hasData) {
    el.style.strokeDashoffset = circumference;
    el.style.stroke = 'rgba(255, 255, 255, 0.12)';
    valueEl.textContent = EMPTY;
    valueEl.style.color = '';
    return;
  }

  const safeScore = Math.max(0, Math.min(100, score));
  const offset = circumference * (1 - safeScore / 100);
  el.style.strokeDashoffset = offset;

  const color = safeScore >= 70 ? '#10b981' : safeScore >= 40 ? '#f59e0b' : '#ef4444';
  el.style.stroke = color;

  valueEl.textContent = safeScore;
  valueEl.style.color = color;
}

function _renderHeatmap(containerId, data) {
  const container = document.getElementById(containerId);
  if (!container) return;

  container.innerHTML = '';

  for (const item of data) {
    const cell = document.createElement('div');
    cell.className = 'heatmap-cell';

    if (item.count === 0) {
      cell.style.opacity = '0.08';
    } else {
      cell.style.opacity = Math.max(item.intensity * 0.85 + 0.15, 0.18);
    }

    const tooltip = document.createElement('div');
    tooltip.className = 'heatmap-tooltip';
    tooltip.textContent = `${item.label}: ${item.count} reel${item.count === 1 ? '' : 's'}`;
    cell.appendChild(tooltip);

    container.appendChild(cell);
  }
}

function _renderWeeklyChart(days) {
  const container = document.getElementById('weekly-chart');
  if (!container) return;

  container.innerHTML = '';

  for (const day of days) {
    const group = document.createElement('div');
    group.className = 'chart-bar-group';

    const hasScore = day.attention_score != null && (day.reels_watched > 0 || day.sessions > 0);
    const score = hasScore ? day.attention_score : 0;
    const heightPct = (score / 100) * 100;

    const scoreLabel = document.createElement('div');
    scoreLabel.className = 'chart-bar-score';
    scoreLabel.textContent = hasScore ? score : EMPTY;

    const barWrap = document.createElement('div');
    barWrap.className = 'chart-bar-wrap';

    const bar = document.createElement('div');
    bar.className = 'chart-bar';
    bar.style.height = `${Math.max(heightPct, 3)}%`;

    if (!hasScore) {
      bar.classList.add('no-data');
      bar.style.height = '4px';
    } else if (score >= 70) {
      bar.classList.add('high');
    } else if (score >= 40) {
      bar.classList.add('medium');
    } else {
      bar.classList.add('low');
    }

    barWrap.appendChild(bar);

    const label = document.createElement('div');
    label.className = 'chart-bar-label';
    label.textContent = _formatDayShort(day.date);

    group.appendChild(scoreLabel);
    group.appendChild(barWrap);
    group.appendChild(label);
    container.appendChild(group);
  }
}

function _renderActiveStatus(state, settings) {
  const card = document.getElementById('active-status-card');
  const alert = document.getElementById('status-alert');
  if (!card || !alert) return;

  if (state.hard_block_active && state.hard_block_expires) {
    card.hidden = false;
    alert.className = 'status-alert block-active';
    alert.innerHTML = `
      <strong>\u{1F534} Hard Block Active</strong><br>
      Your daily limit of <strong>${settings.daily_limit}</strong> reels has been reached.
      Instagram will be available again ${_formatRelativeTime(state.hard_block_expires)}.
    `;
  } else if (state.cooldown_active && state.cooldown_expires) {
    card.hidden = false;
    alert.className = 'status-alert cooldown-active';
    alert.innerHTML = `
      <strong>\u23F8\uFE0F Cooldown Active</strong><br>
      Binge detected. Cooldown ends ${_formatRelativeTime(state.cooldown_expires)}.
    `;
  } else {
    card.hidden = true;
  }
}

// ─── Settings ────────────────────────────────────────────────────────────────

async function _loadSettings() {
  const settings = await getSettings();

  _setInput('setting-daily-limit', settings.daily_limit);
  _setText('setting-daily-limit-value', settings.daily_limit);
  _setInput('setting-binge-threshold', settings.binge_threshold_reels);
  _setInput('setting-binge-window', settings.binge_window_minutes);
  _setInput('setting-cooldown-base', settings.cooldown_base_seconds);
  _setInput('setting-friction-pct', settings.friction_threshold_pct);
  _setText(
    'setting-friction-pct-value',
    `${Math.round(settings.friction_threshold_pct * 100)}%`
  );

  if (!settingsLoaded) {
    settingsLoaded = true;
    _enableSettings();
  }
}

function _enableSettings() {
  const ids = [
    'setting-daily-limit',
    'setting-binge-threshold',
    'setting-binge-window',
    'setting-cooldown-base',
    'setting-friction-pct',
    'settings-save',
  ];
  for (const id of ids) {
    const el = document.getElementById(id);
    if (el) el.disabled = false;
  }
}

function _setupSettings() {
  const dailyLimit = document.getElementById('setting-daily-limit');
  if (dailyLimit) {
    dailyLimit.addEventListener('input', () => {
      _setText('setting-daily-limit-value', dailyLimit.value);
    });
  }

  const frictionPct = document.getElementById('setting-friction-pct');
  if (frictionPct) {
    frictionPct.addEventListener('input', () => {
      _setText(
        'setting-friction-pct-value',
        `${Math.round(frictionPct.value * 100)}%`
      );
    });
  }

  const saveBtn = document.getElementById('settings-save');
  if (saveBtn) {
    saveBtn.addEventListener('click', async () => {
      if (!settingsLoaded) return;

      const newSettings = {
        daily_limit: parseInt(document.getElementById('setting-daily-limit')?.value, 10) || 30,
        binge_threshold_reels: parseInt(document.getElementById('setting-binge-threshold')?.value, 10) || 15,
        binge_window_minutes: parseInt(document.getElementById('setting-binge-window')?.value, 10) || 10,
        cooldown_base_seconds: parseInt(document.getElementById('setting-cooldown-base')?.value, 10) || 300,
        friction_threshold_pct: parseFloat(document.getElementById('setting-friction-pct')?.value) || 0.8,
      };

      const result = await updateSettings(newSettings);

      const statusEl = document.getElementById('settings-status');
      if (statusEl) {
        const parts = [];
        if (Object.keys(result.applied).length > 0) parts.push('Applied immediately \u2713');
        if (result.queued) parts.push('Some changes queued for 24h \u23F3');
        statusEl.textContent = parts.join(' \u00B7 ') || 'No changes';
        setTimeout(() => { statusEl.textContent = ''; }, 4000);
      }

      // Refresh today's panel so the new limit reflects in stats/budget.
      _renderTodayPanel();
    });
  }
}

function _setupExport() {
  const exportBtn = document.getElementById('export-data');
  if (exportBtn) {
    exportBtn.addEventListener('click', async () => {
      const data = await new Promise((resolve) => {
        chrome.storage.local.get(null, resolve);
      });

      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `attentionos-export-${todayUTC()}.json`;
      a.click();
      URL.revokeObjectURL(url);
    });
  }
}

// ─── Tab Navigation ──────────────────────────────────────────────────────────

function _setupTabNavigation() {
  const tabs = document.querySelectorAll('.tab-btn');
  const panels = document.querySelectorAll('.tab-panel');

  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const target = tab.dataset.tab;
      tabs.forEach((t) => t.classList.remove('active'));
      panels.forEach((p) => p.classList.remove('active'));
      tab.classList.add('active');
      const panel = document.getElementById(`panel-${target}`);
      if (panel) panel.classList.add('active');
    });
  });
}

// ─── SVG Defs ────────────────────────────────────────────────────────────────

function _injectSVGDefs() {
  const svg = document.querySelector('.score-ring');
  if (svg && !svg.querySelector('defs')) {
    const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    defs.innerHTML = `
      <linearGradient id="score-gradient" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" style="stop-color:#6366f1" />
        <stop offset="100%" style="stop-color:#a855f7" />
      </linearGradient>
    `;
    svg.prepend(defs);
  }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function _setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = value;
}

function _setInput(id, value) {
  const el = document.getElementById(id);
  if (el && value != null && value !== '') el.value = value;
}

function _formatDuration(seconds) {
  if (!seconds || seconds <= 0) return '0m';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${s}s`;
}

function _formatDate(dateStr) {
  const d = new Date(dateStr + 'T00:00:00Z');
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function _formatDayShort(dateStr) {
  const d = new Date(dateStr + 'T00:00:00Z');
  return d.toLocaleDateString('en-US', { weekday: 'short' }).slice(0, 3);
}

function _formatHour(hour) {
  if (hour === 0) return '12 AM';
  if (hour === 12) return '12 PM';
  if (hour < 12) return `${hour} AM`;
  return `${hour - 12} PM`;
}

function _formatRelativeTime(isoStr) {
  if (!isoStr) return 'soon';
  const diff = new Date(isoStr).getTime() - Date.now();
  if (diff <= 0) return 'now';

  const hours = Math.floor(diff / 3600000);
  const minutes = Math.floor((diff % 3600000) / 60000);

  if (hours > 0) return `in ${hours}h ${minutes}m`;
  if (minutes > 0) return `in ${minutes}m`;
  return 'in <1m';
}
