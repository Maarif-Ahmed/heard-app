package com.maxicon.heard.ui

internal object CaptionTextUtils {

    private val WORD_SPLIT_REGEX = Regex("\\s+")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;!?])")

    fun normalizeCaptionText(input: String): String =
        input
            .replace(WORD_SPLIT_REGEX, " ")
            .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
            .trim()

    fun chooseBestFinalText(finalText: String, currentPartial: String): String {
        if (finalText.isBlank()) return currentPartial.trim()
        val partial = currentPartial.trim()
        if (partial.isBlank()) return finalText
        val finalWords = finalText.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        val partialWords = partial.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        return when {
            partial.startsWith(finalText, ignoreCase = true) &&
                partial.length >= finalText.length + 2 &&
                partialWords <= finalWords + 3 -> partial
            else -> finalText
        }
    }

    fun computeFinalizationDelayMs(text: String, confidence: Float?): Long {
        val words = text.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        val confidenceBase = when {
            confidence == null -> 140L
            confidence >= 0.9f -> 80L
            confidence >= 0.75f -> 120L
            confidence >= 0.55f -> 170L
            else -> 230L
        }
        val shortUtterancePenalty = if (words <= 2) 40L else 0L
        return (confidenceBase + shortUtterancePenalty).coerceIn(80L, 280L)
    }
}
