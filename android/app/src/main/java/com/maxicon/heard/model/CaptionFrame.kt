package com.maxicon.heard.model

import kotlinx.serialization.Serializable

@Serializable
enum class FrameType {
    PARTIAL,
    FINAL,
    STATUS,
    SNAPSHOT,
    SETTINGS
}

@Serializable
data class DisplaySettings(
    val fontScale: Int = 100,
    val bgHex: String = "000000",
    val boxHex: String = "07080f",
    val textHex: String = "f0f4ff",
    val highContrast: Boolean = false,
    val showPartial: Boolean = true,
    val maxLines: Int = 3,
    val lineHeight: Float = 1.1f,
    val letterSpacing: Float = 0f
)

@Serializable
data class CaptionFrame(
    val type: FrameType,
    val text: String = "",
    val finalizedLines: List<String> = emptyList(),
    val languageTag: String = "en-US",
    val status: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val settings: DisplaySettings? = null
)
