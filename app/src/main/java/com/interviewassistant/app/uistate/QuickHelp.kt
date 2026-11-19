package com.interviewassistant.app.uistate

/** Mirror of `InterviewAssistant.App/UiState/QuickHelp.cs`. */

/** What the user is doing when they press "?" in the Live Assistant. */
enum class QuickHelpState { SignedOut, Setup, Listening, Paused }

/** The one link the popover offers for its state. */
enum class QuickHelpAction { None, SignIn, PreInterviewCheck }

/** A titled group of short tips (localization keys). */
data class QuickHelpSection(val titleKey: String, val lineKeys: List<String>)

/** A shortcut row: what it does (key) and the configured keys ("Ctrl+Alt+L", shown left-to-right). */
data class QuickHelpHotkey(val labelKey: String, val keys: String)

/** The user's configured hotkeys (Settings → Hotkeys). */
data class QuickHelpHotkeys(val startPause: String, val manualInput: String, val toggleWindow: String, val coach: String)

data class QuickHelpContent(val sections: List<QuickHelpSection>, val hotkeys: List<QuickHelpHotkey>, val action: QuickHelpAction) {
    /** Every localization key the content uses (tests: all exist and are translated). */
    val keys: List<String> get() =
        sections.flatMap { s -> listOf(s.titleKey) + s.lineKeys } + hotkeys.map { it.labelKey } +
            (QuickHelp.actionKey(action)?.let { a -> listOf(a) } ?: emptyList())
}

/**
 * Context-aware Quick help (the "?" popover, rc.5 — replaces the big first-use overlay): what to do right now, with
 * the hotkeys the user actually configured. Signed out → how to sign in; setup → start listening when the interview
 * begins, run the pre-interview check; listening / paused → what to do in the interview; an answer on screen adds
 * how to rework and browse it. There is no "Don't show again": it opens by itself once (first use), then only on "?".
 */
object QuickHelp {
    /** A running interview wins over the sign-in state (an expired sign-in keeps the session running). */
    fun stateFor(signInRequired: Boolean, listening: Boolean, paused: Boolean): QuickHelpState =
        if (paused) QuickHelpState.Paused else if (listening) QuickHelpState.Listening else if (signInRequired) QuickHelpState.SignedOut else QuickHelpState.Setup

    fun `for`(state: QuickHelpState, answerShown: Boolean, hk: QuickHelpHotkeys): QuickHelpContent {
        val sections = mutableListOf<QuickHelpSection>()
        val keys = mutableListOf<QuickHelpHotkey>()
        var action = QuickHelpAction.None
        when (state) {
            QuickHelpState.SignedOut -> {
                sections.add(QuickHelpSection("qh_title_signed_out", listOf("qh_signed_out_1", "qh_signed_out_2")))
                keys.add(QuickHelpHotkey("qh_hk_window", hk.toggleWindow))
                action = QuickHelpAction.SignIn
            }
            QuickHelpState.Setup -> {
                sections.add(QuickHelpSection("qh_title_setup", listOf("qh_setup_1", "qh_setup_2", "qh_setup_3")))
                keys.add(QuickHelpHotkey("qh_hk_start", hk.startPause))
                keys.add(QuickHelpHotkey("qh_hk_type", hk.manualInput))
                keys.add(QuickHelpHotkey("qh_hk_window", hk.toggleWindow))
                action = QuickHelpAction.PreInterviewCheck
            }
            QuickHelpState.Listening -> {
                sections.add(QuickHelpSection("qh_title_listening", listOf("qh_listening_1", "qh_listening_2", "qh_listening_3")))
                keys.add(QuickHelpHotkey("qh_hk_start", hk.startPause))
                keys.add(QuickHelpHotkey("qh_hk_type", hk.manualInput))
                keys.add(QuickHelpHotkey("qh_hk_coach", hk.coach))
            }
            else -> {
                sections.add(QuickHelpSection("qh_title_paused", listOf("qh_paused_1", "qh_paused_2")))
                keys.add(QuickHelpHotkey("qh_hk_start", hk.startPause))
                keys.add(QuickHelpHotkey("qh_hk_type", hk.manualInput))
            }
        }
        if (answerShown && state != QuickHelpState.SignedOut)
            sections.add(QuickHelpSection("qh_title_answer", listOf("qh_answer_1", "qh_answer_2")))
        return QuickHelpContent(sections, keys.filter { it.keys.isNotBlank() }, action)
    }

    /** Label key of the action link (null: no link). */
    fun actionKey(a: QuickHelpAction): String? = when (a) {
        QuickHelpAction.SignIn -> "sign_in"
        QuickHelpAction.PreInterviewCheck -> "qh_action_check"
        else -> null
    }

    /** Home deep link of the action (see [HomeRoute]). */
    fun actionRoute(a: QuickHelpAction): String? = when (a) {
        QuickHelpAction.SignIn -> "account"
        QuickHelpAction.PreInterviewCheck -> "preinterview"
        else -> null
    }
}
