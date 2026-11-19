package com.interviewassistant.app.ui.home

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.services.Branding
import com.interviewassistant.app.ui.account.AccountPage
import com.interviewassistant.app.ui.components.AppDialogHost
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.HighContrast
import com.interviewassistant.app.ui.components.IaCombo
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.components.ToastHost
import com.interviewassistant.app.ui.components.iaButtonFocusRing
import com.interviewassistant.app.ui.help.HelpPage
import com.interviewassistant.app.ui.preinterview.PreInterviewPage
import com.interviewassistant.app.ui.preinterview.PreInterviewPageControllerImpl
import com.interviewassistant.app.ui.settings.SettingsPage
import com.interviewassistant.app.ui.settings.SettingsPageControllerImpl
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.viewmodels.AccountMenuKey
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc

/**
 * The Home shell (HomeWindow.xaml): title bar with the readiness pill and the account button,
 * the destination navigation, the scrolling page area, and the two sticky action bars (Settings
 * save bar, pre-interview bar). Navigation is state-driven — the shell renders the page the
 * HomeViewModel's route state names (the P4 architecture; the VM also applies the signed-out
 * gate, so the shell simply renders what the state says).
 *
 * Layout follows §3.3: a navigation rail on expanded widths (the C# sidebar's form), a bottom
 * navigation bar on compact widths (DC-149); the page margin is 8/0/16/8 and the content is at
 * most 1120 wide. The Account / PreInterview / Help page bodies plug into the page dispatch
 * (P4-F, DC-136); the Settings body plugs in with its controller in P4-G. The shell chrome —
 * footers included — is here.
 */
@Composable
fun HomeScreen(
    home: HomeViewModel,
    dialogs: DialogController,
    toasts: ToastController,
    focusRegistry: FocusRegistry,
    preInterview: PreInterviewPageControllerImpl,
    settings: SettingsPageControllerImpl,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val page by home.bindableState("page") { home.page }
    val uiLanguageCode by home.bindableState("uiLanguageCode") { home.uiLanguageCode }

    SyncHighContrastWithPlatform()

    // The window's flow direction follows the UI language (the C#'s LocFlow on the window).
    val direction = if (Loc.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Box(modifier.fillMaxSize().background(colors.Bg)) {
            // Loc strings are read during composition but are not snapshot state: a language
            // switch rebuilds the shell's composition (the C# re-renders the whole window), so
            // every string is re-read in the new language. View-model state is untouched.
            key(uiLanguageCode) {
                Column(Modifier.fillMaxSize()) {
                    TitleBar(home, focusRegistry)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        when (HomeLayout.navForm(maxWidth.value)) {
                            NavForm.Rail -> Row(Modifier.fillMaxSize()) {
                                NavRail(home, page)
                                ContentColumn(home, page, focusRegistry, preInterview, settings, Modifier.weight(1f))
                            }
                            NavForm.Bottom -> Column(Modifier.fillMaxSize()) {
                                ContentColumn(home, page, focusRegistry, preInterview, settings, Modifier.weight(1f))
                                LanguageStrip(home)
                                BottomNav(home, page)
                            }
                        }
                    }
                }
                AccountMenuOverlay(home, focusRegistry)
                ToastHost(toasts)
                AppDialogHost(dialogs)
            }
        }
    }
}

/**
 * The platform high-contrast state drives the theme's color set (DC-148): Android's counterpart
 * of the OS high-contrast theme is the "high contrast text" secure setting; the shell observes it
 * and feeds the P4-D HighContrast seam the theme reads.
 */
@Composable
private fun SyncHighContrastWithPlatform() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor("high_text_contrast_enabled")
        fun read() {
            HighContrast.isOn = try {
                Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1
            } catch (ignored: Exception) {
                false
            }
        }
        read()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = read()
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
}

// ---------------------------------------------------------------------------
// Title bar
// ---------------------------------------------------------------------------

@Composable
private fun TitleBar(home: HomeViewModel, focusRegistry: FocusRegistry) {
    val colors = LocalIaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 12.dp, end = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Branding.productName,
                style = IaTextStyles.h1(colors),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ReadinessPill(home)
        }
        AccountButton(home, focusRegistry)
        // The C#'s minimize / maximize / close buttons are OS window chrome; an Android activity
        // has no counterpart for them (DC-137), so the title bar ends at the account button.
    }
}

