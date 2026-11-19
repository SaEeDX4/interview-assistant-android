package com.interviewassistant.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.1 token assertions: every value of Themes/Theme.xaml as transcribed in TRANSFER-MAP §3.1 / audit-app §1
 * (the Android counterpart of the Windows token checks — the values, not the WPF mechanics).
 */
class ThemeTokensTest {

    @Test
    fun colours_MatchSection31Verbatim() {
        assertEquals(Color(0xFF0F1115), IaTokens.Bg)
        assertEquals(Color(0xFF161920), IaTokens.Surface)
        assertEquals(Color(0xFF1D212A), IaTokens.Surface2)
        assertEquals(Color(0xFF252A35), IaTokens.Surface3)
        assertEquals(Color(0xFF2A303C), IaTokens.Border)
        assertEquals(Color(0xFFECEEF2), IaTokens.Text)
        assertEquals(Color(0xFFA3A9B5), IaTokens.TextDim)
        assertEquals(Color(0xFF8B92A0), IaTokens.TextFaint)
        assertEquals(Color(0xFF5B8DEF), IaTokens.Accent)
        assertEquals(Color(0x335B8DEF), IaTokens.AccentSoft)
        assertEquals(Color(0xFF3567D0), IaTokens.AccentStrong)
        assertEquals(Color(0xFF5C6577), IaTokens.InputBorder)
        assertEquals(Color(0xFF8F96A4), IaTokens.Placeholder)
        assertEquals(Color(0xFF3DD68C), IaTokens.Listening)
        assertEquals(Color(0xFFF2B44C), IaTokens.Thinking)
        assertEquals(Color(0x1FF2B44C), IaTokens.ThinkingSoft)
        assertEquals(Color(0xFFEF6B6B), IaTokens.Error)
        assertEquals(Color(0x40EF6B6B), IaTokens.ErrorSoft)
        assertEquals(Color(0xFFB3373A), IaTokens.ErrorStrong)
        assertEquals(Color(0xB3080A0E), IaTokens.Backdrop)
    }

    @Test
    fun normalColorSet_IsTheTokenPalette() {
        val n = IaColorSet.Normal
        assertEquals(IaTokens.Bg, n.Bg)
        assertEquals(IaTokens.Surface, n.Surface)
        assertEquals(IaTokens.Accent, n.Accent)
        assertEquals(IaTokens.Backdrop, n.Backdrop)
        assertEquals(Color.White, n.OnAccentStrong)
        assertEquals(Color.White, n.OnErrorStrong)
    }

    @Test
    fun highContrastColorSet_SwapsSurfacesTextAndHighlight() {
        val hc = IaColorSet.HighContrast
        assertEquals(Color(0xFF000000), hc.Bg)
        assertEquals(Color(0xFF000000), hc.Surface)
        assertEquals(Color(0xFFFFFFFF), hc.Text)
        assertEquals(Color(0xFFFFFFFF), hc.Border)
        assertEquals(Color(0xFFFFFF00), hc.Accent)
        assertNotEquals(IaColorSet.Normal.Accent, hc.Accent)
        // status hues survive the swap (they are not system-swapped on Windows either)
        assertEquals(IaTokens.Listening, hc.Listening)
        assertEquals(IaTokens.Error, hc.Error)
    }

    @Test
    fun radii_MatchSection31_AndNothingIsAPill() {
        assertEquals(12.dp, IaTokens.RadiusCard)
        assertEquals(8.dp, IaTokens.RadiusControl)
        assertEquals(11.dp, IaTokens.RadiusFocusRing)
        assertEquals(10.dp, IaTokens.RadiusReadinessBadge)
        assertEquals(56.dp, IaTokens.EmptyStateIconDisc)
        // the owner's rule: buttons/segments/chips use the control radius (8), never a capsule
        assertTrue(IaTokens.RadiusControl < IaTokens.RadiusCard)
    }

