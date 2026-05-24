package com.github.catatafishen.agentbridge.settings

enum class GitPolicy(val displayName: String, val description: String) {
    LOOSE("Features", "All git tools enabled"),
    STANDARD("Standard", "Block remote git operations (push, fetch, pull), allow local git tools"),
    SAFE("Safety", "Only read-only git tools (status, diff, log, blame, show, file history)");

    companion object {
        fun fromName(name: String): GitPolicy =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
