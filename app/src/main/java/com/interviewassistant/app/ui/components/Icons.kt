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
