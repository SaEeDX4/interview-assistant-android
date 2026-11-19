package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/** The accessible name of an info tip (InfoTip.cs `Render`): "More information" or "More information: <field>". */
object InfoTipNaming {
    fun name(moreText: String, subject: String): String =
        if (subject.isBlank()) moreText else "$moreText: $subject"
}

/**
 * `InfoTip` (Views/InfoTip.cs): a small ⓘ ghost button holding secondary help text. The text opens as a tool
 * tip on keyboard focus and on click (click again or Escape closes it); screen readers get the text as the
 * button's help text and "More information: <field>" as its name, so a row of ⓘ buttons is never eight
 * identical names. Hidden while its text is empty. Use only for secondary hints; warnings stay visible.
 */
@Composable
fun IaInfoTip(text: String, subject: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val colors = LocalIaColors.current
    var open by remember { mutableStateOf(false) }
    val name = InfoTipNaming.name(Loc.t("info_more"), subject)
    Box(modifier) {
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(IaTokens.RadiusControl))
                .clickable(role = Role.Button) { open = !open }
                .onFocusChanged { if (it.isFocused) open = true else if (it.hasFocus.not()) open = false }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && open) {
                        open = false; true
                    } else false
                }
                .semantics {
                    contentDescription = name
                    stateDescription = text
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            IaIcon(IaGlyph.Info, 13.dp, colors.TextDim, null)
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false),
            ) {
                IaToolTipCard(text)
            }
        }
    }
}

/** The `ToolTip` style of Theme.xaml: a Surface3 card, Text colour, Border outline, in the control's direction. */
@Composable
fun IaToolTipCard(text: String) {
    val colors = LocalIaColors.current
    Box(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(colors.Surface3)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusControl))
            .widthIn(max = 340.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = IaTextStyles.hint(colors).copy(color = colors.Text))
    }
}

/**
 * `FormField` (Views/FormField.cs + the Theme.xaml template): the one form-field layout of the app — a label
 * row (label · ⓘ info tip · accessory at the end), the field, and an optional hint below. The label row has
 * the field-label margin (0/12/0/6); the field gap below is 4. With [isShort] the field is the short-field
 * width (320) at the reading-order start instead of stretching. The FormField itself is not a tab stop; the
 * label is the input's accessible name (the caller passes the same [label] as the input's description, the
 * Compose equivalent of the XAML's LabeledBy link).
 */
@Composable
fun IaFormField(
    label: String,
    modifier: Modifier = Modifier,
    info: String = "",
    hint: String = "",
    isShort: Boolean = false,
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalIaColors.current
    Column(modifier.padding(bottom = IaTokens.FieldGap)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = IaTokens.FieldLabelMarginTop, bottom = IaTokens.FieldLabelMarginBottom),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = IaTextStyles.label(colors))
                if (info.isNotEmpty()) {
                    IaInfoTip(info, subject = label, modifier = Modifier.padding(start = 0.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (accessory != null) {
                Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.BottomEnd) { accessory() }
            }
        }
        Box(if (isShort) Modifier.width(IaTokens.ShortFieldWidth) else Modifier.fillMaxWidth()) {
            content()
        }
        if (hint.isNotEmpty()) {
            Text(
                hint,
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(top = IaTokens.HintMarginTop),
            )
        }
    }
}
