package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * The button family of Themes/Theme.xaml (`Btn.*`), one composable per style, same names. Shared template
 * behaviour (the XAML ControlTemplate): radius = control radius (8 — never a pill), 1 dp border, a white
 * state overlay (hover 0.07, pressed 0.14) that works for every variant without changing its colour, the
 * accent focus ring drawn 2 dp outside on keyboard focus, and the whole button at 0.4 opacity when disabled.
 */
object Btn {

    @Composable
    fun Base(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.Surface2, c.Border,
            PaddingValues(IaTokens.ButtonBasePaddingH, IaTokens.ButtonBasePaddingV), IaTokens.CompactButtonHeight) {
            Text(text, style = IaTextStyles.button(c))
        }
    }

    @Composable
    fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.AccentStrong, c.AccentStrong,
            PaddingValues(IaTokens.ButtonBasePaddingH, IaTokens.ButtonBasePaddingV), IaTokens.CompactButtonHeight) {
            Text(text, style = IaTextStyles.buttonStrong(c, c.OnAccentStrong))
        }
    }

    @Composable
    fun Danger(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.ErrorStrong, c.ErrorStrong,
            PaddingValues(IaTokens.ButtonBasePaddingH, IaTokens.ButtonBasePaddingV), IaTokens.CompactButtonHeight) {
            Text(text, style = IaTextStyles.buttonStrong(c, c.OnErrorStrong))
        }
    }

    @Composable
    fun Ghost(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, Color.Transparent, Color.Transparent,
            PaddingValues(IaTokens.GhostPaddingH, IaTokens.GhostPaddingV), 0.dp) {
            Text(text, style = IaTextStyles.button(c).copy(color = c.TextDim))
        }
    }

    /** Inline text action: Accent SemiBold text that turns Text on hover/pressed (its overlay lightens the background). */
    @Composable
    fun Link(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val pressed by interaction.collectIsPressedAsState()
        val fg = if (hovered || pressed) c.Text else c.Accent
        Core(onClick, modifier, enabled, description ?: text, Color.Transparent, Color.Transparent,
            PaddingValues(IaTokens.LinkPaddingH, IaTokens.LinkPaddingV), 0.dp, interaction) {
            Text(text, style = IaTextStyles.button(c).copy(color = fg, fontWeight = FontWeight.SemiBold))
        }
    }

    /** Chip: 12 TextDim, padding 11×5, and the style's own 6 dp trailing margin (action-bar rows). */
    @Composable
    fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Box(modifier.padding(end = 6.dp)) {
            Core(onClick, Modifier, enabled, description ?: text, c.Surface2, c.Border,
                PaddingValues(IaTokens.ChipPaddingH, IaTokens.ChipPaddingV), 0.dp) {
                Text(text, style = IaTextStyles.chip(c))
            }
        }
    }

    /** Form buttons: the height of the inputs next to them (36), padding 14×6. */
    @Composable
    fun Form(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.Surface2, c.Border,
            PaddingValues(IaTokens.ButtonPaddingH, IaTokens.ButtonPaddingV), IaTokens.ButtonHeight) {
            Text(text, style = IaTextStyles.button(c))
        }
    }

    @Composable
    fun FormPrimary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.AccentStrong, c.AccentStrong,
            PaddingValues(IaTokens.FormButtonPaddingH, IaTokens.FormButtonPaddingV), IaTokens.ButtonHeight) {
            Text(text, style = IaTextStyles.buttonStrong(c, c.OnAccentStrong))
        }
    }

    @Composable
    fun FormDanger(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description ?: text, c.ErrorStrong, c.ErrorStrong,
            PaddingValues(IaTokens.FormButtonPaddingH, IaTokens.FormButtonPaddingV), IaTokens.ButtonHeight) {
            Text(text, style = IaTextStyles.buttonStrong(c, c.OnErrorStrong))
        }
    }

    /** Arbitrary content (icon + text) with the Base look — the header icon buttons and menu rows build on it. */
    @Composable
    fun Content(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        description: String? = null,
        background: Color? = null,
        content: @Composable RowScope.() -> Unit,
    ) {
        val c = LocalIaColors.current
        Core(onClick, modifier, enabled, description, background ?: c.Surface2, c.Border,
            PaddingValues(IaTokens.ButtonBasePaddingH, IaTokens.ButtonBasePaddingV), IaTokens.CompactButtonHeight,
            content = content)
    }
}

@Composable
private fun Core(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    description: String?,
    background: Color,
    borderColor: Color,
    padding: PaddingValues,
    minHeight: Dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalIaColors.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(shape)
            .background(background)
            .border(IaTokens.BorderWidth, borderColor, shape)
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (description != null) contentDescription = description }
            .defaultMinSize(minHeight = minHeight)
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
        // state overlay (white 0.07 hover / 0.14 pressed) — the XAML template's `State` border
        if (hovered || pressed) {
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = if (pressed) 0.14f else 0.07f)))
        }
    }
}
