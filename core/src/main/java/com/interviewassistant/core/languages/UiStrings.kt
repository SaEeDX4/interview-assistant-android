package com.interviewassistant.core.languages

/**
 * Mirror of `InterviewAssistant.Core/Languages/UiStrings.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Compatibility wrapper over [Loc] (tables live in the Languages/i18n JSON files).
 */
object UiStrings {
    val keys: Array<String> = arrayOf(
        "status_ready", "status_listening", "status_speech", "status_finalizing", "status_answering", "status_paused", "status_reconnecting",
        "status_no_audio", "status_api_error", "status_stopped", "status_hearing",
        "question", "answer", "coach", "waiting", "type_hint", "start", "pause", "resume",
        "shorter", "technical", "example", "full", "keywords", "structure", "adaptive_concise", "adaptive_rapid", "minutes_left",
        "sign_in", "sign_out", "account", "upgrade", "manage_billing", "profiles", "interviews", "reports", "settings",
        "prepare", "start_interview", "authorized_use", "ready",
        "status_quiet", "status_answer_ready", "detail_listening", "detail_paused",
    )

    val languages: List<String> get() = LanguageRegistry.all.map { it.code }

    fun get(lang: String?, key: String): String = Loc.get(lang, key)

    fun format(lang: String?, key: String, vararg args: Any?): String = Loc.format(lang, key, *args)

    fun isComplete(lang: String): Boolean = Loc.missingKeys(lang).isEmpty()
}
