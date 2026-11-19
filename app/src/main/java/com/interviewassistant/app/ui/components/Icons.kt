package com.interviewassistant.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.interviewassistant.app.R

/**
 * The Segoe Fluent glyphs of §3.1 / audit-app §1.2, one vector drawable per code point (TRANSFER-MAP §8 D11:
 * glyph shapes are a cosmetic platform substitution; code points, sizes and placements are identical).
 * The [codePoint] is the Windows glyph; [drawable] is its Android drawing.
 */
enum class IaGlyph(val codePoint: Int, @DrawableRes val drawable: Int) {
    Play(0xE768, R.drawable.ic_glyph_play),
    Stop(0xE71A, R.drawable.ic_glyph_stop),
    Help(0xE897, R.drawable.ic_glyph_help),
    More(0xE712, R.drawable.ic_glyph_more),
    Min(0xE921, R.drawable.ic_glyph_min),
    Max(0xE922, R.drawable.ic_glyph_max),
    Restore(0xE923, R.drawable.ic_glyph_restore),
    Close(0xE8BB, R.drawable.ic_glyph_close),
    Home(0xE80F, R.drawable.ic_glyph_home),
    Send(0xE724, R.drawable.ic_glyph_send),
    Check(0xE73E, R.drawable.ic_glyph_check),
    Warning(0xE7BA, R.drawable.ic_glyph_warning),
    Cross(0xE711, R.drawable.ic_glyph_cross),
    Sync(0xE895, R.drawable.ic_glyph_sync),
    Clock(0xE823, R.drawable.ic_glyph_clock),
    Unknown(0xE9CE, R.drawable.ic_glyph_unknown),
    Chevron(0xE70D, R.drawable.ic_glyph_chevron),
    Info(0xE946, R.drawable.ic_glyph_info),
    Error(0xEA39, R.drawable.ic_glyph_error),
    // Added in P4-E (DC-144, additive only): the fact-row edit/delete actions and the Reports
    // empty/select states use these Segoe code points in the original; P4-D shipped no drawable
    // for them because no P4-D surface needed one.
    Edit(0xE70F, R.drawable.ic_glyph_edit),
    Delete(0xE74D, R.drawable.ic_glyph_delete),
    List(0xE8FD, R.drawable.ic_glyph_list),
    Report(0xE9F9, R.drawable.ic_glyph_report),
    // Added in P4-F (DC-156, additive only): the Account page's refresh button (E72C) and the
    // device rows' monitor glyph (E7F8) had no drawable in the P4-D set.
    Refresh(0xE72C, R.drawable.ic_glyph_refresh),
    Devices(0xE7F8, R.drawable.ic_glyph_devices),
    // Added in P4-G (DC-163, additive only): the Live chrome and the Settings page need the
    // Start button's pause state (E769), the history chevrons (E76B/E76C), the More menu's
    // Settings (E713), Compact (E73F), Focus (E714) and Focus-restore (E7A7) rows, the reset
    // buttons' restore glyph (E777) and the profile-gap card's add glyph (E710).
    Pause(0xE769, R.drawable.ic_glyph_pause),
    ChevronLeft(0xE76B, R.drawable.ic_glyph_chevron_left),
    ChevronRight(0xE76C, R.drawable.ic_glyph_chevron_right),
    Settings(0xE713, R.drawable.ic_glyph_settings),
    Compact(0xE73F, R.drawable.ic_glyph_compact),
    Focus(0xE714, R.drawable.ic_glyph_focus),
    FocusRestore(0xE7A7, R.drawable.ic_glyph_focus_restore),
    Reset(0xE777, R.drawable.ic_glyph_reset),
    Add(0xE710, R.drawable.ic_glyph_add),
}

/** Draws one [IaGlyph] at [size] with [tint] (the XAML renders the glyph font at a font size; here, a square box). */
@Composable
fun IaIcon(glyph: IaGlyph, size: Dp, tint: Color, contentDescription: String?, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(glyph.drawable),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
