package com.interviewassistant.app.ui.live

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.AppDialogHost
import com.interviewassistant.app.ui.components.AppDialogResult
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.DialogAction
import com.interviewassistant.app.ui.components.DialogButtonRole
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.DialogRequest
import com.interviewassistant.app.ui.components.HighContrast
import com.interviewassistant.app.ui.components.IaCard
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaLevelMeter
import com.interviewassistant.app.ui.components.IaPopover
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.ui.components.PopoverController
import com.interviewassistant.app.ui.components.QuickHelpContentView
import com.interviewassistant.app.ui.components.Seg
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.components.ToastHost
import com.interviewassistant.app.ui.components.iaButtonFocusRing
import com.interviewassistant.app.ui.home.badgeGlyph
import com.interviewassistant.app.ui.home.bindableState
import com.interviewassistant.app.ui.home.brush
import com.interviewassistant.app.ui.home.changeCounter
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.FocusPlacement
import com.interviewassistant.app.uistate.FocusPreset
import com.interviewassistant.app.uistate.LiveChrome
import com.interviewassistant.app.uistate.LiveChromeLayout
import com.interviewassistant.app.uistate.QuickHelp
import com.interviewassistant.app.uistate.QuickHelpHotkeys
import com.interviewassistant.app.uistate.ReadinessIndicator
import com.interviewassistant.app.uistate.ReadinessPresentation
import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.viewmodels.BulletVm
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.FlowDirection
import com.interviewassistant.app.viewmodels.MainViewModel
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.live.Presentation
import java.io.File
import kotlinx.coroutines.launch

/**
 * The Live Assistant destination (MainWindow, TRANSFER-MAP §3.2 row 1): the single-activity
 * counterpart of the Windows Live window, hosted by MainActivity next to Home (DC-143). It draws
 * exactly the state [MainViewModel] exposes — header chrome decided by the pure
 * [LiveChrome] rule, the setup empty state, the question card, the live transcript, the answer
 * card (bullets / Coach / profile-gap), the action and manual bars, Quick help and the More menu,
 * toasts and in-app dialogs through its own hosts.
 *
 * The capture service behind the meter and the session is the P5 platform service
 * (`platform/audio/AudioCaptureService`). Window behaviour maps through the D8 substitutes
 * (DC-162): no minimize/maximize/close buttons (the activity owns them, DC-137), the saved
 * window opacity applies as the root's alpha, Topmost stays the persisted switch the readiness
 * check reads (DC-153), and Interview Focus placement is the platform's PiP act behind the
 * FocusPlatform seam (DC-161, resolved in P5) — its dialog, presets and persistence are here.
 */
@Composable
fun LiveScreen(
    vm: MainViewModel,
    dialogs: DialogController,
    toasts: ToastController,
    onOpenHome: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    SyncHighContrastWithPlatform()

    // A language switch rebuilds the destination's composition (the C# re-renders the window);
    // view-model state is untouched.
    var languageTick by remember { mutableIntStateOf(0) }
    DisposableEffect(vm) {
        val listener: () -> Unit = { languageTick++ }
        Loc.addLanguageChangedListener(listener)
        onDispose { Loc.removeLanguageChangedListener(listener) }
    }

    val direction = if (Loc.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Box(modifier.fillMaxSize().background(colors.Bg)) {
            key(languageTick) {
                LiveContent(vm, dialogs, toasts, onOpenHome, onOpenFile)
            }
            ToastHost(toasts)
            AppDialogHost(dialogs)
        }
    }
}

/** The platform high-contrast state drives the theme (the Home shell's sync, DC-148, for this destination). */
@Composable
private fun SyncHighContrastWithPlatform() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor("high_text_contrast_enabled")
        fun read() {
            HighContrast.isOn = try {
                Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1
            } catch (ignored: Exception) {
                false
            }
        }
        read()
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = read()
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
}

/** The readiness button's tooltip (MainWindow.Chrome.cs `RefreshReadiness`): the run-hint before a first result. */
internal fun liveReadinessTooltip(state: ReadinessState, summary: String): String {
    val label = Loc.t(ReadinessSnapshot.stateKey(state))
    return if (state == ReadinessState.NotChecked || state == ReadinessState.Outdated) {
        Loc.f("live_rd_tt_run", label)
    } else {
        Loc.f("live_rd_tt", label, summary)
    }
}

