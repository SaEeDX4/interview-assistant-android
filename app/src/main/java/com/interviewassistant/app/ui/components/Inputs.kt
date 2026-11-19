package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * The input family of Themes/Theme.xaml (`Input`, `Input.Multiline`, `Input.Bare`, `PasswordInput`): one
 * family — same height (36), radius (8), border, padding, fill and states. States (from the XAML template):
 * hover → border TextDim; focus → border Accent + the soft focus ring 3 dp outside; error → border Error +
 * error-soft ring; read-only → fill Surface; disabled → 0.5 opacity. The placeholder is shown only while the
 * field is empty AND unfocused, starts at the text's own inset, and never takes input (the `v:Watermark` rule).
 */
object Input {

    @Composable
    fun Single(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        placeholder: String? = null,
        enabled: Boolean = true,
        readOnly: Boolean = false,
        hasError: Boolean = false,
        description: String? = null,
        keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
        onFocusChange: (Boolean) -> Unit = {},
    ) = FieldShell(
        modifier = modifier, enabled = enabled, readOnly = readOnly, hasError = hasError,
        multiline = false, onFocusChange = onFocusChange,
    ) { focused, colors ->
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, readOnly = readOnly,
            textStyle = IaTextStyles.input(colors), singleLine = true, keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(colors.Text),
            modifier = Modifier.fillMaxWidth().semantics { if (description != null) contentDescription = description },
            decorationBox = { inner ->
                FieldDecoration(placeholder, value.isEmpty(), focused, colors, inner)
            },
        )
    }

    /** `Input.Multiline`: top-aligned, min height 72 (or the tall 170), multiline padding 10×8. */
    @Composable
    fun Multiline(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        placeholder: String? = null,
        enabled: Boolean = true,
        readOnly: Boolean = false,
        hasError: Boolean = false,
        tall: Boolean = false,
        description: String? = null,
        onFocusChange: (Boolean) -> Unit = {},
    ) = FieldShell(
        modifier = modifier, enabled = enabled, readOnly = readOnly, hasError = hasError,
        multiline = true, tall = tall, onFocusChange = onFocusChange,
    ) { focused, colors ->
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, readOnly = readOnly,
            textStyle = IaTextStyles.input(colors), singleLine = false, keyboardOptions = KeyboardOptions.Default,
            cursorBrush = SolidColor(colors.Text),
            modifier = Modifier.fillMaxWidth().semantics { if (description != null) contentDescription = description },
            decorationBox = { inner ->
                FieldDecoration(placeholder, value.isEmpty(), focused, colors, inner)
            },
        )
    }

    /** `Input.Bare`: no chrome of its own (the editable ComboBox text part). */
    @Composable
    fun Bare(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        description: String? = null,
    ) {
        val colors = LocalIaColors.current
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = IaTextStyles.input(colors), cursorBrush = SolidColor(colors.Text),
            modifier = modifier.semantics { if (description != null) contentDescription = description },
        )
    }
}

/** `PasswordInput`: the Input family with masked text; always left-to-right (Dialogs.cs `DialogForm.Password`). */
@Composable
fun PasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    hasError: Boolean = false,
    description: String? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FieldShell(
            modifier = modifier, enabled = enabled, readOnly = false, hasError = hasError,
            multiline = false, onFocusChange = onFocusChange,
        ) { focused, colors ->
            BasicTextField(
                value = value, onValueChange = onValueChange, enabled = enabled,
                textStyle = IaTextStyles.input(colors), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default,
                cursorBrush = SolidColor(colors.Text),
                modifier = Modifier.fillMaxWidth().semantics { if (description != null) contentDescription = description },
                decorationBox = { inner ->
                    FieldDecoration(placeholder, value.isEmpty(), focused, colors, inner)
                },
            )
        }
    }
}

/** Placeholder + text inset, per the XAML template (placeholder inset by the text's own 2 dp, hidden on focus/fill). */
@Composable
private fun FieldDecoration(
    placeholder: String?,
    empty: Boolean,
    focused: Boolean,
    colors: IaColorSet,
    inner: @Composable () -> Unit,
) {
    Box(Modifier.padding(start = 2.dp)) {
        if (placeholder != null && empty && !focused) {
            Text(
                placeholder,
                style = IaTextStyles.input(colors).copy(color = colors.Placeholder),
                maxLines = 1,
            )
        }
        inner()
    }
}

/** The shared field chrome: fill, border and focus/error ring following the XAML template triggers. */
@Composable
private fun FieldShell(
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    hasError: Boolean,
    multiline: Boolean,
    tall: Boolean = false,
    onFocusChange: (Boolean) -> Unit,
    content: @Composable (focused: Boolean, colors: IaColorSet) -> Unit,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    val hoverInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    val borderColor = when {
        hasError -> colors.Error
        focused -> colors.Accent
        hovered -> colors.TextDim
        else -> colors.InputBorder
    }
    val ringColor = when {
        hasError -> colors.ErrorSoft
        focused -> colors.AccentSoft
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .iaInputFocusRing(hasError || focused, ringColor)
            .clip(shape)
            .background(if (readOnly) colors.Surface else colors.Surface2)
            .border(IaTokens.BorderWidth, borderColor, shape)
            .hoverable(hoverInteraction, enabled = enabled)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .defaultMinSize(minHeight = if (multiline) (if (tall) IaTokens.MultilineTallHeight else IaTokens.MultilineMinHeight) else IaTokens.ControlHeight)
            .padding(
                horizontal = if (multiline) IaTokens.MultilinePaddingH else IaTokens.InputPaddingH,
                vertical = if (multiline) IaTokens.MultilinePaddingV else IaTokens.InputPaddingV,
            ),
        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
    ) {
        content(focused, colors)
    }
}

/**
 * `Check` (Themes/Theme.xaml): a checkbox with a 13 sp label and the 4 dp vertical margin of the style.
 * Box 18 dp (P4-D DC-126: the WPF default template has no size token; 18 dp matches the app's other 18 dp
 * marks — the export-choice ring), checked = AccentStrong fill with a white check glyph.
 */
@Composable
fun IaCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .iaButtonFocusRing(focused, colors.Accent)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) colors.AccentStrong else colors.Surface2)
                .border(IaTokens.BorderWidth, if (checked) colors.AccentStrong else colors.InputBorder, RoundedCornerShape(4.dp))
                .size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) IaIcon(IaGlyph.Check, 12.dp, colors.OnAccentStrong, null)
        }
        Text(
            label,
            style = IaTextStyles.body(colors).copy(color = colors.Text, fontSize = 13.sp),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
