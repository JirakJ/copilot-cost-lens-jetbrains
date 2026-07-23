package com.jakubjirak.copilotcostlens.core

import com.jakubjirak.copilotcostlens.model.UsageEvent
import com.jakubjirak.copilotcostlens.pricing.creditsToUsd
import java.time.Instant

private val HEADER = listOf(
    "timestamp", "provider", "repo", "model", "sessionId",
    "inputTokens", "outputTokens", "cachedTokens", "cacheWriteTokens",
    "credits", "usd", "costSource",
)

fun toCsv(events: List<UsageEvent>): String {
    val rows = events.joinToString("\n") { e ->
        listOf(
            Instant.ofEpochMilli(e.timestamp).toString(),
            e.provider.id,
            field(e.repo.name),
            field(e.model),
            e.sessionId,
            e.inputTokens.toString(),
            e.outputTokens.toString(),
            e.cachedTokens.toString(),
            e.cacheWriteTokens.toString(),
            "%.4f".format(java.util.Locale.ROOT, e.credits),
            "%.4f".format(java.util.Locale.ROOT, creditsToUsd(e.credits)),
            e.costSource.name.lowercase(),
        ).joinToString(",")
    }
    return HEADER.joinToString(",") + "\n" + rows + "\n"
}

private fun field(value: String): String = csvField(value)

internal fun csvField(value: String): String =
    if (value.any { it == '"' || it == ',' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
