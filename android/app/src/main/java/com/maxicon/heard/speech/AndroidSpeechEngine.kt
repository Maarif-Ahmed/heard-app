package com.maxicon.heard.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

sealed interface EngineEvent {
    data class Partial(val text: String) : EngineEvent
    data class Final(val text: String, val confidence: Float?) : EngineEvent
    data class Error(val code: Int) : EngineEvent
    data class Status(val message: String) : EngineEvent
    data class Vad(val active: Boolean, val levelDb: Float) : EngineEvent
}

class AndroidSpeechEngine(
    private val context: Context,
    private val preferOffline: Boolean,
    private val onEvent: (EngineEvent) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var languageTag: String = "en-US"
    private var biasPhrases: List<String> = emptyList()
    private var pendingRestart: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var speechActive = false
    private var noiseFloorDb = -2f
    private var smoothedRmsDb = -2f
    private var aboveThresholdFrames = 0
    private var belowThresholdFrames = 0
    private var lastSpeechEventAtMs: Long = 0L
    private var lastPartialText: String = ""
    private var calibratedAmbientDb: Float? = null
    private var calibratedDeltaDb: Float? = null

    private data class ResultCandidate(
        val text: String,
        val confidence: Float?
    )

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            resetVad()
            markEngineActivity()
            scheduleWatchdog()
            onEvent(EngineEvent.Status("Listening"))
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!listening) {
                return
            }
            markEngineActivity()
            updateVad(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!listening) {
                return
            }
            if (speechActive) {
                speechActive = false
                onEvent(EngineEvent.Vad(active = false, levelDb = smoothedRmsDb))
            }
        }

        override fun onError(error: Int) {
            if (!listening) {
                return
            }
            markEngineActivity()
            if (speechActive) {
                speechActive = false
                onEvent(EngineEvent.Vad(active = false, levelDb = smoothedRmsDb))
            }
            onEvent(EngineEvent.Error(error))
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SERVER -> scheduleRestart(90)

                else -> scheduleRestart(130)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) {
                return
            }
            markEngineActivity()
            selectBestFinalCandidate(results)?.let {
                lastPartialText = ""
                onEvent(
                    EngineEvent.Final(
                        text = it.text,
                        confidence = it.confidence
                    )
                )
            }
            scheduleRestart(20)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) {
                return
            }
            markEngineActivity()
            extractTopResult(partialResults)?.takeIf { it.isNotBlank() }?.let {
                val normalized = it.trim()
                if (normalized != lastPartialText) {
                    lastPartialText = normalized
                    onEvent(EngineEvent.Partial(normalized))
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun start(
        selectedLanguageTag: String,
        biasPhrases: List<String> = emptyList()
    ) {
        languageTag = selectedLanguageTag
        this.biasPhrases = biasPhrases.distinct().take(MAX_BIAS_PHRASES)
        listening = true
        lastPartialText = ""
        markEngineActivity()
        mainHandler.post {
            if (!ensureRecognizer()) {
                onEvent(EngineEvent.Error(SpeechRecognizer.ERROR_CLIENT))
                return@post
            }
            applyAudioCaptureProfile()
            clearPendingRestart()
            beginListening()
        }
    }

    fun prepare() {
        mainHandler.post {
            ensureRecognizer()
        }
    }

    fun setVadCalibration(ambientDb: Float?, speechDb: Float?) {
        calibratedAmbientDb = ambientDb?.takeIf { it.isFinite() }
        val speech = speechDb?.takeIf { it.isFinite() }
        calibratedDeltaDb = if (calibratedAmbientDb != null && speech != null) {
            (speech - calibratedAmbientDb!!).coerceAtLeast(0f)
        } else {
            null
        }
    }

    fun stop() {
        listening = false
        clearPendingRestart()
        clearWatchdog()
        lastPartialText = ""
        mainHandler.post {
            speechRecognizer?.run {
                stopListening()
                cancel()
            }
            restoreAudioCaptureProfile()
        }
    }

    fun release() {
        stop()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    private fun beginListening() {
        if (!listening) {
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && biasPhrases.isNotEmpty()) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(biasPhrases)
                )
            }
        }

        runCatching {
            speechRecognizer?.startListening(intent)
        }.onFailure {
            onEvent(EngineEvent.Error(SpeechRecognizer.ERROR_CLIENT))
            scheduleRestart(130)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!listening) {
            return
        }
        clearPendingRestart()
        val runnable = Runnable {
            if (!listening) {
                return@Runnable
            }
            beginListening()
        }
        pendingRestart = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun clearPendingRestart() {
        val runnable = pendingRestart ?: return
        mainHandler.removeCallbacks(runnable)
        pendingRestart = null
    }

    private fun scheduleWatchdog() {
        if (!listening) {
            return
        }
        clearWatchdog()
        val runnable = Runnable {
            if (!listening) {
                return@Runnable
            }
            val idleMs = SystemClock.elapsedRealtime() - lastSpeechEventAtMs
            if (idleMs >= WATCHDOG_IDLE_TIMEOUT_MS) {
                runCatching {
                    speechRecognizer?.cancel()
                }
                beginListening()
                return@Runnable
            }
            scheduleWatchdog()
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_CHECK_MS)
    }

    private fun clearWatchdog() {
        val runnable = watchdogRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        watchdogRunnable = null
    }

    private fun markEngineActivity() {
        lastSpeechEventAtMs = SystemClock.elapsedRealtime()
    }

    private fun applyAudioCaptureProfile() = Unit

    private fun restoreAudioCaptureProfile() = Unit

    private fun ensureRecognizer(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return false
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
            }
        }
        return true
    }

    private fun extractTopResult(bundle: Bundle?): String? {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
    }

    private fun selectBestFinalCandidate(bundle: Bundle?): ResultCandidate? {
        val candidates = extractCandidates(bundle)
        if (candidates.isEmpty()) {
            return null
        }

        val normalizedBiasTokens = buildBiasTokenSet()
        return candidates.maxByOrNull { candidate ->
            scoreCandidate(
                candidate = candidate,
                biasTokens = normalizedBiasTokens
            )
        }
    }

    private fun extractCandidates(bundle: Bundle?): List<ResultCandidate> {
        val results = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (results.isEmpty()) {
            return emptyList()
        }

        val confidenceScores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return results
            .take(MAX_RESULTS)
            .mapIndexed { index, text ->
                val confidence = confidenceScores
                    ?.getOrNull(index)
                    ?.takeIf { it >= 0f }
                ResultCandidate(text = text, confidence = confidence)
            }
    }

    private fun scoreCandidate(
        candidate: ResultCandidate,
        biasTokens: Set<String>
    ): Float {
        var score = (candidate.confidence ?: DEFAULT_UNKNOWN_CONFIDENCE) * 2.2f
        val candidateText = candidate.text
        val tokens = candidateText
            .lowercase(Locale.US)
            .split(TOKEN_SPLIT_REGEX)
            .filter { it.isNotBlank() }

        if (lastPartialText.isNotBlank()) {
            val partial = lastPartialText.lowercase(Locale.US)
            val current = candidateText.lowercase(Locale.US)
            if (current.startsWith(partial)) {
                score += 0.62f
            } else if (partial.startsWith(current)) {
                score += 0.18f
            } else if (partial.takeLast(4).let { it.isNotBlank() && current.contains(it) }) {
                score += 0.08f
            }
        }

        if (biasTokens.isNotEmpty() && tokens.isNotEmpty()) {
            val matchedBiasTokens = tokens.count { it in biasTokens }
            score += matchedBiasTokens.coerceAtMost(MAX_BIAS_MATCH_BONUS_TOKENS) * 0.16f
        }

        val latinCount = candidateText.count { it.isLatinLetter() }
        val arabicCount = candidateText.count { it.isArabicLetter() }
        when (languageTag) {
            "en-US" -> {
                if (latinCount >= arabicCount + 3) {
                    score += 0.24f
                } else if (arabicCount > latinCount) {
                    score -= 0.11f
                }
            }

            "ur-PK" -> {
                if (arabicCount > 0) {
                    score += 0.31f
                } else if (latinCount > 0) {
                    score -= 0.05f
                }
            }
        }

        score += tokens.size.coerceAtMost(7) * 0.012f
        return score
    }

    private fun buildBiasTokenSet(): Set<String> {
        if (biasPhrases.isEmpty()) {
            return emptySet()
        }
        return biasPhrases
            .asSequence()
            .flatMap { phrase ->
                phrase
                    .lowercase(Locale.US)
                    .split(TOKEN_SPLIT_REGEX)
                    .asSequence()
            }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(MAX_BIAS_TOKENS)
            .toSet()
    }

    private fun Char.isArabicLetter(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.ARABIC ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
    }

    private fun Char.isLatinLetter(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z'
    }

    private fun resetVad() {
        speechActive = false
        aboveThresholdFrames = 0
        belowThresholdFrames = 0
        noiseFloorDb = calibratedAmbientDb ?: -2f
        smoothedRmsDb = noiseFloorDb
    }

    private fun updateVad(rmsdB: Float) {
        val current = rmsdB.takeIf { it.isFinite() } ?: -2f
        smoothedRmsDb = if (smoothedRmsDb < -1.5f) {
            current
        } else {
            smoothedRmsDb * 0.72f + current * 0.28f
        }

        if (!speechActive) {
            val baseNoise = if (noiseFloorDb < -1.5f) {
                smoothedRmsDb
            } else {
                noiseFloorDb * 0.985f + smoothedRmsDb * 0.015f
            }
            noiseFloorDb = calibratedAmbientDb?.let { ambient ->
                baseNoise * 0.82f + ambient * 0.18f
            } ?: baseNoise
        }

        val startMargin = calibratedDeltaDb
            ?.let { (it * 0.58f).coerceIn(MIN_CALIBRATED_START_MARGIN_DB, MAX_CALIBRATED_START_MARGIN_DB) }
            ?: DEFAULT_START_MARGIN_DB
        val stopMargin = calibratedDeltaDb
            ?.let { (startMargin * 0.56f).coerceIn(MIN_CALIBRATED_STOP_MARGIN_DB, MAX_CALIBRATED_STOP_MARGIN_DB) }
            ?: DEFAULT_STOP_MARGIN_DB

        val startThreshold = noiseFloorDb + startMargin
        val stopThreshold = noiseFloorDb + stopMargin

        if (smoothedRmsDb > startThreshold) {
            aboveThresholdFrames += 1
            belowThresholdFrames = 0
        } else if (smoothedRmsDb < stopThreshold) {
            belowThresholdFrames += 1
            aboveThresholdFrames = 0
        }

        if (!speechActive && aboveThresholdFrames >= 2) {
            speechActive = true
            onEvent(EngineEvent.Vad(active = true, levelDb = smoothedRmsDb))
        } else if (speechActive && belowThresholdFrames >= 4) {
            speechActive = false
            onEvent(EngineEvent.Vad(active = false, levelDb = smoothedRmsDb))
        }
    }

    companion object {
        private const val MAX_BIAS_PHRASES = 160
        private const val MAX_RESULTS = 5
        private const val DEFAULT_UNKNOWN_CONFIDENCE = 0.42f
        private const val MAX_BIAS_MATCH_BONUS_TOKENS = 3
        private const val MAX_BIAS_TOKENS = 500
        private const val DEFAULT_START_MARGIN_DB = 4.2f
        private const val DEFAULT_STOP_MARGIN_DB = 2.1f
        private const val MIN_CALIBRATED_START_MARGIN_DB = 2.6f
        private const val MAX_CALIBRATED_START_MARGIN_DB = 4.4f
        private const val MIN_CALIBRATED_STOP_MARGIN_DB = 1.4f
        private const val MAX_CALIBRATED_STOP_MARGIN_DB = 2.5f
        private const val WATCHDOG_CHECK_MS = 1_000L
        private const val WATCHDOG_IDLE_TIMEOUT_MS = 3_000L
        private val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}']+")
    }
}
