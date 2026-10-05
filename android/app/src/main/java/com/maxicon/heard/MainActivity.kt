package com.maxicon.heard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxicon.heard.model.AiTextRefiner
import com.maxicon.heard.ui.CaptionControlScreen
import com.maxicon.heard.ui.CaptionViewModel

// ─── Light theme: black on white ────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary                = Color(0xFF000000),
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFE8E8E8),
    onPrimaryContainer     = Color(0xFF1A1A1A),
    secondary              = Color(0xFF333333),
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFF0F0F0),
    onSecondaryContainer   = Color(0xFF1A1A1A),
    // tertiary = green accent, WCAG AA on white (contrast 4.6:1)
    tertiary               = Color(0xFF1A6B3C),
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFD5F0E3),
    onTertiaryContainer    = Color(0xFF003920),
    background             = Color(0xFFFFFFFF),
    onBackground           = Color(0xFF000000),
    surface                = Color(0xFFF7F7F7),
    onSurface              = Color(0xFF000000),
    surfaceVariant         = Color(0xFFEEEEEE),
    onSurfaceVariant       = Color(0xFF444444),
    outline                = Color(0xFF999999),
    outlineVariant         = Color(0xFFDDDDDD),
    error                  = Color(0xFFB00020),
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFEDED),
    onErrorContainer       = Color(0xFF5C0010),
    scrim                  = Color(0xFF000000),
)

// ─── Dark theme: white on black ─────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary                = Color(0xFFFFFFFF),
    onPrimary              = Color(0xFF000000),
    primaryContainer       = Color(0xFF222222),
    onPrimaryContainer     = Color(0xFFE8E8E8),
    secondary              = Color(0xFFBBBBBB),
    onSecondary            = Color(0xFF111111),
    secondaryContainer     = Color(0xFF2A2A2A),
    onSecondaryContainer   = Color(0xFFDDDDDD),
    // tertiary = bright green, WCAG AA on black
    tertiary               = Color(0xFF4ADE80),
    onTertiary             = Color(0xFF003920),
    tertiaryContainer      = Color(0xFF0D2B1A),
    onTertiaryContainer    = Color(0xFFB8F5D0),
    background             = Color(0xFF000000),
    onBackground           = Color(0xFFFFFFFF),
    surface                = Color(0xFF111111),
    onSurface              = Color(0xFFFFFFFF),
    surfaceVariant         = Color(0xFF1E1E1E),
    onSurfaceVariant       = Color(0xFFAAAAAA),
    outline                = Color(0xFF666666),
    outlineVariant         = Color(0xFF2A2A2A),
    error                  = Color(0xFFFF6B6B),
    onError                = Color(0xFF3E0000),
    errorContainer         = Color(0xFF2D0A0A),
    onErrorContainer       = Color(0xFFFFCDD2),
    scrim                  = Color(0xFF000000),
)

class MainActivity : ComponentActivity() {
    private val viewModel: CaptionViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startCaptions()
        } else {
            viewModel.postStatusFromUi("Microphone permission is required")
        }
    }

    // Best-effort: foreground service notification needs POST_NOTIFICATIONS on API 33+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: service still runs whether granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AiTextRefiner.initialize(applicationContext)

        if (viewModel.uiState.value.autoStartOnLaunch) {
            requestMicAndStart()
        }

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CaptionControlScreen(
                        uiState = uiState,
                        onStart = ::requestMicAndStart,
                        onStop = viewModel::stopCaptions,
                        onStartMicCalibration = viewModel::startMicCalibration,
                        onCancelMicCalibration = viewModel::cancelMicCalibration,
                        onLanguageSelected = viewModel::setLanguage,
                        onModeSelected = viewModel::setRecognitionMode,
                        onCaptureModeSelected = viewModel::setCaptureMode,
                        onCustomVocabularyChanged = viewModel::updateCustomVocabularyDraft,
                        onApplyCustomVocabulary = viewModel::applyCustomVocabulary,
                        onPushToTalkToggle = viewModel::togglePushToTalk,
                        onCaptionFontScaleChanged = viewModel::setCaptionFontScale,
                        onCaptionThemeChanged = viewModel::setCaptionTheme,
                        onShowPartialInBrowserChanged = viewModel::setShowPartialInBrowser,
                        onCaptionMaxLinesChanged = viewModel::setCaptionMaxLines,
                        onCaptionLineSpacingChanged = viewModel::setCaptionLineSpacing,
                        onCaptionLetterSpacingChanged = viewModel::setCaptionLetterSpacing,
                        onKeepScreenOnChanged = viewModel::setKeepScreenOn,
                        onFullBrightnessChanged = viewModel::setFullBrightnessWhenRunning,
                        onVibrationEnabledChanged = viewModel::setVibrationEnabled,
                        onAutoStartOnLaunchChanged = viewModel::setAutoStartOnLaunch,
                        onDismissOnboarding = viewModel::dismissOnboarding,
                        onDownloadVoskModel = viewModel::downloadVoskModel,
                        onCancelVoskDownload = viewModel::cancelVoskDownload,
                        onDeleteVoskModel = viewModel::deleteVoskModel,
                    )
                }
            }
        }
    }

    private fun requestMicAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.startCaptions()
            return
        }

        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
