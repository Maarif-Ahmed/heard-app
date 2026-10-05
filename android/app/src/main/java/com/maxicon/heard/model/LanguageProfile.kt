package com.maxicon.heard.model

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.util.Locale

const val MIXED_LANGUAGE_TAG = "ur+en"

enum class DisplayMode {
    NATIVE,
    ROMANIZED
}

data class CaptionProcessingResult(
    val text: String,
    val detectedLanguageTag: String,
    val suggestedRecognizerTag: String?
)

data class LanguageProfile(
    val label: String,
    val recognizerTag: String,
    val displayMode: DisplayMode,
    val biasPhrases: List<String> = emptyList(),
    val adaptiveRecognizerTags: List<String> = emptyList()
) {
    fun isAdaptiveBilingual(): Boolean = adaptiveRecognizerTags.isNotEmpty()

    fun initialRecognizerTag(): String = adaptiveRecognizerTags.firstOrNull() ?: recognizerTag

    fun processCaption(input: String, currentRecognizerTag: String = recognizerTag): CaptionProcessingResult {
        val raw = normalizeInputForProcessing(input)
        if (raw.isBlank()) {
            return CaptionProcessingResult(
                text = "",
                detectedLanguageTag = currentRecognizerTag,
                suggestedRecognizerTag = null
            )
        }

        val analysis = MixedLanguageAnalyzer.analyze(raw, currentRecognizerTag)
        val rendered = when (displayMode) {
            DisplayMode.NATIVE -> raw
            DisplayMode.ROMANIZED -> Romanizer.toRomanUrduPreservingEnglish(raw)
        }
        val normalizedRendered = normalizeInputForProcessing(rendered)

        val refined = if (analysis.detectedLanguageTag == "en-US") {
            AiTextRefiner.refine(
                input = normalizedRendered,
                detectedLanguageTag = analysis.detectedLanguageTag
            )
        } else {
            normalizedRendered
        }

        return CaptionProcessingResult(
            text = refined,
            detectedLanguageTag = analysis.detectedLanguageTag,
            suggestedRecognizerTag = if (isAdaptiveBilingual()) analysis.suggestedRecognizerTag else null
        )
    }

    companion object {
        private val commonAssistiveBias = listOf(
            "Heard Accessibility STT App",
            "caption",
            "mic testing",
            "microphone testing",
            "reader pace mode",
            "Syed Maarif Ahmed",
            "start listening",
            "stop listening",
            "push mode"
        )

        private val englishBias = listOf(
            "please repeat",
            "speak slowly",
            "microphone",
            "Bluetooth microphone",
            "caption screen",
            "high contrast",
            "pause",
            "catch up"
        ) + commonAssistiveBias

        private val urduBias = listOf(
            "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u06cc\u06a9\u0645",
            "\u0634\u06a9\u0631\u06cc\u06c1",
            "\u0628\u0631\u0627\u06c1 \u06a9\u0631\u0645",
            "\u062f\u0648\u0628\u0627\u0631\u06c1 \u0628\u0648\u0644\u06cc\u06ba",
            "\u0622\u06c1\u0633\u062a\u06c1 \u0628\u0648\u0644\u06cc\u06ba",
            "\u0645\u0627\u0626\u06cc\u06a9\u0631\u0648\u0641\u0648\u0646",
            "\u06a9\u06cc\u067e\u0634\u0646",
            "\u0622\u0648\u0627\u0632",
            "\u0633\u06a9\u0631\u06cc\u0646"
        ) + commonAssistiveBias

        private val romanUrduBias = listOf(
            "assalam alaikum",
            "walaikum assalam",
            "shukriya",
            "barae mehrbani",
            "dobara bolen",
            "ahista bolen",
            "aap ki awaaz",
            "caption dikhao",
            "theek hai",
            "nahi",
            "haan"
        ) + commonAssistiveBias

        val English = LanguageProfile(
            label = "English",
            recognizerTag = "en-US",
            displayMode = DisplayMode.NATIVE,
            biasPhrases = englishBias
        )
        val Urdu = LanguageProfile(
            label = "Urdu",
            recognizerTag = "ur-PK",
            displayMode = DisplayMode.NATIVE,
            biasPhrases = urduBias
        )
        val RomanUrdu = LanguageProfile(
            label = "Roman Urdu",
            recognizerTag = "ur-PK",
            displayMode = DisplayMode.ROMANIZED,
            biasPhrases = romanUrduBias
        )

        val all = listOf(English, Urdu, RomanUrdu)

        private fun normalizeInputForProcessing(input: String): String {
            return input
                .replace(MULTI_SPACE_REGEX, " ")
                .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
                .replace("\u060C", ",")
                .replace("\u06D4", ".")
                .replace("\u061F", "?")
                .trim()
        }

        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;!?])")
    }
}

