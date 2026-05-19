package com.github.catatafishen.agentbridge.services

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class GitInitService(private val project: Project) {

    fun isGitRepo(): Boolean {
        val basePath = project.basePath ?: return true // Assume it is if we can't tell
        val dir = File(basePath)
        return try {
            val p = ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && output == "true"
        } catch (_: Exception) {
            false
        }
    }

    fun initializeGit(): Result<Unit> {
        val basePath = project.basePath ?: return Result.failure(Exception("Project base path not found"))
        val dir = File(basePath)

        return try {
            // 1. git init
            runCommand(dir, "git", "init")

            // 2. git add .
            runCommand(dir, "git", "add", ".")

            // 3. git commit -m "Initial commit"
            runCommand(dir, "git", "commit", "-m", "Initial first commit")

            // Refresh VFS to pick up .git directory
            VirtualFileManager.getInstance().asyncRefresh(null)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun runCommand(dir: File, vararg command: String) {
        val p = ProcessBuilder(*command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw Exception("Command failed: ${command.joinToString(" ")}\nOutput: $output")
        }
    }

    companion object {
        fun getInstance(project: Project): GitInitService =
            PlatformApiCompat.getService(project, GitInitService::class.java)
    }
}
