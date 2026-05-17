package com.github.catatafishen.agentbridge.ui

/**
 * Automatically enriches user prompts that appear to be raw technical content (logs, stack traces,
 * large code blocks) without natural language instructions. This helps LLMs understand what to
 * do with the content rather than returning an empty response.
 */
object PromptEnricher {

    private val STACK_TRACE_PATTERN = Regex("(?m)^\\s*at\\s+[\\w.<>]+\\(.*\\)\\s*$")
    private val LOG_PREFIX_PATTERN = Regex("(?i)^\\[?(DEBUG|INFO|WARN|ERROR|FATAL|TRACE)\\]?")

    /**
     * Detects if the prompt is purely technical data and prepends a helpful instruction if so.
     */
    fun enrich(prompt: String, hasAttachments: Boolean): String {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty()) {
            return if (hasAttachments) {
                "The user has provided one or more file attachments for context. " +
                    "Analyze these files and wait for further instructions."
            } else {
                prompt
            }
        }

        // If it starts with a slash command, don't touch it
        if (trimmed.startsWith("/")) return prompt

        if (isRawTechnicalContent(trimmed)) {
            return "The following is raw technical content (logs, stack traces, or code) provided by the user. " +
                "Analyze this content and wait for further instructions:\n\n$prompt"
        }

        return prompt
    }

    private fun isRawTechnicalContent(text: String): Boolean {
        // Short texts are likely intentional brief instructions or queries
        if (text.length < 50) return false

        // Check for stack traces
        if (STACK_TRACE_PATTERN.containsMatchIn(text)) return true

        // Check for log-like lines at the start
        if (LOG_PREFIX_PATTERN.containsMatchIn(text)) return true

        // Heuristic: Count natural words vs technical symbols
        val words = text.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return true

        val naturalWords = words.count { it.all { char -> char.isLetter() } }
        val ratio = naturalWords.toDouble() / words.size

        // If less than 20% of words are "natural" letters, it's likely code/logs
        return ratio < 0.2
    }
}
