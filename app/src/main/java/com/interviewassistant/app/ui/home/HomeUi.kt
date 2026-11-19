package com.interviewassistant.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.uistate.AccountMenu
import com.interviewassistant.app.uistate.AccountMenuItem
import com.interviewassistant.app.viewmodels.DashboardStep
import com.interviewassistant.app.viewmodels.ObservableObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Shared building blocks of the Home shell and its pages (Package P4-E): the bindable observation
 * helpers every page uses, the "B.*" brush-key resolver (the view models carry WPF brush names as
 * strings, DC-67 — the shell turns them back into colors here), the §3.3 layout decisions as pure
 * functions, and the small pure models (step cards, account menu, readiness badge) the JVM tests pin.
 */

// ---------------------------------------------------------------------------
// Observing the view models (the C#'s WPF data binding)
// ---------------------------------------------------------------------------

/**
 * Collects the StateFlow behind one bindable property as Compose state. The property is read once
 * first: a bindable registers itself on first read or write, and [ObservableObject.propertyFlow]
 * throws for a property that was never touched — the read is the registration (the C1 pattern).
 */
@Composable
fun <T> ObservableObject.bindableState(name: String, read: () -> T): State<T> {
    val flow = remember(this, name) {
        read()
        propertyFlow<T>(name)
    }
    return flow.collectAsState()
}

/**
 * A recomposition trigger for computed (non-bindable) view-model properties: the view models raise
 * property-changed notifications for them (e.g. `profileDetailEnabled`), but no StateFlow exists to
 * collect. The page reads the returned counter, then reads the computed properties directly — the
 * counter's change is what schedules the recomposition, exactly what WPF's PropertyChanged does for
 * a getter-only property.
 */
@Composable
fun ObservableObject.changeCounter(vararg names: String): State<Int> {
    val counter = remember(this) { mutableIntStateOf(0) }
    val watched = remember(this) { names.toSet() }
    DisposableEffect(this, watched) {
        val listener: (String) -> Unit = { if (it in watched) counter.intValue++ }
        addPropertyChangedListener(listener)
        onDispose { removePropertyChangedListener(listener) }
    }
    // Reading the counter here subscribes the calling composition to it: a notification for one
    // of the watched properties is what schedules the recomposition that re-reads the getters.
    counter.intValue
    return counter
}

// ---------------------------------------------------------------------------
// Brush keys ("B.*" strings from the view models -> theme colors)
// ---------------------------------------------------------------------------

/**
 * Resolves a WPF brush key ("B.Accent") to the color of the active color set. The key set is the
 * one the C#'s `B()` helper knows (Themes/Theme.xaml): every brush the theme defines. A null key
 * is transparent (the C#'s nullable brush); an unknown key falls back to TextFaint rather than
 * crashing the shell — the view models only emit keys from this set (pinned by BrushKeyTest).
 */
fun IaColorSet.brush(key: String?): Color = when (key) {
    null -> Color.Transparent
    "B.Bg" -> Bg
    "B.Surface" -> Surface
    "B.Surface2" -> Surface2
    "B.Surface3" -> Surface3
    "B.Border" -> Border
    "B.Text" -> Text
    "B.TextDim" -> TextDim
    "B.TextFaint" -> TextFaint
    "B.Accent" -> Accent
    "B.AccentSoft" -> AccentSoft
    "B.AccentStrong" -> AccentStrong
    "B.OnAccentStrong" -> OnAccentStrong
    "B.InputBorder" -> InputBorder
    "B.Placeholder" -> Placeholder
    "B.Listening" -> Listening
    "B.Thinking" -> Thinking
    "B.ThinkingSoft" -> ThinkingSoft
    "B.Error" -> Error
    "B.ErrorSoft" -> ErrorSoft
    "B.ErrorStrong" -> ErrorStrong
    "B.Backdrop" -> Backdrop
    else -> TextFaint
}

// ---------------------------------------------------------------------------
// Layout decisions (TRANSFER-MAP §3.3), as pure functions of the available width
// ---------------------------------------------------------------------------

/** How the shell's navigation is presented at a given width. */
enum class NavForm { Bottom, Rail }

/** How a list+detail page is arranged at a given width. */
enum class PaneMode { List, Detail, Split }

object HomeLayout {
    /** At and above this width the shell uses a NavigationRail (§3.3 expanded widths). */
    const val RAIL_MIN_WIDTH_DP: Float = 600f

    /** At and above this width list and detail sit side by side (§3.3); below, one pane at a time. */
    const val TWO_PANE_MIN_WIDTH_DP: Float = 720f

