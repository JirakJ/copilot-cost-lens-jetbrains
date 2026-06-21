package com.jakubjirak.copilotcostlens.sources

import com.google.gson.JsonParser
import com.jakubjirak.copilotcostlens.model.RepoRef
import java.io.File
import java.net.URLDecoder

/**
 * Resolves a workspaceStorage dir or a plain folder path to a repository
 * identity: workspace.json → folder URI → git remote slug (or folder name).
 * Cached per key.
 */
class WorkspaceIndex {
    private val cache = HashMap<String, RepoRef>()

    fun resolveStorage(dir: File): RepoRef = cache.getOrPut("ws:${dir.absolutePath}") {
        val folder = readWorkspaceFolder(dir)
            ?: return@getOrPut RepoRef("(unknown) ${dir.name.take(8)}")
        resolveFolderUncached(folder)
    }

    fun resolveFolder(folderPath: String): RepoRef =
        cache.getOrPut("folder:$folderPath") { resolveFolderUncached(folderPath) }

    private fun resolveFolderUncached(folderPath: String): RepoRef {
        // Anchor to the enclosing git repository root, so a working directory that
        // points at a sub-path (a ".git/info" cwd, a nested package folder, …) is
        // attributed to the repository itself instead of producing a separate
        // bucket named after the sub-folder.
        val root = findRepoRoot(folderPath) ?: folderPath
        val slug = readGitRemoteSlug(root)
        return RepoRef(name = slug ?: folderName(root), folderPath = root, remoteSlug = slug)
    }
}

/** Nearest ancestor (inclusive) that holds a `.git` entry — the repo root. */
internal fun findRepoRoot(folderPath: String): String? {
    var dir: File? = File(folderPath)
    var depth = 0
    while (dir != null && depth < 40) {
        if (File(dir, ".git").exists()) return dir.path
        dir = dir.parentFile
        depth++
    }
    return null
}

/**
 * A human-readable folder name when there's no git remote. Skips meaningless
 * basenames (".git", a worktree dir, empty) by walking up to a real name.
 */
internal fun folderName(folderPath: String): String {
    val parts = folderPath.split('/', '\\').filter { it.isNotEmpty() }
    for (i in parts.indices.reversed()) {
        val part = parts[i]
        if (part == ".git" || part == "worktrees" || part == ".claude") continue
        if (i > 0 && parts[i - 1] == "worktrees") continue
        return part
    }
    return parts.lastOrNull() ?: folderPath
}

internal fun readWorkspaceFolder(storageDir: File): String? {
    val json = File(storageDir, "workspace.json")
    if (!json.isFile) return null
    return try {
        val parsed = JsonParser.parseString(json.readText()).asJsonObject
        val uri = sequenceOf("folder", "workspace", "configuration")
            .mapNotNull { if (parsed.has(it)) parsed.get(it).asString else null }
            .firstOrNull { it.startsWith("file://") } ?: return null
        var fsPath = URLDecoder.decode(uri.removePrefix("file://"), "UTF-8")
        if (Regex("^/[a-zA-Z]:/").containsMatchIn(fsPath)) fsPath = fsPath.substring(1)
        fsPath.removeSuffix(".code-workspace")
    } catch (_: Exception) {
        null
    }
}

internal fun readGitRemoteSlug(folderPath: String): String? {
    val config = resolveGitConfig(folderPath) ?: return null
    return try {
        parseRemoteSlug(config.readText())
    } catch (_: Exception) {
        null
    }
}

/**
 * Locate the git config that holds the remotes. Handles git worktrees, where
 * `<folder>/.git` is a file ("gitdir: …/.git/worktrees/<name>") and the remote
 * lives in the main repo's "<main>/.git/config".
 */
private fun resolveGitConfig(folderPath: String): File? {
    val dotGit = File(folderPath, ".git")
    if (dotGit.isDirectory) return File(dotGit, "config")
    if (!dotGit.isFile) return null
    return try {
        val gitdir = Regex("gitdir:\\s*(.+)").find(dotGit.readText())?.groupValues?.get(1)?.trim() ?: return null
        val abs = if (File(gitdir).isAbsolute) File(gitdir) else File(folderPath, gitdir).canonicalFile
        val marker = File.separator + "worktrees" + File.separator
        val path = abs.path
        val idx = path.lastIndexOf(marker)
        val commonDir = if (idx >= 0) File(path.substring(0, idx)) else abs
        File(commonDir, "config")
    } catch (_: Exception) {
        null
    }
}

internal fun parseRemoteSlug(gitConfig: String): String? {
    val origin = Regex("\\[remote \"origin\"][^\\[]*").find(gitConfig)?.value
    val url = Regex("url\\s*=\\s*(.+)").find(origin ?: gitConfig)?.groupValues?.get(1)?.trim() ?: return null
    return Regex("(?:[:/])([^:/]+/[^:/]+?)(?:\\.git)?/?$").find(url)?.groupValues?.get(1)
}