private data class LanguageAnalysis(
    val detectedLanguageTag: String,
    val suggestedRecognizerTag: String?
)

private object MixedLanguageAnalyzer {
    private val romanUrduHints = setOf(
        "aap", "ap", "tum", "tm", "mein", "main", "mai", "hai", "haan", "nahi", "nahin",
        "kyun", "kya", "dobara", "ahista", "bolen", "awaaz", "theek", "shukriya", "walaikum",
        "assalam", "kar", "kuch", "raha", "rahi", "rahe", "hoon", "hain", "mujhe", "tumhe"
    )

    private val englishHints = setOf(
        "the", "is", "are", "was", "were", "and", "for", "with", "without", "please", "repeat",
        "slowly", "start", "stop", "screen", "caption", "mode", "session", "language",
        "microphone", "voice", "noise", "reading", "speed", "back", "next", "catch", "up",
        "developed", "query", "mail", "english", "urdu", "live", "pause", "resume"
    )

    fun analyze(rawText: String, currentRecognizerTag: String): LanguageAnalysis {
        val words = TOKEN_REGEX.findAll(rawText)
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()

        if (words.isEmpty()) {
            return LanguageAnalysis(
                detectedLanguageTag = currentRecognizerTag,
                suggestedRecognizerTag = null
            )
        }

        var arabicWordCount = 0
        var latinWordCount = 0
        var romanUrduCount = 0
        var englishStrongCount = 0

        words.forEach { token ->
            val normalized = token.lowercase(Locale.US)
            val hasArabic = containsArabicScript(token)
            val hasLatin = token.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' }
            if (hasArabic) {
                arabicWordCount += 1
            }
            if (hasLatin) {
                latinWordCount += 1
                if (normalized in romanUrduHints) {
                    romanUrduCount += 1
                }
                if (normalized in englishHints || looksLikeEnglish(normalized)) {
                    englishStrongCount += 1
                }
            }
        }

        val urduScore = arabicWordCount * 3 + romanUrduCount
        val englishScore = englishStrongCount * 2 + (latinWordCount - romanUrduCount).coerceAtLeast(0)

        val detectedLanguageTag = when {
            arabicWordCount > 0 && latinWordCount > 0 -> MIXED_LANGUAGE_TAG
            urduScore >= englishScore + 2 -> "ur-PK"
            englishScore >= urduScore + 2 -> "en-US"
            arabicWordCount > 0 -> "ur-PK"
            latinWordCount > 0 -> if (romanUrduCount >= englishStrongCount) "ur-PK" else "en-US"
            else -> currentRecognizerTag
        }

        val suggestedRecognizerTag = when {
            englishStrongCount >= 3 && englishStrongCount >= romanUrduCount + 1 -> "en-US"
            urduScore >= englishScore + 3 -> "ur-PK"
            englishScore >= urduScore + 4 -> "en-US"
            else -> null
        }

        return LanguageAnalysis(
            detectedLanguageTag = detectedLanguageTag,
            suggestedRecognizerTag = suggestedRecognizerTag
        )
    }

    private fun looksLikeEnglish(token: String): Boolean {
        if (token.length < 4) {
            return false
        }
        if (token in romanUrduHints) {
            return false
        }
        val vowels = token.count { it in setOf('a', 'e', 'i', 'o', 'u') }
        if (vowels == 0) {
            return false
        }
        val hasCommonEnglishPattern = token.contains("th") ||
            token.contains("ing") ||
            token.contains("tion") ||
            token.contains("ment") ||
            token.contains("ous") ||
            token.contains("ly")
        return hasCommonEnglishPattern
    }

    private fun containsArabicScript(text: String): Boolean {
        return text.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
        }
    }

    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}\\-']+")
}

private object Romanizer {
    private val urduToLatin by lazy {
        createTransliterator("Arabic-Latin; Latin-ASCII", "Any-Latin; Latin-ASCII")
    }

    private val latinToAscii by lazy {
        createTransliterator("Latin-ASCII")
    }

    private val tokenMap = mapOf(
        "mai" to "main",
        "men" to "mein",
        "nahin" to "nahi",
        "nhi" to "nahi",
        "nai" to "nahi",
        "kiya" to "kya",
        "kyon" to "kyun",
        "ap" to "aap",
        "tm" to "tum",
        "kch" to "kuch",
        "kchh" to "kuch",
        "han" to "haan",
        "hun" to "hoon",
        "kr" to "kar",
        "rha" to "raha",
        "rhi" to "rahi",
        "rhe" to "rahe",
        "hay" to "hai",
        "hy" to "hai",
        "thik" to "theek"
    )

