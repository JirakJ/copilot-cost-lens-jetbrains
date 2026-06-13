# Copilot Cost Lens — for JetBrains IDEs

**Know exactly what your AI coding tools cost you — per repository, per model, per day. 100% local and private.**

The JetBrains edition of [Copilot Cost Lens](https://github.com/JirakJ/copilot-cost-lens) (originally a VS Code extension). It reads the logs that GitHub Copilot (VS Code Chat **and** the Copilot CLI) and Claude Code already keep on your machine, attributes every request to the repository you were working in, prices it using the providers' model rates, and renders a live dashboard in a tool window — built on JCEF so it looks and behaves exactly like the VS Code version.

## Features

- **Cost per repository** — every request attributed to its repo via the git remote.
- **All your AI tools in one ledger** — VS Code Copilot Chat, GitHub Copilot CLI and Claude Code, with a per-provider spend split. Claude Code never counts against the Copilot allowance.
- **Token anatomy & effective $/1M** — input, output, cache read and cache write tokens per model, and the blended price actually paid (cache effects included).
- **Dashboard** — spend, allowance gauge, end-of-month forecast and burn-rate, cost-by-repo and model charts, daily/monthly trend and an activity heatmap.
- **Project groups** — roll several repositories into one named project, edited inline; combined receipt PDF with a per-repository breakdown.
- **Filter, sort, star** — text filter, source chips and sortable columns, built for 100+ repositories.
- **Receipt PDF export** — a classic printed-receipt per repository or project (hand-written PDF, zero dependencies).
- **Budgets & credit alerts** — set your plan allowance and absolute AI-credit thresholds.

## How it works

Cost Lens combines the same local sources as the VS Code edition:

| Source | What it provides |
|---|---|
| VS Code: `GitHub.copilot-chat/transcripts`, `debug-logs`, `chatSessions` | exact tokens / billed credits (estimation fallback) |
| Copilot CLI: `~/.copilot/session-state/**` | exact per-model tokens, billed premium requests / AI-credit units |
| Claude Code: `~/.claude/projects/**/*.jsonl` | exact per-request tokens incl. cache read/write |
| JetBrains Copilot: `~/.config/github-copilot/<ide>/**` (opt-in) | **estimated** — repo + models recovered, cost estimated from content (no token counts stored by the plugin) |

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
