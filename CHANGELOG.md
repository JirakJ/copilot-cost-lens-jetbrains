# Changelog

## [1.0.1] — 2026-06-13

### Fixed

- **Dashboard showed "No usage found" even when data existed.** The webview received its data only via the page's own `ready` handshake, which could race the JCEF JS bridge and never deliver the payload. Data is now pushed from the browser's load-end handler (and again when the background scan finishes), so the dashboard reliably renders. The scan layer itself was always correct (verified at 6,000+ events across Copilot, Copilot CLI and Claude Code).

## [1.0.0] — 2026-06-13

Initial release — JetBrains edition of Copilot Cost Lens.

- Per-repository cost tracking across GitHub Copilot Chat, Copilot CLI and Claude Code logs.
- JCEF dashboard tool window with parity to the VS Code edition: spend, allowance gauge, forecast & burn-rate, cost-by-repo and model charts, daily/monthly trend, activity heatmap.
- Project groups with inline editor and combined receipt PDF (per-repository breakdown).
- Repository table with text filter, source chips, sortable columns and starred repos.
- Receipt PDF export (hand-written, zero dependencies).
- Budgets, allowance presets and absolute AI-credit alerts.
- Settings page for plan, sources and estimation.
