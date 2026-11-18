package com.interviewassistant.app

import android.app.Application
import com.interviewassistant.app.platform.installLocTables

/**
 * Mirror target of `InterviewAssistant.App/App.xaml.cs` (TRANSFER-MAP §2, layer: Platform).
 *
 * P2 slice: installs the [com.interviewassistant.core.languages.Loc] asset tables so every mirrored
 * string resolves from the byte-identical `:core` i18n assets. The remaining Windows startup duties
 * (global exception handlers, log prune 14 d, self-test isolation mode) are wired in later phases.
 */
class InterviewAssistantApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installLocTables(this)
    }
}
