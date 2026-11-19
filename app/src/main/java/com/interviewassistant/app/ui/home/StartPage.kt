package com.interviewassistant.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCheck
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.iaFocusRing
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.SetupStep
import com.interviewassistant.app.viewmodels.DashboardStep
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.launch

/** The static title key of each dashboard step (the C#'s XAML Loc bindings on the step cards). */
internal fun stepTitleKey(step: SetupStep): String = when (step) {
    SetupStep.Account -> "account"
    SetupStep.Profile -> "gs_step_profile"
    SetupStep.InterviewSetup -> "gs_step_interview"
    SetupStep.PreInterviewCheck -> "preflight"
}

/**
 * Get started (HomeWindow.xaml PageStart): the readiness dashboard — the authorized-use
 * acknowledgement on first run, the four step cards of the SetupPlanner, and exactly one primary
 * action (the next step). All state and actions are the HomeViewModel's; this page only renders.
 */
@Composable
fun StartPage(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val scope = rememberCoroutineScope()

    val ackChecked by home.bindableState("ackChecked") { home.ackChecked }
    val ackCardVisible by home.bindableState("ackCardVisible") { home.ackCardVisible }
    val ackNoteVisible by home.bindableState("ackNoteVisible") { home.ackNoteVisible }
    val steps by home.bindableState("dashboardSteps") { home.dashboardSteps }
    val primaryLabel by home.bindableState("primaryActionLabel") { home.primaryActionLabel }
    val primaryEnabled by home.bindableState("primaryActionEnabled") { home.primaryActionEnabled }
    val primaryTooltip by home.bindableState("primaryActionTooltip") { home.primaryActionTooltip }
    val accountLinkVisible by home.bindableState("accountLinkVisible") { home.accountLinkVisible }
    val developerModeVisible by home.bindableState("developerModeVisible") { home.developerModeVisible }
    val workspaceLinksVisible by home.bindableState("workspaceLinksVisible") { home.workspaceLinksVisible }
    val interviewProfileLinkVisible by home.bindableState("interviewProfileLinkVisible") { home.interviewProfileLinkVisible }

    Column(modifier.widthIn(max = IaTokens.ReadingMaxWidth)) {
        Text(
            Loc.t("gs_title"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(Loc.t("gs_subtitle"), style = IaTextStyles.hint(colors))

        if (ackCardVisible) {
            // The acknowledgement card: a Card with the Accent border (DC-150 for the drawing).
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(IaTokens.RadiusCard))
                    .background(colors.Surface)
                    .border(IaTokens.BorderWidth, colors.Accent, RoundedCornerShape(IaTokens.RadiusCard))
                    .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = IaTokens.CardPaddingVertical),
            ) {
                Column {
                    Text(
                        Loc.t("start_step1"),
                        style = IaTextStyles.label(colors),
                        modifier = Modifier.semantics { heading() },
                    )
                    IaCheck(
                        checked = ackChecked,
                        onCheckedChange = { home.onAckChanged(it) },
                        label = Loc.t("authorized_use"),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        Loc.t("start_step1_hint"),
                        style = IaTextStyles.hint(colors),
                        modifier = Modifier.padding(top = IaTokens.HintMarginTop),
                    )
                }
            }
        }

        steps.forEachIndexed { index, step ->
            StepCard(
                step = step,
                index = index,
                first = index == 0,
                accountLinkVisible = accountLinkVisible,
                developerModeVisible = developerModeVisible,
                workspaceLinksVisible = workspaceLinksVisible,
                interviewProfileLinkVisible = interviewProfileLinkVisible,
                onAccountLink = { home.goAccount() },
                onDeveloperMode = { home.onDeveloperModeClick() },
                onImportProfile = { home.onNewProfileClick() },
                onCompleteProfile = { scope.launch { home.showInterviewProfile() } },
                onAddInterview = { home.navigate("interviews") },
                onOpenCheck = { home.onReadinessClick() },
            )
        }

        Btn.FormPrimary(
            text = primaryLabel,
            onClick = { home.onPrimaryActionClick() },
            enabled = primaryEnabled,
            description = primaryTooltip ?: primaryLabel,
            modifier = Modifier
                .padding(top = 20.dp)
                .widthIn(min = 180.dp)
                .focusRequester(focusRegistry.rememberRequester(HomeFocus.PrimaryAction)),
        )

        if (ackNoteVisible) {
            Text(
                Loc.t("gs_ack_note"),
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    step: DashboardStep,
    index: Int,
    first: Boolean,
    accountLinkVisible: Boolean,
    developerModeVisible: Boolean,
    workspaceLinksVisible: Boolean,
    interviewProfileLinkVisible: Boolean,
    onAccountLink: () -> Unit,
    onDeveloperMode: () -> Unit,
    onImportProfile: () -> Unit,
    onCompleteProfile: () -> Unit,
    onAddInterview: () -> Unit,
    onOpenCheck: () -> Unit,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    val title = Loc.t(stepTitleKey(step.step))
    val numberText = stepNumberText(index, Loc.locale)
    val accessibleName = stepCardAccessibleName(
        numberText,
        Loc.f("fmt_label_value", title, step.stateText),
        step.detail,
    )
    val shape = RoundedCornerShape(IaTokens.RadiusCard)
    Box(
        Modifier
            .padding(top = if (first) 16.dp else 10.dp)
            .fillMaxWidth()
            .iaFocusRing(focused, colors.Accent, IaTokens.RadiusFocusRing, IaTokens.FocusRingOutsetButton)
            .clip(shape)
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.brush(step.cardBorderKey), shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { contentDescription = accessibleName }
            .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = IaTokens.CardPaddingVertical),
    ) {
        Row {
            // The step marker: a 30 dp disc — check for Done, the number otherwise (accent-filled
            // for the current step, outlined for upcoming ones).
            Box(
                Modifier
                    .padding(top = 2.dp, end = 14.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.brush(step.markBackgroundKey))
                    .border(1.5.dp, colors.brush(step.markBorderKey), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (stepMarkShowsCheck(step)) {
                    IaIcon(IaGlyph.Check, 13.dp, colors.Listening, null)
                } else {
                    Text(
                        numberText,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (step.numberOnAccent) Color.White else colors.TextFaint,
                        ),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    step.stateText,
                    style = IaTextStyles.label(colors).copy(color = colors.brush(step.stateBrushKey)),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    title,
                    style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    step.detail,
                    style = IaTextStyles.body(colors).copy(fontSize = 12.5.sp, color = colors.brush(step.detailBrushKey)),
                    modifier = Modifier.padding(top = 3.dp),
                )
                FlowRow(Modifier.padding(top = 6.dp)) {
                    when (step.step) {
                        SetupStep.Account -> {
                            if (accountLinkVisible) Btn.Link(Loc.t("rd_fix_open_account"), onClick = onAccountLink)
                            if (developerModeVisible) Btn.Link(Loc.t("start_developer_mode"), onClick = onDeveloperMode)
                        }
                        SetupStep.Profile -> {
                            if (workspaceLinksVisible) Btn.Link(Loc.t("import_resume"), onClick = onImportProfile)
                            if (interviewProfileLinkVisible) Btn.Link(Loc.t("start_complete_profile"), onClick = onCompleteProfile)
                        }
                        SetupStep.InterviewSetup -> {
                            if (workspaceLinksVisible) Btn.Link(Loc.t("rd_fix_open_interviews"), onClick = onAddInterview)
                        }
                        SetupStep.PreInterviewCheck -> {
                            Btn.Link(Loc.t("rd_open_check"), onClick = onOpenCheck)
                        }
                    }
                }
            }
        }
    }
}
