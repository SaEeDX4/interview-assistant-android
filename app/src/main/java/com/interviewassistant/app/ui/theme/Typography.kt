package com.interviewassistant.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Text styles of §3.1 / audit-app §1.4 (the XAML `T.*` styles), as functions of the active [IaColorSet] so the
 * high-contrast swap recolours text exactly like the Windows brushes do. Font family is the platform sans
 * (D11: Segoe UI Variable → Roboto) at identical sizes and weights.
 *
 * `Section` is not a separate style: it is [label] with the section margin (0/24/0/8) and heading semantics
 * level 2, applied by the caller (see TRANSFER-MAP §3.1).
 */
object IaTextStyles {
    /** `T.Label`: 11 SemiBold TextFaint. */
    fun label(c: IaColorSet): TextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.TextFaint)

    /** `T.Body`: 13 TextDim, wrapping. */
    fun body(c: IaColorSet): TextStyle = TextStyle(fontSize = 13.sp, color = c.TextDim)

    /** `T.Hint`: body at 12, TextFaint. */
    fun hint(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.sp, color = c.TextFaint)

    /** `T.MetaKey`: body at 12.5 (key of a key/value row). */
    fun metaKey(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.5.sp, color = c.TextDim)

    /** `T.MetaValue`: body at 12.5, Text. */
    fun metaValue(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.5.sp, color = c.Text)

    /** `T.Icon`: icon glyph size 14 (colour is the caller's). */
    fun icon(c: IaColorSet): TextStyle = TextStyle(fontSize = 14.sp, color = c.Text)

    /** `T.EmptyTitle`: 15 SemiBold Text. */
    fun emptyTitle(c: IaColorSet): TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.Text)

    /** Page `H1`: Display 20 SemiBold Text. */
    fun h1(c: IaColorSet): TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.Text)

    /** `Btn.Base` text: 12.5 Text. */
    fun button(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.5.sp, color = c.Text)

    /** `Btn.Primary` / `Btn.Danger` text: 12.5 SemiBold white. */
    fun buttonStrong(c: IaColorSet, onColor: androidx.compose.ui.graphics.Color): TextStyle =
        TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onColor)

    /** `Btn.Chip` text: 12 TextDim. */
    fun chip(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.sp, color = c.TextDim)

    /** Field text of `Input` / `Combo`: 13.5 Text. */
    fun input(c: IaColorSet): TextStyle = TextStyle(fontSize = IaTokens.InputFontSize, color = c.Text)

    /** `ComboBoxItem` popup text: 13 (the map's "popup items 13"). */
    fun comboItem(c: IaColorSet): TextStyle = TextStyle(fontSize = 13.sp, color = c.Text)

    /** `Seg.Item` text: 13 (TextDim unchecked; checked is white SemiBold). */
    fun segItem(c: IaColorSet, checked: Boolean): TextStyle =
        if (checked) TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.OnAccentStrong)
        else TextStyle(fontSize = 13.sp, color = c.TextDim)

    /** Dialog title (AppDialog card): Display 16 SemiBold Text. */
    fun dialogTitle(c: IaColorSet): TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.Text)

    /** Dialog message with a title: 13 TextDim, line height 18. */
    fun dialogMessage(c: IaColorSet): TextStyle = TextStyle(fontSize = 13.sp, color = c.TextDim, lineHeight = 18.sp)

    /** Dialog message without a title: 14 Text, line height 20. */
    fun dialogMessageNoTitle(c: IaColorSet): TextStyle = TextStyle(fontSize = 14.sp, color = c.Text, lineHeight = 20.sp)

    /** Toast title: 12.5 SemiBold Text. */
    fun toastTitle(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.Text)

    /** Toast detail / InlineMessage detail: 11.5 TextDim. */
    fun toastDetail(c: IaColorSet): TextStyle = TextStyle(fontSize = 11.5.sp, color = c.TextDim)

    /** InlineMessage text: 12.5 Text. */
    fun inlineText(c: IaColorSet): TextStyle = TextStyle(fontSize = 12.5.sp, color = c.Text)
}
