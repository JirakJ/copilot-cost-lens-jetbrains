package com.jakubjirak.copilotcostlens

import com.jakubjirak.copilotcostlens.sources.folderName
import com.jakubjirak.copilotcostlens.sources.parseRemoteSlug
import com.jakubjirak.copilotcostlens.sources.readGitRemoteSlug
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WorkspaceIndexTest {
    @Test fun `parses ssh and https remotes, prefers origin`() {
        assertEquals("owner/repo", parseRemoteSlug("[remote \"origin\"]\n\turl = git@github.com:owner/repo.git\n"))
        assertEquals("g/p", parseRemoteSlug("[remote \"origin\"]\n\turl = https://gitlab.com/g/p\n"))
        assertNull(parseRemoteSlug("[core]\n\tbare = false\n"))
    }

    @Test fun `folderName skips git and worktree scaffolding`() {
        assertEquals("blog-2025", folderName("/Users/me/work/blog-2025"))
        assertEquals("repo", folderName("/Users/me/work/repo/.git"))
        assertEquals("myproj", folderName("/Users/me/work/myproj/.claude/worktrees/sleepy-mestorf-9e9b83"))
    }

    @Test fun `reads remote from normal repo and follows worktree pointer`(@TempDir tmp: Path) {
        val repo = File(tmp.toFile(), "repo")
        File(repo, ".git").mkdirs()
        File(repo, ".git/config").writeText("[remote \"origin\"]\n\turl = git@github.com:owner/repo.git\n")
        assertEquals("owner/repo", readGitRemoteSlug(repo.absolutePath))

        val wtGitdir = File(repo, ".git/worktrees/sleepy-mestorf-9e9b83").apply { mkdirs() }
        val wt = File(repo, ".claude/worktrees/sleepy-mestorf-9e9b83").apply { mkdirs() }
        File(wt, ".git").writeText("gitdir: ${wtGitdir.absolutePath}\n")
        assertEquals("owner/repo", readGitRemoteSlug(wt.absolutePath))
    }
}
