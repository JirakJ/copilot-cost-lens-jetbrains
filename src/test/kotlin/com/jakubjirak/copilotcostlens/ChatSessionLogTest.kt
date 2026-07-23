package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.sources.findChatSessions
import com.jakubjirak.copilotcostlens.sources.parseChatSession
import com.jakubjirak.copilotcostlens.sources.replaySessionLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ChatSessionLogTest {

    @Test fun `replays initial set push and delete operations`() {
        val log = listOf(
            """{"kind":0,"v":{"sessionId":"abc","requests":[]}}""",
            """{"kind":2,"k":["requests"],"v":[{"modelId":"gpt-5.5","promptTokens":10}]}""",
            """{"kind":1,"k":["requests",0,"completionTokens"],"v":25}""",
            """{"kind":1,"k":["lastMessageDate"],"v":1750000000000}""",
            """{"kind":3,"k":["lastMessageDate"]}""",
        ).joinToString("\n")
        val state = replaySessionLog(log)!!
        assertEquals("abc", state.get("sessionId").asString)
        val req = state.getAsJsonArray("requests").get(0).asJsonObject
        assertEquals(25, req.get("completionTokens").asInt)
        assertNull(state.get("lastMessageDate"))
    }

    @Test fun `push with truncation index replays repeated streaming updates`() {
        val log = listOf(
            """{"kind":0,"v":{"requests":[]}}""",
            """{"kind":2,"k":["requests"],"v":[{"modelId":"a"},{"modelId":"b"}]}""",
            // upstream truncates to 1 and re-pushes the final version of element 1
            """{"kind":2,"k":["requests"],"v":[{"modelId":"b2"}],"i":1}""",
        ).joinToString("\n")
        val state = replaySessionLog(log)!!
        val models = state.getAsJsonArray("requests").map { it.asJsonObject.get("modelId").asString }
        assertEquals(listOf("a", "b2"), models)
    }

    @Test fun `malformed lines and corrupt operations are skipped`() {
        val log = listOf(
            "not json",
            """{"kind":0,"v":{"requests":[]}}""",
            """{"kind":1,"k":["missing","deep","path"],"v":1}""",
            """{"kind":2,"k":["requests"],"v":[{"modelId":"x"}],"i":999}""",
        ).joinToString("\n")
        val state = replaySessionLog(log)!!
        assertEquals(1, state.getAsJsonArray("requests").size())
    }

    @Test fun `log-store session yields exact usage marked estimated`(@TempDir tmp: Path) {
        val ws = tmp.toFile()
        val dir = File(ws, "chatSessions").apply { mkdirs() }
        val request = """{"modelId":"gpt-5.5","timestamp":1750000000000,""" +
            """"promptTokens":1000,"completionTokens":200,"copilotCredits":1.5}"""
        File(dir, "sess1.jsonl").writeText("""{"kind":0,"v":{"sessionId":"sess1","requests":[$request]}}""")
        val usages = parseChatSession(File(dir, "sess1.jsonl"), ws, 4)
        assertEquals(1, usages.size)
        val u = usages[0]
        assertEquals(1000, u.inputTokens)
        assertEquals(200, u.outputTokens)
        assertEquals(1_500_000_000L, u.nanoCredits)
        assertTrue(u.estimated) // exact transcripts for the same session must still supersede
    }

    @Test fun `jsonl shadows sibling json and empty jsonl falls back to it`(@TempDir tmp: Path) {
        val ws = tmp.toFile()
        val dir = File(ws, "chatSessions").apply { mkdirs() }
        val request = """{"modelId":"m","message":{"text":"hello world"},"response":[{"value":"hi"}]}"""
        File(dir, "s.json").writeText(
            """{"sessionId":"s","lastMessageDate":1750000000000,"requests":[$request]}""",
        )
        File(dir, "s.jsonl").writeText("") // crash-truncated migration
        val files = findChatSessions(ws)
        assertEquals(listOf("s.jsonl"), files.map { it.name }) // .json shadowed
        val usages = parseChatSession(files[0], ws, 4)
        assertEquals(1, usages.size) // fell back to the flat .json
        assertTrue(usages[0].estimated)
    }
}
