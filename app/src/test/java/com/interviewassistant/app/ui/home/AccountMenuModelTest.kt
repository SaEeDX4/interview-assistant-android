package com.interviewassistant.app.ui.home

import com.interviewassistant.app.uistate.AccountMenu
import com.interviewassistant.app.uistate.AccountMenuItem
import com.interviewassistant.core.languages.Loc
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The account menu's row model (Package P4-E): the view model's labels paired with the menu
 * items in their fixed order (Manage / Settings / Switch / Sign out) — the order the arrow keys
 * walk and the menu renders.
 */
class AccountMenuModelTest {

    @Test
    fun items_followTheFixedMenuOrder() {
        val items = AccountMenuModel.items(listOf("Manage account", "Settings", "Switch account", "Sign out"))
        assertEquals(
            listOf(
                AccountMenuItem.ManageAccount,
                AccountMenuItem.Settings,
                AccountMenuItem.SwitchAccount,
                AccountMenuItem.SignOut,
            ),
            items.map { it.first },
        )
        assertEquals(
            listOf("Manage account", "Settings", "Switch account", "Sign out"),
            items.map { it.second },
        )
    }

    @Test
    fun missingLabels_fallBackToTheItemsOwnKeys() {
        val items = AccountMenuModel.items(emptyList())
        assertEquals(AccountMenu.items.size, items.size)
        items.forEach { (item, label) ->
            assertEquals(Loc.t(AccountMenu.labelKey(item)), label)
        }
    }

    @Test
    fun partialLabels_fallBackPerRow() {
        val items = AccountMenuModel.items(listOf("Only first"))
        assertEquals("Only first", items[0].second)
        assertEquals(Loc.t(AccountMenu.labelKey(AccountMenuItem.Settings)), items[1].second)
    }

    @Test
    fun badgeGlyph_mapsEveryReadinessIcon() {
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Check, badgeGlyph("\uE73E"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Warning, badgeGlyph("\uE7BA"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Cross, badgeGlyph("\uE711"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Sync, badgeGlyph("\uE895"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Clock, badgeGlyph("\uE823"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Unknown, badgeGlyph("\uE9CE"))
        assertEquals(com.interviewassistant.app.ui.components.IaGlyph.Unknown, badgeGlyph(""))
    }
}
