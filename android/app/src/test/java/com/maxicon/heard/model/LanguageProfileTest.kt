package com.maxicon.heard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageProfileTest {
    @Test
    fun availableProfiles_excludesAutoMode() {
        assertFalse(LanguageProfile.all.any { it.label.startsWith("Auto", ignoreCase = true) })
        assertTrue(LanguageProfile.all.contains(LanguageProfile.English))
        assertTrue(LanguageProfile.all.contains(LanguageProfile.Urdu))
    }

    @Test
    fun englishProfile_detectsEnglish_withoutAdaptiveSuggestion() {
        val result = LanguageProfile.English.processCaption(
            input = "please start the caption screen now",
            currentRecognizerTag = "ur-PK"
        )

        assertEquals("en-US", result.detectedLanguageTag)
        assertEquals(null, result.suggestedRecognizerTag)
    }

    @Test
    fun urduProfile_detectsUrdu_withoutAdaptiveSuggestion() {
        val result = LanguageProfile.Urdu.processCaption(
            input = "\u0622\u06c1\u0633\u062a\u06c1 \u0628\u0648\u0644\u06cc\u06ba",
            currentRecognizerTag = "en-US"
        )

        assertEquals("ur-PK", result.detectedLanguageTag)
        assertEquals(null, result.suggestedRecognizerTag)
    }

    @Test
    fun romanUrduProfile_transliteratesUrduScript() {
        val result = LanguageProfile.RomanUrdu.processCaption(
            input = "\u0622\u067e \u06a9\u06cc \u0622\u0648\u0627\u0632 clear \u0646\u06c1\u06cc\u06ba",
            currentRecognizerTag = "ur-PK"
        )

        assertFalse(result.text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC })
        assertTrue(result.text.contains("clear"))
    }
}
