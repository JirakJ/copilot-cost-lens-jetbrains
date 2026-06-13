package com.jakubjirak.copilotcostlens.sources

import com.jakubjirak.copilotcostlens.model.Provider
import com.jakubjirak.copilotcostlens.model.RawUsage
import java.io.File
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

private fun estimateTokens(chars: Long, charsPerToken: Int): Long =
    if (chars <= 0) 0 else ceil(chars.toDouble() / max(1, charsPerToken)).toLong()

private fun parseTimestamp(text: String?): Long? =
    text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

// ---------------------------------------------------------------------------
// VS Code Copilot Chat — exact JSONL transcripts + estimated chatSessions
// ---------------------------------------------------------------------------

fun findVsCodeJsonl(storageDir: File): List<Pair<File, String>> {
    val base = File(storageDir, "GitHub.copilot-chat")
    val files = mutableListOf<Pair<File, String>>()
    File(base, "transcripts").listFiles { f -> f.name.endsWith(".jsonl") }?.forEach {
        files += it to it.name.removeSuffix(".jsonl")
    }
    File(base, "debug-logs").listFiles { f -> f.isDirectory }?.forEach { dir ->
        dir.listFiles { f -> f.name.endsWith(".jsonl") }?.forEach { files += it to dir.name }
    }
    return files
}

fun parseVsCodeJsonl(file: File, sessionId: String, storageDir: File): List<RawUsage> {
    val out = mutableListOf<RawUsage>()
    forEachJsonLine(file) { record ->
        val attrs = record.obj("attrs")
        fun str(vararg k: String) = record.str(*k) ?: attrs?.str(*k)
        fun num(vararg k: String) = record.num(*k) ?: attrs?.num(*k)

        val input = num("inputTokens", "input_tokens", "usage_input_tokens", "promptTokens", "prompt_tokens")
        val output = num("outputTokens", "output_tokens", "usage_output_tokens", "completionTokens", "completion_tokens")
        val nano = num("copilotUsageNanoAiu", "copilot_usage_nano_aiu")
        if (input == null && output == null && nano == null) return@forEachJsonLine

        val cached = num("cachedTokens", "cached_tokens", "usage_cached_tokens") ?: 0
        val tsNum = num("ts", "timestamp", "time")
        val ts = when {
            tsNum != null -> if (tsNum > 10_000_000_000L) tsNum else tsNum * 1000
            else -> parseTimestamp(str("timestamp", "time", "createdAt", "created_at", "date")) ?: System.currentTimeMillis()
        }
        out += RawUsage(
            sessionId = sessionId,
            provider = Provider.COPILOT,
            workspaceStorageDir = storageDir.absolutePath,
            timestamp = ts,
            model = str("model", "usage_model", "modelName", "model_name") ?: "unknown",
            inputTokens = max(0, (input ?: 0) - cached),
            outputTokens = output ?: 0,
            cachedTokens = cached,
            cacheWriteTokens = num("cacheWriteTokens", "cache_write_tokens", "cacheCreationTokens") ?: 0,
            nanoCredits = nano,
            estimated = false,
        )
    }
    return out
}

fun findChatSessions(storageDir: File): List<File> =
    File(storageDir, "chatSessions").listFiles { f -> f.name.endsWith(".json") }?.toList() ?: emptyList()

