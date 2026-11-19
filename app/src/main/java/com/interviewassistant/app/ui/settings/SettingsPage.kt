package com.interviewassistant.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.services.FontPreset
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCheck
import com.interviewassistant.app.ui.components.IaCombo
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaInfoTip
import com.interviewassistant.app.ui.components.IaInlineMessage
import com.interviewassistant.app.ui.components.IaLevelMeter
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.viewmodels.MessageKind
import com.interviewassistant.app.ui.components.PasswordInput
import com.interviewassistant.app.ui.components.iaButtonFocusRing
import com.interviewassistant.app.ui.home.bindableState
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.SettingsCategories
import com.interviewassistant.core.languages.Loc

/**
 * The Settings page (HomeWindow's `SettingsPage`, TRANSFER-MAP §3.2 rows 10–17): a category list
 * with the accent-bar selection on the reading-order start side and one category pane; the explicit
 * Save/Discard bar is the shell's footer (bound to the controller's guard), so the page renders
 * only the categories. Two-pane at ≥ 720 dp content width, a single pane with in-place detail
 * navigation below it (§3.3, DC-165). All state and behavior live in [SettingsPageControllerImpl] —
 * this file only draws it, like the XAML only drew the code-behind's state.
 */
@Composable
fun SettingsPage(settings: SettingsPageControllerImpl, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val category by settings.bindableState("category") { settings.category }
    val focusTarget by settings.bindableState("focusTarget") { settings.focusTarget }

    val deviceRequester = remember { FocusRequester() }
    val hkToggleRequester = remember { FocusRequester() }
    val hkStartRequester = remember { FocusRequester() }
    val hkManualRequester = remember { FocusRequester() }

    // Single-pane form: the list and the pane swap in place (§3.3); a programmatic jump (a deep
    // link, a failed save landing on Hotkeys) shows the pane, like a selection does.
    var detailShown by remember { mutableStateOf(false) }
    LaunchedEffect(focusTarget) {
        when (focusTarget) {
            SettingsPageControllerImpl.FOCUS_DEVICE -> {
                detailShown = true
                runCatching { deviceRequester.requestFocus() }
            }
            SettingsPageControllerImpl.FOCUS_HK_TOGGLE -> {
                detailShown = true
                runCatching { hkToggleRequester.requestFocus() }
            }
            SettingsPageControllerImpl.FOCUS_HK_START -> {
                detailShown = true
                runCatching { hkStartRequester.requestFocus() }
            }
            SettingsPageControllerImpl.FOCUS_HK_MANUAL -> {
                detailShown = true
                runCatching { hkManualRequester.requestFocus() }
            }
        }
        if (focusTarget != null) settings.clearFocusTarget()
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Loc.t("settings"), style = IaTextStyles.h1(colors))
            IaInfoTip(Loc.t("set_save_hint"), subject = Loc.t("settings"), modifier = Modifier.padding(start = 2.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxWidth()) {
                    CategoryColumn(
                        settings = settings,
                        category = category,
                        onSelect = { settings.selectCategory(it) },
                        modifier = Modifier.width(230.dp),
                    )
                    Box(Modifier.width(16.dp))
                    CategoryPane(
                        settings = settings,
                        category = category,
                        deviceRequester = deviceRequester,
                        hkToggleRequester = hkToggleRequester,
                        hkStartRequester = hkStartRequester,
                        hkManualRequester = hkManualRequester,
                        modifier = Modifier.weight(1f).widthIn(max = IaTokens.FormMaxWidth),
                    )
                }
            } else if (detailShown) {
                BackHandler { detailShown = false }
                Column(Modifier.fillMaxWidth()) {
                    Btn.Link(
                        Loc.t("settings"),
                        onClick = { detailShown = false },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    CategoryPane(
                        settings = settings,
                        category = category,
                        deviceRequester = deviceRequester,
                        hkToggleRequester = hkToggleRequester,
                        hkStartRequester = hkStartRequester,
                        hkManualRequester = hkManualRequester,
                        modifier = Modifier.fillMaxWidth().widthIn(max = IaTokens.FormMaxWidth),
                    )
                }
            } else {
                CategoryColumn(
                    settings = settings,
                    category = category,
                    onSelect = {
                        settings.selectCategory(it)
                        detailShown = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Category list
// ---------------------------------------------------------------------------

@Composable
private fun CategoryColumn(
    settings: SettingsPageControllerImpl,
    category: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val requesters = remember { SettingsCategories.ids.associateWith { FocusRequester() } }
    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val next = when (event.key) {
                Key.DirectionUp -> SettingsCategories.move(category, -1)
                Key.DirectionDown -> SettingsCategories.move(category, +1)
                Key.MoveHome -> SettingsCategories.ids.first()
                Key.MoveEnd -> SettingsCategories.ids.last()
                else -> return@onPreviewKeyEvent false
            }
            if (next != category) onSelect(next)
            requesters[next]?.let { runCatching { it.requestFocus() } }
            true
        },
    ) {
        for (item in SettingsCategories.all) {
            val selected = item.id == category
            CategoryRow(
                title = Loc.t(item.titleKey),
                description = Loc.t(item.descriptionKey),
                selected = selected,
                requester = requesters.getValue(item.id),
                onClick = { onSelect(item.id) },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .height(1.dp)
                .background(colors.Border),
        )
        GhostRowButton(
            glyph = IaGlyph.Reset,
            text = Loc.t("set_restore_all"),
            description = Loc.t("set_restore_body"),
            onClick = { settings.restoreAll() },
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * A ghost button with a leading glyph (the XAML's Restore-all / Reset-section buttons: the E777
 * glyph at 12 dp + 12.5 sp text). Drawn with the ghost family's own values — transparent ground,
 * the white state overlay, the accent focus ring — the same local pattern as the Home shell's
 * ghost content button.
 */
@Composable
private fun GhostRowButton(
    glyph: IaGlyph,
    text: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    Row(
        modifier
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(Color.White.copy(alpha = overlay))
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (description != null) contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IaIcon(glyph, 12.dp, colors.TextDim, null, modifier = Modifier.padding(end = 6.dp))
        Text(text, style = IaTextStyles.button(colors).copy(fontSize = 12.5.sp, color = colors.TextDim))
    }
}

/** One category row: 3 dp accent bar while selected, title over a one-line description (the XAML CategoryItem). */
@Composable
private fun CategoryRow(
    title: String,
    description: String,
    selected: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(shape)
            .background(if (selected) colors.Surface2 else Color.Transparent)
            .clickable(role = Role.Button) { onClick() }
            .focusRequester(requester)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = title
                if (selected) stateDescription = Loc.t("set_cat_current")
            }
            .padding(start = 10.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 10.dp)
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) colors.Accent else Color.Transparent),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = IaTextStyles.body(colors).copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.Text,
                ),
            )
            Text(
                description,
                style = IaTextStyles.body(colors).copy(fontSize = 11.5.sp, color = colors.TextFaint),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Category pane
// ---------------------------------------------------------------------------

@Composable
private fun CategoryPane(
    settings: SettingsPageControllerImpl,
    category: String,
    deviceRequester: FocusRequester,
    hkToggleRequester: FocusRequester,
    hkStartRequester: FocusRequester,
    hkManualRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val item = SettingsCategories.all[SettingsCategories.indexOf(category)]
    val title = Loc.t(item.titleKey)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = IaTextStyles.h1(colors).copy(fontSize = 17.sp),
                    modifier = Modifier.semantics { contentDescription = title },
                )
                settings.categoryHintKey(category)?.let { hint ->
                    IaInfoTip(Loc.t(hint), subject = title, modifier = Modifier.padding(start = 2.dp))
                }
            }
            GhostRowButton(
                glyph = IaGlyph.Reset,
                text = Loc.t("set_reset_section"),
                description = Loc.t("set_reset_section"),
                onClick = { settings.resetSection() },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            Loc.t(item.descriptionKey),
            style = IaTextStyles.body(colors),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        when (category) {
            SettingsCategories.Audio -> AudioPanel(settings, deviceRequester)
            SettingsCategories.Answers -> AnswersPanel(settings)
            SettingsCategories.Listening -> ListeningPanel(settings)
            SettingsCategories.Window -> WindowPanel(settings)
            SettingsCategories.Hotkeys -> HotkeysPanel(settings, hkToggleRequester, hkStartRequester, hkManualRequester)
            SettingsCategories.Privacy -> PrivacyPanel(settings)
            SettingsCategories.Updates -> UpdatesPanel(settings)
        }
    }
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

@Composable
private fun AudioPanel(settings: SettingsPageControllerImpl, deviceRequester: FocusRequester) {
    val colors = LocalIaColors.current
    val devices by settings.bindableState("deviceItems") { settings.deviceItems }
    val selectedIndex by settings.bindableState("deviceSelectedIndex") { settings.deviceSelectedIndex }
    val level by settings.audioLevelFlow.collectAsState()

    IaFormField(label = Loc.t("set_audio_device")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IaCombo(
                items = devices.map { it.name },
                selectedIndex = selectedIndex,
                onSelect = { settings.onDeviceSelected(it) },
                modifier = Modifier.weight(1f).focusRequester(deviceRequester),
                description = Loc.t("set_audio_device"),
            )
            Box(Modifier.width(8.dp))
            Btn.Content(
                onClick = { settings.refreshDevices() },
                description = Loc.t("set_refresh_devices"),
                modifier = Modifier.size(IaTokens.ControlHeight),
            ) {
                IaIcon(IaGlyph.Refresh, 14.dp, colors.Text, null)
            }
        }
    }
    Row(
        Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Loc.t("set_level"),
            style = IaTextStyles.body(colors).copy(fontSize = 12.sp, color = colors.TextDim),
            modifier = Modifier.widthIn(min = 40.dp),
        )
        IaLevelMeter(
            level = level,
            modifier = Modifier.width(200.dp),
            height = 6.dp,
            description = Loc.t("set_level"),
        )
    }
}

// ---------------------------------------------------------------------------
// Answers
// ---------------------------------------------------------------------------

@Composable
private fun AnswersPanel(settings: SettingsPageControllerImpl) {
    val colors = LocalIaColors.current
    val keyText by settings.bindableState("keyText") { settings.keyText }
    val keyStatus by settings.bindableState("keyStatus") { settings.keyStatus }
    val modelText by settings.bindableState("modelText") { settings.modelText }
    val sttText by settings.bindableState("sttText") { settings.sttText }
    val protocolIndex by settings.bindableState("protocolIndex") { settings.protocolIndex }
    val fontIndex by settings.bindableState("fontIndex") { settings.fontIndex }
    val fastCache by settings.bindableState("fastCache") { settings.fastCache }

    if (settings.developerBuild) {
        IaFormField(label = Loc.t("set_dev_key"), hint = Loc.t("set_dev_key_hint")) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PasswordInput(
                        value = keyText,
                        onValueChange = { settings.onKeyTextChanged(it) },
                        modifier = Modifier.weight(1f),
                        description = Loc.t("set_dev_key"),
                    )
                    Box(Modifier.width(8.dp))
                    Btn.Form(Loc.t("set_test"), onClick = { settings.testKey() })
                    Btn.Ghost(Loc.t("set_remove"), onClick = { settings.removeKey() })
                }
                Text(
                    keyStatus,
                    style = IaTextStyles.hint(colors),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        IaFormField(label = Loc.t("set_answer_model")) {
            IaCombo(
                items = settings.modelItems,
                selectedIndex = settings.modelItems.indexOf(modelText),
                onSelect = { settings.onModelItemSelected(it) },
                editableText = modelText,
                onEditableTextChange = { settings.onModelTextChanged(it) },
                description = Loc.t("set_answer_model"),
            )
        }
        IaFormField(label = Loc.t("set_stt_model")) {
            IaCombo(
                items = settings.sttItems,
                selectedIndex = settings.sttItems.indexOf(sttText),
                onSelect = { settings.onSttItemSelected(it) },
                editableText = sttText,
                onEditableTextChange = { settings.onSttTextChanged(it) },
                description = Loc.t("set_stt_model"),
            )
        }
        IaFormField(label = Loc.t("set_protocol"), isShort = true) {
            IaCombo(
                items = settings.protocolItems,
                selectedIndex = protocolIndex,
                onSelect = { settings.onProtocolSelected(it) },
                description = Loc.t("set_protocol"),
            )
        }
    }
    IaFormField(label = Loc.t("set_font_size"), isShort = true) {
        IaCombo(
            items = FontPreset.entries.map { Loc.t("font_" + it.name.lowercase()) },
            selectedIndex = fontIndex,
            onSelect = { settings.onFontSelected(it) },
            description = Loc.t("set_font_size"),
        )
    }
    IaCheck(
        checked = fastCache,
        onCheckedChange = { settings.onFastCacheChanged(it) },
        label = Loc.t("set_fast_cache"),
        modifier = Modifier.padding(top = 14.dp),
    )
}

// ---------------------------------------------------------------------------
// Listening / Window / Privacy
// ---------------------------------------------------------------------------

@Composable
private fun ListeningPanel(settings: SettingsPageControllerImpl) {
    val colors = LocalIaColors.current
    val vad by settings.bindableState("vadSensitivity") { settings.vadSensitivity }
    IaFormField(label = Loc.t("set_end_wait"), info = Loc.t("set_end_wait_hint")) {
        Column {
            IaSlider(
                value = vad,
                onValueChange = { settings.onVadChanged(it) },
                min = 0.6,
                max = 1.8,
                steps = 11,
                description = Loc.t("set_end_wait"),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    Loc.t("set_faster"),
                    style = IaTextStyles.body(colors).copy(fontSize = 11.5.sp, color = colors.TextDim),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    Loc.t("set_waits_longer"),
                    style = IaTextStyles.body(colors).copy(fontSize = 11.5.sp, color = colors.TextDim),
                )
            }
        }
    }
}

