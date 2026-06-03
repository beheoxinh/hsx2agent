package com.github.catatafishen.agentbridge.ui

object TokenCounter {
    private val RATIOS = mapOf(
        "claude" to 3.5,
        "gemini" to 3.5,
        "gpt" to 4.0,
        "deepseek" to 3.8,
        "llama" to 3.6,
        "mistral" to 3.8,
    )
    private const val DEFAULT_CHARS_PER_TOKEN = 4.0
    private const val IMAGE_TOKENS_PER_REF = 250
    private const val BINARY_TOKENS_PER_REF = 100

    fun estimateTokenCount(text: String, modelId: String?): Int {
        if (text.isEmpty()) return 0
        val ratio = resolveRatio(modelId)
        val count = text.codePointCount(0, text.length)
        return maxOf(1, (count / ratio).toInt())
    }

    fun estimateInputTokens(prompt: String, attachments: List<PromptAttachment>, modelId: String?): Int {
        var total = estimateTokenCount(prompt, modelId)
        for (attachment in attachments) {
            when (attachment) {
                is PromptAttachment.TextRef -> total += estimateTokenCount(attachment.text, modelId)
                is PromptAttachment.ImageRef -> total += IMAGE_TOKENS_PER_REF
                is PromptAttachment.BinaryRef -> total += BINARY_TOKENS_PER_REF
            }
        }
        return total
    }

    private fun resolveRatio(modelId: String?): Double {
        if (modelId.isNullOrEmpty()) return DEFAULT_CHARS_PER_TOKEN
        val lower = modelId.lowercase()
        for ((prefix, ratio) in RATIOS) {
            if (lower.contains(prefix)) return ratio
        }
        return DEFAULT_CHARS_PER_TOKEN
    }
}
