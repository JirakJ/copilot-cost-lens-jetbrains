package com.jakubjirak.copilotcostlens.data

import com.jakubjirak.copilotcostlens.model.RawUsage
import com.jakubjirak.copilotcostlens.model.RepoRef
import com.jakubjirak.copilotcostlens.model.UsageEvent
import com.jakubjirak.copilotcostlens.pricing.Pricing
import com.jakubjirak.copilotcostlens.pricing.normalizeModelId
import com.jakubjirak.copilotcostlens.sources.WorkspaceIndex
import com.jakubjirak.copilotcostlens.sources.defaultClaudeCodeRoot
import com.jakubjirak.copilotcostlens.sources.defaultCodexRoot
import com.jakubjirak.copilotcostlens.sources.defaultCopilotCliRoot
import com.jakubjirak.copilotcostlens.sources.findCodexFiles
import com.jakubjirak.copilotcostlens.sources.parseCodexUsage
import com.jakubjirak.copilotcostlens.sources.detectStorageRoots
import com.jakubjirak.copilotcostlens.sources.findChatSessions
import com.jakubjirak.copilotcostlens.sources.findClaudeCodeFiles
import com.jakubjirak.copilotcostlens.sources.findCopilotCliFiles
import com.jakubjirak.copilotcostlens.sources.findJetBrainsCopilotDbs
import java.io.File
import com.jakubjirak.copilotcostlens.sources.findVsCodeJsonl
import com.jakubjirak.copilotcostlens.sources.listWorkspaceStorageDirs
import com.jakubjirak.copilotcostlens.sources.parseChatSession
import com.jakubjirak.copilotcostlens.sources.parseClaudeCode
import com.jakubjirak.copilotcostlens.sources.defaultJetBrainsCopilotRoot
import com.jakubjirak.copilotcostlens.sources.parseCopilotCli
import com.jakubjirak.copilotcostlens.sources.parseJetBrainsCopilot
import com.jakubjirak.copilotcostlens.sources.parseVsCodeJsonl

data class StoreConfig(
    val extraStorageRoots: List<String> = emptyList(),
    /** Map of resolved repo name → user-chosen display name. */
    val repoAliases: Map<String, String> = emptyMap(),
    val claudeCodeEnabled: Boolean = true,
    val copilotCliEnabled: Boolean = true,
    val codexEnabled: Boolean = true,
    val jetbrainsCopilotEnabled: Boolean = false,
    val estimationEnabled: Boolean = true,
    val charsPerToken: Int = 4,
    val priceOverrides: Map<String, Map<String, Double>> = emptyMap(),
)

data class ScanStats(
    val providers: Map<String, Int>,
    val newestTimestamp: Long,
    val scanMs: Long,
    val filesParsed: Int,
    val errors: List<String>,
    val scannedRoots: List<String>,
)

private data class CacheEntry(val mtime: Long, val size: Long, val usages: List<RawUsage>)

/** Scans every data source, dedupes and prices the result. */
class UsageStore(@Volatile private var config: StoreConfig) {
    private val workspaceIndex = WorkspaceIndex()
    private val fileCache = HashMap<String, CacheEntry>()
    @Volatile var events: List<UsageEvent> = emptyList()
        private set
    @Volatile var stats: ScanStats = ScanStats(emptyMap(), 0, 0, 0, emptyList(), emptyList())
        private set
    @Volatile private var firstScanDone = false

    fun updateConfig(newConfig: StoreConfig) { config = newConfig }

