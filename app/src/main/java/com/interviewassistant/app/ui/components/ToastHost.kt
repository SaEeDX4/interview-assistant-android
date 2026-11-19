package com.interviewassistant.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import kotlinx.coroutines.delay

/**
 * The Compose host of [ToastController] (the C#'s `Views/ToastHost`): a bottom-centred stack (12 dp margin)
 * of cards — Surface3 fill, Border outline, radius 10, padding 12/9/16/10, 6 dp between cards, the kind's
 * glyph in a 24 dp chip of its accent at 0x26 alpha. Cards fade/slide in (180/220 ms, ease-out), fade out in
 * 160 ms, dismiss on click; Escape (when nothing else handled it first) dismisses the newest. Each card is a
 * live region: assertive for errors, polite otherwise. The host never covers the answer text: windows place
 * it away from critical content, exactly as on Windows. Visible toasts follow a live UI-language switch.
 */
@Composable
fun ToastHost(controller: ToastController, modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf<List<ToastController.ToastItem>>(emptyList()) }
    var language by remember { mutableStateOf(Loc.language) }
    LaunchedEffect(controller) {
        while (true) {
            if (Loc.language != language) {
                language = Loc.language
                controller.relocalize { Loc.relocalize(it) }
            }
            items = controller.visible(System.currentTimeMillis())
            delay(50)
        }
    }
    Column(
        modifier = modifier
            .padding(12.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    controller.dismissNewest(System.currentTimeMillis())
                } else false
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEach { item ->
            ToastCard(item, onClick = { controller.dismiss(item.id, System.currentTimeMillis()) })
        }
    }
}

@Composable
private fun ToastCard(item: ToastController.ToastItem, onClick: () -> Unit) {
    val colors = LocalIaColors.current
    val dismissing = item.phase == ToastController.Phase.Dismissing
    // entrance: opacity 0→1 in 180 ms, Y 8→0 in 220 ms ease-out (the XAML DoubleAnimations)
    val enter = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) {
        enter.animateTo(1f, tween(ToastController.SLIDE_IN_MS.toInt(), easing = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)))
    }
    val exitAlpha by animateFloatAsState(
        targetValue = if (dismissing) 0f else 1f,
        animationSpec = tween(ToastController.FADE_OUT_MS.toInt()),
        label = "toastExitAlpha",
    )
    val accent = when (item.kind) {
        ToastKind.Success -> colors.Listening
        ToastKind.Warning -> colors.Thinking
        ToastKind.Error -> colors.Error
        ToastKind.Info -> colors.Accent
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .graphicsLayer {
                alpha = enter.value * exitAlpha
                translationY = (1f - enter.value) * 8.dp.toPx()
            }
            .shadow(IaTokens.ShadowElevation, shape)
            .clip(shape)
            .background(colors.Surface3)
            .border(IaTokens.BorderWidth, colors.Border, shape)
            .clickable { onClick() }
            .semantics {
                liveRegion = if (item.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                contentDescription = item.announcement
            }
            .padding(start = 12.dp, top = 9.dp, end = 16.dp, bottom = 10.dp)
            .widthIn(max = 440.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0x26 / 255f)),
            contentAlignment = Alignment.Center,
        ) {
            IaIcon(ToastController.glyph(item.kind), 11.5.dp, accent, null)
        }
        Column {
            Text(item.text, style = IaTextStyles.toastTitle(colors), modifier = Modifier.widthIn(max = 380.dp))
            if (!item.detail.isNullOrEmpty()) {
                Text(item.detail, style = IaTextStyles.toastDetail(colors), modifier = Modifier.widthIn(max = 380.dp).padding(top = 2.dp))
            }
        }
    }
}
