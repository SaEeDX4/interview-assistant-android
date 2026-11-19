package com.interviewassistant.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mirror of `InterviewAssistant.App/Views/FormAssist.cs`'s `HighContrast` singleton: one process-wide flag,
 * `IsOn`, that every themed control consults (the XAML does it with DataTriggers on `HighContrast.Instance`).
 * On Windows it reads `SystemParameters.HighContrast`; on Android the platform layer sets [isOn] from the
 * device's high-contrast accessibility state (P4-D DC-131). Backed by Compose state so flipping it recomposes
 * the tree with the high-contrast colour set, as the Windows trigger swap does.
 */
object HighContrast {
    var isOn: Boolean by mutableStateOf(false)
}
