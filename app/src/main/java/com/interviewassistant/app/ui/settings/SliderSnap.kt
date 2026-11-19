package com.interviewassistant.app.ui.settings

import kotlin.math.roundToLong

/**
 * The Settings sliders' snap rule (WPF `Slider` with `TickFrequency` + `IsSnapToTickEnabled`):
 * a value rests only on a tick — the nearest multiple of [step] from [min], clamped to
 * [min, max]. Pure, so the controller's stored values are exact ticks and the rule is
 * unit-tested without a UI (the Compose slider's own `steps` propose the same ticks; the
 * controller applies this rule to whatever it is handed, like the WPF coercion did).
 */
object SliderSnap {
    fun snap(value: Double, min: Double, max: Double, step: Double): Double {
        val ticks = ((value - min) / step).roundToLong()
        val maxTicks = ((max - min) / step).roundToLong()
        return min + ticks.coerceIn(0, maxTicks) * step
    }
}