    fun refresh(onProgress: (() -> Unit)? = null): List<UsageEvent> {
        val started = System.currentTimeMillis()
        val exact = mutableListOf<RawUsage>()
        val estimated = mutableListOf<RawUsage>()
        val errors = mutableListOf<String>()
        val scannedRoots = mutableListOf<String>()
        var filesParsed = 0
        val cfg = config
        val pricing = Pricing(cfg.priceOverrides)

        fun add(list: List<RawUsage>) {
            filesParsed++
            for (u in list) (if (u.estimated) estimated else exact) += u
        }
        // paint progressively only on the first scan (empty dashboard); later
        // refreshes publish once at the end so they don't flash partial data
        val progressive = !firstScanDone
        fun publish() {
            events = toEvents(dedupeBySession(exact, estimated), pricing)
            val providers = LinkedHashMap<String, Int>()
            var newest = 0L
            for (e in events) {
                providers[e.provider.id] = (providers[e.provider.id] ?: 0) + 1
                newest = maxOf(newest, e.timestamp)
            }
            stats = ScanStats(providers, newest, System.currentTimeMillis() - started, filesParsed, errors, scannedRoots)
            onProgress?.invoke()
        }
        fun guard(source: String, work: () -> Unit) {
            try { work() } catch (e: Exception) { errors += "$source: ${e.message}" }
            if (progressive) publish()
        }
        // serve unchanged files from an mtime+size cache so periodic rescans are cheap
        fun cached(file: File, parse: () -> List<RawUsage>): List<RawUsage> {
            if (!file.exists()) { fileCache.remove(file.path); return emptyList() }
            val key = file.path
            val mtime = file.lastModified()
            val size = file.length()
            val hit = fileCache[key]
            if (hit != null && hit.mtime == mtime && hit.size == size) return hit.usages
            val parsed = parse()
            fileCache[key] = CacheEntry(mtime, size, parsed)
            return parsed
        }

        guard("vscode") {
            val roots = detectStorageRoots(cfg.extraStorageRoots)
            scannedRoots += roots.map { it.absolutePath }
            for (root in roots) for (ws in listWorkspaceStorageDirs(root)) {
                for ((file, sid) in findVsCodeJsonl(ws)) add(cached(file) { parseVsCodeJsonl(file, sid, ws) })
                if (cfg.estimationEnabled) {
                    for (file in findChatSessions(ws)) add(cached(file) { parseChatSession(file, ws, cfg.charsPerToken) })
                }
            }
        }
        if (cfg.claudeCodeEnabled) guard("claude-code") {
            val root = defaultClaudeCodeRoot()
            scannedRoots += root.absolutePath
            for (file in findClaudeCodeFiles(root)) add(cached(file) { parseClaudeCode(file) })
        }
        if (cfg.copilotCliEnabled) guard("copilot-cli") {
            val root = defaultCopilotCliRoot()
            scannedRoots += root.absolutePath
            for ((file, sid) in findCopilotCliFiles(root)) add(cached(file) { parseCopilotCli(file, sid, cfg.charsPerToken) })
        }
        if (cfg.codexEnabled) guard("codex") {
            val root = defaultCodexRoot()
            scannedRoots += root.absolutePath
            for (file in findCodexFiles(root)) add(cached(file) { parseCodexUsage(file) })
        }
        if (cfg.jetbrainsCopilotEnabled) guard("copilot-jetbrains") {
            val root = defaultJetBrainsCopilotRoot()
            scannedRoots += root.absolutePath
            for (db in findJetBrainsCopilotDbs(root)) add(cached(db.file) { parseJetBrainsCopilot(db, cfg.charsPerToken) })
        }

        publish() // one final publish (the only one on non-first scans)
        firstScanDone = true
        return events
    }

    private fun toEvents(raw: List<RawUsage>, pricing: Pricing): List<UsageEvent> =
        raw.map { u ->
            val (credits, source) = pricing.priceUsage(u)
            UsageEvent(
                sessionId = u.sessionId,
                provider = u.provider,
                repo = resolveRepo(u),
                timestamp = u.timestamp,
                model = normalizeModelId(u.model),
                inputTokens = u.inputTokens,
                outputTokens = u.outputTokens,
                cachedTokens = u.cachedTokens,
                cacheWriteTokens = u.cacheWriteTokens,
                credits = credits,
                costSource = source,
            )
        }.sortedBy { it.timestamp }

    private fun resolveRepo(u: RawUsage): RepoRef {
        val base = baseRepo(u)
        val alias = config.repoAliases[base.name]
        return if (alias != null) base.copy(name = alias) else base
    }

    private fun baseRepo(u: RawUsage): RepoRef = when {
        u.repoSlug != null -> RepoRef(u.repoSlug, u.folderPath, u.repoSlug)
        u.folderPath != null -> workspaceIndex.resolveFolder(u.folderPath)
        u.workspaceStorageDir != null -> workspaceIndex.resolveStorage(java.io.File(u.workspaceStorageDir))
        else -> RepoRef("(unknown)")
    }
}

/** Exact data wins over estimates for the same session. */
fun dedupeBySession(exact: List<RawUsage>, estimated: List<RawUsage>): List<RawUsage> {
    val exactSessions = exact.mapTo(HashSet()) { it.sessionId }
    return exact + estimated.filter { it.sessionId !in exactSessions }
}
