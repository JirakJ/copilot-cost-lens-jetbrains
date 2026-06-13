package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.sources.JetBrainsDb
import com.jakubjirak.copilotcostlens.sources.parseJetBrainsCopilot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JetBrainsSourceTest {
    /** Builds a fake Nitrite-like blob: binary noise interspersed with readable runs. */
    private fun fakeDb(dir: File, content: List<String>): JetBrainsDb {
        val session = File(dir, "3Aa7BCoJPHio6DEzYdhKa36WSXB").apply { mkdirs() }
        val file = File(session, "copilot-agent-sessions-nitrite.db")
        val out = file.outputStream()
        val noise = ByteArray(32) // sub-MIN_RUN binary separators
        for (s in content) {
            out.write(noise)
            out.write(s.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(noise)
        out.close()
        return JetBrainsDb(file)
    }

    @Test fun `extracts models, repo and estimates tokens`(@TempDir tmp: Path) {
        val repo = File(tmp.toFile(), "work/new-automation").apply { mkdirs() }
        File(repo, ".git").mkdirs()
        val content = buildList {
            repeat(20) { add("interactionId turnId responder ${repo.absolutePath}/src/Main.kt") }
            repeat(8) { add("model claude-opus-4.5 some chat content about refactoring code here") }
            repeat(2) { add("model gpt-5.5 short follow-up question") }
        }
        val db = fakeDb(File(tmp.toFile(), "iu/chat-agent-sessions"), content)
        val usages = parseJetBrainsCopilot(db, 4)

        assertEquals(setOf("claude-opus-4.5", "gpt-5.5"), usages.map { it.model }.toSet())
        assertTrue(usages.all { it.estimated })
        assertTrue(usages.all { it.provider == Provider.COPILOT })
        assertEquals(repo.absolutePath, usages.first().folderPath)
        assertTrue(usages.sumOf { it.inputTokens + it.outputTokens } > 0)
        // claude was used 8x vs gpt 2x → claude carries the larger estimate share
        val claude = usages.first { it.model == "claude-opus-4.5" }
        val gpt = usages.first { it.model == "gpt-5.5" }
        assertTrue(claude.inputTokens > gpt.inputTokens)
    }

    @Test fun `returns nothing when no models are present`(@TempDir tmp: Path) {
        val db = fakeDb(File(tmp.toFile(), "iu/chat-sessions"), listOf("just some binary-ish text with no model ids"))
        assertTrue(parseJetBrainsCopilot(db, 4).isEmpty())
    }
}