@Composable
private fun LiveContent(
    vm: MainViewModel,
    dialogs: DialogController,
    toasts: ToastController,
    onOpenHome: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // One recomposition trigger for the view model's computed (raised, not flow-backed) surface.
    vm.changeCounter(
        "statusText", "statusBrush", "statusPulse", "statusDetail", "question", "hasQuestionText",
        "questionFlowDirection", "liveTranscript", "liveTranscriptFlowDirection", "pending", "note",
        "answerMeta", "isListening", "isPaused", "startPauseLabel", "isCoachView", "isProfileGap",
        "isThinking", "coachKeywords", "coachStructure", "coachReminder", "contextSummary",
        "compactSpacing", "answerFontSize", "coachFontSize", "questionFontSize", "answerLineHeight",
        "historyPosition", "historyLabel", "presentation", "presentationLabel", "minutesLeftLabel",
        "minutesTooltip", "serviceBanner", "adaptiveLabel", "lastReportPath", "showLiveIntro",
        "lQuestion", "lAnswer", "lWaiting", "lTypeHint",
    )

    // The bullets list mutates in place; its change event is the recomposition trigger.
    var bulletsTick by remember { mutableIntStateOf(0) }
    DisposableEffect(vm) {
        val listener: () -> Unit = { bulletsTick++ }
        vm.bulletsChangedListeners += listener
        onDispose { vm.bulletsChangedListeners -= listener }
    }

    // The readiness snapshot lives in its store; its change event re-renders the indicator.
    var readinessTick by remember { mutableIntStateOf(0) }
    DisposableEffect(vm) {
        val listener: (ReadinessSnapshot) -> Unit = { readinessTick++ }
        vm.readiness.addChangedListener(listener)
        onDispose { vm.readiness.removeChangedListener(listener) }
    }

    val sessionActive = vm.sessionActive
    var compact by remember { mutableStateOf(vm.settings.compactMode) }
    val opacity = vm.settings.windowOpacity.coerceIn(0.75, 1.0).toFloat()

    val quickHelp = remember { PopoverController() }
    val more = remember { PopoverController() }
    val helpAnchor = remember { FocusRequester() }
    val moreAnchor = remember { FocusRequester() }
    val quickHelpClose = remember { FocusRequester() }

    // Quick help closing remembers the intro as seen (the C#'s Popover.Closed handler).
    DisposableEffect(vm, quickHelp) {
        val listener: () -> Unit = { vm.dismissLiveIntro(remember = true) }
        quickHelp.addClosedListener(listener)
        onDispose { quickHelp.removeClosedListener(listener) }
    }

    fun openQuickHelp() {
        more.close(restoreFocus = false)
        vm.showLiveIntro = true
        quickHelp.open()
    }

    // First use: the intro opens by itself once, never over an open dialog (MainWindow.QuickHelp.cs).
    var firstUsePending by remember { mutableStateOf(!vm.settings.liveIntroDismissed) }
    LaunchedEffect(Unit) {
        if (firstUsePending && !dialogs.isOpen) {
            firstUsePending = false
            openQuickHelp()
        }
    }

    val hasFocusRestore = (vm.settings.focusRestoreWidth ?: 0.0) > 0 && (vm.settings.focusRestoreHeight ?: 0.0) > 0

    fun restoreFocusPlacement() {
        // Restoring is the platform placer's act (P5: leaving PiP, D9) behind the FocusPlatform
        // seam; the record it reads is the one applying Focus wrote (DC-161, resolved in P5).
        if (!hasFocusRestore) return
        com.interviewassistant.app.platform.FocusPlatform.restoreFocus()
    }

    fun applyFocus(preset: FocusPreset) {
        // The chosen preset is remembered exactly like the C#'s; the platform act (P5: entering
        // PiP while a session is active, D9) runs behind the FocusPlatform seam (DC-161).
        vm.settings.interviewFocusPreset = preset.name
        vm.settings.save()
        com.interviewassistant.app.platform.FocusPlatform.applyFocus(preset)
    }

    fun showFocusDialog() {
        scope.launch {
            val s = vm.settings
            val first = !s.interviewFocusIntroSeen
            val presets = FocusPreset.entries
            val choice = object {
                var index by mutableStateOf(presets.indexOf(FocusPlacement.parse(s.interviewFocusPreset)))
            }
            val actions = mutableListOf(
                DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
            )
            if (hasFocusRestore) {
                actions += DialogAction(text = Loc.t("focus_restore"), result = AppDialogResult.Secondary)
            }
            actions += DialogAction(
                text = Loc.t("focus_apply"),
                result = AppDialogResult.Primary,
                role = DialogButtonRole.Primary,
                isDefault = true,
            )
            val result = dialogs.show(
                DialogRequest(
                    title = Loc.t(if (first) "focus_title_first" else "focus_title"),
                    message = Loc.t(if (first) "focus_body_first" else "focus_body"),
                    tone = DialogTone.Info,
                    body = {
                        IaFormField(label = Loc.t("focus_position")) {
                            Seg.Track(
                                labels = presets.map { Loc.t(FocusPlacement.key(it)) },
                                selectedIndex = choice.index,
                                onSelect = { choice.index = it },
                                description = Loc.t("focus_position"),
                            )
                        }
                    },
                    actions = actions,
                    maxWidth = 480.dp,
                ),
            )
            if (first) {
                s.interviewFocusIntroSeen = true
                s.save()
            }
            when (result) {
                AppDialogResult.Secondary -> restoreFocusPlacement()
                AppDialogResult.Primary -> applyFocus(presets[choice.index])
                else -> Unit
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().alpha(opacity)) {
        val chrome = LiveChrome.`for`(sessionActive = sessionActive, compact = compact, width = maxWidth.value.toDouble())
        Column(Modifier.fillMaxSize()) {
            LiveHeader(
                vm = vm,
                chrome = chrome,
                compact = compact,
                quickHelp = quickHelp,
                more = more,
                helpAnchor = helpAnchor,
                moreAnchor = moreAnchor,
                quickHelpClose = quickHelpClose,
                onOpenQuickHelp = { openQuickHelp() },
                onOpenHome = onOpenHome,
                onOpenFile = onOpenFile,
                onToggleCompact = {
                    compact = !compact
                    vm.settings.compactMode = compact
                    vm.settings.save()
                },
                onShowFocusDialog = { showFocusDialog() },
                onRestoreFocus = { restoreFocusPlacement() },
                hasFocusRestore = hasFocusRestore,
                toasts = toasts,
            )
            LiveBody(
                vm = vm,
                chrome = chrome,
                compact = compact,
                onOpenHome = onOpenHome,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (!compact) {
                LiveActionBar(vm)
                LiveManualBar(vm)
            }
        }
    }
}

/** The 0.9 s ease-in-out pulse of the status dot and the thinking dots (the XAML `Pulse` storyboard). */
@Composable
private fun pulseAlpha(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    return if (active) value else 1f
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun LiveHeader(
    vm: MainViewModel,
    chrome: LiveChromeLayout,
    compact: Boolean,
    quickHelp: PopoverController,
    more: PopoverController,
    helpAnchor: FocusRequester,
    moreAnchor: FocusRequester,
    quickHelpClose: FocusRequester,
    onOpenQuickHelp: () -> Unit,
    onOpenHome: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleCompact: () -> Unit,
    onShowFocusDialog: () -> Unit,
    onRestoreFocus: () -> Unit,
    hasFocusRestore: Boolean,
    toasts: ToastController,
) {
    val colors = LocalIaColors.current
    val stopEnabled by vm.stopCommand.canExecuteFlow.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(vm, Modifier.weight(1f, fill = false))

        // Optional chips: shed first when the header is narrow (LiveChrome); the More menu takes them.
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chrome.adaptiveBadge && vm.adaptiveLabel.isNotEmpty()) {
                HeaderChip(
                    text = vm.adaptiveLabel,
                    background = colors.AccentSoft,
                    textColor = colors.Text,
                    description = Loc.t("tt_adaptive"),
                    onClick = {
                        vm.resetAdaptive()
                        toasts.show(Loc.t("toast_adaptive_reset"), com.interviewassistant.app.ui.components.ToastKind.Info, nowMs = System.currentTimeMillis())
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (chrome.presentationChip) {
                HeaderChip(
                    text = vm.presentationLabel,
                    background = Color.Transparent,
                    textColor = colors.TextDim,
                    description = Loc.t("tt_presentation"),
                    onClick = { vm.toggleCoach() },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (chrome.minutesLabel && vm.minutesLeftLabel.isNotEmpty()) {
                Text(
                    vm.minutesLeftLabel,
                    style = IaTextStyles.body(colors).copy(fontSize = 10.5.sp, color = colors.TextFaint),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .semantics { contentDescription = vm.minutesTooltip },
                )
            }
        }

        // Interview controls: Start/Pause first, then Stop, readiness, Quick help, More.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveCoreButton(
                onClick = { vm.startPauseCommand.execute() },
                description = vm.startPauseLabel,
                background = colors.AccentStrong,
                borderColor = colors.AccentStrong,
                horizontalPadding = if (chrome.startLabel) 12.dp else 9.dp,
            ) {
                IaIcon(
                    if (vm.isListening) IaGlyph.Pause else IaGlyph.Play,
                    12.dp,
                    colors.OnAccentStrong,
                    null,
                )
                if (chrome.startLabel) {
                    Text(
                        vm.startPauseLabel,
                        style = IaTextStyles.buttonStrong(colors, colors.OnAccentStrong),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).widthIn(max = 140.dp),
                    )
                }
            }
            Box(Modifier.width(4.dp))
            HeaderIconButton(
                glyph = IaGlyph.Stop,
                description = Loc.t("tt_stop"),
                enabled = stopEnabled,
                onClick = { vm.stopCommand.execute() },
            )
            ReadinessButton(vm, chrome, onClick = { onOpenHome("preinterview") })
            IaPopover(
                controller = quickHelp,
                anchorFocusRequester = helpAnchor,
                contentFocusRequester = quickHelpClose,
                anchor = {
                    HeaderIconButton(
                        glyph = IaGlyph.Help,
                        description = Loc.t("tt_help"),
                        onClick = { if (quickHelp.isOpen) quickHelp.close() else onOpenQuickHelp() },
                        modifier = Modifier.focusRequester(helpAnchor),
                    )
                },
                content = {
                    QuickHelpCard(vm, quickHelp, quickHelpClose, onOpenHome)
                },
            )
            IaPopover(
                controller = more,
                anchorFocusRequester = moreAnchor,
                anchor = {
                    HeaderIconButton(
                        glyph = IaGlyph.More,
                        description = Loc.t("tt_more"),
                        onClick = { more.toggle() },
                        modifier = Modifier.focusRequester(moreAnchor),
                    )
                },
                content = {
                    MoreCard(
                        vm = vm,
                        chrome = chrome,
                        compact = compact,
                        more = more,
                        onOpenHome = onOpenHome,
                        onOpenFile = onOpenFile,
                        onToggleCompact = onToggleCompact,
                        onShowFocusDialog = onShowFocusDialog,
                        onRestoreFocus = onRestoreFocus,
                        hasFocusRestore = hasFocusRestore,
                    )
                },
            )
        }
    }
}

/** The status pill: pulsing dot, the meter (max 56) and the status text; the detail is its description. */
@Composable
private fun StatusPill(vm: MainViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val level by vm.bindableState("audioLevel") { vm.audioLevel }
    val pulse = pulseAlpha(vm.statusPulse)
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(14.dp))
            .semantics { if (vm.statusDetail != null) contentDescription = vm.statusDetail!! }
            .padding(start = 10.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulse)
                .clip(CircleShape)
                .background(colors.brush(vm.statusBrush)),
        )
        Text(
            vm.statusText,
            style = IaTextStyles.body(colors).copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brush(vm.statusBrush),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f, fill = false)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IaLevelMeter(
            level = level,
            modifier = Modifier.padding(start = 10.dp).width(56.dp),
            height = 4.dp,
            description = Loc.t("tt_audio_meter"),
        )
    }
}

