package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.model.ModelRate
import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RawUsage
import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.pricing.DEFAULT_RATES
import com.jakubjirak.copilotcostlens.pricing.Pricing
import com.jakubjirak.copilotcostlens.pricing.creditsToUsd
import com.jakubjirak.copilotcostlens.pricing.normalizeModelId
import com.jakubjirak.copilotcostlens.pricing.priceTokensUsd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PricingTest {
    private fun raw(
        model: String = "gpt-5.5",
        input: Long = 0, output: Long = 0, cached: Long = 0, cacheWrite: Long = 0,
        nano: Long? = null, premium: Double? = null, estimated: Boolean = false,
    ) = RawUsage("s", Provider.COPILOT, timestamp = 0, model = model, inputTokens = input,
        outputTokens = output, cachedTokens = cached, cacheWriteTokens = cacheWrite,
        nanoCredits = nano, premiumRequests = premium, estimated = estimated)

    @Test fun `normalizes model ids`() {
        assertEquals("gpt-5-codex", normalizeModelId("copilot/gpt-5-codex"))
        assertEquals("claude-sonnet-4.6", normalizeModelId("Claude Sonnet 4.6"))
        assertEquals("claude-opus-4.6", normalizeModelId("claude-opus-4.6-20260203"))
        assertEquals("claude-opus-4.5", normalizeModelId("claude-opus-4-5-20251101"))
    }

    @Test fun `prices disjoint buckets without subtracting cached`() {
        val usd = priceTokensUsd(2_000_000, 500_000, 1_000_000, 0, ModelRate(2.0, 0.5, 8.0))
        assertEquals(4.0 + 0.5 + 4.0, usd, 1e-9)
    }

    @Test fun `bills cache writes at the cache-write rate`() {
        val usd = priceTokensUsd(0, 0, 0, 1_000_000, ModelRate(5.0, 0.5, 25.0, 6.25))
        assertEquals(6.25, usd, 1e-9)
    }

    @Test fun `gpt-5-6 codex tiers have official rates`() {
        assertEquals(5.0, DEFAULT_RATES["gpt-5.6-sol"]!!.input, 1e-9)
        assertEquals(2.0, DEFAULT_RATES["gpt-5.6-terra"]!!.input, 1e-9)
        assertEquals(0.2, DEFAULT_RATES["gpt-5.6-luna"]!!.input, 1e-9)
        assertEquals(1.2, DEFAULT_RATES["gpt-5.6-luna"]!!.output, 1e-9)
        // Sol and Terra bill cache writes and have long-context tiers
        assertEquals(6.25, DEFAULT_RATES["gpt-5.6-sol"]!!.cacheWrite!!, 1e-9)
        assertEquals(45.0, DEFAULT_RATES["gpt-5.6-sol"]!!.longContext!!.output, 1e-9)
        assertEquals(12.0, DEFAULT_RATES["gpt-5.6-terra"]!!.output, 1e-9)
    }

    @Test fun `claude 5 family is priced instead of falling back`() {
        val opus5 = Pricing().rateFor("claude-opus-5")
        assertEquals(5.0, opus5.input, 1e-9)
        assertEquals(0.5, opus5.cachedInput, 1e-9)
        assertEquals(6.25, opus5.cacheWrite!!, 1e-9)
        assertEquals(25.0, opus5.output, 1e-9)

        val sonnet5 = Pricing().rateFor("claude-sonnet-5")
        assertEquals(2.0, sonnet5.input, 1e-9)
        assertEquals(2.5, sonnet5.cacheWrite!!, 1e-9)
        assertEquals(10.0, sonnet5.output, 1e-9)

        // context-window and date suffixes must not knock the model off its rate
        assertEquals(opus5, Pricing().rateFor("claude-opus-5[1m]"))
        assertEquals(opus5, Pricing().rateFor("claude-opus-5-20260601"))
    }

    @Test fun `long context tier bills cache writes at its own rate`() {
        val rate = DEFAULT_RATES["gpt-5.6-sol"]!!
        // 300k context crosses the 272k threshold → cache write at $12.50, not $6.25
        val usd = priceTokensUsd(300_000, 0, 0, 1_000_000, rate)
        assertEquals(0.3 * 10.0 + 12.5, usd, 1e-6)
    }

    @Test fun `long context tier kicks in above threshold`() {
        val rate = DEFAULT_RATES["gpt-5.5"]!!
        val below = priceTokensUsd(272_000, 1_000_000, 0, 0, rate)
        val above = priceTokensUsd(272_001, 1_000_000, 0, 0, rate)
        assertEquals(0.272 * 5 + 30, below, 1e-6)
        assertEquals(0.272001 * 10 + 45, above, 1e-6)
    }

    @Test fun `prefers billed nano credits then premium requests`() {
        val byNano = Pricing().priceUsage(raw(nano = 2_500_000_000L, input = 1_000_000))
        assertEquals(2.5, byNano.first, 1e-9)
        assertEquals(CostSource.BILLED, byNano.second)
        val byPremium = Pricing().priceUsage(raw(premium = 39.0))
        assertEquals(156.0, byPremium.first, 1e-9) // 39 × $0.04 = $1.56 = 156 credits
    }

    @Test fun `claude fable 5 uses official doubled rate`() {
        val r = DEFAULT_RATES["claude-fable-5"]!!
        assertEquals(10.0, r.input, 1e-9)
        assertEquals(50.0, r.output, 1e-9)
    }

    @Test fun `credits convert at one cent`() {
        assertEquals(1.5, creditsToUsd(150.0), 1e-9)
    }

    @Test fun `estimated usage is marked`() {
        assertEquals(CostSource.ESTIMATED, Pricing().priceUsage(raw(model = "gpt-5-mini", input = 1000, estimated = true)).second)
        assertTrue(Pricing().priceUsage(raw(model = "mystery-9000", input = 1_000_000)).first > 0)
    }
}
