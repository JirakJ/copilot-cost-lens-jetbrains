package com.jakubjirak.copilotcostlens.sources

import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RawUsage
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Best-effort, **estimated** extraction of GitHub Copilot usage from the
 * JetBrains plugin's local session store:
 *   ~/.config/github-copilot/<ide>/{chat-agent-sessions,chat-sessions}/<id>/...nitrite.db
 *
 * The JetBrains plugin does **not** persist token counts or AI-credit usage
 * (unlike the Copilot CLI and Claude Code), so this source reads the readable
 * UTF-8 runs out of the Nitrite/MVStore files to recover the project path and
 * the models used, and estimates token volume from the readable text length.
 * Every event it produces is marked estimated. Reading is capped and scaled so
 * large DBs stay cheap; the format is undocumented, so failures degrade to
 * "nothing", never to wrong exact numbers.
 */
private const val SAMPLE_CAP_BYTES = 4 * 1024 * 1024
private const val MIN_RUN = 6

private val PRODUCT_DIRS = listOf("iu", "ic", "intellij", "py", "pc", "ps", "go", "rd", "ws", "rm", "cl", "ja")
// Bounded to known model families with fixed suffix vocabularies so a stray
// serialization byte after the id (e.g. "gemini-2.5-prot") is not absorbed,
// and binary noise like "o3" is not mistaken for a model.
private val MODEL_RE = Regex(
    "(?:claude-(?:opus|sonnet|haiku|fable)-[0-9]+(?:\\.[0-9]+)?" +
        "|gpt-[0-9]+(?:\\.[0-9]+)?(?:-(?:codex-max|codex|mini|nano))?" +
        "|gemini-[0-9]+(?:\\.[0-9]+)?-(?:pro|flash)" +
        "|grok-code-fast-1|raptor-mini|mai-code-1-flash)",
)
// any reasonably deep absolute unix path; repoRootOf() validates it against
// the filesystem (and prefers the nearest .git ancestor), so a loose match is fine
private val PATH_RE = Regex("/[\\w.\\-]+(?:/[\\w.\\-]+){2,30}")

fun defaultJetBrainsCopilotRoot(): File = File(System.getProperty("user.home"), ".config/github-copilot")

data class JetBrainsDb(val file: File)

fun findJetBrainsCopilotDbs(root: File): List<JetBrainsDb> {
    val out = mutableListOf<JetBrainsDb>()
    for (product in PRODUCT_DIRS) {
        val ideDir = File(root, product)
        if (!ideDir.isDirectory) continue
        for (kind in listOf("chat-agent-sessions", "chat-sessions")) {
            File(ideDir, kind).listFiles { f -> f.isDirectory }?.forEach { sessionDir ->
                sessionDir.listFiles { f -> f.name.endsWith("nitrite.db") }?.forEach { out += JetBrainsDb(it) }
            }
        }
    }
    return out
}

fun parseJetBrainsCopilot(db: JetBrainsDb, charsPerToken: Int): List<RawUsage> {
    val sample = readPrintable(db.file) ?: return emptyList()

    val models = MODEL_RE.findAll(sample.text).map { it.value }.toList()
    if (models.isEmpty()) return emptyList()
    val modelCounts = models.groupingBy { it }.eachCount()

    val folderPath = mostFrequentRepoRoot(sample.text)
    val timestamp = db.file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()

    // estimate total tokens from the (scaled) readable text; chat is context-heavy
    val totalTokens = ceil(sample.text.length.toDouble() / max(1, charsPerToken)).toLong() * sample.scale
    val sessionId = "jb-${db.file.parentFile.name}"
    val totalCount = modelCounts.values.sum()

    return modelCounts.map { (model, count) ->
        val share = count.toDouble() / totalCount
        val tokens = (totalTokens * share).roundToLong()
        RawUsage(
            sessionId = sessionId,
            provider = Provider.COPILOT,
            folderPath = folderPath,
            timestamp = timestamp,
            model = model,
            inputTokens = (tokens * 0.85).roundToLong(),
            outputTokens = (tokens * 0.15).roundToLong(),
            cachedTokens = 0,
            cacheWriteTokens = 0,
            estimated = true,
        )
    }
}

private class Sample(val text: String, val scale: Long)

/** Joined runs of printable text from (a capped prefix of) the file, plus a scale factor. */
private fun readPrintable(file: File): Sample? {
    val size = file.length()
    if (size == 0L) return null
    val cap = minOf(size, SAMPLE_CAP_BYTES.toLong()).toInt()
    val bytes = ByteArray(cap)
    file.inputStream().use { stream ->
        var read = 0
        while (read < cap) {
            val n = stream.read(bytes, read, cap - read)
            if (n < 0) break
            read += n
        }
    }
    val sb = StringBuilder()
    var runStart = -1
    fun flush(end: Int) {
        if (runStart >= 0 && end - runStart >= MIN_RUN) {
            sb.append(String(bytes, runStart, end - runStart, Charsets.ISO_8859_1)).append(' ')
        }
        runStart = -1
    }
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xFF
        if (b in 0x20..0x7E) {
            if (runStart < 0) runStart = i
        } else {
            flush(i)
        }
    }
    flush(bytes.size)
    if (sb.isEmpty()) return null
    val scale = if (size > cap) max(1L, (size + cap - 1) / cap) else 1L
    return Sample(sb.toString(), scale)
}

/** Most frequently mentioned existing directory that looks like a repo root. */
private fun mostFrequentRepoRoot(text: String): String? {
    val counts = HashMap<String, Int>()
    for (m in PATH_RE.findAll(text)) {
        val root = repoRootOf(m.value) ?: continue
        counts[root] = (counts[root] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key
}

private fun repoRootOf(path: String): String? {
    var dir: File? = File(path).let { if (it.isDirectory) it else it.parentFile }
    var depth = 0
    while (dir != null && depth < 12) {
        if (File(dir, ".git").exists()) return dir.absolutePath
        dir = dir.parentFile
        depth++
    }
    // no .git found — fall back to an existing directory at the matched path
    val f = File(path)
    return when {
        f.isDirectory -> f.absolutePath
        f.parentFile?.isDirectory == true -> f.parentFile.absolutePath
        else -> null
    }
}
