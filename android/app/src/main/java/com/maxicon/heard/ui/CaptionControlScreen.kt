package com.maxicon.heard.ui

import android.app.Activity
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxicon.heard.R
import com.maxicon.heard.model.LanguageProfile
import com.maxicon.heard.speech.CaptureMode
import com.maxicon.heard.speech.RecognitionMode
import com.maxicon.heard.speech.VoskModelManager
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionControlScreen(
    uiState: CaptionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartMicCalibration: () -> Unit,
    onCancelMicCalibration: () -> Unit,
    onLanguageSelected: (LanguageProfile) -> Unit,
    onModeSelected: (RecognitionMode) -> Unit,
    onCaptureModeSelected: (CaptureMode) -> Unit,
    onCustomVocabularyChanged: (String) -> Unit,
    onApplyCustomVocabulary: () -> Unit,
    onPushToTalkToggle: () -> Unit,
    onCaptionFontScaleChanged: (Int) -> Unit,
    onCaptionThemeChanged: (CaptionThemePreset) -> Unit,
    onShowPartialInBrowserChanged: (Boolean) -> Unit,
    onCaptionMaxLinesChanged: (Int) -> Unit,
    onCaptionLineSpacingChanged: (Float) -> Unit,
    onCaptionLetterSpacingChanged: (Float) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onFullBrightnessChanged: (Boolean) -> Unit,
    onVibrationEnabledChanged: (Boolean) -> Unit,
    onAutoStartOnLaunchChanged: (Boolean) -> Unit,
    onDismissOnboarding: () -> Unit,
    onDownloadVoskModel: () -> Unit,
    onCancelVoskDownload: () -> Unit,
    onDeleteVoskModel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(uiState.keepScreenOn) {
        if (uiState.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {}
    }

    val wantBright = uiState.isRunning && uiState.fullBrightnessWhenRunning
    DisposableEffect(wantBright) {
        val lp = activity?.window?.attributes
        if (lp != null) {
            lp.screenBrightness = if (wantBright)
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            else
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = lp
        }
        onDispose {
            val lp2 = activity?.window?.attributes
            if (lp2 != null) {
                lp2.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = lp2
            }
        }
    }

    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    LaunchedEffect(uiState.isVoiceDetected) {
        if (uiState.isVoiceDetected && uiState.vibrationEnabled && vibrator?.hasVibrator() == true) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(35)
            }
        }
    }

    if (uiState.showOnboarding) {
        OnboardingScreen(onDismiss = onDismissOnboarding)
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = cs.surface,
                modifier = Modifier.fillMaxHeight()
            ) {
                SettingsDrawerContent(
                    uiState = uiState,
                    onClose = { scope.launch { drawerState.close() } },
                    onFontScaleChanged = onCaptionFontScaleChanged,
                    onThemeChanged = onCaptionThemeChanged,
                    onShowPartialChanged = onShowPartialInBrowserChanged,
                    onMaxLinesChanged = onCaptionMaxLinesChanged,
                    onLineSpacingChanged = onCaptionLineSpacingChanged,
                    onLetterSpacingChanged = onCaptionLetterSpacingChanged,
                    onKeepScreenOnChanged = onKeepScreenOnChanged,
                    onFullBrightnessChanged = onFullBrightnessChanged,
                    onVibrationEnabledChanged = onVibrationEnabledChanged,
                    onAutoStartOnLaunchChanged = onAutoStartOnLaunchChanged,
                    onStartMicCalibration = onStartMicCalibration,
                    onCancelMicCalibration = onCancelMicCalibration,
                    onCustomVocabularyChanged = onCustomVocabularyChanged,
                    onApplyCustomVocabulary = onApplyCustomVocabulary
                )
            }
        },
        scrimColor = Color.Black.copy(alpha = 0.55f)
    ) {
        Scaffold(
            containerColor = cs.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Heard",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = cs.onBackground,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "Assistive Live Captions",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics {
                                contentDescription = "Open settings drawer"
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = null,
                                tint = cs.primary
                            )
                        }
                    },
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            PillBadge(text = uiState.recognitionMode.label)
                            if (uiState.isRunning) LiveBadge()
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = cs.background,
                        titleContentColor = cs.onBackground
                    )
                )
            }
        ) { innerPadding ->
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SessionCard(
                        uiState = uiState,
                        onStart = onStart,
                        onStop = onStop,
                        onPushToTalkToggle = onPushToTalkToggle
                    )
                }
                item {
                    SpeechSettingsCard(
                        uiState = uiState,
                        onLanguageSelected = onLanguageSelected,
                        onModeSelected = onModeSelected,
                        onCaptureModeSelected = onCaptureModeSelected,
                        onDownloadVoskModel = onDownloadVoskModel,
                        onCancelVoskDownload = onCancelVoskDownload,
                        onDeleteVoskModel = onDeleteVoskModel
                    )
                }
                item {
                    MonitorCard(uiState = uiState)
                }
                item {
                    Text(
                        text = "${stringResource(R.string.app_branding_developed_by)}  ·  ${stringResource(R.string.app_branding_contact)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDrawerContent(
    uiState: CaptionUiState,
    onClose: () -> Unit,
    onFontScaleChanged: (Int) -> Unit,
    onThemeChanged: (CaptionThemePreset) -> Unit,
    onShowPartialChanged: (Boolean) -> Unit,
    onMaxLinesChanged: (Int) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onLetterSpacingChanged: (Float) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onFullBrightnessChanged: (Boolean) -> Unit,
    onVibrationEnabledChanged: (Boolean) -> Unit,
    onAutoStartOnLaunchChanged: (Boolean) -> Unit,
    onStartMicCalibration: () -> Unit,
    onCancelMicCalibration: () -> Unit,
    onCustomVocabularyChanged: (String) -> Unit,
    onApplyCustomVocabulary: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close settings drawer" }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                DrawerSection("Caption Display") {
                    DisplaySettingsContent(
                        uiState = uiState,
                        onFontScaleChanged = onFontScaleChanged,
                        onThemeChanged = onThemeChanged,
                        onShowPartialChanged = onShowPartialChanged,
                        onMaxLinesChanged = onMaxLinesChanged,
                        onLineSpacingChanged = onLineSpacingChanged,
                        onLetterSpacingChanged = onLetterSpacingChanged
                    )
                }
            }
            item {
                DrawerSection("Device & Session") {
                    DeviceSettingsContent(
                        uiState = uiState,
                        onKeepScreenOnChanged = onKeepScreenOnChanged,
                        onFullBrightnessChanged = onFullBrightnessChanged,
                        onVibrationEnabledChanged = onVibrationEnabledChanged,
                        onAutoStartOnLaunchChanged = onAutoStartOnLaunchChanged
                    )
                }
            }
            item {
                CollapsibleCard(
                    title = "Mic Calibration",
                    summary = "Ambient ${formatDb(uiState.micCalibrationAmbientDb)}  ·  Quality: ${uiState.micCalibrationQuality}",
                    expanded = showCalibration,
                    onToggle = { showCalibration = !showCalibration }
                ) {
                    CalibrationContent(
                        uiState = uiState,
                        onStartMicCalibration = onStartMicCalibration,
                        onCancelMicCalibration = onCancelMicCalibration
                    )
                }
            }
            item {
                CollapsibleCard(
                    title = "Advanced",
                    summary = "${uiState.customVocabularyCount} vocab terms  ·  ${uiState.adaptiveRuleCount} learned rules",
                    expanded = showAdvanced,
                    onToggle = { showAdvanced = !showAdvanced }
                ) {
                    AdvancedContent(
                        uiState = uiState,
                        onCustomVocabularyChanged = onCustomVocabularyChanged,
                        onApplyCustomVocabulary = onApplyCustomVocabulary
                    )
                }
            }
            item {
                DrawerSection("About") {
                    Text(
                        text = "Heard — Assistive Live Captions",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Author: ${stringResource(R.string.app_developer)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.app_branding_contact),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                AttributionsSection()
            }
            item {
                PrivacySection()
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DrawerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = cs.background,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun OnboardingScreen(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    data class OnboardingStep(val title: String, val body: String)

    val steps = listOf(
        OnboardingStep(
            title = "Welcome to Heard",
            body = "Your phone becomes a live caption controller. Speech is captured here and streamed instantly to any browser on your network — TV, tablet, or laptop."
        ),
        OnboardingStep(
            title = "Start a Session",
            body = "Tap Start Session on the home screen. The microphone begins listening and generates real-time captions. Choose Online (cloud) or Offline mode, and switch languages at any time."
        ),
        OnboardingStep(
            title = "Open on Any Screen",
            body = "Type the URL shown in the Session card into any browser. The caption page loads and connects automatically. No app needed — just a browser."
        ),
        OnboardingStep(
            title = "Settings Sync Live",
            body = "Change text size, colour theme, or line count here and the browser updates the moment you adjust it. No refresh, no long URLs to copy."
        ),
        OnboardingStep(
            title = "All Settings in the Menu",
            body = "Tap the three-line icon at the top-left to open the Settings drawer. Caption Display, Device options, Mic Calibration, and Advanced are all there with full explanations."
        )
    )

    var currentStep by rememberSaveable { mutableStateOf(0) }
    val isLast = currentStep == steps.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.indices.forEach { index ->
                    val active = index == currentStep
                    Box(
                        modifier = Modifier
                            .size(if (active) 10.dp else 7.dp)
                            .background(
                                color = if (active) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                    )
                }
            }

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "onboardingStep"
            ) { step ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${step + 1}",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = cs.primary.copy(alpha = 0.3f),
                        letterSpacing = (-2).sp
                    )
                    Text(
                        text = steps[step].title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = cs.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = steps[step].body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (isLast) onDismiss() else currentStep++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primaryContainer,
                    contentColor = cs.primary
                )
            ) {
                Text(
                    text = if (isLast) "Get Started" else "Next",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isLast) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant.copy(alpha = 0.6f))
                ) {
                    Text("Skip", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    val cs = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "livePulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveAlpha"
    )

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = cs.tertiaryContainer,
        border = BorderStroke(1.dp, cs.tertiary.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(cs.tertiary.copy(alpha = alpha), CircleShape)
            )
            Text(
                text = "LIVE",
                color = cs.tertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun PillBadge(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = cs.primaryContainer,
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = cs.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun SessionCard(
    uiState: CaptionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPushToTalkToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PillBadge(text = uiState.selectedLanguage.label)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = cs.secondaryContainer,
                    border = BorderStroke(1.dp, cs.secondary.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = uiState.captureMode.label,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = cs.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (!uiState.isRunning) {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics { contentDescription = "Start caption session" },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.tertiaryContainer,
                    contentColor = cs.tertiary
                )
            ) {
                Text(
                    text = "Start Session",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp
                )
            }
        } else {
            MicLevelBar(levelDb = uiState.inputLevelDb)

            if (uiState.captureMode == CaptureMode.PUSH_TO_TALK) {
                PushToTalkButton(
                    isActive = uiState.isPushToTalkActive,
                    onToggle = onPushToTalkToggle
                )
            }

            OutlinedButton(
                onClick = {
                    if (uiState.captureMode == CaptureMode.PUSH_TO_TALK && uiState.isPushToTalkActive) {
                        onPushToTalkToggle()
                    }
                    onStop()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "Stop Session",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            text = uiState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = if (uiState.isRunning) cs.tertiary.copy(alpha = 0.8f) else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = cs.background,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    text = "Open on any browser on your network",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.65f)
                )
                Text(
                    text = uiState.captionDisplayUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.captionDisplayUrl.isNotBlank() && !uiState.captionDisplayUrl.contains("0.0.0.0")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    QrCodeImage(
                        url = uiState.captionDisplayUrl,
                        modifier = Modifier
                            .size(160.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeImage(url: String, modifier: Modifier = Modifier) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "QR code",
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
}

@Composable
private fun MicLevelBar(levelDb: Float) {
    val cs = MaterialTheme.colorScheme
    val normalized = ((levelDb + 4f) / 16f).coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = normalized,
        animationSpec = tween(durationMillis = 80),
        label = "micLevel"
    )
    val barColor by animateColorAsState(
        targetValue = when {
            animatedLevel > 0.7f -> cs.tertiary
            animatedLevel > 0.3f -> cs.primary
            else -> cs.onSurfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(120),
        label = "barColor"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Mic Level",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Text(
                text = "${(animatedLevel * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (animatedLevel > 0.5f) cs.tertiary else cs.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { animatedLevel },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Microphone level ${(animatedLevel * 100).toInt()} percent"
                },
            color = barColor,
            trackColor = cs.outlineVariant
        )
    }
}

@Composable
private fun PushToTalkButton(isActive: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val buttonScale by animateFloatAsState(
        targetValue = if (isActive) 1.03f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "pttScale"
    )
    val pulseTransition = rememberInfiniteTransition(label = "pttPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pttPulseScale"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pttPulseAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (isActive) "Listening — tap to stop" else "Tap to start listening",
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) cs.error else cs.onSurfaceVariant
        )
        Box(
            modifier = Modifier.size(136.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(pulseScale)
                        .background(
                            color = cs.error.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
            }
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .size(82.dp)
                    .scale(buttonScale),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) cs.errorContainer else cs.tertiaryContainer,
                    contentColor = if (isActive) cs.error else cs.tertiary
                ),
                border = BorderStroke(
                    2.dp,
                    if (isActive) cs.error.copy(alpha = 0.65f) else cs.tertiary.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (isActive) "STOP" else "START",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isActive) "ACTIVE" else "READY",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechSettingsCard(
    uiState: CaptionUiState,
    onLanguageSelected: (LanguageProfile) -> Unit,
    onModeSelected: (RecognitionMode) -> Unit,
    onCaptureModeSelected: (CaptureMode) -> Unit,
    onDownloadVoskModel: () -> Unit,
    onCancelVoskDownload: () -> Unit,
    onDeleteVoskModel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    BaseCard {
        Text(
            text = "Speech Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface
        )
        DropdownSelector(
            label = "Language",
            selected = uiState.selectedLanguage,
            options = LanguageProfile.all,
            optionText = { it.label },
            onSelected = onLanguageSelected
        )
        SegmentSelector(
            label = "Recognition Mode",
            options = RecognitionMode.entries,
            selected = uiState.recognitionMode,
            optionLabel = { it.label },
            onSelected = onModeSelected
        )
        if (uiState.recognitionMode == RecognitionMode.OFFLINE) {
            VoskModelBanner(
                status = uiState.voskModelStatus,
                progress = uiState.voskDownloadProgress,
                canDelete = !uiState.isRunning,
                onDownload = onDownloadVoskModel,
                onCancel = onCancelVoskDownload,
                onDelete = onDeleteVoskModel
            )
            if (uiState.selectedLanguage.recognizerTag != "en-US") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = cs.errorContainer
                ) {
                    Text(
                        text = "Offline mode only supports English. Urdu recognition requires Online mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
        SegmentSelector(
            label = "Capture Mode",
            options = CaptureMode.entries,
            selected = uiState.captureMode,
            optionLabel = { it.label },
            onSelected = onCaptureModeSelected
        )
    }
}

@Composable
private fun VoskModelBanner(
    status: VoskModelManager.Status,
    progress: Float,
    canDelete: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (status == VoskModelManager.Status.READY) cs.surfaceVariant else cs.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (status) {
                VoskModelManager.Status.READY -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (canDelete) "Offline model ready (~50 MB)"
                                   else "Offline model ready (~50 MB) — stop to delete",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        TextButton(onClick = onDelete, enabled = canDelete) {
                            Text(
                                "Delete",
                                color = if (canDelete) cs.error else cs.onSurfaceVariant.copy(alpha = 0.38f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                VoskModelManager.Status.DOWNLOADING -> {
                    Text(
                        text = "Downloading offline model… ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSecondaryContainer
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = cs.secondary,
                        trackColor = cs.secondaryContainer
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
                VoskModelManager.Status.ERROR -> {
                    Text(
                        text = "Download failed. Check connection and retry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry Download")
                    }
                }
                else -> {
                    Text(
                        text = "Offline mode requires the Vosk model (~50 MB, downloaded once).",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSecondaryContainer
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download Offline Model")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorCard(uiState: CaptionUiState) {
    val cs = MaterialTheme.colorScheme
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Monitor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            )
            if (uiState.isRunning) LiveBadge()
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = cs.background,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val lastLine = uiState.finalizedLines.lastOrNull()
                if (lastLine != null) {
                    Text(
                        text = lastLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "Captions will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                        fontStyle = FontStyle.Italic
                    )
                }

                if (uiState.partialLine.isNotBlank()) {
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "≫",
                            color = cs.primary.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = uiState.partialLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.primary.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    sectionContent: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    BaseCard(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                if (!expanded) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(
                onClick = onToggle,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.primary)
            ) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (expanded) {
            sectionContent()
        }
    }
}

@Composable
private fun ColumnScope.CalibrationContent(
    uiState: CaptionUiState,
    onStartMicCalibration: () -> Unit,
    onCancelMicCalibration: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = uiState.micCalibrationGuidance,
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )

    LinearProgressIndicator(
        progress = { uiState.micCalibrationProgress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = cs.tertiary,
        trackColor = cs.outlineVariant
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(9.dp),
            color = cs.background,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(9.dp)) {
                Text(text = "Phase", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(text = uiState.micCalibrationPhase, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(9.dp),
            color = cs.background,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(9.dp)) {
                Text(text = "Quality", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(text = uiState.micCalibrationQuality, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            }
        }
    }

    Text(
        text = "Ambient ${formatDb(uiState.micCalibrationAmbientDb)}  ·  Speech ${formatDb(uiState.micCalibrationSpeechDb)}  ·  Δ ${formatDb(uiState.micCalibrationDeltaDb)}",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStartMicCalibration,
            enabled = uiState.isRunning && !uiState.isMicCalibrating,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.tertiaryContainer,
                contentColor = cs.tertiary,
                disabledContainerColor = cs.outlineVariant.copy(alpha = 0.25f),
                disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Text("Calibrate", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onCancelMicCalibration,
            enabled = uiState.isMicCalibrating,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
            border = BorderStroke(1.dp, cs.error.copy(alpha = 0.4f))
        ) {
            Text("Cancel", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ColumnScope.AdvancedContent(
    uiState: CaptionUiState,
    onCustomVocabularyChanged: (String) -> Unit,
    onApplyCustomVocabulary: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text("Terms: ${uiState.customVocabularyCount}") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = cs.primaryContainer,
                selectedLabelColor = cs.primary
            ),
            border = FilterChipDefaults.filterChipBorder(
                selected = true, enabled = true,
                selectedBorderColor = cs.primary.copy(alpha = 0.4f),
                borderColor = cs.outlineVariant
            )
        )
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text("Rules: ${uiState.adaptiveRuleCount}") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = cs.secondaryContainer,
                selectedLabelColor = cs.secondary
            ),
            border = FilterChipDefaults.filterChipBorder(
                selected = true, enabled = true,
                selectedBorderColor = cs.secondary.copy(alpha = 0.4f),
                borderColor = cs.outlineVariant
            )
        )
    }

    OutlinedTextField(
        value = uiState.customVocabularyText,
        onValueChange = onCustomVocabularyChanged,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(12.dp),
        label = { Text("Custom Vocabulary") },
        placeholder = { Text("Names, terms — separated by commas") },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = cs.outlineVariant,
            focusedLabelColor = cs.primary,
            unfocusedLabelColor = cs.onSurfaceVariant,
            focusedTextColor = cs.onSurface,
            unfocusedTextColor = cs.onSurface,
            cursorColor = cs.primary,
            focusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
            unfocusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.5f)
        )
    )

    Button(
        onClick = onApplyCustomVocabulary,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.primaryContainer,
            contentColor = cs.primary
        )
    ) {
        Text("Save Vocabulary", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ColumnScope.DisplaySettingsContent(
    uiState: CaptionUiState,
    onFontScaleChanged: (Int) -> Unit,
    onThemeChanged: (CaptionThemePreset) -> Unit,
    onShowPartialChanged: (Boolean) -> Unit,
    onMaxLinesChanged: (Int) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onLetterSpacingChanged: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = "Colour Theme",
        style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant,
        letterSpacing = 0.4.sp
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CaptionThemePreset.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { preset ->
                    val selected = uiState.captionTheme == preset
                    FilterChip(
                        selected = selected,
                        onClick = { onThemeChanged(preset) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            Color(android.graphics.Color.parseColor("#" + preset.textHex)),
                                            CircleShape
                                        )
                                )
                                Text(preset.label, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.primary,
                            containerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                            labelColor = cs.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            selected = selected, enabled = true,
                            selectedBorderColor = cs.primary.copy(alpha = 0.5f),
                            borderColor = cs.outlineVariant
                        )
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Text Size", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            text = "${uiState.captionFontScale}%",
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            fontWeight = FontWeight.Bold
        )
    }
    Slider(
        value = uiState.captionFontScale.toFloat(),
        onValueChange = { onFontScaleChanged(it.toInt()) },
        valueRange = 85f..200f,
        steps = ((200 - 85) / 5) - 1,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = cs.primary,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.outlineVariant
        )
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Visible Lines", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            (2..6).forEach { n ->
                val sel = n == uiState.captionMaxLines
                FilterChip(
                    selected = sel,
                    onClick = { onMaxLinesChanged(n) },
                    label = { Text("$n", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cs.primaryContainer,
                        selectedLabelColor = cs.primary,
                        containerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                        labelColor = cs.onSurfaceVariant
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        selected = sel, enabled = true,
                        selectedBorderColor = cs.primary.copy(alpha = 0.5f),
                        borderColor = cs.outlineVariant
                    )
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Line Spacing", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            text = String.format(Locale.US, "%.1f", uiState.captionLineSpacing),
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            fontWeight = FontWeight.Bold
        )
    }
    Slider(
        value = uiState.captionLineSpacing,
        onValueChange = { onLineSpacingChanged(it) },
        valueRange = 1.0f..1.8f,
        steps = 7,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = cs.primary,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.outlineVariant
        )
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Letter Spacing", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(
            text = String.format(Locale.US, "%.2fem", uiState.captionLetterSpacing),
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            fontWeight = FontWeight.Bold
        )
    }
    Slider(
        value = uiState.captionLetterSpacing,
        onValueChange = { onLetterSpacingChanged(it) },
        valueRange = 0f..0.08f,
        steps = 7,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = cs.primary,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.outlineVariant
        )
    )

    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

    SettingsToggleRow(
        label = "Show Partial Text in Browser",
        description = "Live in-progress words shown below captions",
        checked = uiState.showPartialInBrowser,
        onCheckedChange = onShowPartialChanged
    )
}

@Composable
private fun ColumnScope.DeviceSettingsContent(
    uiState: CaptionUiState,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onFullBrightnessChanged: (Boolean) -> Unit,
    onVibrationEnabledChanged: (Boolean) -> Unit,
    onAutoStartOnLaunchChanged: (Boolean) -> Unit,
) {
    SettingsToggleRow(
        label = "Keep Screen On",
        description = "Prevent the display from sleeping while the app is open",
        checked = uiState.keepScreenOn,
        onCheckedChange = onKeepScreenOnChanged
    )
    SettingsToggleRow(
        label = "Full Brightness",
        description = "Maximise screen brightness while captions are running",
        checked = uiState.fullBrightnessWhenRunning,
        onCheckedChange = onFullBrightnessChanged
    )
    SettingsToggleRow(
        label = "Vibrate on Voice",
        description = "Short haptic pulse each time speech is detected",
        checked = uiState.vibrationEnabled,
        onCheckedChange = onVibrationEnabledChanged
    )
    SettingsToggleRow(
        label = "Auto-Start",
        description = "Begin a caption session immediately when the app opens",
        checked = uiState.autoStartOnLaunch,
        onCheckedChange = onAutoStartOnLaunchChanged
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // Wrap entire row as a single toggleable for screen readers: one tap target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp minimum touch target height per WCAG 2.5.5
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = if (checked) "Turn off $label" else "Turn on $label"
            ) { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. $description"
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = if (checked) "On" else "Off"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null, // handled by row clickable above
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.primary,
                checkedTrackColor = cs.primaryContainer,
                checkedBorderColor = cs.primary.copy(alpha = 0.5f),
                uncheckedThumbColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
                uncheckedTrackColor = cs.surfaceVariant,
                uncheckedBorderColor = cs.outlineVariant
            )
        )
    }
}

@Composable
private fun BaseCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun <T> DropdownSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            letterSpacing = 0.4.sp
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface),
                border = BorderStroke(1.dp, cs.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = optionText(selected),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(text = "▾", color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.95f)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = optionText(option),
                                color = if (option == selected) cs.primary else cs.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentSelector(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            letterSpacing = 0.4.sp
        )
        if (options.size > 2) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelected(option) },
                        label = {
                            Text(
                                text = optionLabel(option),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.primary,
                            containerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                            labelColor = cs.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            selected = option == selected, enabled = true,
                            selectedBorderColor = cs.primary.copy(alpha = 0.5f),
                            borderColor = cs.outlineVariant
                        )
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelected(option) },
                        label = {
                            Text(
                                text = optionLabel(option),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.primary,
                            containerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                            labelColor = cs.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            selected = option == selected, enabled = true,
                            selectedBorderColor = cs.primary.copy(alpha = 0.5f),
                            borderColor = cs.outlineVariant
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AttributionsSection() {
    val cs = MaterialTheme.colorScheme
    DrawerSection("Attributions") {
        AttributionRow(
            name = "Vosk Offline Speech Recognition",
            detail = "Apache 2.0 · alphacephei.com/vosk"
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        AttributionRow(
            name = "NanoHTTPD Web Server",
            detail = "BSD-3-Clause · github.com/NanoHttpd/nanohttpd"
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        AttributionRow(
            name = "Android SpeechRecognizer API",
            detail = "Provided by the Android platform (Google)"
        )
    }
}

@Composable
private fun AttributionRow(name: String, detail: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun PrivacySection() {
    val cs = MaterialTheme.colorScheme
    DrawerSection("Privacy & Data") {
        PrivacyRow(
            label = stringResource(R.string.privacy_author_label),
            body = stringResource(R.string.privacy_author)
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        PrivacyRow(
            label = stringResource(R.string.privacy_audio_label),
            body = stringResource(R.string.privacy_audio)
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        PrivacyRow(
            label = stringResource(R.string.privacy_network_label),
            body = stringResource(R.string.privacy_network)
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        PrivacyRow(
            label = stringResource(R.string.privacy_no_account_label),
            body = stringResource(R.string.privacy_no_account)
        )
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        PrivacyRow(
            label = stringResource(R.string.privacy_permissions_label),
            body = stringResource(R.string.privacy_permissions)
        )
    }
}

@Composable
private fun PrivacyRow(label: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

private fun formatDb(value: Float?): String {
    val v = value ?: return "-- dB"
    return String.format(Locale.US, "%.1f dB", v)
}
