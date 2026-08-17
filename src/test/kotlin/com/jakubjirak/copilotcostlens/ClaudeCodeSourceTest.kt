package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.sources.findClaudeCodeFiles
import com.jakubjirak.copilotcostlens.sources.parseClaudeCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ClaudeCodeSourceTest {

    @Test fun `finds subagent and workflow transcripts nested below the session`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val project = File(root, "-Users-dev-work-acme")
        File(project, "sess-1/subagents/workflows/wf_abc").mkdirs()
        File(project, "sess-1.jsonl").writeText("")
        File(project, "sess-1/subagents/agent-a1.jsonl").writeText("")
        File(project, "sess-1/subagents/workflows/wf_abc/agent-a2.jsonl").writeText("")
        File(project, "sess-1/notes.txt").writeText("")

        // subagent turns are billed API calls of their own — a flat scan of the
        // project directory hid the majority of real usage
        assertEquals(
            setOf("sess-1.jsonl", "agent-a1.jsonl", "agent-a2.jsonl"),
            findClaudeCodeFiles(root).map { it.name }.toSet(),
        )
    }

    @Test fun `parses exact usage and dedupes streamed duplicates`(@TempDir tmp: Path) {
        val project = File(tmp.toFile(), "-Users-dev-work-acme").apply { mkdirs() }
        val file = File(project, "sess-1.jsonl")
        val usage = """{"input_tokens":3436,"cache_creation_input_tokens":3682,""" +
            """"cache_read_input_tokens":7912,"output_tokens":282}"""
        file.writeText(
            listOf(
                """{"type":"assistant","sessionId":"sess-1","cwd":"/Users/dev/work/acme",""" +
                    """"timestamp":"2026-08-10T10:00:05Z","requestId":"req_1",""" +
                    """"message":{"id":"msg_1","model":"claude-opus-5","usage":$usage}}""",
                // same message id + request id replayed by streaming — must count once
                """{"type":"assistant","sessionId":"sess-1","cwd":"/Users/dev/work/acme",""" +
                    """"timestamp":"2026-08-10T10:00:06Z","requestId":"req_1",""" +
                    """"message":{"id":"msg_1","model":"claude-opus-5","usage":$usage}}""",
                """{"type":"assistant","message":{"id":"msg_2","model":"<synthetic>",""" +
                    """"usage":{"input_tokens":1}}}""",
            ).joinToString("\n"),
        )

        val usages = parseClaudeCode(file)
        assertEquals(1, usages.size) // duplicate deduped, synthetic dropped
        val u = usages[0]
        assertEquals(Provider.CLAUDE_CODE, u.provider)
        assertEquals("claude-opus-5", u.model)
        assertEquals("/Users/dev/work/acme", u.folderPath)
        assertEquals(3436, u.inputTokens)
        assertEquals(7912, u.cachedTokens)
        assertEquals(3682, u.cacheWriteTokens)
        assertEquals(282, u.outputTokens)
        assertFalse(u.estimated)
        assertNotNull(u.sessionId)
    }
}
