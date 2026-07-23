# Changelog

## [1.22.3] — 2026-07-23

Parity with the VS Code edition 1.22.3 — everything added there between 1.14.0 and 1.22.3, ported.

### Added

- **ChatGPT Codex support.** A new data source reads exact per-turn token usage from `~/.codex/sessions` rollout logs, attributed to the repository from the session's working directory. On by default; Codex spend never counts against the Copilot allowance gauge.
- **Exact chat usage from VS Code ≥ 1.128.** Chat sessions stored as append-only mutation logs (`chatSessions/<id>.jsonl`) are replayed to the final session state; per-request `promptTokens`, `completionTokens` and `copilotCredits` are used directly (credits priced as billed) instead of character-length estimates. Exact Copilot transcript data for the same session still takes precedence.
- **Rename repositories.** The ✎ button (row hover or detail view) sets a display name — remote repositories no longer show up as `(unknown) 2bebdc79`. Aliases are keyed by the original name so they survive rescans; clear the field to reset.
- **Hide repositories & manage view.** The 🙈 button removes noise repos from the dashboard, status bar and receipts; raw CSV/JSON exports and budget alerts still count them. The "N hidden — manage" link opens a checklist to unhide.
- **Today's spend & tokens.** The Spend card badge (and the empty state) shows today's running total, e.g. `Today: $2.40 · 1.9M tokens`; the heatmap tooltip shows per-day tokens; the status bar tooltip shows today's spend.
- **Status bar modes & sparkline.** Switch the widget between `spend`, `remaining` (counts down AI credits left) and `today`, each with a 7-day unicode sparkline.
- **Display currency.** Show all dashboard and status-bar amounts in your currency with a manually set exchange rate — the plugin still never touches the network. Internal accounting and PDF receipts stay in USD.
- **Summary receipt.** The 🧾 toolbar button exports one PDF covering every repository in the selected period — your monthly AI expense document in one click.
- **Copy Summary as Markdown & Export Summary CSV.** Two new Tools-menu actions: the current month's per-repository costs as a clipboard Markdown table, and a pivot-ready per-repository CSV with a TOTAL row.
- **Per-project budgets.** Give each project its own monthly USD budget (JSON map in Settings); warned once per day when a project crosses the warn percent.
- **Runaway-session alert.** Set a session cost threshold and get warned the moment a single session — say, an agent left unattended — crosses it. Each session alerts at most once.
- **Add Storage Root.** A folder picker right in the empty state (and Settings), so "no data" has a one-click escape hatch.
- **GPT-5.6 Sol/Terra/Luna pricing** — previously fell back to a generic estimated rate.

### Changed

- Repositories assigned to a project group are hidden from the flat Repositories table — they appear only under their project.
- Repository and project rows are keyboard-accessible: focus with Tab, open with Enter or Space.
- Remote SSH, dev-container and WSL workspaces are identified from their `vscode-remote://` metadata instead of showing as `(unknown) <hash>`.
- The Copilot allowance gauge counts only Copilot products (Chat + CLI); Codex and Claude Code are billed separately.
- CSV exports always use dot decimal separators regardless of the IDE locale.

## [1.13.3] — 2026-06-21

Parity with the VS Code edition — everything it does, this does too.

### Added

- **Export usage as CSV or JSON.** New "Export CSV" / "Export JSON" actions on the dashboard write the current period's events (or all-time) to a file you pick, with proper quoting.
- **Credit alerts & budget notifications.** Set absolute AI-credit thresholds (e.g. 2500, 5000) and/or a monthly USD budget in Settings; the plugin fires an IDE balloon at most once per month when month-to-date Copilot usage crosses one.
- **Localized dashboard.** The dashboard now follows the IDE language for Czech, German and Japanese (English elsewhere), reusing the VS Code edition's translations.
- **Settings for the above.** New fields for the monthly budget, comma-separated credit alerts and extra VS Code storage roots to scan.

## [1.13.2] — 2026-06-21

Version aligned with the VS Code edition (no functional change since 1.4.0 — same status-bar widget and shared scan service). Both editions now share a version number.

## [1.4.0] — 2026-06-21

### Added

- **Status-bar widget.** Month-to-date Copilot spend is now always visible in the IDE status bar; click it to open the dashboard, hover for the per-source split. Backed by a shared application service so the widget and the dashboard share one scan instead of each rescanning (also removes the rescan that happened on every tool-window open).

## [1.3.2] — 2026-06-21

### Fixed

- **Cards flashed on every update.** A fade-in animation re-ran on each render, blinking the dashboard whenever data changed. Removed it — updates repaint silently (the spinner still covers the first load).

## [1.3.1] — 2026-06-21

### Fixed

- **Periodic flicker and scroll-jump on auto-refresh.** Progressive painting now runs only on the first load; later 60s refreshes publish once instead of flashing partial data. The view also keeps your scroll position when refreshing the same screen.

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
