package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.core.DisplayCurrency
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.money
import com.jakubjirak.copilotcostlens.core.sanitizeCurrency
import com.jakubjirak.copilotcostlens.core.sessionCosts
import com.jakubjirak.copilotcostlens.core.summaryCsv
import com.jakubjirak.copilotcostlens.core.summaryMarkdown
import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RepoRef
import com.jakubjirak.copilotcostlens.model.UsageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.GregorianCalendar

class SummaryTest {
    private val now = GregorianCalendar(2026, 5, 10).timeInMillis
    private val ts = GregorianCalendar(2026, 5, 10, 12, 0).timeInMillis

    private fun ev(repo: String, credits: Double, session: String = "s") =
        UsageEvent(session, Provider.COPILOT, RepoRef(repo), ts, "gpt-5.5", 1000, 200, 0, 0, credits, CostSource.BILLED)

    @Test fun `summary csv has header rows and TOTAL with dot decimals`() {
        val report = buildMonthReport(listOf(ev("owner/a", 100.0), ev("owner/b", 50.0, "s2")), "2026-06", 0, now = now)
        val csv = summaryCsv(report)
        val lines = csv.trim().split("\n")
        assertEquals("repo,requests,sessions,inputTokens,outputTokens,cachedTokens,cacheWriteTokens,credits,usd", lines[0])
        assertEquals(4, lines.size) // header + 2 repos + TOTAL
        assertTrue(lines[1].startsWith("owner/a,1,1,1000,200,0,0,100.0000,1.0000"))
        assertTrue(lines[3].startsWith("TOTAL,2,2,2000,400,0,0,150.0000,1.5000"))
    }

    @Test fun `summary markdown table with shares and total`() {
        val report = buildMonthReport(listOf(ev("owner/a", 75.0), ev("owner/b", 25.0, "s2")), "2026-06", 0, now = now)
        val md = summaryMarkdown(report, DisplayCurrency("USD", 1.0))
        assertTrue(md.contains("# AI spend — 2026-06"))
        assertTrue(md.contains("| owner/a | 1 | 75.0 | $0.75 | 75.0% |"))
        assertTrue(md.contains("| **Total** | | **100.0** | **$1.00** | |"))
    }

    @Test fun `money formats display currency`() {
        assertEquals("$1.50", money(1.5, DisplayCurrency("USD", 1.0)))
        assertEquals("34.50 CZK", money(1.5, DisplayCurrency("CZK", 23.0)))
    }

    @Test fun `sanitizeCurrency falls back to USD and clamps bad rates`() {
        assertEquals(DisplayCurrency("USD", 1.0), sanitizeCurrency("usd", 42.0)) // USD keeps rate 1
        assertEquals(DisplayCurrency("EUR", 0.9), sanitizeCurrency("eur", 0.9))
        assertEquals(DisplayCurrency("USD", 1.0), sanitizeCurrency("nonsense!", 2.0))
        assertEquals(DisplayCurrency("CZK", 1.0), sanitizeCurrency("CZK", -5.0))
    }

    @Test fun `sessionCosts totals per session and tracks latest repo`() {
        val events = listOf(
            ev("owner/a", 100.0, "big"),
            UsageEvent("big", Provider.COPILOT, RepoRef("owner/b"), ts + 1000, "gpt-5.5", 0, 0, 0, 0, 400.0, CostSource.BILLED),
            ev("owner/c", 1.0, "small"),
        )
        val costs = sessionCosts(events).associateBy { it.sessionId }
        assertEquals(5.0, costs["big"]!!.usd, 1e-9)
        assertEquals("owner/b", costs["big"]!!.repoName) // repo of the most recent event
        assertEquals(0.01, costs["small"]!!.usd, 1e-9)
    }
}
