package com.jakubjirak.copilotcostlens.pricing

import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.model.LongContextTier
import com.jakubjirak.copilotcostlens.model.ModelRate
import com.jakubjirak.copilotcostlens.model.RawUsage
import kotlin.math.max

/** 1 AI Credit = $0.01 (GitHub's dollar-normalized unit). */
const val USD_PER_CREDIT = 0.01

/** Overage price of one premium request (pre-June-2026 Copilot billing). */
const val USD_PER_PREMIUM_REQUEST = 0.04

/** Included monthly AI Credits per user, by plan. */
val PLAN_CREDITS: Map<String, Int> = mapOf(
    "business" to 1900,
    "businessPromo" to 3000,
    "enterprise" to 3900,
    "enterprisePromo" to 7000,
)

/**
 * Built-in price table, USD per 1M tokens. Source: GitHub Copilot "Models and
 * pricing" reference (checked 2026-06-13). Keys are normalized model ids.
 */
val DEFAULT_RATES: Map<String, ModelRate> = mapOf(
    "gpt-4.1" to ModelRate(2.0, 0.5, 8.0),
    "gpt-5" to ModelRate(1.75, 0.175, 14.0),
    "gpt-5-codex" to ModelRate(1.75, 0.175, 14.0),
    "gpt-5-mini" to ModelRate(0.25, 0.025, 2.0),
    "gpt-5.2" to ModelRate(1.75, 0.175, 14.0),
    "gpt-5.2-codex" to ModelRate(1.75, 0.175, 14.0),
    "gpt-5.3-codex" to ModelRate(1.75, 0.175, 14.0),
    "gpt-5.4" to ModelRate(2.5, 0.25, 15.0, longContext = LongContextTier(272_000, 5.0, 0.5, 22.5)),
    "gpt-5.4-mini" to ModelRate(0.75, 0.075, 4.5),
    "gpt-5.4-nano" to ModelRate(0.2, 0.02, 1.25),
    "gpt-5.5" to ModelRate(5.0, 0.5, 30.0, longContext = LongContextTier(272_000, 10.0, 1.0, 45.0)),
    "claude-haiku-4" to ModelRate(1.0, 0.1, 5.0, 1.25),
    "claude-haiku-4.5" to ModelRate(1.0, 0.1, 5.0, 1.25),
    "claude-sonnet-4" to ModelRate(3.0, 0.3, 15.0, 3.75),
    "claude-sonnet-4.5" to ModelRate(3.0, 0.3, 15.0, 3.75),
    "claude-sonnet-4.6" to ModelRate(3.0, 0.3, 15.0, 3.75),
    "claude-opus-4" to ModelRate(5.0, 0.5, 25.0, 6.25),
    "claude-opus-4.5" to ModelRate(5.0, 0.5, 25.0, 6.25),
    "claude-opus-4.6" to ModelRate(5.0, 0.5, 25.0, 6.25),
    "claude-opus-4.7" to ModelRate(5.0, 0.5, 25.0, 6.25),
    "claude-opus-4.8" to ModelRate(5.0, 0.5, 25.0, 6.25),
    "claude-fable-5" to ModelRate(10.0, 1.0, 50.0, 12.5),
    "gemini-2.5-pro" to ModelRate(1.25, 0.125, 10.0),
    "gemini-3-pro" to ModelRate(2.0, 0.2, 12.0),
    "gemini-3-flash" to ModelRate(0.5, 0.05, 3.0),
    "gemini-3.1-pro" to ModelRate(2.0, 0.2, 12.0, longContext = LongContextTier(200_000, 4.0, 0.4, 18.0)),
    "gemini-3.5-flash" to ModelRate(1.5, 0.15, 9.0),
    "grok-code-fast-1" to ModelRate(0.2, 0.02, 1.5),
    "raptor-mini" to ModelRate(0.25, 0.025, 2.0),
    "mai-code-1-flash" to ModelRate(0.75, 0.075, 4.5),
    "goldeneye" to ModelRate(1.25, 0.125, 10.0),
)

