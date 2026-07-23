package com.jakubjirak.copilotcostlens.sources

import java.io.File

/** VS Code variants that may keep a User/workspaceStorage tree. */
private val PRODUCT_DIRS = listOf("Code", "Code - Insiders", "VSCodium", "Cursor", "Windsurf")

private fun home(): File = File(System.getProperty("user.home"))

/** Existing workspaceStorage roots for this platform, across every VS Code variant. */
fun detectStorageRoots(extraRoots: List<String> = emptyList()): List<File> {
    val os = System.getProperty("os.name").lowercase()
    val bases = mutableListOf<File>()
    when {
        os.contains("mac") -> bases += File(home(), "Library/Application Support")
        os.contains("win") -> System.getenv("APPDATA")?.let { bases += File(it) }
        else -> {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            bases += if (xdg != null) File(xdg) else File(home(), ".config")
        }
    }
    val candidates = mutableListOf<File>()
    for (base in bases) for (product in PRODUCT_DIRS) {
        candidates += File(base, "$product/User/workspaceStorage")
    }
    candidates += extraRoots.map { File(it) }
    return candidates.filter { it.isDirectory }.distinctBy { it.absolutePath }
}

fun listWorkspaceStorageDirs(root: File): List<File> =
    root.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

fun defaultClaudeCodeRoot(): File = File(home(), ".claude/projects")

fun defaultCopilotCliRoot(): File = File(home(), ".copilot/session-state")

fun defaultCodexRoot(): File = File(home(), ".codex/sessions")