    fun navForm(widthDp: Float): NavForm =
        if (widthDp >= RAIL_MIN_WIDTH_DP) NavForm.Rail else NavForm.Bottom

    /**
     * The two-pane rule of §3.3: side by side on wide layouts; on narrow ones the detail replaces
     * the list in place while a selection exists (in-place detail navigation).
     */
    fun paneMode(widthDp: Float, hasSelection: Boolean): PaneMode = when {
        widthDp >= TWO_PANE_MIN_WIDTH_DP -> PaneMode.Split
        hasSelection -> PaneMode.Detail
        else -> PaneMode.List
    }

    /**
     * The Reports list's share of the body width on wide layouts: the C#'s 32-star / 68-star sizing
     * with a 260 dp minimum on each pane, expressed as a fraction (the star share 0.32, widened
     * when the minimum pane width bites, narrowed when the preview's minimum does) — always
     * within 0.2–0.5.
     */
    fun reportsListShare(widthDp: Float): Float {
        val minPane = IaTokens.PaneMinWidth.value
        val listWidth = maxOf(minPane, minOf(0.32f * widthDp, widthDp - minPane))
        return (listWidth / widthDp).coerceIn(0.2f, 0.5f)
    }
}

// ---------------------------------------------------------------------------
// Focus routing (the view models' HomeFocus requests)
// ---------------------------------------------------------------------------

/**
 * Routes the view models' focus requests ([com.interviewassistant.app.viewmodels.HomeFocus]) to
 * the controls the pages register. A request for a target that is not composed yet (the view model
 * asks while the destination page is still being built after a navigation) is kept pending and
 * delivered when the target registers — the C# focuses after the page has been shown, which is the
 * same ordering.
 */
class FocusRegistry {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var pending: String? = null

    fun register(target: String, requester: FocusRequester) {
        requesters[target] = requester
        if (pending == target) {
            pending = null
            deliver(requester)
        }
    }

    fun unregister(target: String, requester: FocusRequester) {
        if (requesters[target] === requester) requesters.remove(target)
    }

    fun request(target: String) {
        val requester = requesters[target]
        if (requester == null) {
            pending = target
            return
        }
        deliver(requester)
    }

    private fun deliver(requester: FocusRequester) {
        // requestFocus throws when the requester is not attached to a layout node yet; the page's
        // own focus effects cover that window, so a missed delivery is safe to drop.
        runCatching { requester.requestFocus() }
    }
}

/** Remembers a [FocusRequester] registered with the shell's [FocusRegistry] under [target]. */
@Composable
fun FocusRegistry.rememberRequester(target: String): FocusRequester {
    val requester = remember { FocusRequester() }
    DisposableEffect(this, target, requester) {
        register(target, requester)
        onDispose { unregister(target, requester) }
    }
    return requester
}

// ---------------------------------------------------------------------------
// Pure page models
// ---------------------------------------------------------------------------

/** The step number as the C# formats it: an integer in the UI language's culture (Loc numbers). */
fun stepNumberText(index: Int, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format((index + 1).toLong())

/**
 * The step card's accessible name (the Rc5 counterpart deferred from P4-D): the C# builds
 * "{number}. {title}: {state}. {detail}" — the middle piece is Loc's "fmt_label_value" of the
 * step title and its state text, formatted by the caller.
 */
fun stepCardAccessibleName(numberText: String, labelValue: String, detail: String): String =
    "$numberText. $labelValue. $detail"

/** What the step card's marker disc shows: the check glyph for a done step, the number otherwise. */
fun stepMarkShowsCheck(step: DashboardStep): Boolean = step.iconVisible

/**
 * The account menu's rows: the view model's localized labels zipped with the menu items in their
 * fixed order (Manage / Settings / Switch / Sign out). A missing label falls back to the item's
 * own Loc key, so the menu never renders a blank row.
 */
object AccountMenuModel {
    fun items(labels: List<String>): List<Pair<AccountMenuItem, String>> =
        AccountMenu.items.mapIndexed { index, item ->
            item to (labels.getOrNull(index) ?: com.interviewassistant.core.languages.Loc.t(AccountMenu.labelKey(item)))
        }
}

/** Maps the readiness badge's glyph character (ReadinessPresentation.icon) to a vector glyph. */
fun badgeGlyph(icon: String): IaGlyph = when (icon) {
    "\uE73E" -> IaGlyph.Check
    "\uE7BA" -> IaGlyph.Warning
    "\uE711" -> IaGlyph.Cross
    "\uE895" -> IaGlyph.Sync
    "\uE823" -> IaGlyph.Clock
    else -> IaGlyph.Unknown
}
