package com.interviewassistant.app.uistate

/**
 * Mirror of `InterviewAssistant.App/UiState/HomeRoute.cs`.
 *
 * A deep link into the Home window: a page and an optional section ("settings/audio", "preinterview",
 * "account"). Resolved BEFORE the window shows or switches page, so the target appears directly —
 * never another page first.
 */
data class HomeRoute(val page: String, val section: String? = null) {

    /** The page actually shown: signed out (account service configured), the user's data pages lead to Account. */
    fun gate(signedOutGate: Boolean): HomeRoute =
        if (signedOutGate && (page == "profiles" || page == "interviews" || page == "reports")) HomeRoute("account") else this

    override fun toString(): String = if (section == null) page else "$page/$section"

    companion object {
        /** Pages of the Home window (their navigation tags). */
        val pages: List<String> = listOf("start", "profiles", "interviews", "reports", "preinterview", "account", "settings", "help")

        val start: HomeRoute = HomeRoute("start")

        /** "settings/audio" → (settings, audio). Unknown pages fall back to Get started; unknown sections are dropped by the page. */
        fun parse(route: String?): HomeRoute {
            // C# splits with a count of 2: the section is everything after the first '/' ("a/b/c" → section "b/c").
            val parts = (route ?: "").trim().trim('/').split('/', limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return start
            val page = parts[0].lowercase()
            if (page !in pages) return start
            return HomeRoute(page, if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].lowercase() else null)
        }
    }
}
