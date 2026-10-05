package com.maxicon.heard.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskEngine(
    private val context: Context,
    private val onEvent: (EngineEvent) -> Unit
) : SpeechEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelLock = Any()

    @Volatile private var listening = false
    private var captureThread: Thread? = null
    @Volatile private var cachedModel: Model? = null

    private var calibratedAmbientDb: Float? = null
    private var calibratedDeltaDb: Float? = null

    override fun start(languageTag: String, biasPhrases: List<String>) {
        if (VoskModelManager.modelPath(context) == null) {
            mainHandler.post { onEvent(EngineEvent.Status("Offline model not installed")) }
            return
        }
        listening = true
        captureThread = Thread { runCaptureLoop() }.apply {
            isDaemon = true
            name = "vosk-capture"
            start()
        }
    }

    override fun stop() {
        listening = false
        // Non-blocking: capture thread exits on its next loop iteration.
        // release() joins before closing the model.
    }

    override fun release() {
        listening = false
        val thread = captureThread
        captureThread = null
        // Join + model close happen on a daemon thread so the caller is never blocked.
        Thread {
            thread?.join(2_000)
            synchronized(modelLock) {
                cachedModel?.close()
                cachedModel = null
            }
        }.apply { isDaemon = true; name = "vosk-release"; start() }
    }

    override fun prepare() {
        val path = VoskModelManager.modelPath(context) ?: return
        if (cachedModel != null) return  // fast volatile read
        Thread {
            synchronized(modelLock) {
                if (cachedModel == null) runCatching { cachedModel = Model(path) }
            }
        }.apply { isDaemon = true; name = "vosk-preload"; start() }
    }

    override fun setVadCalibration(ambientDb: Float?, speechDb: Float?) {
        calibratedAmbientDb = ambientDb?.takeIf { it.isFinite() }
        val speech = speechDb?.takeIf { it.isFinite() }
        calibratedDeltaDb = if (calibratedAmbientDb != null && speech != null) {
            (speech - calibratedAmbientDb!!).coerceAtLeast(0f)
        } else null
    }

    private fun runCaptureLoop() {
        val modelPath = VoskModelManager.modelPath(context) ?: return

        val model = cachedModel ?: run {
            mainHandler.post { onEvent(EngineEvent.Status("Loading offline model…")) }
            try {
                synchronized(modelLock) {
                    cachedModel ?: Model(modelPath).also { cachedModel = it }
                }
            } catch (e: Exception) {
                mainHandler.post { onEvent(EngineEvent.Status("Failed to load offline model")) }
                return
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(SAMPLE_RATE * 2)
        )

        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            mainHandler.post { onEvent(EngineEvent.Status("AudioRecord init failed")) }
            return
        }

        var rec = Recognizer(model, SAMPLE_RATE.toFloat())
        val buf = ShortArray(READ_SAMPLES)

        var speechActive = false
        var noiseFloorDb = calibratedAmbientDb ?: -2f
        var smoothedDb = noiseFloorDb
        var aboveFrames = 0
        var belowFrames = 0
        var lastPartial = ""
        var lastOutputMs = SystemClock.elapsedRealtime()
        var consecutiveReadErrors = 0

        try {
            audio.startRecording()
            mainHandler.post { onEvent(EngineEvent.Status("Listening (offline)")) }

            while (listening) {
                val read = audio.read(buf, 0, buf.size)

                if (read < 0) {
                    if (++consecutiveReadErrors >= MAX_READ_ERRORS) {
                        mainHandler.post { onEvent(EngineEvent.Error(SpeechRecognizer.ERROR_AUDIO)) }
                        break
                    }
                    continue
                }
                consecutiveReadErrors = 0
                if (read == 0) continue

                val level = SpeechEngineUtils.computeVadLevel(buf, read)
                smoothedDb = if (smoothedDb < -1.5f) level else smoothedDb * 0.72f + level * 0.28f

                if (!speechActive) {
                    val base = if (noiseFloorDb < -1.5f) smoothedDb
                    else noiseFloorDb * 0.985f + smoothedDb * 0.015f
                    noiseFloorDb = calibratedAmbientDb?.let { base * 0.82f + it * 0.18f } ?: base
                }

                val startMargin = calibratedDeltaDb
                    ?.let { (it * 0.58f).coerceIn(MIN_START_MARGIN, MAX_START_MARGIN) }
                    ?: DEFAULT_START_MARGIN
                val stopMargin = calibratedDeltaDb
                    ?.let { (startMargin * 0.56f).coerceIn(MIN_STOP_MARGIN, MAX_STOP_MARGIN) }
                    ?: DEFAULT_STOP_MARGIN

                if (smoothedDb > noiseFloorDb + startMargin) {
                    aboveFrames++; belowFrames = 0
                } else if (smoothedDb < noiseFloorDb + stopMargin) {
                    belowFrames++; aboveFrames = 0
                }

                if (!speechActive && aboveFrames >= 2) {
                    speechActive = true
                    val db = smoothedDb
                    mainHandler.post { onEvent(EngineEvent.Vad(active = true, levelDb = db)) }
                } else if (speechActive && belowFrames >= 4) {
                    speechActive = false
                    val db = smoothedDb
                    mainHandler.post { onEvent(EngineEvent.Vad(active = false, levelDb = db)) }
                }

                // Stall watchdog: if no Vosk output in STALL_TIMEOUT_MS, recreate Recognizer
                val now = SystemClock.elapsedRealtime()
                if (now - lastOutputMs > STALL_TIMEOUT_MS) {
                    runCatching { rec.close() }
                    rec = Recognizer(model, SAMPLE_RATE.toFloat())
                    lastOutputMs = now
                }

                val accepted = try {
                    rec.acceptWaveForm(buf, read)
                } catch (e: Exception) {
                    // Vosk native error: recreate Recognizer (model stays loaded)
                    runCatching { rec.close() }
                    rec = Recognizer(model, SAMPLE_RATE.toFloat())
                    lastOutputMs = SystemClock.elapsedRealtime()
                    continue
                }

                if (accepted) {
                    val text = parseJson(rec.result, "text")
                    if (text.isNotBlank()) {
                        lastOutputMs = SystemClock.elapsedRealtime()
                        lastPartial = ""
                        mainHandler.post { onEvent(EngineEvent.Final(text, null)) }
                    }
                } else {
                    val text = parseJson(rec.partialResult, "partial")
                    if (text.isNotBlank()) {
                        lastOutputMs = SystemClock.elapsedRealtime()
                        if (text != lastPartial) {
                            lastPartial = text
                            mainHandler.post { onEvent(EngineEvent.Partial(text)) }
                        }
                    }
                }
            }

            val finalText = parseJson(rec.finalResult, "text")
            if (finalText.isNotBlank()) {
                mainHandler.post { onEvent(EngineEvent.Final(finalText, null)) }
            }
        } finally {
            audio.stop()
            audio.release()
            runCatching { rec.close() }
        }
    }

    private fun parseJson(json: String?, key: String): String {
        json ?: return ""
        return runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val READ_SAMPLES = 4000
        private const val DEFAULT_START_MARGIN = 4.2f
        private const val DEFAULT_STOP_MARGIN = 2.1f
        private const val MIN_START_MARGIN = 2.6f
        private const val MAX_START_MARGIN = 4.4f
        private const val MIN_STOP_MARGIN = 1.4f
        private const val MAX_STOP_MARGIN = 2.5f
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val MAX_READ_ERRORS = 5
    }
}
