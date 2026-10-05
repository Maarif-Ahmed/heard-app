package com.maxicon.heard.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.maxicon.heard.CaptionForegroundService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maxicon.heard.model.AiTextRefiner
import com.maxicon.heard.model.CaptionFrame
import com.maxicon.heard.model.DisplaySettings
import com.maxicon.heard.model.FrameType
import com.maxicon.heard.model.LanguageProfile
import com.maxicon.heard.model.MIXED_LANGUAGE_TAG
import com.maxicon.heard.network.CaptionWebServer
import com.maxicon.heard.network.NetworkUtils
import com.maxicon.heard.speech.CaptureMode
import com.maxicon.heard.speech.RecognitionMode
import com.maxicon.heard.speech.SpeechPipeline
import com.maxicon.heard.speech.VoskModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CaptionViewModel(application: Application) : AndroidViewModel(application) {
    private enum class CalibrationStage {
        IDLE,
        AMBIENT,
        SPEECH
    }

    private data class SavedMicCalibration(
        val ambientDb: Float,
        val speechDb: Float,
        val deltaDb: Float,
        val quality: String,
        val guidance: String
    )

    private data class AccessibilityPrefs(
        val fontScale: Int,
        val theme: CaptionThemePreset,
        val showPartial: Boolean,
        val maxLines: Int,
        val lineSpacing: Float,
        val letterSpacing: Float,
        val keepScreenOn: Boolean,
        val fullBrightness: Boolean,
        val vibration: Boolean,
        val autoStart: Boolean
    )

    private val appContext = application.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val webServer = CaptionWebServer(appContext)
    private val speechPipeline = SpeechPipeline(appContext)
    private val lineBuffer = ArrayDeque<String>()
    private var customVocabulary: List<String> = loadCustomVocabulary()

    private var pushReleaseGraceUntilMs: Long = 0L
    private var pushSessionPrimed: Boolean = false
    private var lastPushToggleAtMs: Long = 0L

    private var isVoiceActive: Boolean = false
    private var lastVoiceActiveAtMs: Long = 0L
    private var lastVadLevelDb: Float = -2f
    private var lastPartialAtMs: Long = 0L

    private var pendingFinalizeJob: Job? = null
    private var pendingFinalText: String = ""
    private var partialFlushJob: Job? = null
    private var partialGeneration: Long = 0L
    private var activeRecognizerTag: String = DEFAULT_LANGUAGE.initialRecognizerTag()
    private var publishedLanguageTag: String = DEFAULT_LANGUAGE.initialRecognizerTag()
    private var pendingAdaptiveTag: String? = null
    private var adaptiveTagVotes: Int = 0
    private var lastAdaptiveSwitchAtMs: Long = 0L
    private var lastObservedForLearning: String = ""
    private var calibrationStage: CalibrationStage = CalibrationStage.IDLE
    private var calibrationJob: Job? = null
    private val ambientCalibrationSamples = mutableListOf<Float>()
    private val speechCalibrationSamples = mutableListOf<Float>()
    private val savedMicCalibration: SavedMicCalibration? = loadSavedMicCalibration()
    private val savedAccessibilityPrefs: AccessibilityPrefs = loadAccessibilityPrefs()

    private val _uiState = MutableStateFlow(
        run {
            val base = buildServerUrl()
            val a = savedAccessibilityPrefs
            CaptionUiState(
                serverUrl = base,
                customVocabularyText = customVocabulary.joinToString(", "),
                customVocabularyCount = customVocabulary.size,
                adaptiveRuleCount = AiTextRefiner.learnedRuleCount(),
                micCalibrationPhase = if (savedMicCalibration == null) "Not started" else "Saved",
                micCalibrationAmbientDb = savedMicCalibration?.ambientDb,
                micCalibrationSpeechDb = savedMicCalibration?.speechDb,
                micCalibrationDeltaDb = savedMicCalibration?.deltaDb,
                micCalibrationQuality = savedMicCalibration?.quality ?: "Not calibrated",
                micCalibrationGuidance = savedMicCalibration?.guidance ?: DEFAULT_CALIBRATION_GUIDANCE,
                captionFontScale = a.fontScale,
                captionTheme = a.theme,
                showPartialInBrowser = a.showPartial,
                captionMaxLines = a.maxLines,
                captionLineSpacing = a.lineSpacing,
                captionLetterSpacing = a.letterSpacing,
                keepScreenOn = a.keepScreenOn,
                fullBrightnessWhenRunning = a.fullBrightness,
                vibrationEnabled = a.vibration,
                autoStartOnLaunch = a.autoStart,
                captionDisplayUrl = base,
                showOnboarding = !preferences.getBoolean(PREF_KEY_ONBOARDING_DONE, false)
            )
        }
    )
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    init {
        speechPipeline.setVadCalibration(
            ambientDb = savedMicCalibration?.ambientDb,
            speechDb = savedMicCalibration?.speechDb
        )
        _uiState.update { it.copy(voskModelStatus = VoskModelManager.status(appContext)) }
    }

    private val speechListener = object : SpeechPipeline.Listener {
        override fun onPartial(text: String) {
            if (!shouldAcceptSpeechEvents()) {
                return
            }
            if (!shouldAcceptPartialByVad(text)) {
                return
            }
            lastObservedForLearning = normalizeCaptionText(text)

            val language = _uiState.value.selectedLanguage
            val processed = language.processCaption(
                input = text,
                currentRecognizerTag = activeRecognizerTag
            )
            val displayText = normalizeCaptionText(processed.text)
            if (displayText.isBlank()) {
                return
            }
            val detectedLanguageTag = resolvedLanguageTag(processed.detectedLanguageTag)
            publishedLanguageTag = detectedLanguageTag
            if (displayText == _uiState.value.partialLine) {
                lastPartialAtMs = System.currentTimeMillis()
                maybeSwitchAdaptiveRecognizer(processed.suggestedRecognizerTag, isFinal = false)
                schedulePartialFlush()
                return
            }

            lastPartialAtMs = System.currentTimeMillis()
            if (pendingFinalizeJob != null && isLikelyContinuation(displayText, pendingFinalText)) {
                cancelPendingFinalization()
            }

            _uiState.update {
                it.copy(partialLine = displayText)
            }
            webServer.publish(
                CaptionFrame(
                    type = FrameType.PARTIAL,
                    text = displayText,
                    languageTag = detectedLanguageTag,
                    status = _uiState.value.statusMessage
                )
            )

            schedulePartialFlush()
        }

        override fun onFinal(text: String, confidence: Float?) {
            if (!shouldAcceptSpeechEvents()) {
                return
            }
            val observedForLearning = lastObservedForLearning.ifBlank {
                normalizeCaptionText(text)
            }
            lastObservedForLearning = ""

            val language = _uiState.value.selectedLanguage
            val processed = language.processCaption(
                input = text,
                currentRecognizerTag = activeRecognizerTag
            )
            val rawFinal = normalizeCaptionText(processed.text)
            val displayText = chooseBestFinalText(rawFinal, _uiState.value.partialLine)
            if (displayText.isBlank()) {
                return
            }
            val detectedLanguageTag = resolvedLanguageTag(processed.detectedLanguageTag)
            publishedLanguageTag = detectedLanguageTag

            cancelPartialFlush()
            scheduleFinalizationCandidate(
                text = displayText,
                languageTag = detectedLanguageTag,
                status = _uiState.value.statusMessage,
                confidence = confidence
            )
            maybeLearnFromUsage(
                observedText = observedForLearning,
                canonicalText = displayText,
                detectedLanguageTag = processed.detectedLanguageTag,
                confidence = confidence
            )
            maybeSwitchAdaptiveRecognizer(processed.suggestedRecognizerTag, isFinal = true)
        }

        override fun onStatus(message: String) {
            if (message == _uiState.value.statusMessage) {
                return
            }
            _uiState.update { it.copy(statusMessage = message) }
            webServer.publish(
                CaptionFrame(
                    type = FrameType.STATUS,
                    status = message,
                    languageTag = publishedLanguageTag,
                    finalizedLines = lineBuffer.toList()
                )
            )
        }

        override fun onVad(active: Boolean, levelDb: Float) {
            isVoiceActive = active
            lastVadLevelDb = levelDb
            if (active) {
                lastVoiceActiveAtMs = System.currentTimeMillis()
            }
            _uiState.update {
                it.copy(
                    isVoiceDetected = active,
                    inputLevelDb = levelDb
                )
            }
            collectCalibrationSample(levelDb = levelDb, active = active)

            if (!active && _uiState.value.partialLine.isNotBlank()) {
                schedulePartialFlush(VAD_INACTIVE_FLUSH_MS)
            }
        }
    }

    fun setLanguage(language: LanguageProfile) {
        resetAdaptiveLanguageTracking(language)
        _uiState.update { it.copy(selectedLanguage = language) }
        if (_uiState.value.isRunning) {
            restartSpeech("Language switched to ${language.label}")
        }
    }

    fun setRecognitionMode(mode: RecognitionMode) {
        _uiState.update { it.copy(recognitionMode = mode) }
        if (_uiState.value.isRunning) {
            restartSpeech("Recognition mode switched to ${mode.label}")
        }
    }

    private var voskDownloadJob: Job? = null

    fun downloadVoskModel() {
        if (_uiState.value.voskModelStatus == VoskModelManager.Status.DOWNLOADING) return
        _uiState.update {
            it.copy(
                voskModelStatus = VoskModelManager.Status.DOWNLOADING,
                voskDownloadProgress = 0f
            )
        }
        voskDownloadJob = viewModelScope.launch {
            val success = try {
                VoskModelManager.download(appContext) { progress ->
                    _uiState.update { it.copy(voskDownloadProgress = progress) }
                }
            } catch (_: Exception) {
                false
            }
            val newStatus = if (success) VoskModelManager.Status.READY else VoskModelManager.Status.ERROR
            _uiState.update {
                it.copy(
                    voskModelStatus = newStatus,
                    voskDownloadProgress = if (success) 1f else 0f
                )
            }
            if (success && _uiState.value.recognitionMode == RecognitionMode.OFFLINE && _uiState.value.isRunning) {
                restartSpeech("Offline model ready")
            }
        }
    }

    fun cancelVoskDownload() {
        voskDownloadJob?.cancel()
        voskDownloadJob = null
        _uiState.update {
            it.copy(
                voskModelStatus = VoskModelManager.Status.NOT_DOWNLOADED,
                voskDownloadProgress = 0f
            )
        }
    }

    fun deleteVoskModel() {
        viewModelScope.launch(Dispatchers.IO) {
            VoskModelManager.delete(appContext)
            _uiState.update {
                it.copy(
                    voskModelStatus = VoskModelManager.Status.NOT_DOWNLOADED,
                    voskDownloadProgress = 0f
                )
            }
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        val previous = _uiState.value.captureMode
        if (previous == mode) {
            return
        }
        if (_uiState.value.isMicCalibrating) {
            stopCalibrationRun(
                phase = "Interrupted",
                guidance = "Calibration stopped because capture mode changed."
            )
        }

        cancelPendingFinalization()
        cancelPartialFlush()
        _uiState.update {
            it.copy(
                captureMode = mode,
                isPushToTalkActive = false,
                isVoiceDetected = false,
                inputLevelDb = -2f,
                partialLine = ""
            )
        }

        if (!_uiState.value.isRunning) {
            return
        }

        speechPipeline.stop()
        pushSessionPrimed = false
        pushReleaseGraceUntilMs = 0L

        if (mode == CaptureMode.NORMAL) {
            startSpeechPipeline()
            postStatusFromUi("Normal mode active")
        } else {
            prepareSpeechPipeline()
            pushSessionPrimed = true
            postStatusFromUi("Push mode ready. Tap once to start listening")
        }
    }

    fun updateCustomVocabularyDraft(text: String) {
        _uiState.update { it.copy(customVocabularyText = text) }
    }

    fun startMicCalibration() {
        val state = _uiState.value
        if (!state.isRunning) {
            postStatusFromUi("Start session before mic calibration")
            return
        }
        if (state.isMicCalibrating) {
            return
        }

        calibrationJob?.cancel()
        ambientCalibrationSamples.clear()
        speechCalibrationSamples.clear()
        calibrationStage = CalibrationStage.AMBIENT

        _uiState.update {
            it.copy(
                isMicCalibrating = true,
                micCalibrationPhase = "Ambient noise",
                micCalibrationProgress = 0.2f,
                micCalibrationGuidance = "Stay silent for a few seconds."
            )
        }
        postStatusFromUi("Mic calibration: stay silent")

        calibrationJob = viewModelScope.launch {
            delay(CALIBRATION_AMBIENT_MS)
            if (!_uiState.value.isMicCalibrating || calibrationStage != CalibrationStage.AMBIENT) {
                return@launch
            }

            calibrationStage = CalibrationStage.SPEECH
            _uiState.update {
                it.copy(
                    micCalibrationPhase = "Speech sample",
                    micCalibrationProgress = 0.6f,
                    micCalibrationGuidance = "Speak naturally at your normal distance."
                )
            }
            postStatusFromUi("Mic calibration: speak naturally")

            delay(CALIBRATION_SPEECH_MS)
            if (!_uiState.value.isMicCalibrating || calibrationStage != CalibrationStage.SPEECH) {
                return@launch
            }

            finalizeMicCalibration()
        }
    }

    fun cancelMicCalibration() {
        if (!_uiState.value.isMicCalibrating) {
            return
        }
        stopCalibrationRun(
            phase = "Canceled",
            guidance = "Calibration canceled."
        )
        postStatusFromUi("Mic calibration canceled")
    }

    fun applyCustomVocabulary() {
        val parsed = parseCustomVocabulary(_uiState.value.customVocabularyText)
        customVocabulary = parsed
        preferences.edit {
            putString(PREF_KEY_CUSTOM_VOCAB, parsed.joinToString("\n"))
        }

        _uiState.update {
            it.copy(
                customVocabularyText = parsed.joinToString(", "),
                customVocabularyCount = parsed.size
            )
        }

        if (_uiState.value.isRunning) {
            restartSpeech("Custom vocabulary updated (${parsed.size} terms)")
        }
    }

    fun beginPushToTalk() {
        val state = _uiState.value
        if (!state.isRunning || state.captureMode != CaptureMode.PUSH_TO_TALK || state.isPushToTalkActive) {
            return
        }

        speechPipeline.stop()
        if (!pushSessionPrimed) {
            prepareSpeechPipeline()
            pushSessionPrimed = true
        }

        pushReleaseGraceUntilMs = 0L
        cancelPendingFinalization()
        cancelPartialFlush()
        _uiState.update {
            it.copy(
                isPushToTalkActive = true,
                isVoiceDetected = false,
                inputLevelDb = -2f,
                partialLine = ""
            )
        }
        startSpeechPipeline()
        postStatusFromUi("Push mode listening")
    }

    fun endPushToTalk() {
        val state = _uiState.value
        if (!state.isRunning || state.captureMode != CaptureMode.PUSH_TO_TALK || !state.isPushToTalkActive) {
            return
        }

        val now = System.currentTimeMillis()
        pushReleaseGraceUntilMs = now + PUSH_STOP_GRACE_WINDOW_MS
        cancelPendingFinalization()
        cancelPartialFlush()

        val fallbackFinal = normalizeCaptionText(state.partialLine)
        if (fallbackFinal.isNotBlank()) {
            scheduleFinalizationCandidate(
                text = fallbackFinal,
                languageTag = publishedLanguageTag,
                status = state.statusMessage,
                confidence = null
            )
        }

        speechPipeline.stop()
        prepareSpeechPipeline()
        pushSessionPrimed = true

        _uiState.update {
            it.copy(
                isPushToTalkActive = false,
                isVoiceDetected = false,
                inputLevelDb = -2f,
                partialLine = "",
                finalizedLines = lineBuffer.toList()
            )
        }

        webServer.publish(
            CaptionFrame(
                type = FrameType.PARTIAL,
                text = "",
                languageTag = publishedLanguageTag,
                status = state.statusMessage
            )
        )
        postStatusFromUi("Push mode ready. Tap once to start listening")
    }

    fun togglePushToTalk() {
        val state = _uiState.value
        if (!state.isRunning || state.captureMode != CaptureMode.PUSH_TO_TALK) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPushToggleAtMs < PUSH_TOGGLE_DEBOUNCE_MS) {
            return
        }
        lastPushToggleAtMs = now

        if (state.isPushToTalkActive) {
            endPushToTalk()
        } else {
            beginPushToTalk()
        }
    }

    fun startCaptions() {
        if (_uiState.value.isRunning) {
            return
        }

        cancelPendingFinalization()
        cancelPartialFlush()
        lineBuffer.clear()
        isVoiceActive = false
        lastVoiceActiveAtMs = 0L
        lastVadLevelDb = -2f
        lastPartialAtMs = 0L
        lastPushToggleAtMs = 0L

        runCatching {
            webServer.startIfNeeded()
        }.onFailure {
            _uiState.update { current ->
                current.copy(statusMessage = "Web server failed: ${it.message ?: "unknown"}")
            }
            return
        }

        appContext.startForegroundService(Intent(appContext, CaptionForegroundService::class.java))

        val selected = _uiState.value.selectedLanguage
        val mode = _uiState.value.recognitionMode
        val captureMode = _uiState.value.captureMode
        resetAdaptiveLanguageTracking(selected)
        webServer.resetSession(
            status = "Preparing",
            languageTag = publishedLanguageTag
        )

        if (captureMode == CaptureMode.NORMAL) {
            startSpeechPipeline()
            pushSessionPrimed = false
        } else {
            prepareSpeechPipeline()
            pushSessionPrimed = true
        }

        _uiState.update {
            val base = buildServerUrl()
            it.copy(
                isRunning = true,
                isPushToTalkActive = false,
                isVoiceDetected = false,
                inputLevelDb = -2f,
                partialLine = "",
                finalizedLines = emptyList(),
                serverUrl = base,
                captionDisplayUrl = base,
                statusMessage = when (captureMode) {
                    CaptureMode.NORMAL -> "Running (${mode.label}, ${selected.label})"
                    CaptureMode.PUSH_TO_TALK -> "Running (Push mode ready, ${selected.label})"
                }
            )
        }

        webServer.publish(
            CaptionFrame(
                type = FrameType.STATUS,
                status = _uiState.value.statusMessage,
                languageTag = publishedLanguageTag,
                finalizedLines = lineBuffer.toList()
            )
        )
        publishDisplaySettings()
    }

    fun stopCaptions() {
        if (!_uiState.value.isRunning) {
            return
        }

        if (_uiState.value.isMicCalibrating) {
            stopCalibrationRun(
                phase = "Interrupted",
                guidance = "Calibration stopped because session ended."
            )
        }
        val state = _uiState.value
        val pendingPartial = normalizeCaptionText(state.partialLine)
        speechPipeline.stop()
        cancelPendingFinalization()
        cancelPartialFlush()
        pushSessionPrimed = false
        pushReleaseGraceUntilMs = 0L
        lastPushToggleAtMs = 0L
        isVoiceActive = false
        pendingAdaptiveTag = null
        adaptiveTagVotes = 0
        lastObservedForLearning = ""

        if (pendingPartial.isNotBlank()) {
            commitFinalLine(
                text = pendingPartial,
                languageTag = publishedLanguageTag,
                status = state.statusMessage
            )
        }

        appContext.stopService(Intent(appContext, CaptionForegroundService::class.java))

        _uiState.update {
            it.copy(
                isRunning = false,
                isPushToTalkActive = false,
                isVoiceDetected = false,
                inputLevelDb = -2f,
                partialLine = "",
                statusMessage = "Stopped"
            )
        }

        webServer.publish(
            CaptionFrame(
                type = FrameType.PARTIAL,
                text = "",
                languageTag = publishedLanguageTag,
                status = "Stopped"
            )
        )

        webServer.publish(
            CaptionFrame(
                type = FrameType.STATUS,
                status = "Stopped",
                languageTag = publishedLanguageTag,
                finalizedLines = lineBuffer.toList()
            )
        )
    }

    fun postStatusFromUi(status: String) {
        _uiState.update { it.copy(statusMessage = status) }
        webServer.publish(
            CaptionFrame(
                type = FrameType.STATUS,
                status = status,
                languageTag = publishedLanguageTag,
                finalizedLines = lineBuffer.toList()
            )
        )
    }

    private fun restartSpeech(statusMessage: String) {
        cancelPendingFinalization()
        cancelPartialFlush()
        val state = _uiState.value
        when (state.captureMode) {
            CaptureMode.NORMAL -> {
                startSpeechPipeline()
                pushSessionPrimed = false
            }

            CaptureMode.PUSH_TO_TALK -> {
                if (state.isPushToTalkActive) {
                    startSpeechPipeline()
                } else {
                    speechPipeline.stop()
                    prepareSpeechPipeline()
                }
                pushSessionPrimed = true
            }
        }
        postStatusFromUi(statusMessage)
    }

    private fun startSpeechPipeline() {
        val selected = _uiState.value.selectedLanguage
        val mode = _uiState.value.recognitionMode
        speechPipeline.start(
            mode = mode,
            languageTag = activeRecognizerTag,
            biasPhrases = activeBiasPhrases(selected),
            listener = speechListener
        )
    }

    private fun prepareSpeechPipeline() {
        val selected = _uiState.value.selectedLanguage
        val mode = _uiState.value.recognitionMode
        speechPipeline.prepare(
            mode = mode,
            languageTag = activeRecognizerTag,
            biasPhrases = activeBiasPhrases(selected),
            listener = speechListener
        )
    }

    private fun scheduleFinalizationCandidate(
        text: String,
        languageTag: String,
        status: String,
        confidence: Float?
    ) {
        cancelPendingFinalization()
        cancelPartialFlush()
        pendingFinalText = text

        val delayMs = computeFinalizationDelayMs(text, confidence)
        pendingFinalizeJob = viewModelScope.launch {
            try {
                delay(delayMs)
                if (pendingFinalText != text) {
                    return@launch
                }
                if (!shouldAcceptSpeechEvents()) {
                    return@launch
                }

                val stableText = stabilizeFinalCandidate(text)
                commitFinalLine(stableText, languageTag, status)
            } finally {
                if (pendingFinalizeJob === this) {
                    pendingFinalizeJob = null
                    if (pendingFinalText == text) {
                        pendingFinalText = ""
                    }
                }
            }
        }
    }

    private fun stabilizeFinalCandidate(text: String): String {
        val partial = normalizeCaptionText(_uiState.value.partialLine)
        if (partial.isBlank()) {
            return text
        }

        val now = System.currentTimeMillis()
        val recentPartial = now - lastPartialAtMs <= 280L
        val finalWords = text.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        val partialWords = partial.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }

        return when {
            recentPartial &&
                partial.startsWith(text, ignoreCase = true) &&
                partial.length >= text.length + 2 &&
                partialWords <= finalWords + 2 -> partial

            else -> text
        }
    }

    private fun commitFinalLine(text: String, languageTag: String, status: String) {
        val finalText = normalizeCaptionText(text)
        if (finalText.isBlank()) {
            return
        }

        cancelPartialFlush()
        if (lineBuffer.lastOrNull() == finalText) {
            _uiState.update { it.copy(partialLine = "") }
            return
        }

        appendFinalLine(finalText)
        _uiState.update {
            it.copy(
                partialLine = "",
                finalizedLines = lineBuffer.toList()
            )
        }

        webServer.publish(
            CaptionFrame(
                type = FrameType.FINAL,
                text = finalText,
                languageTag = languageTag,
                status = status,
                finalizedLines = lineBuffer.toList()
            )
        )
    }

    private fun shouldAcceptSpeechEvents(): Boolean {
        val state = _uiState.value
        if (state.captureMode != CaptureMode.PUSH_TO_TALK) {
            return true
        }
        if (state.isPushToTalkActive) {
            return true
        }
        return System.currentTimeMillis() <= pushReleaseGraceUntilMs
    }

    private fun shouldAcceptPartialByVad(text: String): Boolean {
        val state = _uiState.value
        if (state.captureMode == CaptureMode.NORMAL) {
            return true
        }
        if (state.captureMode == CaptureMode.PUSH_TO_TALK && state.isPushToTalkActive) {
            return true
        }
        if (isVoiceActive || System.currentTimeMillis() <= pushReleaseGraceUntilMs) {
            return true
        }

        val words = normalizeCaptionText(text).split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
        return words > 0
    }

    private fun isLikelyContinuation(newPartial: String, pendingFinal: String): Boolean {
        if (pendingFinal.isBlank()) {
            return false
        }
        if (newPartial == pendingFinal) {
            return true
        }
        return newPartial.startsWith(pendingFinal, ignoreCase = true) &&
            newPartial.length >= pendingFinal.length + 3
    }

    private fun chooseBestFinalText(finalText: String, currentPartial: String): String =
        CaptionTextUtils.chooseBestFinalText(finalText, currentPartial)

    private fun computeFinalizationDelayMs(text: String, confidence: Float?): Long =
        CaptionTextUtils.computeFinalizationDelayMs(text, confidence)

    private fun cancelPendingFinalization() {
        pendingFinalizeJob?.cancel()
        pendingFinalizeJob = null
        pendingFinalText = ""
    }

    private fun schedulePartialFlush(delayMs: Long = DEFAULT_PARTIAL_FLUSH_MS) {
        partialFlushJob?.cancel()
        val generation = ++partialGeneration
        partialFlushJob = viewModelScope.launch {
            delay(delayMs)
            if (generation != partialGeneration) {
                return@launch
            }
            if (pendingFinalizeJob != null) {
                return@launch
            }

            val state = _uiState.value
            val partial = normalizeCaptionText(state.partialLine)
            if (partial.isBlank()) {
                return@launch
            }

            val now = System.currentTimeMillis()
            val words = partial.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
            val staleMs = (now - lastPartialAtMs).coerceAtLeast(0L)
            val forceCommit = staleMs >= FORCE_PARTIAL_FLUSH_STALE_MS ||
                (words >= FORCE_PARTIAL_FLUSH_LONG_WORDS && staleMs >= FORCE_PARTIAL_FLUSH_LONG_STALE_MS)

            if (state.captureMode == CaptureMode.PUSH_TO_TALK && state.isPushToTalkActive) {
                if (!forceCommit) {
                    schedulePartialFlush(PUSH_TO_TALK_RETRY_FLUSH_MS)
                    return@launch
                }
            }

            val recentVoice = isVoiceActive || now - lastVoiceActiveAtMs <= RECENT_VOICE_WINDOW_MS
            if (state.captureMode == CaptureMode.NORMAL && recentVoice && !forceCommit) {
                schedulePartialFlush(ACTIVE_VOICE_RETRY_FLUSH_MS)
                return@launch
            }

            commitFinalLine(
                text = partial,
                languageTag = publishedLanguageTag,
                status = state.statusMessage
            )
        }
    }

    private fun cancelPartialFlush() {
        partialFlushJob?.cancel()
        partialFlushJob = null
    }

    private fun collectCalibrationSample(levelDb: Float, active: Boolean) {
        if (!_uiState.value.isMicCalibrating) {
            return
        }

        val sample = levelDb.takeIf { it.isFinite() } ?: return
        when (calibrationStage) {
            CalibrationStage.AMBIENT -> {
                ambientCalibrationSamples.add(sample)
                trimCalibrationSamples(ambientCalibrationSamples)
                val ambient = averageOrNull(ambientCalibrationSamples)
                _uiState.update { state ->
                    state.copy(micCalibrationAmbientDb = ambient)
                }
            }

            CalibrationStage.SPEECH -> {
                if (active || sample > ((_uiState.value.micCalibrationAmbientDb ?: sample) + 1.2f)) {
                    speechCalibrationSamples.add(sample)
                    trimCalibrationSamples(speechCalibrationSamples)
                }
                val ambient = averageOrNull(ambientCalibrationSamples)
                val speech = averageTopSlice(speechCalibrationSamples)
                val delta = if (ambient != null && speech != null) {
                    (speech - ambient).coerceAtLeast(0f)
                } else {
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        micCalibrationAmbientDb = ambient ?: state.micCalibrationAmbientDb,
                        micCalibrationSpeechDb = speech ?: state.micCalibrationSpeechDb,
                        micCalibrationDeltaDb = delta ?: state.micCalibrationDeltaDb
                    )
                }
            }

            CalibrationStage.IDLE -> Unit
        }
    }

    private fun finalizeMicCalibration() {
        val ambient = averageOrNull(ambientCalibrationSamples) ?: lastVadLevelDb
        val speech = averageTopSlice(speechCalibrationSamples) ?: ambient
        val delta = (speech - ambient).coerceAtLeast(0f)
        val quality = when {
            delta >= 6.0f -> "Excellent"
            delta >= 4.0f -> "Good"
            delta >= 2.5f -> "Fair"
            else -> "Poor"
        }
        val guidance = when {
            delta >= 6.0f -> "Mic setup is strong. Keep this distance for best results."
            delta >= 4.0f -> "Mic setup is good. Minor improvement possible by reducing background noise."
            delta >= 2.5f -> "Usable, but move mic slightly closer and reduce room noise."
            else -> "Low voice/noise separation. Move lapel mic closer and reduce external noise."
        }

        calibrationJob?.cancel()
        calibrationJob = null
        calibrationStage = CalibrationStage.IDLE
        ambientCalibrationSamples.clear()
        speechCalibrationSamples.clear()

        _uiState.update {
            it.copy(
                isMicCalibrating = false,
                micCalibrationPhase = "Completed",
                micCalibrationProgress = 1f,
                micCalibrationAmbientDb = ambient,
                micCalibrationSpeechDb = speech,
                micCalibrationDeltaDb = delta,
                micCalibrationQuality = quality,
                micCalibrationGuidance = guidance
            )
        }
        saveMicCalibration(
            ambientDb = ambient,
            speechDb = speech,
            deltaDb = delta,
            quality = quality,
            guidance = guidance
        )
        speechPipeline.setVadCalibration(
            ambientDb = ambient,
            speechDb = speech
        )
        postStatusFromUi("Mic calibration completed: $quality")
    }

    private fun stopCalibrationRun(phase: String, guidance: String) {
        calibrationJob?.cancel()
        calibrationJob = null
        calibrationStage = CalibrationStage.IDLE
        ambientCalibrationSamples.clear()
        speechCalibrationSamples.clear()
        _uiState.update {
            it.copy(
                isMicCalibrating = false,
                micCalibrationPhase = phase,
                micCalibrationProgress = 0f,
                micCalibrationGuidance = guidance
            )
        }
    }

    private fun trimCalibrationSamples(samples: MutableList<Float>) {
        if (samples.size <= MAX_CALIBRATION_SAMPLES) {
            return
        }
        val removeCount = samples.size - MAX_CALIBRATION_SAMPLES
        samples.subList(0, removeCount).clear()
    }

    private fun averageOrNull(samples: List<Float>): Float? {
        if (samples.isEmpty()) {
            return null
        }
        return samples.average().toFloat()
    }

    private fun averageTopSlice(samples: List<Float>): Float? {
        if (samples.isEmpty()) {
            return null
        }
        val sorted = samples.sortedDescending()
        val slice = sorted.take((sorted.size * 0.6f).toInt().coerceAtLeast(1))
        return slice.average().toFloat()
    }

    private fun resetAdaptiveLanguageTracking(language: LanguageProfile) {
        activeRecognizerTag = language.initialRecognizerTag()
        publishedLanguageTag = activeRecognizerTag
        pendingAdaptiveTag = null
        adaptiveTagVotes = 0
        lastAdaptiveSwitchAtMs = 0L
    }

    private fun resolvedLanguageTag(tag: String): String {
        return when (tag) {
            "en-US",
            "ur-PK",
            MIXED_LANGUAGE_TAG -> tag

            else -> activeRecognizerTag
        }
    }

    private fun maybeSwitchAdaptiveRecognizer(suggestedTag: String?, isFinal: Boolean) {
        val state = _uiState.value
        val selectedLanguage = state.selectedLanguage
        if (!selectedLanguage.isAdaptiveBilingual()) {
            return
        }
        if (!isFinal) {
            return
        }

        val candidateTag = suggestedTag?.trim().orEmpty()
        if (candidateTag.isBlank() || candidateTag == activeRecognizerTag) {
            pendingAdaptiveTag = null
            adaptiveTagVotes = 0
            return
        }
        if (!selectedLanguage.adaptiveRecognizerTags.contains(candidateTag)) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAdaptiveSwitchAtMs < ADAPTIVE_SWITCH_MIN_INTERVAL_MS) {
            return
        }

        if (pendingAdaptiveTag == candidateTag) {
            adaptiveTagVotes += 1
        } else {
            pendingAdaptiveTag = candidateTag
            adaptiveTagVotes = 1
        }

        val neededVotes = if (isFinal) ADAPTIVE_SWITCH_FINAL_VOTES else ADAPTIVE_SWITCH_PARTIAL_VOTES
        if (adaptiveTagVotes < neededVotes) {
            return
        }

        activeRecognizerTag = candidateTag
        publishedLanguageTag = candidateTag
        pendingAdaptiveTag = null
        adaptiveTagVotes = 0
        lastAdaptiveSwitchAtMs = now
        val languageLabel = if (candidateTag == "ur-PK") "Urdu" else "English"
        restartSpeech("Auto language switched to $languageLabel")
    }

    private fun maybeLearnFromUsage(
        observedText: String,
        canonicalText: String,
        detectedLanguageTag: String,
        confidence: Float?
    ) {
        if (observedText.isBlank() || canonicalText.isBlank()) {
            return
        }
        if (detectedLanguageTag != "en-US" && detectedLanguageTag != MIXED_LANGUAGE_TAG) {
            return
        }

        AiTextRefiner.learnFromObservation(
            observedText = observedText,
            canonicalText = canonicalText,
            confidence = confidence
        )
        val learnedCount = AiTextRefiner.learnedRuleCount()
        if (learnedCount != _uiState.value.adaptiveRuleCount) {
            _uiState.update { it.copy(adaptiveRuleCount = learnedCount) }
        }
    }

    private fun activeBiasPhrases(language: LanguageProfile): List<String> {
        return (language.biasPhrases + customVocabulary)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ACTIVE_BIAS_PHRASES)
    }

    private fun loadCustomVocabulary(): List<String> {
        val raw = preferences.getString(PREF_KEY_CUSTOM_VOCAB, "").orEmpty()
        return parseCustomVocabulary(raw)
    }

    private fun loadSavedMicCalibration(): SavedMicCalibration? {
        if (!preferences.contains(PREF_KEY_CALIBRATION_AMBIENT_DB) ||
            !preferences.contains(PREF_KEY_CALIBRATION_SPEECH_DB)
        ) {
            return null
        }

        val ambient = preferences.getFloat(PREF_KEY_CALIBRATION_AMBIENT_DB, Float.NaN)
        val speech = preferences.getFloat(PREF_KEY_CALIBRATION_SPEECH_DB, Float.NaN)
        if (!ambient.isFinite() || !speech.isFinite()) {
            return null
        }

        val delta = preferences.getFloat(
            PREF_KEY_CALIBRATION_DELTA_DB,
            (speech - ambient).coerceAtLeast(0f)
        ).let { if (it.isFinite()) it.coerceAtLeast(0f) else (speech - ambient).coerceAtLeast(0f) }

        val quality = preferences
            .getString(PREF_KEY_CALIBRATION_QUALITY, "Calibrated")
            .orEmpty()
            .ifBlank { "Calibrated" }
        val guidance = preferences
            .getString(PREF_KEY_CALIBRATION_GUIDANCE, DEFAULT_CALIBRATION_GUIDANCE)
            .orEmpty()
            .ifBlank { DEFAULT_CALIBRATION_GUIDANCE }

        return SavedMicCalibration(
            ambientDb = ambient,
            speechDb = speech,
            deltaDb = delta,
            quality = quality,
            guidance = guidance
        )
    }

    private fun saveMicCalibration(
        ambientDb: Float,
        speechDb: Float,
        deltaDb: Float,
        quality: String,
        guidance: String
    ) {
        preferences.edit {
            putFloat(PREF_KEY_CALIBRATION_AMBIENT_DB, ambientDb)
            putFloat(PREF_KEY_CALIBRATION_SPEECH_DB, speechDb)
            putFloat(PREF_KEY_CALIBRATION_DELTA_DB, deltaDb)
            putString(PREF_KEY_CALIBRATION_QUALITY, quality)
            putString(PREF_KEY_CALIBRATION_GUIDANCE, guidance)
        }
    }

    private fun parseCustomVocabulary(raw: String): List<String> {
        return raw
            .split(CUSTOM_VOCAB_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_CUSTOM_VOCAB)
    }

    // ── Accessibility settings ────────────────────────────────────────────────

    fun setCaptionFontScale(scale: Int) {
        val clamped = scale.coerceIn(85, 200)
        preferences.edit { putInt(PREF_KEY_CAPTION_FONT_SCALE, clamped) }
        _uiState.update { it.copy(captionFontScale = clamped) }
        publishDisplaySettings()
    }

    fun setCaptionTheme(preset: CaptionThemePreset) {
        preferences.edit { putInt(PREF_KEY_CAPTION_THEME, preset.ordinal) }
        _uiState.update { it.copy(captionTheme = preset) }
        publishDisplaySettings()
    }

    fun setShowPartialInBrowser(show: Boolean) {
        preferences.edit { putBoolean(PREF_KEY_SHOW_PARTIAL, show) }
        _uiState.update { it.copy(showPartialInBrowser = show) }
        publishDisplaySettings()
    }

    fun setCaptionMaxLines(lines: Int) {
        val clamped = lines.coerceIn(2, 6)
        preferences.edit { putInt(PREF_KEY_CAPTION_MAX_LINES, clamped) }
        _uiState.update { it.copy(captionMaxLines = clamped) }
        publishDisplaySettings()
    }

    fun setCaptionLineSpacing(spacing: Float) {
        val clamped = spacing.coerceIn(1.0f, 1.8f)
        preferences.edit { putFloat(PREF_KEY_CAPTION_LINE_SPACING, clamped) }
        _uiState.update { it.copy(captionLineSpacing = clamped) }
        publishDisplaySettings()
    }

    fun setCaptionLetterSpacing(spacing: Float) {
        val clamped = spacing.coerceIn(0f, 0.08f)
        preferences.edit { putFloat(PREF_KEY_CAPTION_LETTER_SPACING, clamped) }
        _uiState.update { it.copy(captionLetterSpacing = clamped) }
        publishDisplaySettings()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        preferences.edit { putBoolean(PREF_KEY_KEEP_SCREEN_ON, enabled) }
        _uiState.update { it.copy(keepScreenOn = enabled) }
    }

    fun setFullBrightnessWhenRunning(enabled: Boolean) {
        preferences.edit { putBoolean(PREF_KEY_FULL_BRIGHTNESS, enabled) }
        _uiState.update { it.copy(fullBrightnessWhenRunning = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(PREF_KEY_VIBRATION, enabled) }
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    fun setAutoStartOnLaunch(enabled: Boolean) {
        preferences.edit { putBoolean(PREF_KEY_AUTO_START, enabled) }
        _uiState.update { it.copy(autoStartOnLaunch = enabled) }
    }

    private fun publishDisplaySettings() {
        val s = _uiState.value
        webServer.publishSettings(
            CaptionFrame(
                type = FrameType.SETTINGS,
                settings = DisplaySettings(
                    fontScale = s.captionFontScale,
                    bgHex = s.captionTheme.bgHex,
                    boxHex = s.captionTheme.boxHex,
                    textHex = s.captionTheme.textHex,
                    highContrast = s.captionTheme.highContrast,
                    showPartial = s.showPartialInBrowser,
                    maxLines = s.captionMaxLines,
                    lineHeight = s.captionLineSpacing,
                    letterSpacing = s.captionLetterSpacing
                )
            )
        )
    }

    fun dismissOnboarding() {
        preferences.edit { putBoolean(PREF_KEY_ONBOARDING_DONE, true) }
        _uiState.update { it.copy(showOnboarding = false) }
    }

    private fun loadAccessibilityPrefs(): AccessibilityPrefs = AccessibilityPrefs(
        fontScale = preferences.getInt(PREF_KEY_CAPTION_FONT_SCALE, 100),
        theme = CaptionThemePreset.entries.getOrElse(preferences.getInt(PREF_KEY_CAPTION_THEME, 0)) { CaptionThemePreset.DEFAULT },
        showPartial = preferences.getBoolean(PREF_KEY_SHOW_PARTIAL, true),
        maxLines = preferences.getInt(PREF_KEY_CAPTION_MAX_LINES, 3),
        lineSpacing = preferences.getFloat(PREF_KEY_CAPTION_LINE_SPACING, 1.1f),
        letterSpacing = preferences.getFloat(PREF_KEY_CAPTION_LETTER_SPACING, 0f),
        keepScreenOn = preferences.getBoolean(PREF_KEY_KEEP_SCREEN_ON, true),
        fullBrightness = preferences.getBoolean(PREF_KEY_FULL_BRIGHTNESS, false),
        vibration = preferences.getBoolean(PREF_KEY_VIBRATION, false),
        autoStart = preferences.getBoolean(PREF_KEY_AUTO_START, false)
    )

    // ── URL & server ─────────────────────────────────────────────────────────

    private val sessionToken: String = buildSessionToken().also { webServer.sessionToken = it }

    private fun buildServerUrl(): String {
        val ip = NetworkUtils.localIpAddress(appContext) ?: "0.0.0.0"
        return "http://$ip:${webServer.listenPort}/?t=$sessionToken"
    }

    private fun buildSessionToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rng = java.security.SecureRandom()
        return (1..6).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    override fun onCleared() {
        calibrationJob?.cancel()
        cancelPendingFinalization()
        cancelPartialFlush()
        AiTextRefiner.flush()
        speechPipeline.release()
        webServer.stopServer()
        appContext.stopService(Intent(appContext, CaptionForegroundService::class.java))
        super.onCleared()
    }

    private fun normalizeCaptionText(input: String): String =
        CaptionTextUtils.normalizeCaptionText(input)

    private fun appendFinalLine(text: String) {
        lineBuffer.addLast(text)
        while (lineBuffer.size > 8) {
            lineBuffer.removeFirst()
        }
    }

    companion object {
        private val DEFAULT_LANGUAGE = LanguageProfile.English
        private const val PREFS_NAME = "accessibility_stt_prefs"
        private const val PREF_KEY_CUSTOM_VOCAB = "custom_vocab_terms"
        private const val PREF_KEY_CALIBRATION_AMBIENT_DB = "mic_calibration_ambient_db"
        private const val PREF_KEY_CALIBRATION_SPEECH_DB = "mic_calibration_speech_db"
        private const val PREF_KEY_CALIBRATION_DELTA_DB = "mic_calibration_delta_db"
        private const val PREF_KEY_CALIBRATION_QUALITY = "mic_calibration_quality"
        private const val PREF_KEY_CALIBRATION_GUIDANCE = "mic_calibration_guidance"
        private const val PREF_KEY_CAPTION_FONT_SCALE = "caption_font_scale"
        private const val PREF_KEY_CAPTION_THEME = "caption_theme_ordinal"
        private const val PREF_KEY_SHOW_PARTIAL = "caption_show_partial"
        private const val PREF_KEY_CAPTION_MAX_LINES = "caption_max_lines"
        private const val PREF_KEY_CAPTION_LINE_SPACING = "caption_line_spacing"
        private const val PREF_KEY_CAPTION_LETTER_SPACING = "caption_letter_spacing"
        private const val PREF_KEY_KEEP_SCREEN_ON = "device_keep_screen_on"
        private const val PREF_KEY_FULL_BRIGHTNESS = "device_full_brightness"
        private const val PREF_KEY_VIBRATION = "device_vibration"
        private const val PREF_KEY_AUTO_START = "device_auto_start"
        private const val PREF_KEY_ONBOARDING_DONE = "onboarding_done"
        private val WORD_SPLIT_REGEX = Regex("\\s+")
        private val CUSTOM_VOCAB_SPLIT_REGEX = Regex("[,;\\n\\r]+")
        private const val DEFAULT_CALIBRATION_GUIDANCE = "Run calibration to check mic distance and room noise."
        private const val DEFAULT_PARTIAL_FLUSH_MS = 480L
        private const val VAD_INACTIVE_FLUSH_MS = 480L
        private const val ACTIVE_VOICE_RETRY_FLUSH_MS = 240L
        private const val PUSH_TO_TALK_RETRY_FLUSH_MS = 200L
        private const val RECENT_VOICE_WINDOW_MS = 480L
        private const val FORCE_PARTIAL_FLUSH_STALE_MS = 1_200L
        private const val FORCE_PARTIAL_FLUSH_LONG_STALE_MS = 800L
        private const val FORCE_PARTIAL_FLUSH_LONG_WORDS = 12
        private const val PUSH_STOP_GRACE_WINDOW_MS = 260L
        private const val PUSH_TOGGLE_DEBOUNCE_MS = 220L
        private const val CALIBRATION_AMBIENT_MS = 2_800L
        private const val CALIBRATION_SPEECH_MS = 3_800L
        private const val MAX_CALIBRATION_SAMPLES = 140
        private const val ADAPTIVE_SWITCH_MIN_INTERVAL_MS = 18_000L
        private const val ADAPTIVE_SWITCH_PARTIAL_VOTES = 5
        private const val ADAPTIVE_SWITCH_FINAL_VOTES = 4
        private const val MAX_CUSTOM_VOCAB = 160
        private const val MAX_ACTIVE_BIAS_PHRASES = 220
    }
}
