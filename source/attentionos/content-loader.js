/**
 * AttentionOS — Content Loader
 *
 * Lightweight bootstrap file registered as a content script in manifest.json.
 * Mirrors Antigram's pattern: uses dynamic import() to load the AttentionOS
 * module graph, avoiding the content script isolation limitations.
 *
 * Guards against duplicate injection (important for SPA navigations and
 * Instagram's client-side routing which can re-trigger content scripts).
 */

if (!window.__attentionos_loaded) {
  window.__attentionos_loaded = true;

  (async () => {
    try {
      const src = chrome.runtime.getURL('attentionos/content-main.js');
      const module = await import(src);
      module.init();
    } catch (err) {
      console.error('[AttentionOS] Failed to load content-main:', err);
    }
  })();
}
