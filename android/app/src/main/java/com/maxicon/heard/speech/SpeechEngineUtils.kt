package com.maxicon.heard.speech

import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

internal object SpeechEngineUtils {

    private const val DEFAULT_UNKNOWN_CONFIDENCE = 0.42f
    private const val MAX_BIAS_MATCH_BONUS_TOKENS = 3
    internal const val MAX_BIAS_TOKENS = 500
    internal val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}']+")

    fun errorRestartDelay(error: Int, consecutiveErrors: Int): Long {
        val base = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 90L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 250L
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1_000L
            else -> 130L
        }
        val backoff = when {
            consecutiveErrors <= 2 -> 1f
            consecutiveErrors <= 4 -> 1.8f
            else -> 3f
        }
        return (base * backoff).toLong().coerceAtMost(4_000L)
    }

    fun buildBiasTokenSet(biasPhrases: List<String>): Set<String> {
        if (biasPhrases.isEmpty()) return emptySet()
        return biasPhrases
            .asSequence()
            .flatMap { phrase ->
                phrase.lowercase(Locale.US).split(TOKEN_SPLIT_REGEX).asSequence()
            }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(MAX_BIAS_TOKENS)
            .toSet()
    }

    fun scoreCandidate(
        text: String,
        confidence: Float?,
        lastPartialText: String,
        biasTokens: Set<String>,
        languageTag: String
    ): Float {
        var score = (confidence ?: DEFAULT_UNKNOWN_CONFIDENCE) * 2.2f
        val tokens = text.lowercase(Locale.US).split(TOKEN_SPLIT_REGEX).filter { it.isNotBlank() }

        if (lastPartialText.isNotBlank()) {
            val partial = lastPartialText.lowercase(Locale.US)
            val current = text.lowercase(Locale.US)
            when {
                current.startsWith(partial) -> score += 0.62f
                partial.startsWith(current) -> score += 0.18f
                partial.takeLast(4).let { it.isNotBlank() && current.contains(it) } -> score += 0.08f
            }
        }

        if (biasTokens.isNotEmpty() && tokens.isNotEmpty()) {
            val matchedBiasTokens = tokens.count { it in biasTokens }
            score += matchedBiasTokens.coerceAtMost(MAX_BIAS_MATCH_BONUS_TOKENS) * 0.16f
        }

        val latinCount = text.count { it.isLatinLetter() }
        val arabicCount = text.count { it.isArabicLetter() }
        when (languageTag) {
            "en-US" -> {
                if (latinCount >= arabicCount + 3) score += 0.24f
                else if (arabicCount > latinCount) score -= 0.11f
            }
            "ur-PK" -> {
                if (arabicCount > 0) score += 0.31f
                else if (latinCount > 0) score -= 0.05f
            }
        }

        score += tokens.size.coerceAtMost(7) * 0.012f
        return score
    }

    // Maps 16-bit PCM RMS to a normalized level matching Android's onRmsChanged scale (~-2..12).
    // dBFS[-60, -10] → [-2, 10]; quiet room ≈0, speech ≈6–9. Clipped to [-2, 12].
    fun computeVadLevel(buf: ShortArray, count: Int): Float {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = buf[i].toDouble() / 32768.0
            sumSq += s * s
        }
        val rms = sqrt(sumSq / count)
        if (rms < 1e-10) return -2f
        val dbfs = (20.0 * log10(rms)).toFloat()
        return ((dbfs + 60f) * (12f / 50f) - 2f).coerceIn(-2f, 12f)
    }

    private fun Char.isArabicLetter(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.ARABIC ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
    }

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
}
