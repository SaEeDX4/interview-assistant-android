package com.interviewassistant.app.ui.home

import androidx.compose.ui.graphics.Color
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.uistate.SetupStep
import com.interviewassistant.app.uistate.StepStatus
import com.interviewassistant.app.uistate.UsageMetric
import com.interviewassistant.app.viewmodels.DashboardStep
import com.interviewassistant.app.viewmodels.MetricRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The brush-key resolver (Package P4-E): every "B.*" key the view models can emit must resolve
 * to the theme color of the same name — a key that fell back silently would render the wrong
 * color on the readiness pill, the step cards, the footers and the usage bars.
 */
class BrushKeyTest {

    private val colors = IaColorSet.Normal

    /** The full brush table (Themes/Theme.xaml), key -> token of the same name. */
    private val table = mapOf(
        "B.Bg" to IaTokens.Bg,
        "B.Surface" to IaTokens.Surface,
        "B.Surface2" to IaTokens.Surface2,
        "B.Surface3" to IaTokens.Surface3,
        "B.Border" to IaTokens.Border,
        "B.Text" to IaTokens.Text,
        "B.TextDim" to IaTokens.TextDim,
        "B.TextFaint" to IaTokens.TextFaint,
        "B.Accent" to IaTokens.Accent,
        "B.AccentSoft" to IaTokens.AccentSoft,
        "B.AccentStrong" to IaTokens.AccentStrong,
        "B.InputBorder" to IaTokens.InputBorder,
        "B.Placeholder" to IaTokens.Placeholder,
        "B.Listening" to IaTokens.Listening,
        "B.Thinking" to IaTokens.Thinking,
        "B.ThinkingSoft" to IaTokens.ThinkingSoft,
        "B.Error" to IaTokens.Error,
        "B.ErrorSoft" to IaTokens.ErrorSoft,
        "B.ErrorStrong" to IaTokens.ErrorStrong,
        "B.Backdrop" to IaTokens.Backdrop,
    )

    @Test
    fun everyThemeBrushKeyResolvesToItsOwnColor() {
        for ((key, color) in table) {
            assertEquals("brush $key", color, colors.brush(key))
        }
    }

    @Test
    fun dashboardStepKeysAllResolve() {
        val keys = mutableSetOf<String?>()
        for (status in StepStatus.entries) {
            val step = DashboardStep(SetupStep.Account, status, "state", "detail")
            keys += step.markBackgroundKey
            keys += step.markBorderKey
            keys += step.stateBrushKey
            keys += step.cardBorderKey
            keys += step.detailBrushKey
        }
        // The complete key set the step model can emit (plus null = transparent background).
        assertEquals(
            setOf(null, "B.AccentStrong", "B.Surface2", "B.Listening", "B.Border", "B.Accent", "B.TextFaint", "B.TextDim"),
            keys,
        )
        for (key in keys) {
            if (key == null) assertEquals(Color.Transparent, colors.brush(null))
            else assertEquals("step key $key", table[key], colors.brush(key))
        }
    }

    @Test
    fun metricRowKeysResolve() {
        val thinking = MetricRow.from(UsageMetric("id", "L", "V", "D", 0.95))
        assertEquals("B.Thinking", thinking.barBrushKey)
        assertEquals(table[thinking.barBrushKey], colors.brush(thinking.barBrushKey))

        val accent = MetricRow.from(UsageMetric("id", "L", "V", "D", 0.5))
        assertEquals("B.Accent", accent.barBrushKey)
        assertEquals(table[accent.barBrushKey], colors.brush(accent.barBrushKey))
    }

    @Test
    fun nullKeyIsTransparent() {
        assertEquals(Color.Transparent, colors.brush(null))
    }
}
