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
        val slug = readGitRemoteSlug(folderPath)
        return RepoRef(name = slug ?: File(folderPath).name, folderPath = folderPath, remoteSlug = slug)
    }
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
    val config = File(folderPath, ".git/config")
    if (!config.isFile) return null
    return try {
        parseRemoteSlug(config.readText())
    } catch (_: Exception) {
        null
    }
}

internal fun parseRemoteSlug(gitConfig: String): String? {
    val origin = Regex("\\[remote \"origin\"][^\\[]*").find(gitConfig)?.value
    val url = Regex("url\\s*=\\s*(.+)").find(origin ?: gitConfig)?.groupValues?.get(1)?.trim() ?: return null
    return Regex("(?:[:/])([^:/]+/[^:/]+?)(?:\\.git)?/?$").find(url)?.groupValues?.get(1)
}