/** The readiness pill: state dot + label (never color alone); opens the pre-interview check. */
@Composable
private fun ReadinessPill(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val pillText by home.bindableState("pillText") { home.pillText }
    val pillBrushKey by home.bindableState("pillBrushKey") { home.pillBrushKey }
    val pillName by home.bindableState("pillName") { home.pillName }

    Box(Modifier.padding(start = 6.dp, top = 2.dp)) {
        Btn.Content(
            onClick = { home.onReadinessPillClick() },
            description = pillName,
            background = colors.Surface2,
        ) {
            Box(
                Modifier
                    .padding(end = 7.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.brush(pillBrushKey)),
            )
            Text(pillText, style = IaTextStyles.chip(colors).copy(fontSize = 12.sp, color = colors.Text))
        }
    }
}

@Composable
private fun AccountButton(home: HomeViewModel, focusRegistry: FocusRegistry) {
    val colors = LocalIaColors.current
    val buttonText by home.bindableState("accountMenuButtonText") { home.accountMenuButtonText }
    val buttonName by home.bindableState("accountMenuButtonName") { home.accountMenuButtonName }
    val avatarText by home.bindableState("accountAvatarText") { home.accountAvatarText }
    val avatarVisible by home.bindableState("accountAvatarVisible") { home.accountAvatarVisible }
    val chevronVisible by home.bindableState("accountMenuChevronVisible") { home.accountMenuChevronVisible }
    val menuOpen by home.bindableState("accountMenuOpen") { home.accountMenuOpen }

    Box(Modifier.padding(end = 8.dp)) {
        GhostContentButton(
            onClick = { home.onAccountMenuButtonClick() },
            description = buttonName,
            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 3.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .focusRequester(focusRegistry.rememberRequester(HomeFocus.AccountMenuButton))
                .onPreviewKeyEvent { event ->
                    if (!menuOpen) false else home.onAccountMenuKey(accountMenuKey(event.key) ?: return@onPreviewKeyEvent false, home.accountMenuFocusIndex)
                },
        ) {
            if (avatarVisible) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.AccentStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        avatarText,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.OnAccentStrong),
                    )
                }
            }
            Text(
                buttonText,
                style = TextStyle(fontSize = 12.5.sp, color = colors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (chevronVisible) {
                IaIcon(
                    IaGlyph.Chevron, 10.dp, colors.TextDim, null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * A ghost button with custom content (the C#'s Btn.Ghost with a StackPanel inside: the account
 * button, the nav entries). Btn.Content is the Base look (Surface2 + border); the ghost treatment
 * is transparent with the family's white state overlay (hover 0.07 / pressed 0.14) and the accent
 * focus ring — drawn here with the same values the P4-D Core uses.
 */
@Composable
private fun GhostContentButton(
    onClick: () -> Unit,
    description: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeBackground: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    padding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = LocalIaColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val overlay = when {
        pressed -> 0.14f
        hovered -> 0.07f
        else -> 0f
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(activeBackground)
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = overlay))
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (description != null) contentDescription = description }
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Maps a Compose key to the account menu's key set; null = not a menu key. */
private fun accountMenuKey(key: Key): AccountMenuKey? = when (key) {
    Key.DirectionDown -> AccountMenuKey.Down
    Key.DirectionUp -> AccountMenuKey.Up
    Key.MoveHome -> AccountMenuKey.Home
    Key.MoveEnd -> AccountMenuKey.End
    Key.Escape -> AccountMenuKey.Escape
    else -> null
}

// ---------------------------------------------------------------------------
// Account menu (the C#'s AccountMenuLayer, DC-139)
// ---------------------------------------------------------------------------

@Composable
private fun AccountMenuOverlay(home: HomeViewModel, focusRegistry: FocusRegistry) {
    val colors = LocalIaColors.current
    val open by home.bindableState("accountMenuOpen") { home.accountMenuOpen }
    val email by home.bindableState("accountMenuEmailText") { home.accountMenuEmailText }
    val plan by home.bindableState("accountMenuPlanText") { home.accountMenuPlanText }
    val labels by home.bindableState("accountMenuItemLabels") { home.accountMenuItemLabels }
    val focusIndex by home.bindableState("accountMenuFocusIndex") { home.accountMenuFocusIndex }

    if (!open) return
    val items = AccountMenuModel.items(labels)
    val firstItemRequester = focusRegistry.rememberRequester(HomeFocus.AccountMenuFirstItem)
    val requesters = mutableListOf<FocusRequester>()
    for (index in items.indices) {
        requesters += if (index == 0) firstItemRequester else remember { FocusRequester() }
    }
    LaunchedEffect(open, focusIndex) {
        if (open) requesters.getOrNull(focusIndex)?.let { runCatching { it.requestFocus() } }
    }

    Box(Modifier.fillMaxSize()) {
        // Transparent backdrop: a click anywhere outside closes the menu (without focus return).
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { home.onAccountMenuBackdropClick() },
        )
        // The menu card: top-end under the title bar, Card drawing with 6 dp padding.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 52.dp, end = 10.dp)
                .widthIn(min = 240.dp, max = 340.dp)
                .clip(RoundedCornerShape(IaTokens.RadiusCard))
                .background(colors.Surface)
                .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
                .onPreviewKeyEvent { event ->
                    val menuKey = accountMenuKey(event.key) ?: return@onPreviewKeyEvent false
                    home.onAccountMenuKey(menuKey, focusIndex)
                }
                .padding(6.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    email,
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(
                plan,
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .height(1.dp)
                    .background(colors.Border),
            )
            items.forEachIndexed { index, (item, label) ->
                AccountMenuRow(
                    label = label,
                    requester = requesters[index],
                    onClick = { home.onAccountMenuItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun AccountMenuRow(label: String, requester: FocusRequester, onClick: () -> Unit) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClick() }
            .focusRequester(requester)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 13.sp, color = colors.Text))
    }
}

// ---------------------------------------------------------------------------
// Navigation (rail on expanded widths, bottom bar on compact — DC-149)
// ---------------------------------------------------------------------------

/** The destinations in the C#'s nav order, with their label keys. */
private val navDestinations: List<Pair<String, String>> = listOf(
    "start" to "nav_start",
    "profiles" to "profiles",
    "interviews" to "interviews",
    "preinterview" to "preflight",
    "reports" to "reports",
    "account" to "account",
    "settings" to "settings",
    "help" to "nav_help",
)

/** The data pages the signed-out gate hides (the view model rewrites their routes to Account). */
private val gatedDestinations = setOf("profiles", "interviews", "reports")

@Composable
private fun NavRail(home: HomeViewModel, page: String) {
    val colors = LocalIaColors.current
    home.changeCounter("navDataPagesVisible")
    val dataVisible = home.navDataPagesVisible

    Column(
        Modifier
            .width(210.dp)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
    ) {
        navDestinations.forEach { (tag, labelKey) ->
            if (tag in gatedDestinations && !dataVisible) return@forEach
            NavItem(tag, Loc.t(labelKey), active = page == tag, modifier = Modifier.fillMaxWidth()) { home.navigate(tag) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .height(1.dp)
                .background(colors.Border),
        )
        LanguageField(home)
    }
}

@Composable
private fun BottomNav(home: HomeViewModel, page: String) {
    val colors = LocalIaColors.current
    home.changeCounter("navDataPagesVisible")
    val dataVisible = home.navDataPagesVisible

    Column(Modifier.fillMaxWidth().background(colors.Surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.Border),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            navDestinations.forEach { (tag, labelKey) ->
                if (tag in gatedDestinations && !dataVisible) return@forEach
                NavItem(tag, Loc.t(labelKey), active = page == tag) { home.navigate(tag) }
            }
        }
    }
}

/** The language picker row shown above the bottom bar in the compact form (DC-149). */
@Composable
private fun LanguageStrip(home: HomeViewModel) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        LanguageField(home)
    }
}

@Composable
private fun LanguageField(home: HomeViewModel) {
    val items by home.bindableState("uiLanguageItems") { home.uiLanguageItems }
    val code by home.bindableState("uiLanguageCode") { home.uiLanguageCode }
    IaFormField(label = Loc.t("ui_language"), info = Loc.t("ui_language_hint")) {
        IaCombo(
            items = items.map { it.displayName },
            selectedIndex = items.indexOfFirst { it.code == code },
            onSelect = { index -> items.getOrNull(index)?.let { home.onUiLanguageChanged(it.code) } },
            description = Loc.t("ui_language"),
        )
    }
}

/** One nav entry: the C#'s Nav button — a ghost text button, Surface2 + SemiBold while active. */
@Composable
private fun NavItem(tag: String, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalIaColors.current
    Box(modifier.padding(bottom = 2.dp)) {
        GhostContentButton(
            onClick = onClick,
            description = label,
            activeBackground = if (active) colors.Surface2 else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                label,
                style = TextStyle(
                    fontSize = 13.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.Text else colors.TextDim,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Content: the page dispatch + the sticky action bars
// ---------------------------------------------------------------------------

@Composable
private fun ContentColumn(
    home: HomeViewModel,
    page: String,
    focusRegistry: FocusRegistry,
    preInterview: PreInterviewPageControllerImpl,
    settings: SettingsPageControllerImpl,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    // Entering a page scrolls back to the top (the C#'s ShowPage).
    LaunchedEffect(page) { scrollState.scrollTo(0) }

    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = IaTokens.PageMarginStart,
                        top = IaTokens.PageMarginTop,
                        end = IaTokens.PageMarginEnd,
                        bottom = IaTokens.PageMarginBottom,
                    ),
            ) {
                Box(Modifier.widthIn(max = IaTokens.ContentMaxWidth)) {
                    when (page) {
                        "start" -> StartPage(home, focusRegistry)
                        "profiles" -> ProfilesPage(home, focusRegistry)
                        "interviews" -> InterviewsPage(home, focusRegistry)
                        "reports" -> ReportsPage(home, focusRegistry)
                        "preinterview" -> PreInterviewPage(home, preInterview)
                        "account" -> AccountPage(home, focusRegistry)
                        "help" -> HelpPage(home)
                        "settings" -> SettingsPage(settings)
                        else -> Unit
                    }
                }
            }
        }
        if (page == "settings") SettingsFooter(home)
        if (page == "preinterview") PreInterviewFooter(home)
    }
}

/** The Settings sticky save bar: dirty dot + state text, Discard / Save (shown on Settings only). */
@Composable
private fun SettingsFooter(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val dirtyText by home.bindableState("settingsDirtyText") { home.settingsDirtyText }
    val dirtyVisible by home.bindableState("settingsDirtyVisible") { home.settingsDirtyVisible }
    val saveEnabled by home.bindableState("settingsSaveEnabled") { home.settingsSaveEnabled }
    val discardEnabled by home.bindableState("settingsDiscardEnabled") { home.settingsDiscardEnabled }

    FooterBar {
        if (dirtyVisible) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.Thinking),
            )
        }
        Text(
            dirtyText,
            style = IaTextStyles.body(colors).copy(color = colors.Text),
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Btn.Form(
            Loc.t("dlg_discard"),
            onClick = { home.onSettingsDiscardClick() },
            enabled = discardEnabled,
            modifier = Modifier.padding(end = 8.dp),
        )
        Btn.FormPrimary(
            Loc.t("dlg_save"),
            onClick = { home.onSettingsSaveClick() },
            enabled = saveEnabled,
        )
    }
}