/** Fallback for models missing from the table ("versatile"-tier rate). */
val FALLBACK_RATE = ModelRate(2.0, 0.2, 10.0)

private val DATE_SUFFIX = Regex("-(\\d{8}|\\d{4}-\\d{2}-\\d{2}|preview|latest)$", RegexOption.IGNORE_CASE)
private val DASHED_VERSION = Regex("-(\\d+)-(\\d+)$")

/** Normalize raw model ids from logs to price-table keys. */
fun normalizeModelId(raw: String): String {
    var id = raw.trim().lowercase()
    val slash = id.lastIndexOf('/')
    if (slash >= 0) id = id.substring(slash + 1)
    id = id.replace(Regex("\\s+"), "-")
    id = DATE_SUFFIX.replace(id, "")
    id = DASHED_VERSION.replace(id) { "-${it.groupValues[1]}.${it.groupValues[2]}" }
    return id
}

class Pricing(private val overrides: Map<String, Map<String, Double>> = emptyMap()) {

    fun rateFor(model: String): ModelRate {
        val id = normalizeModelId(model)
        val base = DEFAULT_RATES[id] ?: bestPrefixMatch(id) ?: FALLBACK_RATE
        val ov = overrides[id] ?: return base
        return base.copy(
            input = ov["input"] ?: base.input,
            cachedInput = ov["cachedInput"] ?: base.cachedInput,
            output = ov["output"] ?: base.output,
            cacheWrite = ov["cacheWrite"] ?: base.cacheWrite,
        )
    }

    private fun bestPrefixMatch(id: String): ModelRate? {
        var bestKey: String? = null
        var bestRate: ModelRate? = null
        for ((key, rate) in DEFAULT_RATES) {
            if (id.startsWith(key) && (bestKey == null || key.length > bestKey!!.length)) {
                bestKey = key
                bestRate = rate
            }
        }
        return bestRate
    }

    fun priceUsage(raw: RawUsage): Pair<Double, CostSource> {
        raw.nanoCredits?.let { if (it > 0) return it / 1_000_000_000.0 to CostSource.BILLED }
        raw.premiumRequests?.let {
            if (it > 0) return it * USD_PER_PREMIUM_REQUEST / USD_PER_CREDIT to CostSource.BILLED
        }
        val usd = priceTokensUsd(raw.inputTokens, raw.outputTokens, raw.cachedTokens, raw.cacheWriteTokens, rateFor(raw.model))
        return usd / USD_PER_CREDIT to if (raw.estimated) CostSource.ESTIMATED else CostSource.COMPUTED
    }
}

/**
 * Price disjoint token buckets in USD. Each bucket bills at its own rate;
 * nothing is subtracted. The whole request jumps to the long-context tier
 * once fresh input + cache reads cross the threshold.
 */
fun priceTokensUsd(
    inputTokens: Long,
    outputTokens: Long,
    cachedTokens: Long,
    cacheWriteTokens: Long,
    rate: ModelRate,
): Double {
    val m = 1_000_000.0
    val freshInput = max(0L, inputTokens)
    val cached = max(0L, cachedTokens)
    val cacheWrite = max(0L, cacheWriteTokens)
    val output = max(0L, outputTokens)

    val context = freshInput + cached
    val lc = rate.longContext
    val (rIn, rCached, rOut) = if (lc != null && context > lc.threshold) {
        Triple(lc.input, lc.cachedInput, lc.output)
    } else {
        Triple(rate.input, rate.cachedInput, rate.output)
    }
    val rCacheWrite = rate.cacheWrite ?: rIn

    return (freshInput / m) * rIn +
        (cached / m) * rCached +
        (cacheWrite / m) * rCacheWrite +
        (output / m) * rOut
}

fun creditsToUsd(credits: Double): Double = credits * USD_PER_CREDIT
