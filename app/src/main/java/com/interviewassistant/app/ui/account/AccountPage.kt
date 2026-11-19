package com.interviewassistant.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCard
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaInlineMessage
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.ui.components.PasswordInput
import com.interviewassistant.app.ui.components.iaButtonFocusRing
import com.interviewassistant.app.ui.home.FocusRegistry
import com.interviewassistant.app.ui.home.bindableState
import com.interviewassistant.app.ui.home.brush
import com.interviewassistant.app.ui.theme.IaColorSet
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.AccountLayout
import com.interviewassistant.app.viewmodels.DeviceRow
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.app.viewmodels.InlineMessageState
import com.interviewassistant.app.viewmodels.MetricRow
import com.interviewassistant.core.languages.Loc

/**
 * Account (HomeWindow.xaml PageAccount + HomeWindow.Account.cs): the page header, the signed-out
 * product + auth-form layout (one card, five states, driven entirely by the view model's
 * ApplyAuthState outputs), the signed-in dashboard (Plan & usage / Devices | Security / Privacy /
 * Danger zone — two columns from 720 dp of content width, one below), and the not-configured note.
 * Every text, visibility and enabled state is a HomeViewModel bindable; the page only draws them.
 */
@Composable
fun AccountPage(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val headerVisible by home.bindableState("accountHeaderVisible") { home.accountHeaderVisible }
    val statusText by home.bindableState("accountStatusText") { home.accountStatusText }
    val statusBrushKey by home.bindableState("accountStatusBrushKey") { home.accountStatusBrushKey }
    val notConfigured by home.bindableState("notConfiguredPanelVisible") { home.notConfiguredPanelVisible }
    val signedOut by home.bindableState("signedOutPanelVisible") { home.signedOutPanelVisible }
    val signedIn by home.bindableState("signedInPanelVisible") { home.signedInPanelVisible }

    Column(modifier) {
        if (headerVisible) {
            Text(
                Loc.t("account"),
                style = IaTextStyles.h1(colors),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                statusText,
                style = IaTextStyles.hint(colors).copy(fontSize = 13.sp, color = colors.brush(statusBrushKey)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (signedOut) SignedOutPanel(home, focusRegistry, Modifier.padding(top = 24.dp))
        if (signedIn) SignedInPanel(home, Modifier.padding(top = 12.dp))
        if (notConfigured) {
            Text(
                Loc.t("not_configured_install"),
                style = IaTextStyles.hint(colors).copy(fontSize = 13.sp),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** One view-model inline message (the C#'s `Views/InlineMessage` bound to an InlineMessageState). */
@Composable
private fun VmInlineMessage(
    state: InlineMessageState,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val text by state.bindableState("text") { state.text }
    val detail by state.bindableState("detail") { state.detail }
    val kind by state.bindableState("kind") { state.kind }
    val actionText by state.bindableState("actionText") { state.actionText }
    IaInlineMessage(
        text = text,
        kind = kind,
        modifier = modifier,
        detail = detail,
        actionText = actionText,
        onAction = onAction,
    )
}

/** The dashboard cards' heading (HomeWindow.xaml's CardTitle: the label style, margin 0/2/0/10). */
@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val colors = LocalIaColors.current
    Text(
        text,
        style = if (color == null) IaTextStyles.label(colors) else IaTextStyles.label(colors).copy(color = color),
        modifier = modifier
            .padding(top = 2.dp, bottom = 10.dp)
            .semantics { heading() },
    )
}

// ---------------------------------------------------------------------------
// Signed out: product column + the auth form
// ---------------------------------------------------------------------------

@Composable
private fun SignedOutPanel(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val panelWidth = minOf(maxWidth.value.toDouble(), AccountLayout.authMaxWidth)
        // The panel is capped at 940 (460 in the one-column form) and centred, as in the original.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.widthIn(max = AccountLayout.authMaxWidth.dp)) {
                if (AccountLayout.authColumns(panelWidth) == 2) {
                    Row {
                        AuthHero(home, Modifier.weight(1f).padding(top = 8.dp, end = 40.dp, bottom = 24.dp))
                        AuthFormCard(home, focusRegistry, Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.widthIn(max = AccountLayout.authNarrowMaxWidth.dp)) {
                        AuthHero(home, Modifier.padding(bottom = 20.dp))
                        AuthFormCard(home, focusRegistry, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Product name, the value line and the three factual trust points. */
@Composable
private fun AuthHero(home: HomeViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val productName by home.bindableState("authProductName") { home.authProductName }

    Column(modifier) {
        Text(
            productName,
            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
        )
        Text(
            Loc.t("auth_hero_value"),
            style = IaTextStyles.body(colors).copy(fontSize = 15.sp),
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        for (key in listOf("auth_trust_local", "auth_trust_paddle", "auth_trust_cancel")) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    IaIcon(IaGlyph.Check, 11.dp, colors.Text, null)
                }
                Text(
                    Loc.t(key),
                    style = IaTextStyles.body(colors).copy(color = colors.Text),
                )
            }
        }
    }
}

/** The auth card: one form for every state of the five-mode state machine. */
@Composable
private fun AuthFormCard(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val heading by home.bindableState("authHeadingText") { home.authHeadingText }
    val subtitle by home.bindableState("authSubtitleText") { home.authSubtitleText }
    val subtitleVisible by home.bindableState("authSubtitleVisible") { home.authSubtitleVisible }
    val email by home.bindableState("authEmail") { home.authEmail }
    val password by home.bindableState("authPassword") { home.authPassword }
    val confirm by home.bindableState("authConfirmPassword") { home.authConfirmPassword }
    val emailVisible by home.bindableState("emailFieldVisible") { home.emailFieldVisible }
    val passwordVisible by home.bindableState("passwordFieldVisible") { home.passwordFieldVisible }
    val confirmVisible by home.bindableState("confirmFieldVisible") { home.confirmFieldVisible }
    val emailHasError by home.bindableState("emailHasError") { home.emailHasError }
    val passwordHasError by home.bindableState("passwordHasError") { home.passwordHasError }
    val confirmHasError by home.bindableState("confirmHasError") { home.confirmHasError }
    val emailReadOnly by home.bindableState("emailReadOnly") { home.emailReadOnly }
    val forgotVisible by home.bindableState("forgotVisible") { home.forgotVisible }
    val forgotEnabled by home.bindableState("forgotEnabled") { home.forgotEnabled }
    val providersVisible by home.bindableState("providersVisible") { home.providersVisible }
    val providerButtons by home.bindableState("providerButtons") { home.providerButtons }
    val legalVisible by home.bindableState("legalNoticeVisible") { home.legalNoticeVisible }
    val legalText by home.bindableState("signUpLegalText") { home.signUpLegalText }
    val submitText by home.bindableState("authSubmitText") { home.authSubmitText }
    val submitEnabled by home.bindableState("authSubmitEnabled") { home.authSubmitEnabled }
    val resendVisible by home.bindableState("resendVisible") { home.resendVisible }
    val resendText by home.bindableState("resendText") { home.resendText }
    val resendEnabled by home.bindableState("resendEnabled") { home.resendEnabled }
    val promptText by home.bindableState("modePromptText") { home.modePromptText }
    val promptVisible by home.bindableState("modePromptVisible") { home.modePromptVisible }
    val linkText by home.bindableState("modeLinkText") { home.modeLinkText }
    val linksVisible by home.bindableState("modeLinksVisible") { home.modeLinksVisible }
    val linkEnabled by home.bindableState("modeLinkEnabled") { home.modeLinkEnabled }
    val privacyHintVisible by home.bindableState("passwordHintVisible") { home.passwordHintVisible }

    // Focus after a state change: the first visible field, else the primary button (the C#'s
    // FocusAuthForm) — the AuthForm requester sits on whichever of the two that is.
    val authFormRequester = focusRegistry.rememberRequester(HomeFocus.AuthForm)

    IaCard(modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(heading, style = IaTextStyles.h1(colors))
            if (subtitleVisible) {
                Text(
                    subtitle,
                    style = IaTextStyles.body(colors),
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            // The service's state when it blocks sign-in (session expired, account disabled, …).
            VmInlineMessage(home.authStatusMessage)

            if (emailVisible) {
                IaFormField(label = Loc.t("email")) {
                    Column {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Input.Single(
                                value = email,
                                onValueChange = { home.authEmail = it },
                                enabled = true,
                                readOnly = emailReadOnly,
                                hasError = emailHasError,
                                description = Loc.t("email"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier
                                    .focusRequester(focusRegistry.rememberRequester(HomeFocus.Email))
                                    .then(if (emailVisible) Modifier.focusRequester(authFormRequester) else Modifier)
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                                            home.onEmailEnter()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }
                        VmInlineMessage(home.emailError)
                    }
                }
            }
            if (passwordVisible) {
                IaFormField(
                    label = Loc.t("password"),
                    accessory = if (forgotVisible) {
                        {
                            Btn.Link(
                                Loc.t("forgot_password"),
                                onClick = { home.onForgotClick() },
                                enabled = forgotEnabled,
                            )
                        }
                    } else {
                        null
                    },
                ) {
                    Column {
                        PasswordInput(
                            value = password,
                            onValueChange = { home.authPassword = it },
                            hasError = passwordHasError,
                            description = Loc.t("password"),
                            modifier = Modifier
                                .focusRequester(focusRegistry.rememberRequester(HomeFocus.Password))
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                                        home.onPasswordEnter(isConfirmField = false)
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                        VmInlineMessage(home.passwordError)
                    }
                }
            }
            if (confirmVisible) {
                Column {
                    IaFormField(label = Loc.t("confirm_password"), hint = Loc.t("password_rules")) {
                        Column {
                            PasswordInput(
                                value = confirm,
                                onValueChange = { home.authConfirmPassword = it },
                                hasError = confirmHasError,
                                description = Loc.t("confirm_password"),
                                modifier = Modifier
                                    .focusRequester(focusRegistry.rememberRequester(HomeFocus.ConfirmPassword))
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                                            home.onPasswordEnter(isConfirmField = true)
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                            VmInlineMessage(home.confirmError)
                        }
                    }
                    if (legalVisible) {
                        Text(legalText, style = IaTextStyles.hint(colors))
                    }
                }
            }

            // The form's own result: sign-in failures, the reset notice, the confirmation e-mail.
            VmInlineMessage(
                home.authMessage,
                Modifier.padding(top = 4.dp),
                onAction = { home.onAuthMessageActionClick() },
            )
            Btn.FormPrimary(
                submitText,
                onClick = { home.onAuthSubmitClick() },
                enabled = submitEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .then(if (!emailVisible) Modifier.focusRequester(authFormRequester) else Modifier),
            )
            if (resendVisible) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Btn.Link(
                        resendText,
                        onClick = { home.onResendClick() },
                        enabled = resendEnabled,
                    )
                }
            }
            if (providersVisible && providerButtons.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).height(1.dp).background(colors.Border))
                    Text(
                        Loc.t("auth_or"),
                        style = IaTextStyles.body(colors).copy(fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(colors.Border))
                }
                Column {
                    providerButtons.forEach { provider ->
                        Btn.Form(
                            provider.label,
                            onClick = { home.onProviderClick(provider.provider) },
                            enabled = provider.enabled,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
            }
            if (linksVisible) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f))
                    if (promptVisible) {
                        Text(
                            promptText,
                            style = IaTextStyles.body(colors),
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                    Btn.Link(
                        linkText,
                        onClick = { home.onModeLinkClick() },
                        enabled = linkEnabled,
                    )
                    Box(Modifier.weight(1f))
                }
            }
            if (privacyHintVisible) {
                Text(
                    Loc.t("password_privacy_hint"),
                    style = IaTextStyles.hint(colors),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
    // The XAML's AuthLegalLinks wrap panel under the card is never populated by the original's
    // code (the legal sentence inside the form covers sign-up; the links live in the dashboard's
    // Privacy card) — an empty panel renders nothing, so there is nothing to draw here.
}

// ---------------------------------------------------------------------------
// Signed in: the dashboard
// ---------------------------------------------------------------------------

@Composable
private fun SignedInPanel(home: HomeViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (AccountLayout.dashboardColumns(maxWidth.value.toDouble()) == 2) {
            Row {
                Column(Modifier.weight(3f)) {
                    PlanCard(home)
                    DevicesCard(home)
                }
                Box(Modifier.width(IaTokens.PaneGap))
                Column(Modifier.weight(2f)) {
                    SecurityCard(home)
                    PrivacyCard(home)
                    DangerCard(home)
                }
            }
        } else {
            Column {
                PlanCard(home)
                DevicesCard(home)
                SecurityCard(home)
                PrivacyCard(home)
                DangerCard(home)
            }
        }
    }
}

/** A ghost button with an icon (the C#'s Btn.Ghost with a StackPanel: the Plan card's Refresh). */
@Composable
private fun GhostIconButton(
    text: String,
    glyph: IaGlyph,
    onClick: () -> Unit,
    enabled: Boolean,
    description: String?,
    modifier: Modifier = Modifier,
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
            .background(Color.White.copy(alpha = overlay))
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (description != null) contentDescription = description }
            .padding(horizontal = IaTokens.GhostPaddingH, vertical = IaTokens.GhostPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IaIcon(glyph, 12.dp, colors.TextDim, null)
        Text(
            text,
            style = IaTextStyles.button(colors).copy(color = colors.TextDim),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Plan & usage: plan name + billing badge, the metric tiles, upgrade / manage billing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val planText by home.bindableState("planText") { home.planText }
    val planSubText by home.bindableState("planSubText") { home.planSubText }
    val badgeText by home.bindableState("planBadgeText") { home.planBadgeText }
    val badgeBrushKey by home.bindableState("planBadgeBrushKey") { home.planBadgeBrushKey }
    val badgeVisible by home.bindableState("planBadgeVisible") { home.planBadgeVisible }
    val metrics by home.bindableState("usageMetrics") { home.usageMetrics }
    val billingNotice by home.bindableState("billingNoticeText") { home.billingNoticeText }
    val upgradeVisible by home.bindableState("upgradeVisible") { home.upgradeVisible }
    val upgradeText by home.bindableState("upgradeText") { home.upgradeText }
    val upgradeEnabled by home.bindableState("upgradeEnabled") { home.upgradeEnabled }
    val manageVisible by home.bindableState("manageBillingVisible") { home.manageBillingVisible }
    val manageEnabled by home.bindableState("manageBillingEnabled") { home.manageBillingEnabled }
    val upgradeStatus by home.bindableState("upgradeStatusText") { home.upgradeStatusText }
    val billingTerms by home.bindableState("billingTermsText") { home.billingTermsText }
    val refreshEnabled by home.bindableState("refreshAccountEnabled") { home.refreshAccountEnabled }
    val badgeBrush = colors.brush(badgeBrushKey)

    IaCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                CardTitle(Loc.t("acct_card_plan"), Modifier.weight(1f))
                GhostIconButton(
                    text = Loc.t("refresh"),
                    glyph = IaGlyph.Refresh,
                    onClick = { home.onRefreshAccountClick() },
                    enabled = refreshEnabled,
                    description = Loc.t("tt_refresh_account"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    planText,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                    modifier = Modifier.padding(end = 10.dp),
                )
                if (badgeVisible) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                            .border(IaTokens.BorderWidth, badgeBrush, RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) {
                        Text(
                            badgeText,
                            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = badgeBrush),
                        )
                    }
                }
            }
            if (planSubText.isNotEmpty()) {
                Text(planSubText, style = IaTextStyles.hint(colors))
            }
            VmInlineMessage(home.summaryMessage, onAction = { home.onSummaryMessageActionClick() })
            // Metric tiles: two per row, each with an optional usage bar.
            Column(Modifier.padding(top = 12.dp)) {
                metrics.chunked(2).forEach { rowMetrics ->
                    Row {
                        rowMetrics.forEach { metric ->
                            MetricTile(metric, Modifier.weight(1f).padding(end = 8.dp, bottom = 8.dp))
                        }
                        if (rowMetrics.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (billingNotice.isNotEmpty()) {
                Text(
                    billingNotice,
                    style = IaTextStyles.hint(colors).copy(fontSize = 12.5.sp, color = colors.Thinking),
                )
            }
            if (upgradeVisible || manageVisible) {
                FlowRow(Modifier.padding(top = 12.dp)) {
                    if (upgradeVisible) {
                        Btn.FormPrimary(
                            upgradeText,
                            onClick = { home.onUpgradeClick() },
                            enabled = upgradeEnabled,
                            description = Loc.t("tt_upgrade"),
                            modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                        )
                    }
                    if (manageVisible) {
                        Btn.Form(
                            Loc.t("manage_billing"),
                            onClick = { home.onPortalClick() },
                            enabled = manageEnabled,
                            description = Loc.t("tt_manage_billing"),
                            modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                        )
                    }
                }
            }
            VmInlineMessage(home.billingMessage, onAction = { home.onBillingMessageActionClick() })
            if (upgradeStatus.isNotEmpty()) {
                Text(
                    upgradeStatus,
                    style = IaTextStyles.hint(colors),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (billingTerms.isNotEmpty()) {
                Text(billingTerms, style = IaTextStyles.hint(colors))
            }
        }
    }
}

/** One metric tile of "Plan & usage" (Surface2, radius 8, padding 12×10). */
@Composable
private fun MetricTile(metric: MetricRow, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(colors.Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                metric.label,
                style = IaTextStyles.label(colors),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                metric.value,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
            )
            if (metric.detailVisible) {
                Text(metric.detail, style = IaTextStyles.hint(colors))
            }
            if (metric.barVisible) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.Surface3)
                        .semantics {
                            contentDescription = metric.barName
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(metric.filledFraction.toFloat(), 0f..1f)
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(metric.filledFraction.toFloat().coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(colors.brush(metric.barBrushKey)),
                    )
                }
            }
        }
    }
}

/** Devices: one row per device with its own Remove action. */
@Composable
private fun DevicesCard(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val rows by home.bindableState("deviceRows") { home.deviceRows }
    val emptyVisible by home.bindableState("devicesEmptyVisible") { home.devicesEmptyVisible }

    IaCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column {
            CardTitle(Loc.t("devices"))
            VmInlineMessage(
                home.devicesMessage,
                Modifier.padding(bottom = 8.dp),
                onAction = { home.onDevicesMessageActionClick() },
            )
            Column(Modifier.semantics { contentDescription = Loc.t("devices") }) {
                rows.forEach { row -> DeviceRowView(home, row, colors) }
            }
            if (emptyVisible) {
                Text(Loc.t("acct_devices_empty"), style = IaTextStyles.hint(colors))
            }
        }
    }
}

@Composable
private fun DeviceRowView(home: HomeViewModel, row: DeviceRow, colors: IaColorSet) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.Border))
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 12.dp)) {
                IaIcon(IaGlyph.Devices, 18.dp, colors.TextDim, null)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name,
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (row.thisPcVisible) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.AccentSoft)
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        ) {
                            Text(
                                Loc.t("acct_device_this_pc_tag"),
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                            )
                        }
                    }
                }
                Text(
                    row.meta,
                    style = IaTextStyles.hint(colors),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Btn.Form(
                Loc.t("dlg_remove"),
                onClick = { home.onRemoveDeviceClick(row) },
                enabled = row.canRemove,
                description = row.removeName,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Security: the account e-mail, change password (re-auth first), switch account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecurityCard(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val email by home.bindableState("securityEmailText") { home.securityEmailText }

    IaCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column {
            CardTitle(Loc.t("security"))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    email,
                    style = IaTextStyles.body(colors).copy(color = colors.Text),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            FlowRow {
                Btn.Form(
                    Loc.t("change_password"),
                    onClick = { home.onChangePasswordClick() },
                    modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                )
                Btn.Form(
                    Loc.t("switch_account"),
                    onClick = { home.onSwitchAccountClick() },
                    description = Loc.t("tt_sign_in_another"),
                    modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                )
            }
            VmInlineMessage(home.securityMessage)
        }
    }
}

/** Privacy & data: export, the re-auth hint, and the legal/support links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyCard(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val legalHeaderVisible by home.bindableState("legalHeaderVisible") { home.legalHeaderVisible }
    val legalLinks by home.bindableState("legalLinks") { home.legalLinks }
    val supportEmail by home.bindableState("supportEmailText") { home.supportEmailText }

    IaCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column {
            CardTitle(Loc.t("acct_card_privacy"))
            FlowRow {
                Btn.Form(
                    Loc.t("export_my_data"),
                    onClick = { home.onExportAccountClick() },
                    modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                )
            }
            VmInlineMessage(home.privacyMessage)
            Text(Loc.t("reauth_hint"), style = IaTextStyles.hint(colors))
            if (legalHeaderVisible) {
                Text(
                    Loc.t("legal_support"),
                    style = IaTextStyles.label(colors),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            FlowRow {
                legalLinks.forEach { link ->
                    Btn.Ghost(
                        link.label,
                        onClick = { home.onLegalLinkClick(link) },
                        description = link.url,
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                    )
                }
            }
            if (supportEmail.isNotEmpty()) {
                Text(supportEmail, style = IaTextStyles.hint(colors))
            }
        }
    }
}

/** Danger zone: set apart with the error outline; delete account (the 2-step dialog is the VM's). */
@Composable
private fun DangerCard(home: HomeViewModel) {
    val colors = LocalIaColors.current

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Error, RoundedCornerShape(IaTokens.RadiusCard))
            .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = IaTokens.CardPaddingVertical),
    ) {
        Column {
            CardTitle(Loc.t("acct_card_danger"), color = colors.Error)
            Text(Loc.t("acct_danger_hint"), style = IaTextStyles.body(colors))
            DangerButton(
                text = Loc.t("delete_account"),
                onClick = { home.onDeleteAccountClick() },
                modifier = Modifier.padding(top = 12.dp),
            )
            VmInlineMessage(home.dangerMessage)
        }
    }
}

/** The delete-account button: the Form outline in the error colour (the C#'s restyled Btn.Form). */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(IaTokens.RadiusControl)
    Box(
        modifier
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(shape)
            .background(colors.Surface2)
            .border(IaTokens.BorderWidth, colors.Error, shape)
            .hoverable(interaction, enabled = true)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = true,
                role = Role.Button,
                onClick = onClick,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = text }
            .defaultMinSize(minHeight = IaTokens.ButtonHeight)
            .padding(horizontal = IaTokens.ButtonPaddingH, vertical = IaTokens.ButtonPaddingV),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = IaTextStyles.button(colors).copy(color = colors.Error))
        if (hovered || pressed) {
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = if (pressed) 0.14f else 0.07f)))
        }
    }
}
