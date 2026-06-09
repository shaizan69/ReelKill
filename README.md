ReelKill
========

A browser extension that puts you back in control of your Instagram experience. Blocks reels, enforces daily usage limits, and helps you build healthier social media habits — without cutting you off entirely.

Built on the bones of Antigram (https://github.com/aymyo/antigram-extension).


What it does
------------

ReelKill has two layers of protection:


1. Element Hiding
.................

Hides Instagram's most addictive surfaces so you never fall into the rabbit hole:

  - Reels — the /reels/ page and reel posts in your feed
  - Explore — the /explore/ page
  - Stories — the stories tray and /stories/ page
  - Posts — the main feed (optional)
  - Suggested followers — the "discover people" section
  - Threads links — navigation links to Threads
  - For You feed — redirects to your Following feed

All toggles are customizable. You choose what to block.


2. Usage Management (AttentionOS)
.................................

The core system that actually changes behavior:

Reel tracking — Detects individual reel views using IntersectionObserver. A reel only counts when you've watched it for 2+ seconds at 50%+ visibility. No false positives from scrolling past.

Daily limit — Set a reel-per-day limit (1-50). When you hit it, Instagram gets blurred for 24 hours. No bypass, no snooze, no way around it.

Binge detection — Watches for rapid-fire scrolling. If you watch 15+ reels in 10 minutes, a cooldown kicks in — escalating from 5 min to 10 min to 20 min to 30 min on repeat offenses.

Friction warnings — At 80% of your daily limit, a soft warning appears. One shot per session to course-correct before the hard block hits.

Pattern detection — Spots harmful habits: late-night scrolling, frequent Instagram opens, cooldown clusters. Logs interventions for awareness.


Analytics Dashboard
-------------------

A full-page dashboard that shows you what's actually happening:

  - Attention Score (0-100) — composite metric weighing daily limit compliance, cooldown avoidance, late-night usage, week-over-week improvement, and friction response
  - Hourly heatmap — see exactly which hours you binge the most
  - Weekly trends — bar chart comparing daily scores across the week
  - Streak tracker — consecutive days without hitting the hard block
  - Budget bar — visual progress toward your daily limit
  - Settings editor — adjust limits with tightening-is-immediate, loosening-requires-24h enforcement
  - Data export — download your full event history as JSON

Live-updates while you browse. No page refresh needed.


How it works
------------

  Instagram Page
    |
    +-- content.js (element hiding)
    |     MutationObserver watches DOM, hides blocked elements
    |
    +-- content-loader.js (AttentionOS)
          tracker.js          detects reel views via IntersectionObserver
          reels-blur.js       blurs reels + shows countdown badge
          friction-modal.js   soft warning at 80% limit
          storage.js          logs events to chrome.storage.local

  Service Worker
    |
    +-- On reel view: recount -> check budget -> check binge -> check friction -> check patterns
    +-- Broadcasts: blur, block, cooldown, friction to all Instagram tabs
    +-- Manages: alarms, tab coordination, pending settings, event pruning

  Popup              quick stats, limit stepper, element toggles
  Dashboard          full analytics, scores, heatmaps, settings


Stack
-----

  - Manifest V3 — Chrome extension, Firefox-compatible via make swap-agent
  - Zero dependencies — vanilla JS, HTML, CSS
  - ES modules — dynamic imports for content scripts
  - Two storage layers:
      chrome.storage.sync — element hiding toggles (syncs across devices)
      chrome.storage.local — usage data, events, analytics (local only)
  - No network requests — everything runs locally, your data never leaves your browser


Install
-------

Chrome (manual)
~~~~~~~~~~~~~~~

1. Clone the repo:
     git clone https://github.com/shaizan69/ReelKill.git

2. Open Chrome > three-dot menu > Extensions > Manage Extensions
3. Toggle Developer mode (top right)
4. Click Load unpacked
5. Select the source/ folder
6. Done — ReelKill icon appears in your toolbar

Firefox
~~~~~~~

  cd antigram-extension
  make swap-agent

Then load source-firefox/ as a temporary add-on in about:debugging.


Development
-----------

  cd antigram-extension
  yarn install

Edit files in source/. Refresh the extension in chrome://extensions to see changes. No build step needed.

Project structure
~~~~~~~~~~~~~~~~~

  source/
    manifest.json
    service_worker.js              Root SW, bridges to AttentionOS
    content.js                     Legacy element hiding entry
    modules/
      main.js                      MutationObserver + hide()
      lib.js                       Selectors, defaults, hide utility
    attentionos/
      content-loader.js            Bootstrap, dedup guard
      content-main.js              Orchestrator, message routing
      background/
        service-worker.js          Central coordinator
      core/
        storage.js                 chrome.storage.local wrapper
        tracker.js                 Reel detection (IntersectionObserver)
        budget.js                  Daily limit + hard block
        cooldown.js                Binge detection + escalating cooldowns
        friction.js                Soft warning logic
        intervention.js            Pattern detection engine
      analytics/
        aggregator.js              Daily summaries + weekly reports
        score.js                   0-100 attention score
        heatmap.js                 24-bucket hourly heatmap
      ui/
        reels-blur.js              Blur + pause + badge
        friction-modal.js          Warning modal
        dashboard.html/js/css      Full analytics dashboard
    popup/
      popup.html/js/css            Toolbar popup
    public/
      logo.png


Permissions
-----------

  storage       Store settings, events, analytics
  alarms        Schedule cooldown/block expiry
  tabs          Coordinate tracker across Instagram tabs

No network permissions. No data leaves your browser.


Privacy
-------

ReelKill runs entirely locally. All usage data is stored in chrome.storage.local and never transmitted anywhere. The extension makes zero network requests. Your browsing habits stay on your machine.


Contributing
------------

Open a PR. I'll review it when I can.

To set up the dev environment:

  git clone https://github.com/shaizan69/ReelKill.git
  cd antigram-extension
  yarn install

Load source/ as an unpacked extension and start hacking.


License
-------

MIT
