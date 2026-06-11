# ReelKill — Privacy Policy

Last updated: June 2026

## Overview

ReelKill is a browser extension that helps you manage your time on Instagram by enforcing daily limits on reel viewing. Your privacy is fundamental to how we built this tool.

## Data Collection

ReelKill does NOT collect, transmit, or share any personal data. There are zero network requests made by this extension. All data stays on your device.

## Data Storage

ReelKill stores the following data locally in your browser using `chrome.storage.local`:

- **Viewing statistics** — number of reels watched, time spent, daily summaries
- **Extension settings** — daily limits, cooldown durations, feature toggles
- **Session state** — current session ID, active block/cooldown status
- **Event logs** — reel view events used for budget enforcement (auto-pruned after 30 days)

All data is stored exclusively on your device. It never leaves your browser.

## Network Requests

ReelKill makes zero network requests. It does not contact any server, API, or third-party service during normal operation.

The only external URL in the extension is an optional uninstall feedback form (hosted on tally.so). This URL is only opened if you choose to submit feedback when uninstalling the extension. You are not required to use it.

## Permissions

ReelKill requests the following browser permissions:

- **storage** — to save your settings and viewing data locally
- **alarms** — to schedule maintenance tasks (e.g., clearing expired blocks)
- **tabs** — to coordinate tracking across multiple Instagram tabs

No other permissions are requested. ReelKill does not access your browsing history, cookies, passwords, or any data from websites other than Instagram.

## Content Scripts

ReelKill injects content scripts on instagram.com pages only. These scripts:

- Detect reel video elements in the page DOM
- Apply visual blur overlays when usage limits are reached
- Count reel views for budget enforcement

Content scripts do not read your messages, posts, comments, or any personal content on Instagram. They only interact with video elements and page structure.

## Data Retention

- Event logs are automatically pruned after 30 days
- You can clear all extension data at any time through your browser's extension settings
- Uninstalling the extension removes all stored data

## Changes to This Policy

If this privacy policy is updated, the changes will be reflected in the extension's source code and the Chrome Web Store listing.

## Contact

If you have questions about this privacy policy, you can open an issue at:

https://github.com/shaizan69/ReelKill/issues
