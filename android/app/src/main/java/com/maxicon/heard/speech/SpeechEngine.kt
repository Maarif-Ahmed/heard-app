package com.maxicon.heard.speech

interface SpeechEngine {
    fun start(languageTag: String, biasPhrases: List<String>)
    fun stop()
    fun release()
    fun prepare()
    fun setVadCalibration(ambientDb: Float?, speechDb: Float?)
}
