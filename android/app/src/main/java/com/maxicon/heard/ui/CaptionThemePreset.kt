package com.maxicon.heard.ui

enum class CaptionThemePreset(
    val label: String,
    val bgHex: String,
    val boxHex: String,
    val textHex: String,
    val highContrast: Boolean
) {
    DEFAULT("Default Dark", "000000", "07080f", "f0f4ff", false),
    YELLOW_BLACK("Yellow / Black", "000000", "020100", "ffef4f", false),
    HIGH_CONTRAST("HC Yellow", "000000", "000000", "ffff00", true),
    GREEN_TERM("Green Terminal", "010d01", "010801", "00e54a", false),
    BLUE_NIGHT("Blue Night", "00020f", "000107", "bedeff", false),
    WARM_AMBER("Warm Amber", "080400", "050200", "ffd27a", false),
}
