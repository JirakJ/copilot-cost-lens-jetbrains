# Copilot Cost Lens — for JetBrains IDEs

**Know exactly what your AI coding tools cost you — per repository, per model, per day. 100% local and private.**

The JetBrains edition of [Copilot Cost Lens](https://github.com/JirakJ/copilot-cost-lens) (originally a VS Code extension). It reads the logs that GitHub Copilot (VS Code Chat **and** the Copilot CLI), Claude Code and ChatGPT Codex already keep on your machine, attributes every request to the repository you were working in, prices it using the providers' model rates, and renders a live dashboard in a tool window — built on JCEF so it looks and behaves exactly like the VS Code version.

## Features

- **Cost per repository** — every request attributed to its repo via the git remote.
- **All your AI tools in one ledger** — VS Code Copilot Chat, GitHub Copilot CLI, Claude Code and ChatGPT Codex, with a per-provider spend split. Only Copilot counts against the Copilot allowance.
- **Token anatomy & effective $/1M** — input, output, cache read and cache write tokens per model, and the blended price actually paid (cache effects included).
- **Dashboard** — spend, today's running spend & tokens, allowance gauge, end-of-month forecast and burn-rate, cost-by-repo and model charts, daily/monthly trend and an activity heatmap.
- **Project groups & per-project budgets** — roll several repositories into one named project, edited inline; combined receipt PDF and a monthly USD budget per project.
- **Organize** — rename repositories (aliases survive rescans), hide noise repos with a manage view, text filter, source chips, sortable columns and starred repos — built for 100+ repositories.
- **Receipt PDF export** — per repository, per project, or one summary receipt for the whole period (hand-written PDF, zero dependencies).
- **Summary exports** — copy the month as a Markdown table or export a pivot-ready per-repository CSV (Tools menu).
- **Budgets & alerts** — plan allowance, absolute AI-credit thresholds, monthly budget and runaway-session warnings.
- **Display currency & status bar modes** — show amounts in your currency (manual rate, still zero network) and switch the status bar between spend / credits remaining / today.

## How it works

Cost Lens combines the same local sources as the VS Code edition:

| Source | What it provides |
|---|---|
| VS Code: `GitHub.copilot-chat/transcripts`, `debug-logs`, `chatSessions` | exact tokens / billed credits (estimation fallback) |
| Copilot CLI: `~/.copilot/session-state/**` | exact per-model tokens, billed premium requests / AI-credit units |
| Claude Code: `~/.claude/projects/**/*.jsonl` | exact per-request tokens incl. cache read/write |
| ChatGPT Codex: `~/.codex/sessions/**/*.jsonl` | exact per-turn tokens incl. cache reads |
| JetBrains Copilot: `~/.config/github-copilot/<ide>/**` (opt-in) | **estimated** — repo + models recovered, cost estimated from content (no token counts stored by the plugin) |

VS Code ≥ 1.128 chat-session logs (`chatSessions/*.jsonl`, an append-only mutation log) are replayed to the final session state; per-request `promptTokens`/`completionTokens`/`copilotCredits` are used exactly when present.

Costs use billed units when present (`1 credit = $0.01`, or `$0.04`/premium request), otherwise token × model rate. Token buckets are disjoint (fresh input / cache read / cache write / output).

> **Privacy:** everything happens on your machine — no network requests, no telemetry.
>
> **Disclaimer:** independent open-source project, not affiliated with GitHub or Microsoft. Numbers are an analytical aid, not a bill.

## Building

```bash
./gradlew buildPlugin   # builds the installable ZIP in build/distributions
./gradlew test          # unit tests (pricing + aggregation)
./gradlew runIde        # launch a sandbox IDE with the plugin
```

Requires JDK 17. Open the **Copilot Cost Lens** tool window (right dock) after installing.

## License

[MIT](LICENSE)
