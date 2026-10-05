package com.maxicon.heard.speech

import android.content.Context
import android.speech.SpeechRecognizer

class SpeechPipeline(
    private val context: Context
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String, confidence: Float?)
        fun onStatus(message: String)
        fun onVad(active: Boolean, levelDb: Float)
    }

    private enum class Target {
        ONLINE,
        OFFLINE
    }

    private var listener: Listener? = null
    private var mode: RecognitionMode = RecognitionMode.ONLINE
    private var languageTag: String = "en-US"
    private var biasPhrases: List<String> = emptyList()
    private var vadAmbientDb: Float? = null
    private var vadSpeechDb: Float? = null
    private var running = false
    private var activeTarget: Target? = null

    private val onlineEngine: SpeechEngine = AndroidSpeechEngine(
        context = context,
        preferOffline = false,
        onEvent = ::handleOnlineEvent
    )

    private val offlineEngine: SpeechEngine = VoskEngine(
        context = context,
        onEvent = ::handleOfflineEvent
    )

    fun prepare(
        mode: RecognitionMode,
        languageTag: String,
        biasPhrases: List<String>,
        listener: Listener
    ) {
        this.mode = mode
        this.languageTag = languageTag
        this.biasPhrases = biasPhrases
        this.listener = listener
        onlineEngine.setVadCalibration(vadAmbientDb, vadSpeechDb)
        offlineEngine.setVadCalibration(vadAmbientDb, vadSpeechDb)
        when (mode) {
            RecognitionMode.ONLINE -> onlineEngine.prepare()
            RecognitionMode.OFFLINE -> offlineEngine.prepare()
        }
    }

    fun start(
        mode: RecognitionMode,
        languageTag: String,
        biasPhrases: List<String>,
        listener: Listener
    ) {
        stop()
        this.mode = mode
        this.languageTag = languageTag
        this.biasPhrases = biasPhrases
        this.listener = listener
        onlineEngine.setVadCalibration(vadAmbientDb, vadSpeechDb)
        offlineEngine.setVadCalibration(vadAmbientDb, vadSpeechDb)
        running = true

        val initialTarget = when (mode) {
            RecognitionMode.ONLINE -> Target.ONLINE
            RecognitionMode.OFFLINE -> Target.OFFLINE
        }

        switchTarget(initialTarget, "Mode ${mode.label}: ${initialTarget.name.lowercase()} recognizer active")
    }

    fun stop() {
        running = false
        onlineEngine.stop()
        offlineEngine.stop()
        activeTarget = null
    }

    fun release() {
        stop()
        onlineEngine.release()
        offlineEngine.release()
    }

    fun setVadCalibration(ambientDb: Float?, speechDb: Float?) {
        vadAmbientDb = ambientDb
        vadSpeechDb = speechDb
        onlineEngine.setVadCalibration(ambientDb, speechDb)
        offlineEngine.setVadCalibration(ambientDb, speechDb)
    }

    private fun switchTarget(target: Target, statusMessage: String? = null) {
        if (!running) {
            return
        }

        if (activeTarget == target) {
            statusMessage?.let { listener?.onStatus(it) }
            return
        }

        when (activeTarget) {
            Target.ONLINE -> onlineEngine.stop()
            Target.OFFLINE -> offlineEngine.stop()
            null -> Unit
        }

        activeTarget = target
        when (target) {
            Target.ONLINE -> onlineEngine.start(languageTag, biasPhrases)
            Target.OFFLINE -> offlineEngine.start(languageTag, biasPhrases)
        }

        statusMessage?.let { listener?.onStatus(it) }
    }

    private fun handleOnlineEvent(event: EngineEvent) {
        if (activeTarget != Target.ONLINE || !running) {
            return
        }
        when (event) {
            is EngineEvent.Partial -> listener?.onPartial(event.text)
            is EngineEvent.Final -> listener?.onFinal(event.text, event.confidence)
            is EngineEvent.Status -> listener?.onStatus("Online: ${event.message}")
            is EngineEvent.Vad -> listener?.onVad(event.active, event.levelDb)
            is EngineEvent.Error -> listener?.onStatus("Online warning: ${errorLabel(event.code)}")
        }
    }

    private fun handleOfflineEvent(event: EngineEvent) {
        if (activeTarget != Target.OFFLINE || !running) {
            return
        }
        when (event) {
            is EngineEvent.Partial -> listener?.onPartial(event.text)
            is EngineEvent.Final -> listener?.onFinal(event.text, event.confidence)
            is EngineEvent.Status -> listener?.onStatus("Offline: ${event.message}")
            is EngineEvent.Vad -> listener?.onVad(event.active, event.levelDb)
            is EngineEvent.Error -> listener?.onStatus("Offline warning: ${errorLabel(event.code)}")
        }
    }

    private fun errorLabel(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            else -> "code $code"
        }
    }
}
