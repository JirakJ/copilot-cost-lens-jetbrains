package com.jakubjirak.copilotcostlens.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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

/**
 * VS Code stores chat sessions either as flat JSON or, since 1.128
 * (`chat.useLogSessionStorage`), as append-only mutation logs
 * (`chatSessions/<sessionId>.jsonl`). When both formats exist for one
 * session id, only the `.jsonl` is listed.
 */
fun findChatSessions(storageDir: File): List<File> {
    val entries = File(storageDir, "chatSessions").listFiles()?.toList() ?: return emptyList()
    val logSessions = entries.filter { it.name.endsWith(".jsonl") }
        .mapTo(HashSet()) { it.name.removeSuffix(".jsonl") }
    return entries.filter { f ->
        f.name.endsWith(".jsonl") || (f.name.endsWith(".json") && f.name.removeSuffix(".json") !in logSessions)
    }
}

fun parseChatSession(file: File, storageDir: File, charsPerToken: Int): List<RawUsage> {
    val usages = parseOneChatFormat(file, storageDir, charsPerToken)
    if (usages.isNotEmpty() || !file.name.endsWith(".jsonl")) return usages
    // Empty/corrupt/stale .jsonl (crash-truncated migration, downgrade) — fall
    // back to the sibling flat .json it shadowed in findChatSessions. No
    // double-count risk: the .jsonl contributed zero records.
    return parseOneChatFormat(File(file.parentFile, file.name.removeSuffix(".jsonl") + ".json"), storageDir, charsPerToken)
}

