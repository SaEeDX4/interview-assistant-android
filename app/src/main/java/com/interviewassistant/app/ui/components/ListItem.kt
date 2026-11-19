package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * `ListItem.Row` (Themes/Theme.xaml): a Home list row (profiles, interviews, reports, devices) — padding
 * 10×7, 2 dp bottom margin, radius 6; hover fills Surface2; selection is the AccentSoft fill with an Accent
 * outline, visible with and without keyboard focus; keyboard focus draws the accent ring. In high contrast
 * the selected row takes the highlight set (the XAML's system Highlight brushes).
 */
@Composable
fun IaListItemRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    selectedDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalIaColors.current
    val hc = HighContrast.isOn
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val background = when {
        selected && hc -> colors.Accent
        selected -> colors.AccentSoft
        hovered -> colors.Surface2
        else -> Color.Transparent
    }
    val borderColor = when {
        selected -> colors.Accent
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .iaFocusRing(focused, colors.Accent, 6.dp, IaTokens.FocusRingOutsetButton)
            .clip(shape)
            .background(background)
            .border(IaTokens.BorderWidth, borderColor, shape)
            .hoverable(interaction)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, onClick = onClick)
            .semantics {
                if (description != null) contentDescription = description
                if (selected && selectedDescription != null) stateDescription = selectedDescription
            }
            .padding(horizontal = IaTokens.ListItemPaddingH, vertical = IaTokens.ListItemPaddingV),
        content = content,
    )
}