@Composable
private fun WindowPanel(settings: SettingsPageControllerImpl) {
    val opacity by settings.bindableState("windowOpacity") { settings.windowOpacity }
    val topmost by settings.bindableState("alwaysOnTop") { settings.alwaysOnTop }
    IaFormField(label = Loc.t("set_opacity")) {
        IaSlider(
            value = opacity,
            onValueChange = { settings.onOpacityChanged(it) },
            min = 0.75,
            max = 1.0,
            steps = 4,
            description = Loc.t("set_opacity"),
        )
    }
    IaCheck(
        checked = topmost,
        onCheckedChange = { settings.onTopmostChanged(it) },
        label = Loc.t("set_topmost"),
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun PrivacyPanel(settings: SettingsPageControllerImpl) {
    val colors = LocalIaColors.current
    val saveTranscript by settings.bindableState("saveTranscript") { settings.saveTranscript }
    IaCheck(
        checked = saveTranscript,
        onCheckedChange = { settings.onSaveTranscriptChanged(it) },
        label = Loc.t("set_save_transcript"),
        modifier = Modifier.padding(top = 14.dp),
    )
    Text(
        Loc.t("set_save_transcript_hint"),
        style = IaTextStyles.hint(colors),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** The settings sliders: the design system's track/thumb over the material slider, tick-snapped. */
@Composable
private fun IaSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    min: Double,
    max: Double,
    steps: Int,
    description: String,
) {
    val colors = LocalIaColors.current
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.toDouble()) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = colors.Accent,
            activeTrackColor = colors.Accent,
            inactiveTrackColor = colors.Surface3,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

// ---------------------------------------------------------------------------
// Hotkeys
// ---------------------------------------------------------------------------

@Composable
private fun HotkeysPanel(
    settings: SettingsPageControllerImpl,
    toggleRequester: FocusRequester,
    startRequester: FocusRequester,
    manualRequester: FocusRequester,
) {
    val toggleText by settings.bindableState("hkToggleText") { settings.hkToggleText }
    val startText by settings.bindableState("hkStartText") { settings.hkStartText }
    val manualText by settings.bindableState("hkManualText") { settings.hkManualText }
    val toggleError by settings.bindableState("hkToggleError") { settings.hkToggleError }
    val startError by settings.bindableState("hkStartError") { settings.hkStartError }
    val manualError by settings.bindableState("hkManualError") { settings.hkManualError }
    val toggleHasError by settings.bindableState("hkToggleHasError") { settings.hkToggleHasError }
    val startHasError by settings.bindableState("hkStartHasError") { settings.hkStartHasError }
    val manualHasError by settings.bindableState("hkManualHasError") { settings.hkManualHasError }

    HotkeyFieldEditor(
        label = Loc.t("set_hk_toggle"),
        text = toggleText,
        error = toggleError,
        hasError = toggleHasError,
        requester = toggleRequester,
        onTextChange = { settings.onHotkeyTextChanged(HotkeyField.Toggle, it) },
    )
    HotkeyFieldEditor(
        label = Loc.t("set_hk_start"),
        text = startText,
        error = startError,
        hasError = startHasError,
        requester = startRequester,
        onTextChange = { settings.onHotkeyTextChanged(HotkeyField.Start, it) },
    )
    HotkeyFieldEditor(
        label = Loc.t("set_hk_manual"),
        text = manualText,
        error = manualError,
        hasError = manualHasError,
        requester = manualRequester,
        onTextChange = { settings.onHotkeyTextChanged(HotkeyField.Manual, it) },
    )
}

@Composable
private fun HotkeyFieldEditor(
    label: String,
    text: String,
    error: String?,
    hasError: Boolean,
    requester: FocusRequester,
    onTextChange: (String) -> Unit,
) {
    IaFormField(label = label, isShort = true) {
        Column {
            // A technical value: always left-to-right, also inside a right-to-left layout.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Input.Single(
                    value = text,
                    onValueChange = onTextChange,
                    hasError = hasError,
                    description = label,
                    modifier = Modifier.focusRequester(requester),
                )
            }
            if (error != null) {
                IaInlineMessage(text = error, kind = MessageKind.Error)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Updates
// ---------------------------------------------------------------------------

@Composable
private fun UpdatesPanel(settings: SettingsPageControllerImpl) {
    val colors = LocalIaColors.current
    val version by settings.bindableState("updateVersionText") { settings.updateVersionText }
    val channel by settings.bindableState("updateChannelText") { settings.updateChannelText }
    val commit by settings.bindableState("updateCommitText") { settings.updateCommitText }
    val status by settings.bindableState("updateStatusText") { settings.updateStatusText }
    val restartVisible by settings.bindableState("updateRestartVisible") { settings.updateRestartVisible }
    val checkVisible by settings.bindableState("updateCheckVisible") { settings.updateCheckVisible }
    val checkEnabled by settings.bindableState("updateCheckEnabled") { settings.updateCheckEnabled }
    val channelIndex by settings.bindableState("channelIndex") { settings.channelIndex }

    AboutRow(Loc.t("about_version"), version, ltr = true)
    AboutRow(Loc.t("about_channel"), channel, ltr = false)
    AboutRow(Loc.t("about_build"), commit, ltr = true)
    IaFormField(label = Loc.t("update_preference"), hint = Loc.t("set_upd_pref_note"), isShort = true) {
        IaCombo(
            items = listOf(Loc.t("channel_stable"), Loc.t("channel_beta")),
            selectedIndex = channelIndex,
            onSelect = { settings.onChannelSelected(it) },
            description = Loc.t("update_preference"),
        )
    }
    Row(Modifier.padding(top = 4.dp)) {
        if (checkVisible) {
            Btn.Form(
                Loc.t("set_upd_check"),
                onClick = { settings.checkUpdates() },
                enabled = checkEnabled,
            )
        }
        if (restartVisible) {
            Btn.Form(Loc.t("restart_to_update"), onClick = { settings.restartUpdate() })
        }
    }
    if (status.isNotEmpty()) {
        Text(
            status,
            style = IaTextStyles.body(colors).copy(color = colors.Text),
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Text(
        Loc.t("set_upd_install_note"),
        style = IaTextStyles.hint(colors),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** One About-style key/value row of the Updates panel (the C#'s About grid). */
@Composable
private fun AboutRow(label: String, value: String, ltr: Boolean) {
    val colors = LocalIaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = IaTextStyles.metaKey(colors),
            modifier = Modifier.width(140.dp),
        )
        if (ltr) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(value, style = IaTextStyles.metaValue(colors))
            }
        } else {
            Text(value, style = IaTextStyles.metaValue(colors))
        }
    }
}
