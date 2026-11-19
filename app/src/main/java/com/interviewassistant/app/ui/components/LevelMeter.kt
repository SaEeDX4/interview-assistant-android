package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.LocalIaColors
import kotlin.math.roundToInt

/** The read-only 0–100 value the meter exposes to screen readers (the C#'s `LevelMeter` UIA peer). */
object LevelMeterValue {
    /** Level 0..1 → rounded 0..100 (clamped), exactly the C# peer's `Value`. */
    fun percent(level: Double): Int = (level.coerceIn(0.0, 1.0) * 100).roundToInt()
}

/**
 * Mirror of `Views/Accessible.cs`'s `LevelMeter`: an audio level bar that is a progress bar for screen
 * readers (read-only 0–100), not a plain visual box. The fill width is the level fraction; the track is
 * Surface3, the fill the Listening green.
 */
@Composable
fun IaLevelMeter(
    level: Double,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    description: String? = null,
) {
    val colors = LocalIaColors.current
    val fraction = level.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.Surface3)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (description != null) contentDescription = description
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(colors.Listening),
        )
    }
}