    @Test
    fun sizes_MatchSection31() {
        assertEquals(36.dp, IaTokens.ControlHeight)
        assertEquals(36.dp, IaTokens.ButtonHeight)
        assertEquals(30.dp, IaTokens.CompactButtonHeight)
        assertEquals(72.dp, IaTokens.MultilineMinHeight)
        assertEquals(170.dp, IaTokens.MultilineTallHeight)
        assertEquals(13.5.sp, IaTokens.InputFontSize)
        assertEquals(1.dp, IaTokens.BorderWidth)
        assertEquals(820.dp, IaTokens.PageMaxWidth)
        assertEquals(1120.dp, IaTokens.ContentMaxWidth)
        assertEquals(760.dp, IaTokens.ReadingMaxWidth)
        assertEquals(940.dp, IaTokens.SplitMaxWidth)
        assertEquals(620.dp, IaTokens.FormMaxWidth)
        assertEquals(320.dp, IaTokens.ShortFieldWidth)
        assertEquals(240.dp, IaTokens.SidePaneWidth)
        assertEquals(20.dp, IaTokens.PaneGap)
        assertEquals(260.dp, IaTokens.PaneMinWidth)
        assertEquals(12.dp, IaTokens.ColumnGap)
        assertEquals(980.dp, IaTokens.LiveReadableColumnMax)
    }

    @Test
    fun paddings_MatchSection31() {
        assertEquals(10.dp, IaTokens.InputPaddingH); assertEquals(6.dp, IaTokens.InputPaddingV)
        assertEquals(10.dp, IaTokens.MultilinePaddingH); assertEquals(8.dp, IaTokens.MultilinePaddingV)
        assertEquals(14.dp, IaTokens.ButtonPaddingH); assertEquals(6.dp, IaTokens.ButtonPaddingV)
        assertEquals(12.dp, IaTokens.ButtonBasePaddingH); assertEquals(6.dp, IaTokens.ButtonBasePaddingV)
        assertEquals(8.dp, IaTokens.GhostPaddingH); assertEquals(6.dp, IaTokens.GhostPaddingV)
        assertEquals(6.dp, IaTokens.LinkPaddingH); assertEquals(2.dp, IaTokens.LinkPaddingV)
        assertEquals(11.dp, IaTokens.ChipPaddingH); assertEquals(5.dp, IaTokens.ChipPaddingV)
        assertEquals(16.dp, IaTokens.FormButtonPaddingH); assertEquals(6.dp, IaTokens.FormButtonPaddingV)
        assertEquals(10.dp, IaTokens.ListItemPaddingH); assertEquals(7.dp, IaTokens.ListItemPaddingV)
        assertEquals(18.dp, IaTokens.CardPaddingHorizontal); assertEquals(14.dp, IaTokens.CardPaddingVertical)
        assertEquals(12.dp, IaTokens.ChoiceCardPaddingH); assertEquals(10.dp, IaTokens.ChoiceCardPaddingV)
        assertEquals(3.dp, IaTokens.SegTrackPadding)
    }

    @Test
    fun marginsAndShadow_MatchSection31() {
        assertEquals(8.dp, IaTokens.PageMarginStart); assertEquals(16.dp, IaTokens.PageMarginEnd)
        assertEquals(24.dp, IaTokens.SectionMarginTop); assertEquals(8.dp, IaTokens.SectionMarginBottom)
        assertEquals(12.dp, IaTokens.FieldLabelMarginTop); assertEquals(6.dp, IaTokens.FieldLabelMarginBottom)
        assertEquals(4.dp, IaTokens.HintMarginTop)
        assertEquals(14.dp, IaTokens.ButtonRowMarginTop)
        assertEquals(8.dp, IaTokens.StackGap)
        assertEquals(8.dp, IaTokens.DividerMarginTop); assertEquals(16.dp, IaTokens.DividerMarginBottom)
        assertEquals(4.dp, IaTokens.FieldGap)
        assertEquals(24.dp, IaTokens.ShadowBlur); assertEquals(4.dp, IaTokens.ShadowDepth)
        assertEquals(0.35f, IaTokens.ShadowOpacity, 0.0001f)
        assertEquals(2.dp, IaTokens.FocusRingWidth)
        assertEquals(2.dp, IaTokens.FocusRingOutsetButton)
        assertEquals(3.dp, IaTokens.FocusRingOutsetInput)
    }

