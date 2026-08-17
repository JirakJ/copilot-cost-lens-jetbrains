package com.jakubjirak.copilotcostlens.model

/** Where a usage event came from. */
enum class Provider(val id: String) {
    COPILOT("copilot"),
    COPILOT_CLI("copilot-cli"),
    CLAUDE_CODE("claude-code"),
    CODEX("codex"),
}

/** How the cost of a usage event was determined. */
enum class CostSource { BILLED, COMPUTED, ESTIMATED }

data class RepoRef(
    val name: String,
    val folderPath: String? = null,
    val remoteSlug: String? = null,
)

/**
 * Raw usage extracted by a source, before pricing. Token buckets are disjoint:
 * [inputTokens] is fresh (non-cached) input, [cachedTokens] is cache reads,
 * [cacheWriteTokens] is cache creation, [outputTokens] is generation.
 */
data class RawUsage(
    val sessionId: String,
    val provider: Provider,
    val workspaceStorageDir: String? = null,
    val folderPath: String? = null,
    val repoSlug: String? = null,
    val timestamp: Long,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    /** copilotUsageNanoAiu: nano AI-credit units, 1e9 = 1 credit. */
    val nanoCredits: Long? = null,
    /** Billed premium requests (pre-June-2026 Copilot billing), 1 = $0.04. */
    val premiumRequests: Double? = null,
    val estimated: Boolean,
)

/** A priced billable interaction. */
data class UsageEvent(
    val sessionId: String,
    val provider: Provider,
    val repo: RepoRef,
    val timestamp: Long,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val credits: Double,
    val costSource: CostSource,
)

data class ModelRate(
    val input: Double,
    val cachedInput: Double,
    val output: Double,
    val cacheWrite: Double? = null,
    val longContext: LongContextTier? = null,
)

data class LongContextTier(
    val threshold: Long,
    val input: Double,
    val cachedInput: Double,
    val output: Double,
    /** Null when the model has no separate cache-write price at this tier. */
    val cacheWrite: Double? = null,
)
