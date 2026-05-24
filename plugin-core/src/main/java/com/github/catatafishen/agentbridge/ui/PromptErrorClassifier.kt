package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.agent.AgentException

/**
 * Pure error classification and quick-reply detection for prompt errors.
 * Extracted from [PromptOrchestrator] to enable unit testing without UI dependencies.
 */
object PromptErrorClassifier {

    private val QUICK_REPLY_TAG_REGEX = Regex("\\[\\s*quick-reply:\\s*([^]]+)]")

    /**
     * Classification result for a prompt error — all decisions are captured as data,
     * so the UI layer can act on them without re-analyzing the exception.
     */
    data class Classification(
        val isCancelled: Boolean,
        val isAuthError: Boolean,
        val isRecoverable: Boolean,
        val isProcessCrashWithRecovery: Boolean,
        val shouldRestorePrompt: Boolean,
        val displayMessage: String,
    )

    /**
     * Classifies a prompt error into a set of boolean decisions and a display message.
     *
     * @param exception the thrown exception
     * @param turnHadContent whether the agent produced any content before the error
     * @param isAuthenticationError predicate to check if a message indicates an auth failure
     * @param isClientHealthy whether the agent client is still responsive
     */
    fun classify(
        exception: Exception,
        turnHadContent: Boolean,
        isAuthenticationError: (String) -> Boolean,
        isClientHealthy: Boolean,
    ): Classification {
        val isCancelled = exception is InterruptedException
            || exception.cause is InterruptedException

        var msg = if (isCancelled) "Request cancelled"
        else exception.message ?: "Unknown error"

        // Walk the cause chain looking for an authentication error
        var isAuthError = false
        var cause: Throwable? = exception
        while (cause != null) {
            val causeMsg = cause.message ?: ""
            if (isAuthenticationError(causeMsg)) {
                msg = causeMsg
                isAuthError = true
                break
            }
            cause = cause.cause
        }

        // For ACP errors, ensure the message is descriptive
        if (exception is AgentException && !msg.startsWith("(")) {
            msg = "ACP error: $msg"
        }

        val isRecoverable = isCancelled
            || (exception is AgentException && exception.isRecoverable)

        // Agent process crashed but already recovered — preserve session
        val isProcessCrashWithRecovery = !isCancelled
            && generateSequence(exception as Throwable?) { it.cause }.any {
            it.message?.contains("process exited unexpectedly", ignoreCase = true) == true
        }
            && isClientHealthy

        val shouldRestorePrompt = !turnHadContent

        return Classification(
            isCancelled = isCancelled,
            isAuthError = isAuthError,
            isRecoverable = isRecoverable,
            isProcessCrashWithRecovery = isProcessCrashWithRecovery,
            shouldRestorePrompt = shouldRestorePrompt,
            displayMessage = msg,
        )
    }

    /**
     * Detailed classification for an "empty turn" — when the agent returns end_turn
     * without producing any content (text, tool calls, or thoughts).
     */
    fun classifyEmptyTurn(
        agentName: String,
        pendingBannerMessage: String?,
        isClientHealthy: Boolean
    ): String {
        if (!isClientHealthy) {
            return "Session lost — the $agentName process exited unexpectedly. " +
                "Your session has been reset. Please resend your message to continue."
        }

        if (pendingBannerMessage != null) {
            val msg = pendingBannerMessage.lowercase()
            val cleanBanner = pendingBannerMessage.removePrefix("\u26a0").trim()

            return when {
                msg.contains("safety") || msg.contains("filter") || msg.contains("policy") ->
                    "Content filtered — $agentName blocked the response due to safety or policy filters. " +
                        "Note: \"$cleanBanner\". Your session has been reset."

                msg.contains("rate") || msg.contains("quota") || msg.contains("limit") ->
                    "Capacity reached — $agentName is rate-limited or you have exceeded your quota. " +
                        "Note: \"$cleanBanner\". Your session has been reset."

                msg.contains("timeout") || msg.contains("connection") || msg.contains("unreachable") ->
                    "Network error — $agentName timed out or lost connection to the server. " +
                        "Note: \"$cleanBanner\". Your session has been reset."

                msg.contains("model") || msg.contains("invalid") || msg.contains("unknown") ->
                    "Model error — $agentName could not use the selected model. " +
                        "Note: \"$cleanBanner\". Your session has been reset."

                else -> "Session interrupted — $agentName returned an empty response with this note: \"$cleanBanner\". " +
                    "Your session has been reset. Please resend your message to continue."
            }
        }

        // Known agent specific hints without explicit banners
        if (agentName.contains("OpenCode", ignoreCase = true)) {
            return "Session corrupted — $agentName returned an empty response (likely a history compaction error). " +
                "Your session has been reset to clear internal state. Your last message has been restored."
        }

        if (agentName.contains("Claude", ignoreCase = true)) {
            return "Session limit — $agentName returned an empty response. " +
                "This usually indicates a full context window or a rate limit interruption. " +
                "Your session has been reset. Your last message has been restored."
        }

        if (agentName.contains("Junie", ignoreCase = true) || agentName.contains("Kiro", ignoreCase = true)) {
            return "Protocol error — $agentName returned an empty turn. This can happen if the agent script " +
                "encounters an unhandled internal exception. Your session has been reset."
        }

        if (agentName.equals("Pi", ignoreCase = true) || agentName.contains("Pi ", ignoreCase = true)) {
            return "Pi returned an empty response — the subprocess may have crashed before completing the turn, " +
                "or the configured provider rejected the request. Check ~/.pi/agent for the latest session log. " +
                "Your session has been reset and your last message restored."
        }

        return "Session not resumed — $agentName returned an empty response. " +
            "Your session has been reset. Your last message has been restored to the input box."
    }

    /**
     * Detects `[quick-reply: opt1 | opt2]` tags in response text and returns the parsed options.
     * Returns an empty list if no quick-reply tag is found.
     */
    fun detectQuickReplies(responseText: String): List<String> {
        val match = QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Returns true if the exception cause chain contains a non-recoverable [AgentException],
     * indicating the agent CLI binary was not found.
     */
    fun isCLINotFoundError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AgentException && !cause.isRecoverable) return true
            cause = cause.cause
        }
        return false
    }
}
