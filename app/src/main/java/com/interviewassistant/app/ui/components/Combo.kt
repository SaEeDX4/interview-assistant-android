package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * `Combo` (Themes/Theme.xaml): the input family's field with a chevron-only toggle — the toggle is a
 * transparent full-field target that only adds the chevron (10 sp, TextDim, 12 dp from the end); the popup is
 * a Surface2 card (Border outline, radius 8, padding 4) whose items are 13 sp with 10×7 padding, highlighted
 * Surface3, selected AccentSoft. Border/focus/error/disabled states are the input family's. With
 * [editable] the closed box shows the bare text part (margin 11 start, 32 end) instead of the selection text.
 */
@Composable
fun IaCombo(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasError: Boolean = false,
    editableText: String? = null,
    onEditableTextChange: (String) -> Unit = {},
    description: String? = null,
) {
    val colors = LocalIaColors.current
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    val borderColor = when {
        hasError -> colors.Error
        expanded || focused -> colors.Accent
        hovered -> colors.TextDim
        else -> colors.InputBorder
    }
    val ringColor = when {
        hasError -> colors.ErrorSoft
        focused || expanded -> colors.AccentSoft
        else -> Color.Transparent
    }
    Box(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .iaInputFocusRing(hasError || focused || expanded, ringColor)
                .clip(shape)
                .background(colors.Surface2)
                .border(IaTokens.BorderWidth, borderColor, shape)
                .hoverable(hoverInteraction, enabled = enabled)
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                .onFocusChanged { focused = it.isFocused }
                .semantics { if (description != null) contentDescription = description }
                .defaultMinSize(minHeight = IaTokens.ControlHeight)
                .padding(start = 13.dp, end = 32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (editableText != null) {
                Input.Bare(editableText, onEditableTextChange, Modifier.fillMaxWidth(), enabled, description)
            } else {
                Text(
                    items.getOrNull(selectedIndex) ?: "",
                    style = IaTextStyles.input(colors),
                    maxLines = 1,
                )
            }
            // the chevron of the ComboToggle template (not focusable, not a separate target)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IaIcon(IaGlyph.Chevron, 10.dp, colors.TextDim, null, Modifier.padding(end = 0.dp))
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(colors.Surface2)
                .border(IaTokens.BorderWidth, colors.Border, shape)
                .padding(4.dp),
        ) {
            items.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label, style = IaTextStyles.comboItem(colors)) },
                    onClick = { onSelect(i); expanded = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i == selectedIndex) colors.AccentSoft else Color.Transparent)
                        .semantics { contentDescription = label },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(IaTokens.ListItemPaddingH, IaTokens.ListItemPaddingV),
                )
            }
        }
    }
}
