package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.viewmodels.MessageKind
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/** Icon + accent colour of an inline message kind (InlineMessage.cs `Render`). */
object InlineMessageStyle {
    fun glyph(kind: MessageKind): IaGlyph = when (kind) {
        MessageKind.Error -> IaGlyph.Error
        MessageKind.Warning -> IaGlyph.Warning
        MessageKind.Success -> IaGlyph.Check
        MessageKind.Info -> IaGlyph.Info
    }

    fun accent(kind: MessageKind, c: IaColorSet): Color = when (kind) {
        MessageKind.Error -> c.Error
        MessageKind.Warning -> c.Thinking
        MessageKind.Success -> c.Listening
        MessageKind.Info -> c.Accent
    }

    /** The announced text: the message, plus ". " + detail when there is one (InlineMessage.cs `Render`). */
    fun announcement(text: String, detail: String?): String =
        if (detail.isNullOrEmpty()) text else "$text. $detail"

    /** Errors are announced assertively, everything else politely. */
    fun liveMode(kind: MessageKind): LiveRegionMode =
        if (kind == MessageKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
}

/**
 * Mirror of `Views/InlineMessage.cs`: the shared inline feedback component — icon + message (+ optional
 * secondary line and a text action such as "Try again"), placed right where the problem is. Hidden while it
 * has no text. Surface = the kind's accent at 0x1F alpha, outline at 0x66, radius 8, padding 10×7, an 8 dp
 * margin above. Errors announce assertively to screen readers, everything else politely.
 */
@Composable
fun IaInlineMessage(
    text: String,
    kind: MessageKind,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    if (text.isBlank()) return
    val colors = LocalIaColors.current
    val accent = InlineMessageStyle.accent(kind, colors)
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0x1F / 255f))
            .border(IaTokens.BorderWidth, accent.copy(alpha = 0x66 / 255f), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics {
                liveRegion = InlineMessageStyle.liveMode(kind)
                contentDescription = InlineMessageStyle.announcement(text, detail)
            },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp, end = 10.dp)) {
            IaIcon(InlineMessageStyle.glyph(kind), 13.dp, accent, null)
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = IaTextStyles.inlineText(colors))
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = IaTextStyles.toastDetail(colors), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!actionText.isNullOrEmpty()) {
            Box(Modifier.padding(start = 8.dp)) {
                Btn.Link(actionText, onAction)
            }
        }
    }
}
