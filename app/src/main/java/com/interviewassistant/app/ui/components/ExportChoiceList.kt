package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.viewmodels.ExportChoice
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * Selection state of an [ExportChoiceList] (ExportChoiceList.xaml.cs): the initially checked option is the
 * one whose key was passed in, else the first; arrow keys move the selection through [RovingSelection]
 * (one tab stop for the group, mirrored in RTL).
 */
class ExportChoiceState(
    val choices: List<ExportChoice>,
    selectedKey: String? = null,
) {
    // Snapshot state: the cards recompose when the selection moves (the C# radios raise Checked changes).
    var selectedIndex: Int by androidx.compose.runtime.mutableStateOf(
        run {
            val initial = choices.indexOfFirst { it.key == selectedKey }
            if (choices.isEmpty()) -1 else maxOf(0, initial)
        },
    )
        private set

    val selectedKey: String? get() = choices.getOrNull(selectedIndex)?.key

    fun select(index: Int) {
        if (index in choices.indices) selectedIndex = index
    }

    /** An arrow key moves the selection (and the caller moves focus). True when the key was an arrow. */
    fun onArrowKey(key: RovingKey, rtl: Boolean): Boolean {
        if (choices.isEmpty()) return false
        selectedIndex = RovingSelection.next(selectedIndex, choices.size, key, rtl)
        return true
    }
}

/**
 * Mirror of `Views/ExportChoiceList.xaml(.cs)`: a short list of mutually exclusive options shown as cards
 * (title + one-line description) inside an in-app dialog. Card template: Surface2 fill, Border outline,
 * radius 8, padding 12×10; an 18 dp ring (1.5 dp InputBorder) with an 8 dp Accent dot when checked; checked
 * cards take the AccentSoft fill and Accent outline (high contrast: the highlight set). The whole card is
 * the target; the group carries the question as its accessible name; each option's name is its title and
 * its help text the description. Cards stack with the 8 dp stack gap, the group starts 4 dp below the
 * dialog message.
 */
@Composable
fun ExportChoiceList(
    state: ExportChoiceState,
    groupName: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var focusIndex by remember { mutableStateOf(-1) }
    Column(
        modifier = modifier
            .padding(top = 4.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = when (e.key) {
                    Key.DirectionDown -> RovingKey.Down
                    Key.DirectionRight -> RovingKey.Right
                    Key.DirectionUp -> RovingKey.Up
                    Key.DirectionLeft -> RovingKey.Left
                    else -> return@onPreviewKeyEvent false
                }
                state.onArrowKey(key, rtl)
            }
            .semantics { contentDescription = groupName },
    ) {
        state.choices.forEachIndexed { i, choice ->
            val checked = i == state.selectedIndex
            val shape = RoundedCornerShape(IaTokens.RadiusControl)
            val hc = HighContrast.isOn
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = IaTokens.StackGap)
                    .iaFocusRing(focusIndex == i, colors.Accent, IaTokens.RadiusFocusRing, IaTokens.FocusRingOutsetInput)
                    .clip(shape)
                    .background(if (checked) colors.AccentSoft else colors.Surface2)
                    .border(
                        IaTokens.BorderWidth,
                        when {
                            checked -> colors.Accent
                            else -> colors.Border
                        },
                        shape,
                    )
                    .onFocusChanged { if (it.isFocused) focusIndex = i }
                    .selectable(selected = checked, role = Role.RadioButton) { state.select(i) }
                    .semantics {
                        contentDescription = choice.label
                        stateDescription = choice.description
                    }
                    .padding(horizontal = IaTokens.ChoiceCardPaddingH, vertical = IaTokens.ChoiceCardPaddingV),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp, end = 12.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (checked) colors.Accent else colors.InputBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (hc) colors.Accent else colors.Accent),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(choice.label, style = IaTextStyles.input(colors).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Text(choice.description, style = IaTextStyles.hint(colors).copy(color = colors.TextDim), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
