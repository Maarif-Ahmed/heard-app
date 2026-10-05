package com.maxicon.heard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTextUtilsTest {

    // ── normalizeCaptionText ──────────────────────────────────────────────────

    @Test
    fun normalize_collapsesMultipleSpaces() {
        assertEquals("hello world", CaptionTextUtils.normalizeCaptionText("hello   world"))
    }

    @Test
    fun normalize_trimsLeadingAndTrailingWhitespace() {
        assertEquals("hello", CaptionTextUtils.normalizeCaptionText("  hello  "))
    }

    @Test
    fun normalize_removesSpaceBeforeComma() {
        assertEquals("hello, world", CaptionTextUtils.normalizeCaptionText("hello , world"))
    }

    @Test
    fun normalize_removesSpaceBeforeExclamation() {
        assertEquals("hello!", CaptionTextUtils.normalizeCaptionText("hello !"))
    }

    @Test
    fun normalize_removesSpaceBeforeQuestionMark() {
        assertEquals("really?", CaptionTextUtils.normalizeCaptionText("really ?"))
    }

    @Test
    fun normalize_removesSpaceBeforeSemicolon() {
        assertEquals("first; second", CaptionTextUtils.normalizeCaptionText("first ; second"))
    }

    @Test
    fun normalize_emptyString_returnsEmpty() {
        assertEquals("", CaptionTextUtils.normalizeCaptionText(""))
    }

    @Test
    fun normalize_onlyWhitespace_returnsEmpty() {
        assertEquals("", CaptionTextUtils.normalizeCaptionText("   "))
    }

    @Test
    fun normalize_normalText_unchanged() {
        assertEquals("hello world", CaptionTextUtils.normalizeCaptionText("hello world"))
    }

    @Test
    fun normalize_combinedIssues_allFixed() {
        assertEquals("hello, world!", CaptionTextUtils.normalizeCaptionText("  hello ,  world !  "))
    }

    // ── chooseBestFinalText ───────────────────────────────────────────────────

    @Test
    fun choose_blankFinal_returnsPartial() {
        assertEquals("hello world", CaptionTextUtils.chooseBestFinalText("", "hello world"))
    }

    @Test
    fun choose_blankPartial_returnsFinal() {
        assertEquals("hello world", CaptionTextUtils.chooseBestFinalText("hello world", ""))
    }

    @Test
    fun choose_bothBlank_returnsEmpty() {
        assertEquals("", CaptionTextUtils.chooseBestFinalText("", ""))
    }

    @Test
    fun choose_partialStartsWithFinalAndShortExtension_prefersPartial() {
        // partial starts with final, at least 2 chars longer, at most 3 extra words
        assertEquals(
            "hello world now",
            CaptionTextUtils.chooseBestFinalText("hello world", "hello world now")
        )
    }

    @Test
    fun choose_partialStartsWithFinalCaseInsensitive_prefersPartial() {
        assertEquals(
            "Hello World Now",
            CaptionTextUtils.chooseBestFinalText("hello world", "Hello World Now")
        )
    }

    @Test
    fun choose_partialTooManyExtraWords_prefersFinal() {
        // 5 extra words exceeds the 3-word allowance
        assertEquals(
            "hello",
            CaptionTextUtils.chooseBestFinalText("hello", "hello world today is a great day")
        )
    }

    @Test
    fun choose_partialDoesNotStartWithFinal_returnsFinal() {
        assertEquals(
            "hello world",
            CaptionTextUtils.chooseBestFinalText("hello world", "something completely different")
        )
    }

    @Test
    fun choose_partialSameLengthAsFinal_returnsFinal() {
        // Not 2+ chars longer, so condition fails
        assertEquals(
            "hello world",
            CaptionTextUtils.chooseBestFinalText("hello world", "hello world")
        )
    }

    @Test
    fun choose_partialOnlyOneCharLonger_returnsFinal() {
        // Only 1 char longer, needs at least 2
        assertEquals(
            "hello worldx",
            CaptionTextUtils.chooseBestFinalText("hello worldx", "hello worldxy")
        )
    }

    // ── computeFinalizationDelayMs ────────────────────────────────────────────

    @Test
    fun delay_nullConfidence_multiWord_returns140ms() {
        assertEquals(140L, CaptionTextUtils.computeFinalizationDelayMs("hello world today", null))
    }

    @Test
    fun delay_nullConfidence_singleWord_returns180ms() {
        // 140 base + 40 short-utterance penalty
        assertEquals(180L, CaptionTextUtils.computeFinalizationDelayMs("hello", null))
    }

    @Test
    fun delay_highConfidence_multiWord_returns80ms() {
        assertEquals(80L, CaptionTextUtils.computeFinalizationDelayMs("hello world today", 0.95f))
    }

    @Test
    fun delay_highConfidence_singleWord_returns120ms() {
        assertEquals(120L, CaptionTextUtils.computeFinalizationDelayMs("hello", 0.95f))
    }

    @Test
    fun delay_mediumHighConfidence_multiWord_returns120ms() {
        assertEquals(120L, CaptionTextUtils.computeFinalizationDelayMs("hello world today", 0.80f))
    }

    @Test
    fun delay_mediumConfidence_multiWord_returns170ms() {
        assertEquals(170L, CaptionTextUtils.computeFinalizationDelayMs("hello world today", 0.60f))
    }

    @Test
    fun delay_lowConfidence_multiWord_returns230ms() {
        assertEquals(230L, CaptionTextUtils.computeFinalizationDelayMs("hello world today", 0.30f))
    }

    @Test
    fun delay_lowConfidence_singleWord_returns270ms() {
        assertEquals(270L, CaptionTextUtils.computeFinalizationDelayMs("hello", 0.30f))
    }

    @Test
    fun delay_shortUtterancePenaltyAppliesUpToTwoWords() {
        val oneWord = CaptionTextUtils.computeFinalizationDelayMs("hello", 0.80f)
        val twoWords = CaptionTextUtils.computeFinalizationDelayMs("hello world", 0.80f)
        val threeWords = CaptionTextUtils.computeFinalizationDelayMs("hello world today", 0.80f)
        // Both 1- and 2-word texts get +40 penalty
        assertEquals(oneWord, twoWords)
        // 3-word text does not
        assertEquals(40L, twoWords - threeWords)
    }

    @Test
    fun delay_neverExceeds280ms() {
        val delay = CaptionTextUtils.computeFinalizationDelayMs("hi", 0.10f)
        assertTrue(delay <= 280L)
    }

    @Test
    fun delay_neverBelow80ms() {
        val delay = CaptionTextUtils.computeFinalizationDelayMs("hello world today right now here", 0.99f)
        assertTrue(delay >= 80L)
    }

    @Test
    fun delay_exactlyAtConfidenceThresholds() {
        // Boundary: exactly 0.9 → high
        assertEquals(80L, CaptionTextUtils.computeFinalizationDelayMs("one two three", 0.9f))
        // Boundary: exactly 0.75 → medium-high
        assertEquals(120L, CaptionTextUtils.computeFinalizationDelayMs("one two three", 0.75f))
        // Boundary: exactly 0.55 → medium
        assertEquals(170L, CaptionTextUtils.computeFinalizationDelayMs("one two three", 0.55f))
    }
}
