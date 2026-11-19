package com.interviewassistant.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import kotlinx.coroutines.launch

/** Tracks what has focus inside the open dialog, for the Enter rule (AppDialog.cs `OnKeyAsync`). */
class DialogFocusTracker {
    var current: DialogFocus = DialogFocus.Other
}

/** The tracker of the dialog currently rendering (bodies report multiline/combo focus through it). */
val LocalDialogFocusTracker = compositionLocalOf { DialogFocusTracker() }

/** Tone → icon glyph + accent (AppDialog.cs's tone switch). */
object DialogToneStyle {
    fun glyph(tone: DialogTone): IaGlyph = when (tone) {
        DialogTone.Question -> IaGlyph.Unknown
        DialogTone.Warning -> IaGlyph.Warning
        DialogTone.Danger -> IaGlyph.Warning
        DialogTone.Success -> IaGlyph.Check
        DialogTone.Info -> IaGlyph.Info
    }

    fun accent(tone: DialogTone, c: IaColorSet): Color = when (tone) {
        DialogTone.Question -> c.Accent
        DialogTone.Warning -> c.Thinking
        DialogTone.Danger -> c.Error
        DialogTone.Success -> c.Listening
        DialogTone.Info -> c.Accent
    }
}

/**
 * The Compose host of a [DialogController] (AppDialog.cs `DialogHost`): renders the TOP dialog of the stack
 * as a modal card over the dimmed Backdrop — Surface fill, Border outline, card radius 12, padding
 * 22/20/22/18, the request's max width (min width = min(320, maxWidth)), the tone's glyph in a 32 dp chip of
 * its accent at 0x26 alpha, title (16 SemiBold, heading level 1), message, body, and the action row
 * (end-aligned, primary/destructive last, each button at least 88 wide, 8 apart, 18 above). Entrance: 140 ms
 * fade + scale 0.97→1 ease-out. Back/Escape = the controller's cancel semantics; clicks never reach the
 * content behind. While a validation runs, every button is disabled. Placed once per window (P4-D DC-134:
 * the platform Dialog window stands in for the C#'s in-window overlay; the visible result is the same).
 */
@Composable
fun AppDialogHost(controller: DialogController) {
    val layers by controller.layers.collectAsState()
    val top = layers.lastOrNull() ?: return
    val scope = rememberCoroutineScope()
    val tracker = remember(top.id) { DialogFocusTracker() }
    Dialog(
        onDismissRequest = { controller.escape(top.id) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDialogFocusTracker provides tracker) {
            DialogCard(
                layer = top,
                initialFocusIndex = controller.initialFocusIndex(top),
                onAction = { action -> scope.launch { controller.clickAction(top.id, action) } },
                onEnter = { scope.launch { controller.enter(top.id, tracker.current) } },
                onFocusButton = { index -> tracker.current = DialogFocus.Button(index) },
            )
        }
    }
}

@Composable
private fun DialogCard(
    layer: DialogController.Layer,
    initialFocusIndex: Int,
    onAction: (DialogAction) -> Unit,
    onEnter: () -> Unit,
    onFocusButton: (Int) -> Unit,
) {
    val colors = LocalIaColors.current
    val request = layer.request
    val enterAnim = remember(layer.id) { Animatable(0f) }
    LaunchedEffect(layer.id) {
        enterAnim.animateTo(1f, tween(140, easing = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)))
    }
    val defaultFocus = remember(layer.id) { FocusRequester() }
    LaunchedEffect(layer.id) {
        if (initialFocusIndex >= 0 && request.initialFocusAction == null) defaultFocus.requestFocus()
    }
    val shape = RoundedCornerShape(IaTokens.RadiusCard)
    val accent = DialogToneStyle.accent(request.tone, colors)
    Box(Modifier.fillMaxSize().background(colors.Backdrop), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = minOf(320.dp, request.maxWidth), max = request.maxWidth)
                .graphicsLayer {
                    alpha = enterAnim.value
                    scaleX = 0.97f + 0.03f * enterAnim.value
                    scaleY = 0.97f + 0.03f * enterAnim.value
                }
                .shadow(IaTokens.ShadowElevation, shape)
                .clip(shape)
                .background(colors.Surface)
                .border(IaTokens.BorderWidth, colors.Border, shape)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        onEnter(); true
                    } else false
                }
                .padding(start = 22.dp, top = 20.dp, end = 22.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0x26 / 255f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IaIcon(DialogToneStyle.glyph(request.tone), 15.dp, accent, null)
                }
                Column(Modifier.weight(1f)) {
                    if (!request.title.isNullOrBlank()) {
                        Text(
                            request.title,
                            style = IaTextStyles.dialogTitle(colors),
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).semantics { heading() },
                        )
                    }
                    if (!request.message.isNullOrBlank()) {
                        if (request.title.isNullOrBlank()) {
                            Text(request.message, style = IaTextStyles.dialogMessageNoTitle(colors), modifier = Modifier.padding(top = 5.dp))
                        } else {
                            Text(request.message, style = IaTextStyles.dialogMessage(colors))
                        }
                    }
                }
            }
            if (request.body != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    request.body.invoke()
                }
            }
            // buttons: reading order, the primary/destructive action last (right in LTR, left in RTL)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                request.actions.forEachIndexed { i, action ->
                    if (i > 0) Spacer(Modifier.width(8.dp))
                    val focusMod = if (i == initialFocusIndex && request.initialFocusAction == null) {
                        Modifier.focusRequester(defaultFocus)
                    } else Modifier
                    val buttonMod = focusMod
                        .widthIn(min = 88.dp)
                        .onFocusChanged { if (it.isFocused) onFocusButton(i) }
                    when (action.role) {
                        DialogButtonRole.Primary -> Btn.FormPrimary(action.text, { onAction(action) }, buttonMod, enabled = !layer.busy)
                        DialogButtonRole.Destructive -> Btn.FormDanger(action.text, { onAction(action) }, buttonMod, enabled = !layer.busy)
                        DialogButtonRole.Secondary -> Btn.Form(action.text, { onAction(action) }, buttonMod, enabled = !layer.busy)
                    }
                }
            }
        }
    }
}