/** The readiness indicator: a labelled pill in setup mode, a dot during the interview (LiveChrome). */
@Composable
private fun ReadinessButton(vm: MainViewModel, chrome: LiveChromeLayout, onClick: () -> Unit) {
    val colors = LocalIaColors.current
    val state = vm.readinessState
    val label = Loc.t(ReadinessSnapshot.stateKey(state))
    val summary = ReadinessPresentation.summary(vm.readiness.snapshot, vm.readinessContext)
    val summaryText = if (summary.args.isEmpty()) {
        Loc.t(summary.key)
    } else {
        Loc.f(summary.key, *summary.args.toTypedArray())
    }
    val tip = liveReadinessTooltip(state, summaryText)
    val labelled = chrome.readiness == ReadinessIndicator.Label
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(if (labelled) colors.Surface2 else Color.Transparent)
            .border(
                IaTokens.BorderWidth,
                if (labelled) colors.Border else Color.Transparent,
                RoundedCornerShape(IaTokens.RadiusControl),
            )
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = tip }
            .padding(
                start = if (labelled) 9.dp else 8.dp,
                top = if (labelled) 4.dp else 5.dp,
                end = if (labelled) 10.dp else 8.dp,
                bottom = if (labelled) 4.dp else 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IaIcon(badgeGlyph(ReadinessPresentation.icon(state)), 11.dp, colors.brush(ReadinessSnapshot.brushKey(state)), null)
        if (labelled) {
            Text(
                label,
                style = IaTextStyles.body(colors).copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp),
            )
        }
    }
}

