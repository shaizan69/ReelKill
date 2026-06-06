/**
 * AttentionOS — Hourly Heatmap
 *
 * Generates a 24-bucket array of reel counts for heatmap visualization.
 * Each bucket represents one hour of the day (0 = midnight, 23 = 11 PM)
 * in the user's LOCAL timezone.
 *
 * Note: Event timestamps are stored as UTC ISO strings. The `hour` field
 * on each event is the user's local hour at the time of the event.
 * We use that field for bucketing so the heatmap matches the user's
 * perception of "late night" vs "morning".
 */

import { getEventsInWindow } from '../core/storage.js';

// ─── Heatmap Generation ──────────────────────────────────────────────────────

/**
 * Generates a 24-element array where index = hour (0-23) and value = reel count.
 * Covers a single day.
 *
 * @param {string} dateStr — "YYYY-MM-DD"
 * @returns {Promise<number[]>} Array of length 24
 */
async function generateDailyHeatmap(dateStr) {
  const dayStart = `${dateStr}T00:00:00.000Z`;
  const dayEnd = `${dateStr}T23:59:59.999Z`;

  const events = await getEventsInWindow(dayStart, dayEnd, 'reel_viewed');
  const buckets = new Array(24).fill(0);

  for (const event of events) {
    // Use the stored local hour for accurate timezone representation
    const hour = event.hour != null
      ? event.hour
      : new Date(event.timestamp).getHours();
    buckets[hour]++;
  }

  return buckets;
}

/**
 * Generates a 24-element heatmap aggregated across a date range.
 * Useful for the weekly view — shows which hours are consistently hot.
 *
 * @param {string} endDateStr — "YYYY-MM-DD" (last day, inclusive)
 * @param {number} numDays — number of days to look back
 * @returns {Promise<number[]>} Array of length 24 (sum across all days)
 */
async function generateRangeHeatmap(endDateStr, numDays) {
  const buckets = new Array(24).fill(0);
  const end = new Date(endDateStr + 'T00:00:00Z');

  for (let i = 0; i < numDays; i++) {
    const d = new Date(end.getTime() - i * 24 * 60 * 60 * 1000);
    const dateStr = d.toISOString().slice(0, 10);
    const dayBuckets = await generateDailyHeatmap(dateStr);

    for (let h = 0; h < 24; h++) {
      buckets[h] += dayBuckets[h];
    }
  }

  return buckets;
}

/**
 * Returns a structured heatmap with labels and intensity values (0.0-1.0).
 * Ready for direct rendering in the dashboard.
 *
 * @param {number[]} buckets — raw 24-element count array
 * @returns {Object[]} Array of { hour, label, count, intensity }
 */
function formatHeatmapForDisplay(buckets) {
  const maxCount = Math.max(...buckets, 1); // avoid division by zero

  return buckets.map((count, hour) => ({
    hour,
    label: _formatHourLabel(hour),
    count,
    intensity: count / maxCount,
  }));
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Formats an hour (0-23) into a human-readable label.
 * @param {number} hour
 * @returns {string} e.g. "12 AM", "3 PM"
 */
function _formatHourLabel(hour) {
  if (hour === 0) return '12 AM';
  if (hour === 12) return '12 PM';
  if (hour < 12) return `${hour} AM`;
  return `${hour - 12} PM`;
}

// ─── Exports ─────────────────────────────────────────────────────────────────

export {
  generateDailyHeatmap,
  generateRangeHeatmap,
  formatHeatmapForDisplay,
};
