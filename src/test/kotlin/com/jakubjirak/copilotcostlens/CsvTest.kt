package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.core.toCsv
import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RepoRef
import com.jakubjirak.copilotcostlens.model.UsageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CsvTest {
    private fun event(repo: String, model: String) = UsageEvent(
        sessionId = "s1",
        provider = Provider.COPILOT,
        repo = RepoRef(name = repo),
        timestamp = 0L,
        model = model,
        inputTokens = 1,
        outputTokens = 2,
        cachedTokens = 3,
        cacheWriteTokens = 4,
        credits = 1.5,
        costSource = CostSource.BILLED,
    )

    @Test
    fun `header is first line and one row per event`() {
        val csv = toCsv(listOf(event("a", "gpt-4o"), event("b", "claude")))
        val lines = csv.trim().lines()
        assertEquals("timestamp,provider,repo,model,sessionId,inputTokens,outputTokens,cachedTokens,cacheWriteTokens,credits,usd,costSource", lines[0])
        assertEquals(3, lines.size) // header + 2 rows
    }

    @Test
    fun `fields containing commas or quotes are escaped`() {
        val csv = toCsv(listOf(event("my, repo", "a\"b")))
        val row = csv.trim().lines()[1]
        assertTrue(row.contains("\"my, repo\""), "comma field must be quoted: $row")
        assertTrue(row.contains("\"a\"\"b\""), "quote must be doubled: $row")
    }
}