/** Quick help card: the state-built content plus the "Got it" button (the action links are the view's own). */
@Composable
private fun QuickHelpCard(
    vm: MainViewModel,
    controller: PopoverController,
    closeRequester: FocusRequester,
    onOpenHome: (String?) -> Unit,
) {
    val colors = LocalIaColors.current
    val signInRequired = vm.usingCloud && vm.cloud.isConfigured && !vm.cloud.isSignedIn
    val state = QuickHelp.stateFor(signInRequired, vm.isListening, vm.isPaused)
    val content = QuickHelp.`for`(
        state,
        answerShown = vm.bullets.isNotEmpty() || vm.isCoachView,
        hk = QuickHelpHotkeys(
            vm.settings.hotkeyStartPause,
            vm.settings.hotkeyManualInput,
            vm.settings.hotkeyToggleWindow,
            vm.settings.hotkeyCoachToggle,
        ),
    )
    Column(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            .width(340.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface2)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
    ) {
        QuickHelpContentView(
            content = content,
            onAction = { route ->
                controller.close(restoreFocus = false)
                onOpenHome(route)
            },
            onFullHelp = {
                controller.close(restoreFocus = false)
                onOpenHome("help")
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Spacer(Modifier.weight(1f))
            Btn.Primary(
                Loc.t("live_intro_got_it"),
                onClick = { controller.close(restoreFocus = true) },
                modifier = Modifier.focusRequester(closeRequester),
            )
        }
    }
}

/** The More (overflow) card: what the header has no room for, plus the view and navigation rows. */
@Composable
private fun MoreCard(
    vm: MainViewModel,
    chrome: LiveChromeLayout,
    compact: Boolean,
    more: PopoverController,
    onOpenHome: (String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleCompact: () -> Unit,
    onShowFocusDialog: () -> Unit,
    onRestoreFocus: () -> Unit,
    hasFocusRestore: Boolean,
) {
    val colors = LocalIaColors.current
    Column(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            .widthIn(min = 230.dp, max = 320.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface2)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .padding(6.dp),
    ) {
        MenuRow(
            glyph = IaGlyph.Compact,
            label = Loc.t("more_compact"),
            checked = compact,
            itemStatus = compact,
        ) {
            more.close(restoreFocus = true)
            onToggleCompact()
        }
        if (chrome.presentationInMore) {
            val coach = vm.presentation == Presentation.Coach
            MenuRow(
                glyph = IaGlyph.List,
                label = Loc.t("more_coach"),
                checked = coach,
                itemStatus = coach,
            ) {
                vm.toggleCoach() // the C#'s Presentation_Click: the menu stays open, the check follows
            }
        }
        MenuRow(glyph = IaGlyph.Focus, label = Loc.t("more_focus"), checked = null, itemStatus = null) {
            more.close()
            onShowFocusDialog()
        }
        if (hasFocusRestore) {
            MenuRow(glyph = IaGlyph.FocusRestore, label = Loc.t("focus_restore"), checked = null, itemStatus = null) {
                more.close(restoreFocus = true)
                onRestoreFocus()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(1.dp)
                .background(colors.Border),
        )
        MenuRow(glyph = IaGlyph.Home, label = Loc.t("more_home"), checked = null, itemStatus = null) {
            more.close()
            onOpenHome(null)
        }
        val reportPath = vm.lastReportPath
        if (reportPath != null) {
            MenuRow(glyph = IaGlyph.Report, label = Loc.t("more_last_report"), checked = null, itemStatus = null) {
                more.close()
                if (File(reportPath).exists()) onOpenFile(reportPath)
            }
        }
        MenuRow(glyph = IaGlyph.Settings, label = Loc.t("settings"), checked = null, itemStatus = null) {
            more.close()
            onOpenHome("settings")
        }
        if (chrome.minutesInMore && vm.minutesLeftLabel.isNotEmpty()) {
            Text(
                vm.minutesLeftLabel,
                style = IaTextStyles.body(colors).copy(fontSize = 11.sp, color = colors.TextFaint),
                modifier = Modifier
                    .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 4.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = vm.minutesTooltip
                    },
            )
        }
    }
}

/** One More-menu row: glyph + label + the optional check mark and on/off item status. */
@Composable
private fun MenuRow(
    glyph: IaGlyph,
    label: String,
    checked: Boolean?,
    itemStatus: Boolean?,
    onClick: () -> Unit,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .clickable(role = Role.Button) { onClick() }
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = label
                if (itemStatus != null) stateDescription = Loc.t(if (itemStatus) "a11y_on" else "a11y_off")
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IaIcon(glyph, 13.dp, colors.TextDim, null, modifier = Modifier.padding(end = 10.dp))
        Text(
            label,
            style = IaTextStyles.button(colors).copy(fontSize = 12.5.sp, color = colors.Text),
            modifier = Modifier.weight(1f),
        )
        if (checked == true) {
            IaIcon(IaGlyph.Check, 12.dp, colors.Accent, null, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Body: banner, question card, answer card
// ---------------------------------------------------------------------------

@Composable
private fun LiveBody(
    vm: MainViewModel,
    chrome: LiveChromeLayout,
    compact: Boolean,
    onOpenHome: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    Box(modifier.padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().widthIn(max = 980.dp)) {
            if (vm.serviceBanner.isNotEmpty()) {
                LiveCoreButton(
                    onClick = { onOpenHome("account") },
                    description = vm.serviceBanner,
                    background = colors.Surface2,
                    borderColor = colors.Thinking,
                    horizontalPadding = 10.dp,
                    verticalPadding = 5.dp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(
                        vm.serviceBanner,
                        style = IaTextStyles.body(colors).copy(fontSize = 12.sp, color = colors.Text),
                    )
                }
            }
            QuestionCard(vm, chrome, compact, onOpenHome)
            AnswerCard(
                vm = vm,
                compact = compact,
                onOpenHome = onOpenHome,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun QuestionCard(
    vm: MainViewModel,
    chrome: LiveChromeLayout,
    compact: Boolean,
    onOpenHome: (String?) -> Unit,
) {
    val colors = LocalIaColors.current
    IaCard(Modifier.fillMaxWidth()) {
        Column {
            if (!compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.lQuestion, style = IaTextStyles.label(colors))
                    Spacer(Modifier.weight(1f))
                    if (vm.statusDetail != null) {
                        Text(
                            vm.statusDetail!!,
                            style = IaTextStyles.body(colors).copy(fontSize = 11.sp, color = colors.TextFaint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .semantics { contentDescription = vm.statusDetail!! },
                        )
                    }
                }
            }
            if (vm.hasQuestionText) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (vm.questionFlowDirection == FlowDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Text(
                        vm.question,
                        style = IaTextStyles.body(colors).copy(
                            fontSize = vm.questionFontSize.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.Text,
                        ),
                    )
                }
            }
            if (vm.contextSummary.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Loc.t("live_context")) }
                        append(": ")
                        append(vm.contextSummary)
                    },
                    style = IaTextStyles.body(colors).copy(fontSize = 11.5.sp, color = colors.TextDim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { contentDescription = vm.contextSummary },
                )
            }
            val noQuestion = vm.question.isEmpty()
            if (chrome.setupEmptyState && noQuestion) {
                Column {
                    Text(
                        Loc.t("live_empty_setup"),
                        style = IaTextStyles.body(colors).copy(fontSize = vm.questionFontSize.sp, color = colors.TextDim),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (chrome.homeLink) {
                        LiveLinkRow(
                            glyph = IaGlyph.Home,
                            text = Loc.t("live_open_home"),
                            description = Loc.t("tt_home"),
                            onClick = { onOpenHome(null) },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (!chrome.setupEmptyState && noQuestion) {
                Text(
                    vm.lWaiting,
                    style = IaTextStyles.body(colors).copy(fontSize = vm.questionFontSize.sp, color = colors.TextFaint),
                )
            }
            if (vm.liveTranscript.isNotEmpty()) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (vm.liveTranscriptFlowDirection == FlowDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Text(
                        vm.liveTranscript,
                        style = IaTextStyles.body(colors).copy(
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = colors.TextDim,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(
    vm: MainViewModel,
    compact: Boolean,
    onOpenHome: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val shape = RoundedCornerShape(IaTokens.RadiusCard)

    // ---- auto-fit (AnswerFit.kt): measurements drive the fitter; the fitter drives the VM ----
    val fitter = remember { AnswerFitter() }
    var viewportH by remember { mutableIntStateOf(0) }
    var contentH by remember { mutableIntStateOf(0) }
    var outerW by remember { mutableIntStateOf(0) }
    val bullets = vm.bullets.toList()
    val effectiveViewport = (viewportH - AnswerFitter.SAFETY_PX).coerceAtLeast(0.0)
    val fitKey = AnswerFitter.key(
        viewport = effectiveViewport,
        outerWidth = outerW.toDouble(),
        question = vm.question,
        note = vm.note,
        isCoachView = vm.isCoachView,
        isProfileGap = vm.isProfileGap,
        isThinking = vm.isThinking,
        pending = vm.pending,
        coachKeywords = vm.coachKeywords,
        coachStructure = vm.coachStructure,
        coachReminder = vm.coachReminder,
        fontPreset = vm.settings.fontPreset.name,
        bullets = bullets.map { it.text },
    )
    var fitRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(fitKey, viewportH) {
        if (viewportH > 0 && vm.settings.autoFitAnswer && fitter.begin(fitKey)) {
            fitRun++
            vm.compactSpacing = false
            vm.setFitFontSize(null)
        }
    }
    LaunchedEffect(contentH, viewportH, fitRun) {
        if (viewportH > 0 && vm.settings.autoFitAnswer) {
            when (
                val action = fitter.measured(
                    key = fitKey,
                    fits = contentH.toDouble() <= effectiveViewport,
                    viewport = effectiveViewport,
                    contentHeight = contentH.toDouble(),
                    fontSize = vm.answerFontSize,
                    minFontSize = vm.minAnswerFontSize,
                )
            ) {
                is FitAction.SetCompact -> vm.compactSpacing = action.compact
                is FitAction.SetFontSize -> vm.setFitFontSize(action.size)
                null -> Unit
            }
        }
    }

    Box(
        modifier
            .clip(shape)
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column {
            if (!compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.lAnswer, style = IaTextStyles.label(colors))
                    Spacer(Modifier.weight(1f))
                    Text(
                        vm.answerMeta,
                        style = IaTextStyles.body(colors).copy(fontSize = 11.sp, color = colors.TextFaint),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged {
                        viewportH = it.height
                        outerW = it.width
                    },
            ) {
                val scrollState = rememberScrollState()
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (vm.answerFlowDirection == FlowDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .onSizeChanged { contentH = it.height },
                    ) {
                        AnswerContent(vm, bullets, onOpenHome)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerContent(vm: MainViewModel, bullets: List<BulletVm>, onOpenHome: (String?) -> Unit) {
    val colors = LocalIaColors.current
    if (vm.isCoachView) {
        Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            Text(vm.lKeywords, style = IaTextStyles.label(colors), modifier = Modifier.padding(bottom = 4.dp))
            Text(
                vm.coachKeywords,
                style = IaTextStyles.body(colors).copy(
                    fontSize = vm.coachFontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.Accent,
                ),
            )
            Text(vm.lStructure, style = IaTextStyles.label(colors), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            Text(
                vm.coachStructure,
                style = IaTextStyles.body(colors).copy(fontSize = vm.answerFontSize.sp, color = colors.Text),
            )
            if (!vm.coachReminder.isNullOrEmpty()) {
                Text(
                    vm.coachReminder!!,
                    style = IaTextStyles.body(colors).copy(fontSize = 13.sp, color = colors.TextDim),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        return
    }

    val gap = vm.isProfileGap
    Box {
        if (gap) {
            // The profile-gap card: Thinking-soft ground, a 3 dp accent bar on the reading-order
            // start side, its own title — never presented as the candidate's answer.
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(IaTokens.RadiusControl))
                    .background(colors.ThinkingSoft)
                    .border(IaTokens.BorderWidth, colors.Thinking, RoundedCornerShape(IaTokens.RadiusControl)),
            )
        }
        // The card's 3 dp left accent (the XAML's BorderThickness 3,1,1,1) is drawn with the
        // content column, whose height is the card's height.
        val thinkingColor = colors.Thinking
        Column(
            Modifier
                .then(if (gap) Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 4.dp) else Modifier)
                .then(
                    if (gap) {
                        Modifier.drawBehind { drawRect(color = thinkingColor, size = Size(3.dp.toPx(), size.height)) }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (gap) {
                Row(Modifier.padding(bottom = 10.dp)) {
                    IaIcon(IaGlyph.Info, 16.dp, colors.Thinking, null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                    Column {
                        Text(
                            Loc.t("live_gap_title"),
                            style = IaTextStyles.body(colors).copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.Text,
                            ),
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            Loc.t("live_gap_subtitle"),
                            style = IaTextStyles.body(colors),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            for (bullet in bullets) {
                BulletRow(bullet, vm)
            }
            if (vm.pending.isNotEmpty()) {
                Text(
                    vm.pending,
                    style = IaTextStyles.body(colors).copy(fontSize = vm.answerFontSize.sp, color = colors.TextFaint),
                    modifier = Modifier.padding(start = 22.dp, bottom = 14.dp),
                )
            }
            if (vm.isThinking) {
                Text(
                    "●  ●  ●",
                    style = IaTextStyles.body(colors).copy(fontSize = 12.sp, color = colors.Accent),
                    modifier = Modifier
                        .padding(start = 22.dp, top = 2.dp)
                        .alpha(pulseAlpha(active = true)),
                )
            }
            if (!vm.note.isNullOrEmpty()) {
                Text(
                    vm.note!!,
                    style = IaTextStyles.body(colors).copy(fontSize = 12.sp, color = colors.Thinking),
                    modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                )
            }
            if (gap) {
                LiveLinkRow(
                    glyph = IaGlyph.Add,
                    text = Loc.t("live_gap_add"),
                    description = Loc.t("tt_live_gap_add"),
                    onClick = { onOpenHome("profiles/personal") },
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** One answer bullet: the accent dot (unless a Full-style paragraph) and the text, fading/sliding in. */
@Composable
private fun BulletRow(bullet: BulletVm, vm: MainViewModel) {
    val colors = LocalIaColors.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "bulletAlpha",
    )
    val shift by animateFloatAsState(
        targetValue = if (shown) 0f else 6f,
        animationSpec = tween(220, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)),
        label = "bulletShift",
    )
    Row(
        Modifier
            .padding(bottom = vm.bulletMargin.bottom.dp)
            .offset(y = shift.dp)
            .alpha(alpha),
    ) {
        Box(Modifier.width(22.dp)) {
            if (!bullet.isParagraph) {
                Box(
                    Modifier
                        .padding(start = 2.dp, top = 13.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.Accent),
                )
            }
        }
        Text(
            bullet.text,
            style = IaTextStyles.body(colors).copy(
                fontSize = vm.answerFontSize.sp,
                lineHeight = vm.answerLineHeight.sp,
                color = colors.Text,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A link-styled row with a leading glyph (the setup Home link, the profile-gap "add" action). */
@Composable
private fun LiveLinkRow(
    glyph: IaGlyph,
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .clickable(role = Role.Button) { onClick() }
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IaIcon(glyph, 12.dp, colors.Accent, null, modifier = Modifier.padding(end = 6.dp))
        Text(
            text,
            style = IaTextStyles.button(colors).copy(color = colors.Accent, fontWeight = FontWeight.SemiBold),
        )
    }
}

// ---------------------------------------------------------------------------
// Action bar + manual bar
// ---------------------------------------------------------------------------

@Composable
private fun LiveActionBar(vm: MainViewModel) {
    val colors = LocalIaColors.current
    val shorterEnabled by vm.shorterCommand.canExecuteFlow.collectAsState()
    val technicalEnabled by vm.technicalCommand.canExecuteFlow.collectAsState()
    val exampleEnabled by vm.exampleCommand.canExecuteFlow.collectAsState()
    val fullEnabled by vm.fullCommand.canExecuteFlow.collectAsState()
    val regenerateEnabled by vm.regenerateCommand.canExecuteFlow.collectAsState()
    val previousEnabled by vm.previousCommand.canExecuteFlow.collectAsState()
    val nextEnabled by vm.nextCommand.canExecuteFlow.collectAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = 980.dp)) {
            Row(Modifier.weight(1f)) {
                Btn.Chip(vm.lShorter, onClick = { vm.shorterCommand.execute() }, enabled = shorterEnabled, description = Loc.t("tt_shorter"))
                Btn.Chip(vm.lTechnical, onClick = { vm.technicalCommand.execute() }, enabled = technicalEnabled, description = Loc.t("tt_technical"))
                Btn.Chip(vm.lExample, onClick = { vm.exampleCommand.execute() }, enabled = exampleEnabled, description = Loc.t("tt_example"))
                Btn.Chip(vm.lFull, onClick = { vm.fullCommand.execute() }, enabled = fullEnabled, description = Loc.t("tt_full"))
                LiveCoreButton(
                    onClick = { vm.regenerateCommand.execute() },
                    description = Loc.t("regenerate"),
                    enabled = regenerateEnabled,
                    background = colors.Surface2,
                    borderColor = colors.Border,
                    horizontalPadding = 11.dp,
                    verticalPadding = 5.dp,
                ) {
                    IaIcon(IaGlyph.Refresh, 12.dp, colors.TextDim, null)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderIconButton(
                    glyph = if (rtl) IaGlyph.ChevronRight else IaGlyph.ChevronLeft,
                    description = Loc.t("tt_previous"),
                    enabled = previousEnabled,
                    onClick = { vm.previousCommand.execute() },
                )
                if (vm.historyLabel.isNotEmpty()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            vm.historyLabel,
                            style = IaTextStyles.body(colors).copy(fontSize = 11.sp, color = colors.TextFaint),
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
                HeaderIconButton(
                    glyph = if (rtl) IaGlyph.ChevronLeft else IaGlyph.ChevronRight,
                    description = Loc.t("tt_next"),
                    enabled = nextEnabled,
                    onClick = { vm.nextCommand.execute() },
                )
            }
        }
    }
}

@Composable
private fun LiveManualBar(vm: MainViewModel) {
    val colors = LocalIaColors.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    // The D2 ManualInput shortcut lands here: while the bar is composed it registers the focus
    // action the platform's LiveFocusRequests dispatches to (P5).
    val manualRequester = remember { FocusRequester() }
    DisposableEffect(manualRequester) {
        val listener: () -> Unit = { runCatching { manualRequester.requestFocus() } }
        com.interviewassistant.app.platform.LiveFocusRequests.manualInputListeners += listener
        onDispose {
            com.interviewassistant.app.platform.LiveFocusRequests.manualInputListeners -= listener
        }
    }

    fun submit() {
        val value = text
        text = ""
        scope.launch { vm.submitManualAsync(value) }
    }

    Box(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 980.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Input.Single(
                value = text,
                onValueChange = { text = it },
                placeholder = vm.lTypeHint,
                description = vm.lTypeHint,
                modifier = Modifier
                    .weight(1f)
                    .height(IaTokens.ControlHeight)
                    .focusRequester(manualRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
            Box(Modifier.width(8.dp))
            // Field and send share the control height and one centred row (the XAML's rule).
            LiveCoreButton(
                onClick = { submit() },
                description = Loc.t("tt_send"),
                background = colors.AccentStrong,
                borderColor = colors.AccentStrong,
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
                modifier = Modifier.size(IaTokens.ControlHeight),
            ) {
                IaIcon(IaGlyph.Send, 14.dp, colors.OnAccentStrong, null)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Local button primitives (the Btn family's template values, with content slots)
// ---------------------------------------------------------------------------

/**
 * The button template of the design system (white state overlay, accent focus ring, control
 * radius — never a pill, 0.4 opacity while disabled) with caller content: the Live header's
 * primary button, banner and icon chips build on it, as the XAML builds them on Btn styles.
 */
@Composable
private fun LiveCoreButton(
    onClick: () -> Unit,
    description: String?,
    background: Color,
    borderColor: Color,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    verticalPadding: androidx.compose.ui.unit.Dp = 4.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = LocalIaColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val overlay = when {
        pressed -> 0.14f
        hovered -> 0.07f
        else -> 0f
    }
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(shape)
            .background(background)
            .background(Color.White.copy(alpha = overlay))
            .border(IaTokens.BorderWidth, borderColor, shape)
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (description != null) contentDescription = description }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A header icon button (Btn.HeaderIcon: the ghost family at 6 dp padding). */
@Composable
private fun HeaderIconButton(
    glyph: IaGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalIaColors.current
    LiveCoreButton(
        onClick = onClick,
        description = description,
        background = Color.Transparent,
        borderColor = Color.Transparent,
        horizontalPadding = 6.dp,
        verticalPadding = 6.dp,
        modifier = modifier,
        enabled = enabled,
    ) {
        IaIcon(glyph, 14.dp, colors.TextDim, null)
    }
}

/** A header chip (Btn.Chip at the header's 10.5 sp SemiBold, 8×2 padding). */
@Composable
private fun HeaderChip(
    text: String,
    background: Color,
    textColor: Color,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    LiveCoreButton(
        onClick = onClick,
        description = description,
        background = background,
        borderColor = Color.Transparent,
        horizontalPadding = 8.dp,
        verticalPadding = 2.dp,
        modifier = modifier,
    ) {
        Text(
            text,
            style = IaTextStyles.body(colors).copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = textColor),
        )
    }
}
