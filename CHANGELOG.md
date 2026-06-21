# Changelog

## [1.3.0] — 2026-06-13

### Changed

- **Faster, smoother dashboard.** Results now paint as soon as the first source is scanned and fill in progressively instead of waiting for the whole scan, with a modern loading spinner until then. The view skips re-rendering when nothing changed, so background refreshes never flicker.

## [1.2.0] — 2026-06-13

### Added

- **Automatic background refresh.** The dashboard now rescans on its own every 60 seconds, so new usage appears without clicking Refresh. Backed by a new mtime+size file cache so repeat scans only re-parse changed files and stay cheap.

## [1.1.1] — 2026-06-13

### Fixed

- **Wrong repository names for git worktrees.** Work done in a git worktree (e.g. Claude Code's `<repo>/.claude/worktrees/<slug>`) was attributed to the random worktree slug instead of the real repository — a worktree's `.git` is a file (`gitdir: …`), so the remote couldn't be read and the folder basename was used. The resolver now follows the worktree pointer to the main repo's config and reads the `owner/repo` remote; folder-name fallback also skips `.git` and worktree scaffolding.

## [1.1.0] — 2026-06-13

### Added

- **JetBrains Copilot usage (estimated, opt-in).** A new source recovers GitHub Copilot activity from the JetBrains plugin's local session store (`~/.config/github-copilot/<ide>/`). It attributes usage to the right repository (from the project path), identifies the models used, and **estimates** cost from chat content — every entry is marked `~est`. Off by default; enable it in Settings → Copilot Cost Lens. The JetBrains plugin does not persist token counts locally (unlike the Copilot CLI and Claude Code), so exact pricing isn't possible; the reader is resilient and degrades to "nothing" if the undocumented store format changes.

## [1.0.2] — 2026-06-13

### Fixed

- **Low-contrast, hard-to-read dashboard text.** The webview theme was derived from the IDE's tool-window grays, which often fail readability. It now uses high-contrast content palettes (near-white text on a dark surface / near-black on light), keyed to the IDE's dark/light mode and accented with the IDE link color — comfortably meeting WCAG AA for primary text and AA for secondary text.

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
