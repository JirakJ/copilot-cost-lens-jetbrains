package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.sources.findCodexFiles
import com.jakubjirak.copilotcostlens.sources.parseCodexUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CodexSourceTest {

    @Test fun `finds jsonl files recursively`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        File(root, "2026/07/10").mkdirs()
        File(root, "2026/07/10/rollout-1.jsonl").writeText("")
        File(root, "top.jsonl").writeText("")
        File(root, "ignored.txt").writeText("")
        assertEquals(setOf("rollout-1.jsonl", "top.jsonl"), findCodexFiles(root).map { it.name }.toSet())
    }

    @Test fun `parses token_count events with session and model context`(@TempDir tmp: Path) {
        val file = File(tmp.toFile(), "rollout.jsonl")
        val usage = """{"last_token_usage":{"input_tokens":1000,"cached_input_tokens":400,"output_tokens":50}}"""
        file.writeText(
            listOf(
                """{"timestamp":"2026-07-10T10:00:00Z","type":"session_meta",""" +
                    """"payload":{"session_id":"sess-1","cwd":"/Users/me/work/proj"}}""",
                """{"timestamp":"2026-07-10T10:00:01Z","type":"turn_context",""" +
                    """"payload":{"model":"gpt-5.3-codex","cwd":"/Users/me/work/proj"}}""",
                """{"timestamp":"2026-07-10T10:00:05Z","type":"event_msg",""" +
                    """"payload":{"type":"token_count","info":$usage}}""",
                """{"timestamp":"2026-07-10T10:00:06Z","type":"event_msg",""" +
                    """"payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":0,"output_tokens":0}}}}""",
            ).joinToString("\n"),
        )
        val usages = parseCodexUsage(file)
        assertEquals(1, usages.size) // the zero-token event is dropped
        val u = usages[0]
        assertEquals("sess-1", u.sessionId)
        assertEquals(Provider.CODEX, u.provider)
        assertEquals("/Users/me/work/proj", u.folderPath)
        assertEquals("gpt-5.3-codex", u.model)
        assertEquals(600, u.inputTokens) // fresh = input - cached
        assertEquals(400, u.cachedTokens)
        assertEquals(50, u.outputTokens)
        assertFalse(u.estimated)
    }

    @Test fun `caps cached tokens at input tokens`(@TempDir tmp: Path) {
        val file = File(tmp.toFile(), "rollout.jsonl")
        val usage = """{"last_token_usage":{"input_tokens":100,"cached_input_tokens":500,"output_tokens":10}}"""
        file.writeText(
            """{"timestamp":"2026-07-10T10:00:05Z","type":"event_msg","payload":{"type":"token_count","info":$usage}}""",
        )
        val u = parseCodexUsage(file)[0]
        assertEquals(0, u.inputTokens)
        assertEquals(100, u.cachedTokens)
    }
}
