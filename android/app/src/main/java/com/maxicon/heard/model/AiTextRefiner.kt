package com.maxicon.heard.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AiTextRefiner {
    @Volatile
    private var initialized = false

    private var prefs: SharedPreferences? = null
    private var trainedTokenMap: Map<String, String> = emptyMap()
    private val adaptiveVotes: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()
    private var adaptiveTokenMap: Map<String, String> = emptyMap()
    private var pendingVoteUpdates: Int = 0

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            trainedTokenMap = loadTrainedTokenMap(context)
            loadAdaptiveVotesFromPrefs()
            rebuildAdaptiveTokenMap()
            initialized = true
        }
    }

    fun learnedRuleCount(): Int = synchronized(this) { adaptiveTokenMap.size }

    fun learnFromObservation(
        observedText: String,
        canonicalText: String,
        confidence: Float?
    ) {
        if (!initialized) {
            return
        }
        if (observedText.isBlank() || canonicalText.isBlank()) {
            return
        }
        if (confidence != null && confidence < MIN_LEARNING_CONFIDENCE) {
            return
        }

        val observedTokens = tokenize(observedText)
        val canonicalTokens = tokenize(canonicalText)
        if (observedTokens.isEmpty() || canonicalTokens.isEmpty()) {
            return
        }

        synchronized(this) {
            var updates = 0
            observedTokens.forEachIndexed { index, observed ->
                val canonical = bestAlignedCanonicalToken(
                    observedToken = observed,
                    observedIndex = index,
                    canonicalTokens = canonicalTokens
                ) ?: return@forEachIndexed

                if (recordAdaptiveVote(observed, canonical)) {
                    updates += 1
                }
            }

            if (updates <= 0) {
                return
            }
            pendingVoteUpdates += updates
            rebuildAdaptiveTokenMap()
            if (pendingVoteUpdates >= SAVE_EVERY_UPDATES) {
                persistAdaptiveVotes()
                pendingVoteUpdates = 0
            }
        }
    }

    fun flush() {
        synchronized(this) {
            if (pendingVoteUpdates <= 0) {
                return
            }
            persistAdaptiveVotes()
            pendingVoteUpdates = 0
        }
    }

    fun refine(input: String, detectedLanguageTag: String): String {
        if (input.isBlank()) {
            return input
        }

        val applyEnglishCleanup = detectedLanguageTag == "en-US"
        if (!applyEnglishCleanup) {
            return input
        }

        val output = StringBuilder(input.length + 16)
        var cursor = 0
        TOKEN_REGEX.findAll(input).forEach { match ->
            output.append(input, cursor, match.range.first)
            val rawToken = match.value
            val normalized = rawToken.lowercase(Locale.US)
            val corrected = adaptiveTokenMap[normalized]
                ?: trainedTokenMap[normalized]
                ?: learnedTokenMap[normalized]
            if (corrected != null) {
                output.append(preserveTokenStyle(rawToken, corrected))
            } else {
                output.append(rawToken)
            }
            cursor = match.range.last + 1
        }
        output.append(input.substring(cursor))
        return output.toString()
    }

    private fun bestAlignedCanonicalToken(
        observedToken: String,
        observedIndex: Int,
        canonicalTokens: List<String>
    ): String? {
        var bestToken: String? = null
        var bestDistance = Int.MAX_VALUE
        val start = (observedIndex - ALIGNMENT_WINDOW).coerceAtLeast(0)
        val end = (observedIndex + ALIGNMENT_WINDOW).coerceAtMost(canonicalTokens.lastIndex)
        for (index in start..end) {
            val candidate = canonicalTokens[index]
            val distance = levenshteinDistanceBounded(
                left = observedToken,
                right = candidate,
                maxDistance = MAX_EDIT_DISTANCE
            )
            if (distance in 1..MAX_EDIT_DISTANCE && distance < bestDistance) {
                bestDistance = distance
                bestToken = candidate
            }
        }
        return bestToken
    }

    private fun recordAdaptiveVote(observedToken: String, canonicalToken: String): Boolean {
        val observed = observedToken.lowercase(Locale.US)
        val canonical = canonicalToken.lowercase(Locale.US)
        if (!isLearningCandidate(observed, canonical)) {
            return false
        }

        val targets = adaptiveVotes.getOrPut(observed) { linkedMapOf() }
        val current = targets[canonical] ?: 0
        if (current >= MAX_VOTES_PER_PAIR) {
            return false
        }

        targets[canonical] = current + 1
        trimAdaptiveVotesIfNeeded()
        return true
    }

    private fun isLearningCandidate(observed: String, canonical: String): Boolean {
        if (observed == canonical) {
            return false
        }
        if (observed.length < MIN_TOKEN_LEN || canonical.length < MIN_TOKEN_LEN) {
            return false
        }
        if (abs(observed.length - canonical.length) > MAX_LENGTH_DELTA) {
            return false
        }
        if (!observed.firstOrNull().isValidLearningStart() || !canonical.firstOrNull().isValidLearningStart()) {
            return false
        }
        if (observed in BLOCKED_TOKENS || canonical in BLOCKED_TOKENS) {
            return false
        }
        if (!observed.all { it.isLearningChar() } || !canonical.all { it.isLearningChar() }) {
            return false
        }
        val distance = levenshteinDistanceBounded(observed, canonical, MAX_EDIT_DISTANCE)
        return distance in 1..MAX_EDIT_DISTANCE
    }

    private fun Char?.isValidLearningStart(): Boolean {
        return this != null && (this in 'a'..'z' || this in 'A'..'Z')
    }

    private fun Char.isLearningChar(): Boolean {
        return this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '\''
    }

    private fun tokenize(input: String): List<String> {
        return TOKEN_REGEX.findAll(input)
            .map { it.value.lowercase(Locale.US) }
            .toList()
    }

    private fun rebuildAdaptiveTokenMap() {
        val refreshed = linkedMapOf<String, String>()
        adaptiveVotes.forEach { (observed, targetVotes) ->
            val total = targetVotes.values.sum()
            if (total < MIN_TOTAL_VOTES_TO_ACTIVATE) {
                return@forEach
            }
            val winner = targetVotes.maxByOrNull { it.value } ?: return@forEach
            val winnerVotes = winner.value
            if (winnerVotes < MIN_TOP_VOTES_TO_ACTIVATE) {
                return@forEach
            }
            val dominance = winnerVotes.toFloat() / total.toFloat()
            if (dominance < MIN_DOMINANCE_RATIO) {
                return@forEach
            }
            refreshed[observed] = winner.key
        }
        adaptiveTokenMap = refreshed
    }

    private fun trimAdaptiveVotesIfNeeded() {
        if (adaptiveVotes.size <= MAX_OBSERVED_TOKENS) {
            return
        }
        val removeCount = adaptiveVotes.size - MAX_OBSERVED_TOKENS
        adaptiveVotes.entries
            .sortedBy { entry -> entry.value.values.sum() }
            .take(removeCount)
            .map { it.key }
            .forEach { adaptiveVotes.remove(it) }
    }

    private fun persistAdaptiveVotes() {
        val snapshot = adaptiveVotes.mapValues { (_, votes) -> votes.toMap() }
        val jsonString = encodeVotes(snapshot)
        prefs?.edit {
            putString(PREF_KEY_ADAPTIVE_VOTES, jsonString)
        }
    }

    private fun loadAdaptiveVotesFromPrefs() {
        adaptiveVotes.clear()
        val raw = prefs?.getString(PREF_KEY_ADAPTIVE_VOTES, null) ?: return
        if (raw.isBlank()) {
            return
        }
        val parsed = decodeVotes(raw)
        parsed.forEach { (observed, targets) ->
            if (observed.isBlank() || targets.isEmpty()) {
                return@forEach
            }
            val cleanTargets = targets
                .filterKeys { it.isNotBlank() }
                .filterValues { it > 0 }
                .toMutableMap()
            if (cleanTargets.isNotEmpty()) {
                adaptiveVotes[observed] = cleanTargets
            }
        }
    }

    private fun encodeVotes(votes: Map<String, Map<String, Int>>): String {
        val json: JsonObject = buildJsonObject {
            votes.forEach { (observed, targets) ->
                put(observed, buildJsonObject {
                    targets.forEach { (canonical, count) ->
                        if (count > 0) {
                            put(canonical, JsonPrimitive(count))
                        }
                    }
                })
            }
        }
        return json.toString()
    }

    private fun decodeVotes(raw: String): Map<String, Map<String, Int>> {
        return runCatching {
            Json.parseToJsonElement(raw)
                .jsonObject
                .mapValues { (_, innerValue) ->
                    innerValue.jsonObject
                        .mapNotNull { (canonical, countValue) ->
                            val count = countValue.jsonPrimitive.intOrNull
                            if (canonical.isBlank() || count == null || count <= 0) {
                                null
                            } else {
                                canonical to count
                            }
                        }
                        .toMap()
                }
        }.getOrElse { emptyMap() }
    }

    private fun levenshteinDistanceBounded(left: String, right: String, maxDistance: Int): Int {
        if (left == right) {
            return 0
        }
        if (left.isEmpty()) {
            return right.length
        }
        if (right.isEmpty()) {
            return left.length
        }
        if (abs(left.length - right.length) > maxDistance) {
            return maxDistance + 1
        }

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
                if (current[j] < rowMin) {
                    rowMin = current[j]
                }
            }
            if (rowMin > maxDistance) {
                return maxDistance + 1
            }
            val tmp = previous
            previous = current
            current = tmp
        }

        return previous[right.length]
    }

    private fun preserveTokenStyle(source: String, correctedLower: String): String {
        return when {
            source.all { it.isUpperCase() } -> correctedLower.uppercase(Locale.US)
            source.firstOrNull()?.isUpperCase() == true -> correctedLower.replaceFirstChar { it.titlecase(Locale.US) }
            else -> correctedLower
        }
    }

    private fun loadTrainedTokenMap(context: Context): Map<String, String> {
        val rawJson = runCatching {
            context.assets.open("token_model.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyMap()

        return runCatching {
            Json.parseToJsonElement(rawJson)
                .jsonObject
                .mapNotNull { (key, value) ->
                    val canonical = value.jsonPrimitive.contentOrNull?.trim()?.lowercase(Locale.US)
                    val observed = key.trim().lowercase(Locale.US)
                    if (observed.isBlank() || canonical.isNullOrBlank()) {
                        null
                    } else {
                        observed to canonical
                    }
                }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    // Generated from seed data and then hand-pruned for captioning quality.
    private val learnedTokenMap = mapOf(
        "mike" to "mic",
        "mik" to "mic",
        "microfone" to "microphone",
        "microphon" to "microphone",
        "microfhone" to "microphone",
        "captian" to "caption",
        "capshun" to "caption",
        "captin" to "caption",
        "kaption" to "caption",
        "screan" to "screen",
        "scren" to "screen",
        "skreen" to "screen",
        "langauge" to "language",
        "languge" to "language",
        "englsh" to "english",
        "inglish" to "english",
        "englis" to "english",
        "urdhu" to "urdu",
        "urdo" to "urdu",
        "stob" to "stop",
        "speach" to "speech",
        "nois" to "noise",
        "acuracy" to "accuracy",
        "accurcy" to "accuracy",
        "realiable" to "reliable"
    )

    private val TOKEN_REGEX = Regex("[A-Za-z][A-Za-z0-9\\-']*")

    private const val PREFS_NAME = "ai_text_refiner"
    private const val PREF_KEY_ADAPTIVE_VOTES = "adaptive_votes_v1"
    private const val MIN_LEARNING_CONFIDENCE = 0.62f
    private const val MIN_TOKEN_LEN = 4
    private const val MAX_LENGTH_DELTA = 2
    private const val MAX_EDIT_DISTANCE = 2
    private const val ALIGNMENT_WINDOW = 1
    private const val MIN_TOTAL_VOTES_TO_ACTIVATE = 5
    private const val MIN_TOP_VOTES_TO_ACTIVATE = 4
    private const val MIN_DOMINANCE_RATIO = 0.72f
    private const val SAVE_EVERY_UPDATES = 8
    private const val MAX_VOTES_PER_PAIR = 200
    private const val MAX_OBSERVED_TOKENS = 1_200

    private val BLOCKED_TOKENS = setOf(
        "this", "that", "with", "from", "have", "will", "your", "please", "thank",
        "kuch", "mein", "main", "nahi", "haan", "theek", "kara", "karna"
    )
}
