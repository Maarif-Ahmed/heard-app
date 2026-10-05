package com.maxicon.heard.ui

import com.maxicon.heard.model.LanguageProfile
import com.maxicon.heard.speech.CaptureMode
import com.maxicon.heard.speech.RecognitionMode

data class CaptionUiState(
    val selectedLanguage: LanguageProfile = LanguageProfile.English,
    val recognitionMode: RecognitionMode = RecognitionMode.ONLINE,
    val captureMode: CaptureMode = CaptureMode.NORMAL,
    val customVocabularyText: String = "",
    val customVocabularyCount: Int = 0,
    val adaptiveRuleCount: Int = 0,
    val isRunning: Boolean = false,
    val isPushToTalkActive: Boolean = false,
    val isVoiceDetected: Boolean = false,
    val inputLevelDb: Float = -2f,
    val isMicCalibrating: Boolean = false,
    val micCalibrationPhase: String = "Not started",
    val micCalibrationProgress: Float = 0f,
    val micCalibrationAmbientDb: Float? = null,
    val micCalibrationSpeechDb: Float? = null,
    val micCalibrationDeltaDb: Float? = null,
    val micCalibrationQuality: String = "Not calibrated",
    val micCalibrationGuidance: String = "Run calibration to check mic distance and room noise.",
    val partialLine: String = "",
    val finalizedLines: List<String> = emptyList(),
    val statusMessage: String = "Ready",
    val serverUrl: String = "http://0.0.0.0:8765",

    // Caption display settings (propagated to browser via URL params)
    val captionFontScale: Int = 100,
    val captionTheme: CaptionThemePreset = CaptionThemePreset.DEFAULT,
    val showPartialInBrowser: Boolean = true,
    val captionMaxLines: Int = 3,
    val captionLineSpacing: Float = 1.1f,
    val captionLetterSpacing: Float = 0f,

    // Device & session behaviour
    val keepScreenOn: Boolean = true,
    val fullBrightnessWhenRunning: Boolean = false,
    val vibrationEnabled: Boolean = false,
    val autoStartOnLaunch: Boolean = false,

    // Base server URL shown to user – settings are synced live via WebSocket
    val captionDisplayUrl: String = "http://0.0.0.0:8765",

    // Onboarding
    val showOnboarding: Boolean = true,
)