    fun toRomanUrduPreservingEnglish(input: String): String {
        if (input.isBlank()) {
            return input
        }

        val words = input
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .split(MULTI_SPACE_REGEX)

        val converted = words.map { word ->
            val parts = TOKEN_PARTS_REGEX.matchEntire(word)
            if (parts == null) {
                sanitizeLooseToken(word)
            } else {
                val prefix = parts.groupValues[1]
                val core = parts.groupValues[2]
                val suffix = parts.groupValues[3]
                val normalizedCore = convertCore(core)
                "$prefix$normalizedCore$suffix"
            }
        }

        return converted.joinToString(" ")
            .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
            .trim()
    }

    private fun convertCore(core: String): String {
        if (core.isBlank()) {
            return core
        }
        val hasArabic = containsArabicScript(core)
        if (!hasArabic) {
            return core
        }

        val preMapped = mapUrduChars(core)
        val transliterated = transliterate(preMapped, urduToLatin)
        val ascii = transliterate(transliterated, latinToAscii)
        val normalized = Normalizer.normalize(ascii, Normalizer.Form.NFD)
            .replace(DIACRITIC_REGEX, "")
            .replace(APOSTROPHE_REGEX, "")
            .replace(NON_ASCII_TOKEN_REGEX, "")
            .lowercase(Locale.US)

        if (normalized.isBlank()) {
            return ""
        }
        return tokenMap[normalized] ?: normalized
    }

    private fun sanitizeLooseToken(token: String): String {
        return token
            .replace(NON_ASCII_LOOSE_REGEX, "")
            .trim()
    }

    private fun mapUrduChars(text: String): String {
        if (text.isEmpty()) {
            return text
        }

        val sb = StringBuilder(text.length)
        text.forEach { ch ->
            val mapped = urduCharMap[ch]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun containsArabicScript(text: String): Boolean {
        return text.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
        }
    }

    private fun createTransliterator(vararg ids: String): Transliterator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        return createTransliteratorApi29(ids)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createTransliteratorApi29(ids: Array<out String>): Transliterator? {
        ids.forEach { id ->
            val transliterator = runCatching {
                Transliterator.getInstance(id)
            }.getOrNull()
            if (transliterator != null) {
                return transliterator
            }
        }
        return null
    }

    private fun transliterate(text: String, transliterator: Transliterator?): String {
        if (text.isBlank() || transliterator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return text
        }
        return transliterateApi29(transliterator, text)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun transliterateApi29(transliterator: Transliterator, text: String): String {
        return transliterator.transliterate(text)
    }

    private val DIACRITIC_REGEX = Regex("\\p{M}+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val APOSTROPHE_REGEX = Regex("[`']")
    private val NON_ASCII_TOKEN_REGEX = Regex("[^A-Za-z0-9-]")
    private val NON_ASCII_LOOSE_REGEX = Regex("[^A-Za-z0-9\\s.,!?-]")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;!?])")
    private val TOKEN_PARTS_REGEX = Regex("^([^A-Za-z0-9]*)([A-Za-z0-9-'`]+)([^A-Za-z0-9]*)$")

    private val urduCharMap = mapOf(
        '\u0628' to "b",
        '\u067e' to "p",
        '\u062a' to "t",
        '\u0679' to "t",
        '\u062b' to "s",
        '\u062c' to "j",
        '\u0686' to "ch",
        '\u062f' to "d",
        '\u0688' to "d",
        '\u0630' to "z",
        '\u0631' to "r",
        '\u0691' to "r",
        '\u0632' to "z",
        '\u0698' to "zh",
        '\u0633' to "s",
        '\u0634' to "sh",
        '\u0635' to "s",
        '\u0636' to "z",
        '\u0637' to "t",
        '\u0638' to "z",
        '\u0639' to "a",
        '\u0641' to "f",
        '\u0642' to "q",
        '\u06a9' to "k",
        '\u06af' to "g",
        '\u0644' to "l",
        '\u0645' to "m",
        '\u06c1' to "h",
        '\u06be' to "h",
        '\u062d' to "h",
        '\u062e' to "kh",
        '\u063a' to "gh",
        '\u06ba' to "n",
        '\u0646' to "n",
        '\u064a' to "i",
        '\u0649' to "i",
        '\u06cc' to "i",
        '\u06d2' to "e",
        '\u0626' to "i",
        '\u0624' to "o",
        '\u0648' to "o",
        '\u0627' to "a",
        '\u0622' to "aa",
        '\u0625' to "i",
        '\u0623' to "a",
        '\u0629' to "a",
        '\u0621' to "",
        '\u064b' to "",
        '\u064c' to "",
        '\u064d' to "",
        '\u064e' to "",
        '\u064f' to "",
        '\u0650' to "",
        '\u0651' to "",
        '\u0652' to "",
        '\u0670' to "",
        '\u0640' to ""
    )
}
