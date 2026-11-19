package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * The segmented control of Themes/Theme.xaml (`Seg.Track` + `Seg.Item`): a Surface2 track (Border outline,
 * radius 8, padding 3, min height 36) of equal-width radio segments; the checked segment is filled
 * AccentStrong with white SemiBold text (radius 6 inside the track — never a pill). One Tab stop for the
 * group: the arrow keys move the selection via [RovingSelection], mirrored in RTL (the C#'s
 * SegmentedNavigation / RovingRadios behaviour).
 */
object Seg {

    @Composable
    fun Track(
        labels: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier,
        description: String? = null,
    ) {
        val colors = LocalIaColors.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val shape = RoundedCornerShape(IaTokens.RadiusControl)
        Row(
            modifier = modifier
                .clip(shape)
                .background(colors.Surface2)
                .border(IaTokens.BorderWidth, colors.Border, shape)
                .defaultMinSize(minHeight = IaTokens.ControlHeight)
                .padding(IaTokens.SegTrackPadding)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val key = when (e.key) {
                        Key.DirectionLeft -> RovingKey.Left
                        Key.DirectionRight -> RovingKey.Right
                        Key.DirectionUp -> RovingKey.Up
                        Key.DirectionDown -> RovingKey.Down
                        else -> return@onPreviewKeyEvent false
                    }
                    onSelect(RovingSelection.next(selectedIndex, labels.size, key, rtl))
                    true
                }
                .semantics { if (description != null) contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                Item(
                    label = label,
                    checked = i == selectedIndex,
                    onClick = { onSelect(i) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    fun Item(label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val colors = LocalIaColors.current
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(6.dp)
        Box(
            modifier = modifier
                .iaFocusRing(focused, colors.Accent, IaTokens.RadiusControl, IaTokens.FocusRingOutsetButton)
                .clip(shape)
                .background(if (checked) colors.AccentStrong else androidx.compose.ui.graphics.Color.Transparent)
                .defaultMinSize(minHeight = IaTokens.CompactButtonHeight)
                .onFocusChanged { focused = it.isFocused }
                .selectable(selected = checked, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(horizontal = IaTokens.SegItemPaddingH, vertical = IaTokens.SegItemPaddingV),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = IaTextStyles.segItem(colors, checked),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