fun parseChatSession(file: File, storageDir: File, charsPerToken: Int): List<RawUsage> {
    val root = runCatching { com.google.gson.JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        ?: return emptyList()
    val requests = if (root.has("requests") && root.get("requests").isJsonArray) root.getAsJsonArray("requests") else return emptyList()
    val sessionId = root.str("sessionId") ?: file.name.removeSuffix(".json")
    val fallbackTs = root.num("lastMessageDate") ?: root.num("creationDate") ?: System.currentTimeMillis()
    val out = mutableListOf<RawUsage>()
    for (el in requests) {
        if (!el.isJsonObject) continue
        val req = el.asJsonObject
        val model = req.str("modelId") ?: "unknown"
        val ts = req.num("timestamp") ?: fallbackTs
        val promptChars = totalTextLength(req.get("message"))
        val resultMeta = req.obj("result")?.get("metadata")
        val responseChars = totalTextLength(req.get("response")) + totalTextLength(resultMeta)
        if (promptChars == 0L && responseChars == 0L) continue
        out += RawUsage(
            sessionId = sessionId,
            provider = Provider.COPILOT,
            workspaceStorageDir = storageDir.absolutePath,
            timestamp = ts,
            model = model,
            inputTokens = estimateTokens(promptChars, charsPerToken),
            outputTokens = estimateTokens(responseChars, charsPerToken),
            cachedTokens = 0,
            cacheWriteTokens = 0,
            estimated = true,
        )
    }
    return out
}

// ---------------------------------------------------------------------------
// Claude Code — exact per-request usage from ~/.claude/projects
// ---------------------------------------------------------------------------

fun findClaudeCodeFiles(root: File): List<File> {
    val files = mutableListOf<File>()
    root.listFiles { f -> f.isDirectory }?.forEach { proj ->
        proj.listFiles { f -> f.name.endsWith(".jsonl") }?.forEach { files += it }
    }
    return files
}

fun parseClaudeCode(file: File): List<RawUsage> {
    val byMessage = LinkedHashMap<String, RawUsage>()
    val fallbackSession = file.name.removeSuffix(".jsonl")
    forEachJsonLine(file) { record ->
        if (record.str("type") != "assistant") return@forEachJsonLine
        val message = record.obj("message") ?: return@forEachJsonLine
        val usage = message.obj("usage") ?: return@forEachJsonLine
        val model = message.str("model") ?: "unknown"
        if (model == "<synthetic>") return@forEachJsonLine
        val ts = parseTimestamp(record.str("timestamp")) ?: System.currentTimeMillis()
        val key = "${message.str("id") ?: ""}:${record.str("requestId") ?: byMessage.size}"
        byMessage[key] = RawUsage(
            sessionId = record.str("sessionId") ?: fallbackSession,
            provider = Provider.CLAUDE_CODE,
            folderPath = record.str("cwd"),
            timestamp = ts,
            model = model,
            inputTokens = usage.num("input_tokens") ?: 0,
            outputTokens = usage.num("output_tokens") ?: 0,
            cachedTokens = usage.num("cache_read_input_tokens") ?: 0,
            cacheWriteTokens = usage.num("cache_creation_input_tokens") ?: 0,
            estimated = false,
        )
    }
    return byMessage.values.toList()
}

// ---------------------------------------------------------------------------
// GitHub Copilot CLI — exact per-model metrics from session.shutdown events
// ---------------------------------------------------------------------------

fun findCopilotCliFiles(root: File): List<Pair<File, String>> {
    val files = mutableListOf<Pair<File, String>>()
    root.listFiles()?.forEach { entry ->
        if (entry.isFile && entry.name.endsWith(".jsonl")) {
            files += entry to entry.name.removeSuffix(".jsonl")
        } else if (entry.isDirectory) {
            val events = File(entry, "events.jsonl")
            if (events.isFile) files += events to entry.name
        }
    }
    return files
}

fun parseCopilotCli(file: File, sessionId: String, charsPerToken: Int): List<RawUsage> {
    val shutdown = mutableListOf<RawUsage>()
    var cwd: String? = null
    var repoSlug: String? = null
    var lastTs = 0L
    var currentModel = "unknown"
    val fallback = LinkedHashMap<String, LongArray>() // model -> [inputChars, outputTokens]

    fun entry(model: String) = fallback.getOrPut(model) { longArrayOf(0, 0) }

    forEachJsonLine(file) { record ->
        val type = record.str("type") ?: ""
        val data = record.obj("data") ?: com.google.gson.JsonObject()
        val ts = parseTimestamp(record.str("timestamp"))
        if (ts != null) lastTs = max(lastTs, ts)

        when (type) {
            "session.start" -> {
                val ctx = data.obj("context")
                if (cwd == null) cwd = ctx?.str("cwd")
                val repo = ctx?.str("repository")
                if (repoSlug == null && repo != null && repo.contains('/')) repoSlug = repo
            }
            "session.model_change" -> currentModel = data.str("model", "newModel") ?: currentModel
            "user.message" -> entry(currentModel)[0] += totalTextLength(data.get("content"))
            "tool.execution_complete" -> entry(data.str("model") ?: currentModel)[0] += totalTextLength(data.get("result"))
            "assistant.message" -> {
                val model = data.str("model") ?: currentModel
                currentModel = model
                val e = entry(model)
                val exactOut = data.num("outputTokens") ?: 0
                e[1] += if (exactOut > 0) exactOut else estimateTokens(
                    totalTextLength(data.get("content")) + totalTextLength(data.get("toolRequests")), charsPerToken,
                )
            }
            "session.shutdown" -> {
                val metrics = data.obj("modelMetrics")
                metrics?.entrySet()?.forEach { (model, value) ->
                    if (!value.isJsonObject) return@forEach
                    val v = value.asJsonObject
                    val usage = v.obj("usage")
                    val requests = v.obj("requests")
                    val cacheRead = usage?.num("cacheReadTokens") ?: 0
                    val totalInput = usage?.num("inputTokens") ?: 0
                    shutdown += RawUsage(
                        sessionId = sessionId,
                        provider = Provider.COPILOT_CLI,
                        folderPath = cwd,
                        repoSlug = repoSlug,
                        timestamp = ts ?: (if (lastTs > 0) lastTs else System.currentTimeMillis()),
                        model = model,
                        inputTokens = max(0, totalInput - cacheRead),
                        outputTokens = usage?.num("outputTokens") ?: 0,
                        cachedTokens = cacheRead,
                        cacheWriteTokens = usage?.num("cacheWriteTokens") ?: 0,
                        nanoCredits = (v.num("totalNanoAiu") ?: 0).takeIf { it > 0 },
                        premiumRequests = (requests?.dbl("cost") ?: 0.0).takeIf { it > 0 },
                        estimated = false,
                    )
                }
                fallback.clear()
            }
        }
    }

    if (shutdown.isNotEmpty()) return shutdown

    return fallback.mapNotNull { (model, arr) ->
        if (arr[0] == 0L && arr[1] == 0L) return@mapNotNull null
        RawUsage(
            sessionId = sessionId,
            provider = Provider.COPILOT_CLI,
            folderPath = cwd,
            repoSlug = repoSlug,
            timestamp = if (lastTs > 0) lastTs else System.currentTimeMillis(),
            model = model,
            inputTokens = estimateTokens(arr[0], charsPerToken),
            outputTokens = arr[1],
            cachedTokens = 0,
            cacheWriteTokens = 0,
            estimated = true,
        )
    }
}
