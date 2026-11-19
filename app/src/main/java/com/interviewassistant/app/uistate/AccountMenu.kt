package com.interviewassistant.app.uistate

/** Mirror of `InterviewAssistant.App/UiState/AccountMenu.cs`. */

enum class AccountMenuItem { ManageAccount, Settings, SwitchAccount, SignOut }

/**
 * The header account button and its menu (Home title bar). Signed in: avatar initial + e-mail, opening a menu with the
 * account e-mail, the plan line and Manage account / Settings / Switch account / Sign out. Signed out: the button
 * reads "Sign in" and goes straight to the Account page. Plain class (no WPF) so the rules are unit-tested.
 */
object AccountMenu {
    /** Menu entries in display (and arrow-key) order. */
    val items: List<AccountMenuItem> =
        listOf(AccountMenuItem.ManageAccount, AccountMenuItem.Settings, AccountMenuItem.SwitchAccount, AccountMenuItem.SignOut)

    fun labelKey(item: AccountMenuItem): String = when (item) {
        AccountMenuItem.ManageAccount -> "menu_manage_account"
        AccountMenuItem.Settings -> "settings"
        AccountMenuItem.SwitchAccount -> "switch_account"
        else -> "sign_out"
    }

    /** Home route each entry opens (null = an action on this page: switch account, sign out). */
    fun route(item: AccountMenuItem): String? = when (item) {
        AccountMenuItem.ManageAccount -> "account"
        AccountMenuItem.Settings -> "settings"
        else -> null
    }

    /** The button opens the menu only for a signed-in account; otherwise it leads to the sign-in page. */
    fun opensMenu(signedIn: Boolean): Boolean = signedIn

    /** Avatar letter: the first letter or digit of the e-mail, upper-case ("?" when there is none). */
    fun initial(email: String?): String {
        for (ch in email ?: "") if (ch.isLetterOrDigit()) return ch.uppercaseChar().toString()
        return "?"
    }

    /** Arrow keys move through the entries and wrap around (Down after the last goes to the first). */
    fun move(current: Int, count: Int, down: Boolean): Int {
        if (count <= 0) return -1
        if (current < 0 || current >= count) return if (down) 0 else count - 1
        return ((if (down) current + 1 else current - 1) % count + count) % count
    }
}
