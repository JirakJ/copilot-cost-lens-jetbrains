package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.core.ALL_TIME
import com.jakubjirak.copilotcostlens.core.availableMonths
import com.jakubjirak.copilotcostlens.core.buildGroupDetail
import com.jakubjirak.copilotcostlens.core.buildHeatmap
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.buildRepoDetail
import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RepoRef
import com.jakubjirak.copilotcostlens.model.UsageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.GregorianCalendar

class AggregateTest {
    private fun ts(y: Int, m: Int, d: Int) = GregorianCalendar(y, m - 1, d, 12, 0).timeInMillis
    private val now = GregorianCalendar(2026, 5, 10).timeInMillis // 2026-06-10

    private fun ev(
        repo: String = "owner/alpha", credits: Double = 10.0, provider: Provider = Provider.COPILOT,
        timestamp: Long = ts(2026, 6, 10), model: String = "gpt-5.5", session: String = "s",
        estimated: Boolean = false,
    ) = UsageEvent(session, provider, RepoRef(repo), timestamp, model, 1000, 200, 0, 0, credits,
        if (estimated) CostSource.ESTIMATED else CostSource.BILLED)

    @Test fun `available months newest first incl current`() {
        val events = listOf(ev(timestamp = ts(2025, 12, 1)))
        assertEquals(listOf("2026-06", "2025-12"), availableMonths(events, now))
    }

    @Test fun `month report totals filters and ranks repos`() {
        val events = listOf(
            ev(repo = "owner/alpha", credits = 1.0),
            ev(repo = "owner/beta", credits = 9.0, session = "s2"),
            ev(credits = 5.0, timestamp = ts(2026, 5, 1)),
        )
        val r = buildMonthReport(events, "2026-06", 1900, now = now)
        assertEquals(10.0, r.totalCredits, 1e-9)
        assertEquals(listOf("owner/beta", "owner/alpha"), r.repos.map { it.repo.name })
    }

    @Test fun `claude code excluded from allowance`() {
        val events = listOf(
            ev(credits = 100.0, provider = Provider.COPILOT),
            ev(credits = 900.0, provider = Provider.CLAUDE_CODE, session = "s3"),
        )
        val r = buildMonthReport(events, "2026-06", 1900, now = now)
        assertEquals(1000.0, r.totalCredits, 1e-9)
        assertEquals(100.0, r.copilotCredits, 1e-9)
    }

    @Test fun `codex excluded from allowance but counted in totals`() {
        val events = listOf(
            ev(credits = 100.0, provider = Provider.COPILOT),
            ev(credits = 40.0, provider = Provider.COPILOT_CLI, session = "s2"),
            ev(credits = 500.0, provider = Provider.CODEX, session = "s3"),
        )
        val r = buildMonthReport(events, "2026-06", 1900, now = now)
        assertEquals(640.0, r.totalCredits, 1e-9)
        assertEquals(140.0, r.copilotCredits, 1e-9)
    }

    @Test fun `day points accumulate tokens`() {
        val events = listOf(ev(credits = 1.0), ev(credits = 2.0, session = "s2"))
        val r = buildMonthReport(events, "2026-06", 0, now = now)
        assertEquals(1, r.days.size)
        assertEquals(2 * (1000L + 200L), r.days[0].tokens) // input + output per event
    }

    @Test fun `all-time disables allowance and forecast`() {
        val events = listOf(ev(credits = 10.0, timestamp = ts(2025, 10, 1)), ev(credits = 20.0))
        val r = buildMonthReport(events, ALL_TIME, 1900, now = now)
        assertEquals(30.0, r.totalCredits, 1e-9)
        assertEquals(0, r.includedCredits)
        assertEquals(30.0, r.forecastCredits, 1e-9)
    }

    @Test fun `forecast extrapolates current month`() {
        val r = buildMonthReport(listOf(ev(credits = 190.0)), "2026-06", 1900, now = now)
        assertEquals(570.0, r.forecastCredits, 1e-6) // 190 in 10 of 30 days
    }

    @Test fun `repo detail ranks top sessions`() {
        val events = listOf(
            ev(session = "cheap", credits = 1.0),
            ev(session = "big", credits = 50.0, model = "gpt-5.5"),
            ev(session = "big", credits = 30.0, model = "claude-sonnet-4.6"),
        )
        val d = buildRepoDetail(events, "owner/alpha", "2026-06")!!
        assertEquals("big", d.topSessions[0].sessionId)
        assertEquals(80.0, d.topSessions[0].credits, 1e-9)
    }

    @Test fun `group detail aggregates members`() {
        val events = listOf(
            ev(repo = "acme/fe", credits = 5.0),
            ev(repo = "acme/be", credits = 7.0, session = "s2"),
            ev(repo = "other", credits = 99.0, session = "s3"),
        )
        val d = buildGroupDetail(events, "Acme", listOf("acme/fe", "acme/be"), "2026-06")!!
        assertEquals(12.0, d.group.credits, 1e-9)
        assertNull(buildGroupDetail(events, "X", listOf("nope"), "2026-06"))
    }

    @Test fun `heatmap is 26 weeks aligned to today`() {
        val today = GregorianCalendar(2026, 5, 10, 9, 0).timeInMillis
        val events = listOf(
            ev(credits = 30.0, timestamp = GregorianCalendar(2026, 5, 10, 8, 0).timeInMillis),
            ev(credits = 99.0, timestamp = ts(2025, 1, 1)),
        )
        val h = buildHeatmap(events, today)
        assertEquals(26 * 7, h.size)
        assertEquals("2026-06-10", h.last().day)
        assertEquals(30.0, h.last().credits, 1e-9)
        assertEquals(30.0, h.sumOf { it.credits }, 1e-9) // old event excluded
    }

    @Test fun `flags estimates`() {
        val r = buildMonthReport(listOf(ev(estimated = true)), "2026-06", 1900, now = now)
        assertTrue(r.hasEstimates)
    }
}