    @Test
    fun textStyles_MatchSection31() {
        val c = IaColorSet.Normal
        val label = IaTextStyles.label(c)
        assertEquals(11.sp, label.fontSize); assertEquals(FontWeight.SemiBold, label.fontWeight); assertEquals(c.TextFaint, label.color)
        val body = IaTextStyles.body(c)
        assertEquals(13.sp, body.fontSize); assertEquals(c.TextDim, body.color)
        val hint = IaTextStyles.hint(c)
        assertEquals(12.sp, hint.fontSize); assertEquals(c.TextFaint, hint.color)
        assertEquals(12.5.sp, IaTextStyles.metaKey(c).fontSize); assertEquals(c.TextDim, IaTextStyles.metaKey(c).color)
        assertEquals(12.5.sp, IaTextStyles.metaValue(c).fontSize); assertEquals(c.Text, IaTextStyles.metaValue(c).color)
        assertEquals(14.sp, IaTextStyles.icon(c).fontSize)
        val empty = IaTextStyles.emptyTitle(c)
        assertEquals(15.sp, empty.fontSize); assertEquals(FontWeight.SemiBold, empty.fontWeight); assertEquals(c.Text, empty.color)
        val h1 = IaTextStyles.h1(c)
        assertEquals(20.sp, h1.fontSize); assertEquals(FontWeight.SemiBold, h1.fontWeight)
        assertEquals(12.5.sp, IaTextStyles.button(c).fontSize)
        assertEquals(13.5.sp, IaTextStyles.input(c).fontSize)
        assertEquals(13.sp, IaTextStyles.comboItem(c).fontSize)
        val segChecked = IaTextStyles.segItem(c, checked = true)
        assertEquals(FontWeight.SemiBold, segChecked.fontWeight); assertEquals(Color.White, segChecked.color)
        assertEquals(c.TextDim, IaTextStyles.segItem(c, checked = false).color)
        assertEquals(16.sp, IaTextStyles.dialogTitle(c).fontSize)
    }

    @Test
    fun answerAndQuestionFonts_MatchTheFontPresetTable() {
        assertEquals(16.sp, IaTokens.AnswerFontCompact)
        assertEquals(19.sp, IaTokens.AnswerFontNormal)
        assertEquals(22.sp, IaTokens.AnswerFontLarge)
        assertEquals(26.sp, IaTokens.AnswerFontExtraLarge)
        assertEquals(14.sp, IaTokens.QuestionFontCompact)
        assertEquals(16.sp, IaTokens.QuestionFontNormal)
        assertEquals(17.sp, IaTokens.QuestionFontLarge)
        assertEquals(19.sp, IaTokens.QuestionFontExtraLarge)
    }

    @Test
    fun glyphs_CarryTheSegoeFluentCodePoints() {
        assertEquals(0xE768, IaGlyph.Play.codePoint)
        assertEquals(0xE71A, IaGlyph.Stop.codePoint)
        assertEquals(0xE897, IaGlyph.Help.codePoint)
        assertEquals(0xE712, IaGlyph.More.codePoint)
        assertEquals(0xE921, IaGlyph.Min.codePoint)
        assertEquals(0xE922, IaGlyph.Max.codePoint)
        assertEquals(0xE923, IaGlyph.Restore.codePoint)
        assertEquals(0xE8BB, IaGlyph.Close.codePoint)
        assertEquals(0xE80F, IaGlyph.Home.codePoint)
        assertEquals(0xE724, IaGlyph.Send.codePoint)
        assertEquals(0xE73E, IaGlyph.Check.codePoint)
        assertEquals(0xE7BA, IaGlyph.Warning.codePoint)
        assertEquals(0xE711, IaGlyph.Cross.codePoint)
        assertEquals(0xE895, IaGlyph.Sync.codePoint)
        assertEquals(0xE823, IaGlyph.Clock.codePoint)
        assertEquals(0xE9CE, IaGlyph.Unknown.codePoint)
        assertEquals(0xE70D, IaGlyph.Chevron.codePoint)
        assertEquals(0xE946, IaGlyph.Info.codePoint)
        assertEquals(0xEA39, IaGlyph.Error.codePoint)
        // one drawable per glyph
        assertEquals(IaGlyph.entries.size, IaGlyph.entries.map { it.drawable }.distinct().size)
        assertEquals(IaGlyph.entries.size, IaGlyph.entries.map { it.codePoint }.distinct().size)
    }
}