private fun parseOneChatFormat(file: File, storageDir: File, charsPerToken: Int): List<RawUsage> {
    if (!file.isFile) return emptyList()
    val root = runCatching {
        if (file.name.endsWith(".jsonl")) replaySessionLog(file.readText())
        else JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull() ?: return emptyList()
    val requests = if (root.has("requests") && root.get("requests").isJsonArray) root.getAsJsonArray("requests") else return emptyList()
    val sessionId = root.str("sessionId") ?: file.name.removeSuffix(".jsonl").removeSuffix(".json")
    val fallbackTs = root.num("lastMessageDate") ?: root.num("creationDate") ?: System.currentTimeMillis()
    val out = mutableListOf<RawUsage>()
    for (el in requests) {
        if (!el.isJsonObject) continue
        val req = el.asJsonObject
        val model = req.str("modelId") ?: "unknown"
        val ts = req.num("timestamp") ?: fallbackTs

        // Log-store sessions carry exact per-request usage — prefer it. Records
        // stay `estimated` so exact GitHub.copilot-chat transcripts for the
        // same session still supersede them instead of double counting.
        val exactInput = req.num("promptTokens") ?: 0
        val exactOutput = req.num("completionTokens") ?: 0
        val credits = req.dbl("copilotCredits") ?: 0.0
        if (exactInput > 0 || exactOutput > 0 || credits > 0) {
            out += RawUsage(
                sessionId = sessionId,
                provider = Provider.COPILOT,
                workspaceStorageDir = storageDir.absolutePath,
                timestamp = ts,
                model = model,
                inputTokens = exactInput,
                outputTokens = exactOutput,
                cachedTokens = 0,
                cacheWriteTokens = 0,
                nanoCredits = if (credits > 0) Math.round(credits * 1_000_000_000) else null,
                estimated = true,
            )
            continue
        }

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

/**
 * Replays a chat-session mutation log into its final state. Line format
 * (upstream objectMutationLog.ts): {kind:0,v} initial, {kind:1,k,v} set,
 * {kind:2,k,v?,i?} array push (truncate to `i` first), {kind:3,k} delete.
 * Malformed lines and failed operations are skipped.
 */
internal fun replaySessionLog(content: String): JsonObject? {
    var state: JsonElement? = null
    for (line in content.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val entry = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        runCatching {
            when (entry.num("kind")) {
                0L -> state = entry.get("v")
                1L -> applyLogSet(state, logPath(entry), entry.get("v"))
                2L -> applyLogPush(state, logPath(entry), entry.get("v"), entry.num("i"))
                3L -> applyLogSet(state, logPath(entry), null)
                else -> Unit
            }
        }
    }
    return state?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun logPath(entry: JsonObject): List<JsonPrimitive> =
    entry.get("k")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.filter { it.isJsonPrimitive }?.map { it.asJsonPrimitive } ?: emptyList()

private fun logChild(parent: JsonElement?, key: JsonPrimitive): JsonElement? = when {
    parent == null -> null
    parent.isJsonObject -> parent.asJsonObject.get(key.asString)
    parent.isJsonArray && key.isNumber -> parent.asJsonArray.let { arr ->
        val i = key.asInt
        if (i in 0 until arr.size()) arr.get(i) else null
    }
    else -> null
}

private fun logWalkToParent(state: JsonElement?, keys: List<JsonPrimitive>): JsonElement? {
    var current = state
    for (i in 0 until keys.size - 1) current = logChild(current, keys[i])
    return current?.takeIf { it.isJsonObject || it.isJsonArray }
}

private fun applyLogSet(state: JsonElement?, keys: List<JsonPrimitive>, value: JsonElement?) {
    if (keys.isEmpty()) return
    val parent = logWalkToParent(state, keys) ?: return
    val key = keys.last()
    if (parent.isJsonObject) {
        if (value == null) parent.asJsonObject.remove(key.asString) else parent.asJsonObject.add(key.asString, value)
    } else if (parent.isJsonArray && key.isNumber) {
        // arrays only ever take numeric indices; out-of-range sets are dropped
        val arr = parent.asJsonArray
        val i = key.asInt
        when {
            i in 0 until arr.size() -> arr.set(i, value ?: JsonNull.INSTANCE)
            i == arr.size() && value != null -> arr.add(value)
        }
    }
}

private fun applyLogPush(state: JsonElement?, keys: List<JsonPrimitive>, values: JsonElement?, startIndex: Long?) {
    if (keys.isEmpty()) return
    val parent = logWalkToParent(state, keys) ?: return
    val key = keys.last()
    val existing = logChild(parent, key)
    val arr = if (existing != null && existing.isJsonArray) existing.asJsonArray else JsonArray()
    // upstream only writes i <= arr.size (truncation) — clamp corrupt indices
    if (startIndex != null && startIndex >= 0 && startIndex < arr.size()) {
        while (arr.size() > startIndex) arr.remove(arr.size() - 1)
    }
    if (values != null && values.isJsonArray) values.asJsonArray.forEach { arr.add(it) }
    if (existing !== arr) applyLogSet(state, keys, arr)
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
        val data = record.obj("data") ?: JsonObject()
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

// ---------------------------------------------------------------------------
// ChatGPT Codex — exact token usage from ~/.codex/sessions rollout logs
// ---------------------------------------------------------------------------

fun findCodexFiles(root: File): List<File> {
    val files = mutableListOf<File>()
    fun walk(dir: File) {
        dir.listFiles()?.forEach { entry ->
            if (entry.isDirectory) walk(entry)
            else if (entry.isFile && entry.name.endsWith(".jsonl")) files += entry
        }
    }
    walk(root)
    return files
}

fun parseCodexUsage(file: File): List<RawUsage> {
    val out = mutableListOf<RawUsage>()
    var sessionId = file.name.removeSuffix(".jsonl")
    var folderPath: String? = null
    var model = "unknown"

    forEachJsonLine(file) { record ->
        val payload = record.obj("payload") ?: JsonObject()
        when (record.str("type")) {
            "session_meta" -> {
                sessionId = payload.str("session_id") ?: payload.str("id") ?: sessionId
                folderPath = payload.str("cwd") ?: folderPath
            }
            "turn_context" -> {
                model = payload.str("model") ?: model
                folderPath = payload.str("cwd") ?: folderPath
            }
            "event_msg" -> {
                if (payload.str("type") != "token_count") return@forEachJsonLine
                val last = payload.obj("info")?.obj("last_token_usage") ?: return@forEachJsonLine
                val input = max(0, last.num("input_tokens") ?: 0)
                val cached = minOf(input, max(0, last.num("cached_input_tokens") ?: 0))
                val output = max(0, last.num("output_tokens") ?: 0)
                if (input == 0L && output == 0L) return@forEachJsonLine
                out += RawUsage(
                    sessionId = sessionId,
                    provider = Provider.CODEX,
                    folderPath = folderPath,
                    timestamp = parseTimestamp(record.str("timestamp")) ?: System.currentTimeMillis(),
                    model = model,
                    inputTokens = input - cached,
                    outputTokens = output,
                    cachedTokens = cached,
                    cacheWriteTokens = 0,
                    estimated = false,
                )
            }
        }
    }
    return out
}
