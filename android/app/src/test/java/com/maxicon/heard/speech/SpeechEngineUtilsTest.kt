package com.maxicon.heard.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineUtilsTest {

    // ── errorRestartDelay ─────────────────────────────────────────────────────

    @Test
    fun errorRestartDelay_noMatch_returns90ms() {
        assertEquals(90L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_NO_MATCH, 1))
    }

    @Test
    fun errorRestartDelay_speechTimeout_returns90ms() {
        assertEquals(90L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 1))
    }

    @Test
    fun errorRestartDelay_recognizerBusy_returns250ms() {
        assertEquals(250L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 1))
    }

    @Test
    fun errorRestartDelay_serverError_returns1000ms() {
        assertEquals(1_000L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 1))
    }

    @Test
    fun errorRestartDelay_networkError_returns1000ms() {
        assertEquals(1_000L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_NETWORK, 1))
    }

    @Test
    fun errorRestartDelay_networkTimeoutError_returns1000ms() {
        assertEquals(1_000L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 1))
    }

    @Test
    fun errorRestartDelay_audioError_returns130ms() {
        assertEquals(130L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_AUDIO, 1))
    }

    @Test
    fun errorRestartDelay_clientError_returns130ms() {
        assertEquals(130L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_CLIENT, 1))
    }

    @Test
    fun errorRestartDelay_backoff_firstTwoErrorsNoMultiplier() {
        val first = SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 1)
        val second = SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 2)
        assertEquals(first, second)
        assertEquals(1_000L, first)
    }

    @Test
    fun errorRestartDelay_backoff_thirdErrorApplies1_8x() {
        // 1000 * 1.8 = 1800
        assertEquals(1_800L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 3))
    }

    @Test
    fun errorRestartDelay_backoff_fifthErrorApplies3x() {
        // 1000 * 3.0 = 3000
        assertEquals(3_000L, SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 5))
    }

    @Test
    fun errorRestartDelay_capsAt4000ms() {
        val delay = SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_SERVER, 100)
        assertTrue(delay <= 4_000L)
    }

    @Test
    fun errorRestartDelay_backoffAppliesToAllErrorTypes() {
        val base = SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 1)
        val backed = SpeechEngineUtils.errorRestartDelay(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 3)
        assertTrue(backed > base)
    }

    // ── buildBiasTokenSet ─────────────────────────────────────────────────────

    @Test
    fun buildBiasTokenSet_emptyList_returnsEmptySet() {
        assertTrue(SpeechEngineUtils.buildBiasTokenSet(emptyList()).isEmpty())
    }

    @Test
    fun buildBiasTokenSet_singlePhrase_tokenizesWords() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("hello world"))
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
    }

    @Test
    fun buildBiasTokenSet_multiplePhrases_combinesAllTokens() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("hello world", "foo bar"))
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
        assertTrue(tokens.contains("foo"))
        assertTrue(tokens.contains("bar"))
    }

    @Test
    fun buildBiasTokenSet_singleCharTokensFiltered() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("a bc def"))
        assertFalse(tokens.contains("a"))
        assertTrue(tokens.contains("bc"))
        assertTrue(tokens.contains("def"))
    }

    @Test
    fun buildBiasTokenSet_deduplicatesAcrossPhrases() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("hello world", "hello there"))
        assertEquals(1, tokens.count { it == "hello" })
    }

    @Test
    fun buildBiasTokenSet_normalizesToLowercase() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("Hello WORLD"))
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
        assertFalse(tokens.contains("Hello"))
        assertFalse(tokens.contains("WORLD"))
    }

    @Test
    fun buildBiasTokenSet_punctuationSplitsTokens() {
        val tokens = SpeechEngineUtils.buildBiasTokenSet(listOf("hello, world"))
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
    }

    // ── scoreCandidate ────────────────────────────────────────────────────────

    @Test
    fun scoreCandidate_higherConfidence_scoresHigher() {
        val high = scoreBaseline(confidence = 0.95f)
        val low = scoreBaseline(confidence = 0.30f)
        assertTrue(high > low)
    }

    @Test
    fun scoreCandidate_nullConfidence_matchesDefaultConfidence() {
        val withNull = scoreBaseline(confidence = null)
        val withDefault = scoreBaseline(confidence = 0.42f)
        assertEquals(withDefault, withNull, 0.001f)
    }

    @Test
    fun scoreCandidate_textStartsWithPartial_addsContinuityBonus() {
        val withPartial = SpeechEngineUtils.scoreCandidate(
            text = "hello world",
            confidence = 0.5f,
            lastPartialText = "hello",
            biasTokens = emptySet(),
            languageTag = "en-US"
        )
        val withoutPartial = SpeechEngineUtils.scoreCandidate(
            text = "hello world",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = emptySet(),
            languageTag = "en-US"
        )
        assertTrue(withPartial > withoutPartial)
    }

    @Test
    fun scoreCandidate_partialStartsWithText_addsSmallContinuityBonus() {
        // partial "hello world" starts with candidate "hello" — smaller bonus (0.18)
        val withPartial = SpeechEngineUtils.scoreCandidate(
            text = "hello",
            confidence = 0.5f,
            lastPartialText = "hello world",
            biasTokens = emptySet(),
            languageTag = "en-US"
        )
        val withoutPartial = SpeechEngineUtils.scoreCandidate(
            text = "hello",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = emptySet(),
            languageTag = "en-US"
        )
        assertTrue(withPartial > withoutPartial)
    }

    @Test
    fun scoreCandidate_biasTokenMatch_addsBonus() {
        val biasTokens = setOf("heard", "caption")
        val withMatch = SpeechEngineUtils.scoreCandidate(
            text = "heard something",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = biasTokens,
            languageTag = "en-US"
        )
        val withoutMatch = SpeechEngineUtils.scoreCandidate(
            text = "heard something",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = emptySet(),
            languageTag = "en-US"
        )
        assertTrue(withMatch > withoutMatch)
    }

    @Test
    fun scoreCandidate_multipleBiasMatches_cappedAtThreeTokens() {
        val biasTokens = setOf("one", "two", "three", "four")
        val threeMatches = SpeechEngineUtils.scoreCandidate(
            text = "one two three",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = biasTokens,
            languageTag = "en-US"
        )
        val fourMatches = SpeechEngineUtils.scoreCandidate(
            text = "one two three four",
            confidence = 0.5f,
            lastPartialText = "",
            biasTokens = biasTokens,
            languageTag = "en-US"
        )
        // Four matches scores same bias bonus as three (cap at 3), but fourMatches may get
        // length bonus for extra word
        assertTrue(fourMatches >= threeMatches)
        // Bias delta should be equal (both capped at 3 tokens × 0.16)
        val extraWordBonus = 0.012f
        assertEquals(fourMatches - threeMatches, extraWordBonus, 0.001f)
    }

    @Test
    fun scoreCandidate_englishLatinText_getsScriptAffinityBonus() {
        val enScore = scoreBaseline(text = "hello world today", confidence = 0.5f, lang = "en-US")
        val urScore = scoreBaseline(text = "hello world today", confidence = 0.5f, lang = "ur-PK")
        // en-US with Latin text gets +0.24; ur-PK with Latin text gets -0.05
        assertTrue(enScore > urScore)
    }

    @Test
    fun scoreCandidate_urduArabicText_getsScriptAffinityBonus() {
        val arabicText = "آہستہ بولیں"
        val urScore = scoreBaseline(text = arabicText, confidence = 0.5f, lang = "ur-PK")
        val enScore = scoreBaseline(text = arabicText, confidence = 0.5f, lang = "en-US")
        assertTrue(urScore > enScore)
    }

    @Test
    fun scoreCandidate_longerText_scoresSlightlyHigher() {
        val short = scoreBaseline(text = "hello", confidence = 0.5f)
        val longer = scoreBaseline(text = "hello world from the caption screen right now", confidence = 0.5f)
        assertTrue(longer > short)
    }

    @Test
    fun scoreCandidate_wordLengthBonusCapsAtSeven() {
        val sevenWords = scoreBaseline(text = "one two three four five six seven", confidence = 0.5f)
        val eightWords = scoreBaseline(text = "one two three four five six seven eight", confidence = 0.5f)
        // Both should get the same word-count bonus (capped at 7)
        assertEquals(sevenWords, eightWords, 0.001f)
    }

    // ── computeVadLevel ───────────────────────────────────────────────────────

    @Test
    fun computeVadLevel_silenceBuffer_returnsMinusTwo() {
        val buf = ShortArray(256) { 0 }
        assertEquals(-2f, SpeechEngineUtils.computeVadLevel(buf, buf.size), 0.001f)
    }

    @Test
    fun computeVadLevel_fullScaleBuffer_clampedToTwelve() {
        val buf = ShortArray(256) { 32767 }
        assertEquals(12f, SpeechEngineUtils.computeVadLevel(buf, buf.size), 0.001f)
    }

    @Test
    fun computeVadLevel_amplitude100_approxPoint33() {
        val buf = ShortArray(256) { 100 }
        val level = SpeechEngineUtils.computeVadLevel(buf, buf.size)
        assertTrue("expected ~0.33, got $level", level in 0.1f..0.6f)
    }

    @Test
    fun computeVadLevel_amplitude1000_approx5() {
        val buf = ShortArray(256) { 1000 }
        val level = SpeechEngineUtils.computeVadLevel(buf, buf.size)
        assertTrue("expected ~5.1, got $level", level in 4.5f..5.8f)
    }

    @Test
    fun computeVadLevel_levelIncreasesMonotonically() {
        val amplitudes = listOf(10, 100, 1000, 10000, 30000)
        val levels = amplitudes.map { amp ->
            val buf = ShortArray(256) { amp.toShort() }
            SpeechEngineUtils.computeVadLevel(buf, buf.size)
        }
        for (i in 0 until levels.size - 1) {
            assertTrue("level[$i]=${levels[i]} should be < level[${i+1}]=${levels[i+1]}",
                levels[i] < levels[i + 1])
        }
    }

    @Test
    fun computeVadLevel_outputAlwaysInRange() {
        listOf(0, 1, 100, 1000, 16000, 32767).forEach { amp ->
            val buf = ShortArray(256) { amp.toShort() }
            val level = SpeechEngineUtils.computeVadLevel(buf, buf.size)
            assertTrue("level=$level out of [-2, 12] for amp=$amp", level in -2f..12f)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun scoreBaseline(
        text: String = "hello world",
        confidence: Float? = 0.5f,
        lang: String = "en-US"
    ) = SpeechEngineUtils.scoreCandidate(
        text = text,
        confidence = confidence,
        lastPartialText = "",
        biasTokens = emptySet(),
        languageTag = lang
    )
}
