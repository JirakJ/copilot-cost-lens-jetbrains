package com.jakubjirak.copilotcostlens.core

import java.util.Locale

/**
 * Aggregated per-repository summary of a month report — the pivot-friendly
 * counterpart to the raw event exports (CSV for finance, Markdown for
 * standups and status reports).
 */

fun summaryCsv(report: MonthReport): String {
    val header = listOf(
        "repo", "requests", "sessions", "inputTokens", "outputTokens",
        "cachedTokens", "cacheWriteTokens", "credits", "usd",
    ).joinToString(",")
    val rows = report.repos.map { r ->
        listOf(
            csvField(r.repo.name),
            r.requestCount.toString(),
            r.sessionCount.toString(),
            r.inputTokens.toString(),
            r.outputTokens.toString(),
            r.cachedTokens.toString(),
            r.cacheWriteTokens.toString(),
            "%.4f".format(Locale.ROOT, r.credits),
            "%.4f".format(Locale.ROOT, r.usd),
        ).joinToString(",")
    }
    val total = listOf(
        "TOTAL",
        report.repos.sumOf { it.requestCount }.toString(),
        report.repos.sumOf { it.sessionCount }.toString(),
        report.repos.sumOf { it.inputTokens }.toString(),
        report.repos.sumOf { it.outputTokens }.toString(),
        report.repos.sumOf { it.cachedTokens }.toString(),
        report.repos.sumOf { it.cacheWriteTokens }.toString(),
        "%.4f".format(Locale.ROOT, report.totalCredits),
        "%.4f".format(Locale.ROOT, report.totalUsd),
    ).joinToString(",")
    return (listOf(header) + rows + total).joinToString("\n") + "\n"
}

fun summaryMarkdown(report: MonthReport, currency: DisplayCurrency): String {
    val lines = mutableListOf(
        "# AI spend — ${report.month}",
        "",
        "| Repository | Requests | Credits | Spend | Share |",
        "| --- | ---: | ---: | ---: | ---: |",
    )
    for (r in report.repos) {
        val share = if (report.totalUsd > 0) "%.1f%%".format(Locale.ROOT, r.usd / report.totalUsd * 100) else "—"
        val name = r.repo.name.replace("|", "\\|")
        lines += "| $name | ${r.requestCount} | ${"%.1f".format(Locale.ROOT, r.credits)} | ${money(r.usd, currency)} | $share |"
    }
    lines += "| **Total** | | **${"%.1f".format(Locale.ROOT, report.totalCredits)}** | **${money(report.totalUsd, currency)}** | |"
    return lines.joinToString("\n") + "\n"
}