/** The pre-interview sticky bar: state dot + text, Open settings / Run all / Start interview. */
@Composable
private fun PreInterviewFooter(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val footerText by home.bindableState("readinessFooterText") { home.readinessFooterText }
    val footerBrushKey by home.bindableState("readinessFooterBrushKey") { home.readinessFooterBrushKey }
    val runEnabled by home.bindableState("pfRunEnabled") { home.pfRunEnabled }
    val startEnabled by home.bindableState("pfStartEnabled") { home.pfStartEnabled }
    val startTooltip by home.bindableState("pfStartTooltip") { home.pfStartTooltip }

    FooterBar(maxWidth = IaTokens.PageMaxWidth) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.brush(footerBrushKey)),
        )
        Text(
            footerText,
            style = IaTextStyles.body(colors).copy(color = colors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Btn.Ghost(
            Loc.t("rd_open_settings"),
            onClick = { home.onPfSettingsClick() },
            modifier = Modifier.padding(end = 8.dp),
        )
        Btn.Form(
            Loc.t("pf_run_all"),
            onClick = { home.onPfRunClick() },
            enabled = runEnabled,
            modifier = Modifier.padding(end = 8.dp),
        )
        Btn.FormPrimary(
            Loc.t("pf_start_interview"),
            onClick = { home.onPfStartClick() },
            enabled = startEnabled,
            description = startTooltip,
        )
    }
}

/** The sticky action bars' shared drawing: a Card bar, start-aligned, as wide as its page. */
@Composable
private fun FooterBar(
    maxWidth: androidx.compose.ui.unit.Dp = IaTokens.ContentMaxWidth,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = LocalIaColors.current
    Box(
        Modifier
            .padding(start = 8.dp, top = 8.dp, end = 16.dp)
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
