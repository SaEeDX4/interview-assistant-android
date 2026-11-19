package com.interviewassistant.app.uistate

import kotlin.math.max
import kotlin.math.min

/** Mirror of `InterviewAssistant.App/UiState/SettingsCategories.cs`. */

/** One category of the Settings page: its id (also the deep-link section, "settings/<id>") and its texts. */
data class SettingsCategory(val id: String) {
    /** Title in the category list and as the heading of the category ("Audio"). */
    val titleKey: String get() = "set_nav_" + id
    /** One-line description under the title ("Meeting audio device and level"). */
    val descriptionKey: String get() = "set_cat_${id}_desc"
}

/**
 * The Settings page shows one category at a time (progressive disclosure), chosen in a list on the reading-order start
 * side. Deep links ("settings/audio" from the Live gear and the pre-interview check) select a category; anything unknown
 * selects the last used one, or Audio.
 */
object SettingsCategories {
    const val Audio: String = "audio"
    const val Answers: String = "answers"
    const val Listening: String = "listening"
    const val Window: String = "window"
    const val Hotkeys: String = "hotkeys"
    const val Privacy: String = "privacy"
    const val Updates: String = "updates"

    /** In list order. */
    val all: List<SettingsCategory> =
        listOf(Audio, Answers, Listening, Window, Hotkeys, Privacy, Updates).map { id -> SettingsCategory(id) }

    val ids: List<String> = all.map { it.id }

    fun isKnown(id: String?): Boolean = id != null && id in ids

    /** Position in the list, or -1. */
    fun indexOf(id: String?): Int {
        for (i in ids.indices) if (ids[i] == id) return i
        return -1
    }

    /**
     * The category a deep-link section opens: the section itself when known (case and spaces ignored), otherwise the
     * last used category when known, otherwise Audio.
     */
    fun parse(section: String?, lastUsed: String? = null): String {
        val s = section?.trim()?.lowercase()
        if (isKnown(s)) return s!!
        return if (isKnown(lastUsed)) lastUsed!! else Audio
    }

    /** Keyboard Up/Down in the category list: the neighbour, clamped at both ends (no wrap-around). */
    fun move(current: String, delta: Int): String {
        val i = indexOf(current)
        if (i < 0) return Audio
        return ids[min(max(i + delta, 0), ids.size - 1)]
    }
}
