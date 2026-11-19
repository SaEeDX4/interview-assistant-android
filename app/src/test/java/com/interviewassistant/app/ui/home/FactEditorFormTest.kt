package com.interviewassistant.app.ui.home

import com.interviewassistant.app.viewmodels.FactRow
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.ProfileFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fact editor's form rules (Package P4-E), mirroring the C# FactEditor: the kind list and
 * its lock while editing, the defaults for a new fact, and the validation — text of at least 2
 * non-space characters, unless the kind is Role with a job title.
 */
class FactEditorFormTest {

    private fun row(kind: FactKind, text: String = "Led a team of five", status: FactStatus = FactStatus.UserConfirmed): FactRow =
        FactRow(ProfileFact(kind = kind, text = text, status = status), status)

    @Test
    fun kindList_matchesTheOriginalsOrder() {
        assertEquals(
            listOf(
                FactKind.Role, FactKind.Achievement, FactKind.Skill, FactKind.Education,
                FactKind.Language, FactKind.Certification, FactKind.Project, FactKind.Summary,
                FactKind.Other,
            ),
            FactEditorKinds,
        )
    }

    @Test
    fun newFact_defaultsToAchievementAndConfirmed() {
        val form = FactEditorForm(null)
        assertEquals(FactKind.Achievement, form.kind)
        assertTrue(form.confirmed)
        assertFalse(form.kindLocked)
        assertEquals("", form.text)
    }

    @Test
    fun editing_prefillsAndLocksTheKind() {
        val form = FactEditorForm(row(FactKind.Skill, "Kotlin and Compose"))
        assertEquals(FactKind.Skill, form.kind)
        assertTrue(form.kindLocked)
        assertEquals("Kotlin and Compose", form.text)
        assertTrue(form.confirmed)
    }

    @Test
    fun editing_anUnconfirmedFact_startsUnticked() {
        val form = FactEditorForm(row(FactKind.Summary, "Summary text", FactStatus.Source))
        assertFalse(form.confirmed)
    }

    @Test
    fun editing_anUnlistedKind_showsTheFirstEntry() {
        // The C#: SelectedIndex = Max(0, IndexOf(Kinds, kind)) — Personal is not in the list.
        val form = FactEditorForm(row(FactKind.Personal, "Personal text"))
        assertEquals(FactKind.Role, form.kind)
    }

    @Test
    fun validation_rejectsShortText() {
        val form = FactEditorForm(null)
        form.text = " x "
        assertFalse(form.validate())
        assertTrue(form.textHasError)
        assertEquals(
            com.interviewassistant.core.languages.Loc.t("fact_text_required"),
            form.errorText,
        )
    }

    @Test
    fun validation_acceptsTwoCharacters() {
        val form = FactEditorForm(null)
        form.text = "ab"
        assertTrue(form.validate())
        assertFalse(form.textHasError)
    }

    @Test
    fun validation_roleMayBeCarriedByItsTitle() {
        val form = FactEditorForm(null)
        form.kindIndex = FactEditorKinds.indexOf(FactKind.Role)
        form.text = ""
        form.title = "Senior Engineer"
        assertTrue(form.validate())

        form.title = "  "
        assertFalse(form.validate())
    }

    @Test
    fun result_trimsEveryField() {
        val form = FactEditorForm(null)
        form.kindIndex = FactEditorKinds.indexOf(FactKind.Role)
        form.text = "  Did things  "
        form.company = "  Acme "
        form.title = " Lead "
        form.period = " 2020–2024 "
        form.confirmed = false
        val result = form.result()
        assertEquals(FactKind.Role, result.kind)
        assertEquals("Did things", result.text)
        assertEquals("Acme", result.company)
        assertEquals("Lead", result.title)
        assertEquals("2020–2024", result.period)
        assertFalse(result.confirmed)
    }
}
