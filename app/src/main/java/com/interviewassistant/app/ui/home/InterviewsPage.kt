package com.interviewassistant.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCombo
import com.interviewassistant.app.ui.components.IaDivider
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaInlineMessage
import com.interviewassistant.app.ui.components.IaListItemRow
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.launch

/**
 * Interviews (HomeWindow.xaml PageInterviews): the interview list with New/Delete, and the
 * detail — the readiness card of the active snapshot, job title / company, the interview and
 * answer languages, the job description with its Import file accessory, and the
 * Prepare / Save / Start interview action bar with the preparation progress. Same two-pane rule
 * as Profiles (§3.3, DC-142).
 */
@Composable
fun InterviewsPage(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val selectedTargetId by home.bindableState("selectedTargetId") { home.selectedTargetId }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier) {
        val mode = HomeLayout.paneMode(maxWidth.value, selectedTargetId != null)
        when (mode) {
            PaneMode.Split -> Row {
                TargetListColumn(home, Modifier.width(IaTokens.SidePaneWidth))
                Spacer(Modifier.width(IaTokens.PaneGap))
                TargetDetail(home, focusRegistry, Modifier.weight(1f).widthIn(max = IaTokens.PageMaxWidth))
            }
            PaneMode.List -> TargetListColumn(home, Modifier.fillMaxWidth())
            PaneMode.Detail -> {
                BackHandler { scope.launch { home.onTargetSelectionChanged(null) } }
                Column {
                    Btn.Link(
                        Loc.t("interviews"),
                        onClick = { scope.launch { home.onTargetSelectionChanged(null) } },
                    )
                    TargetDetail(home, focusRegistry, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TargetListColumn(home: HomeViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val scope = rememberCoroutineScope()
    val items by home.bindableState("targetItems") { home.targetItems }
    val selectedTargetId by home.bindableState("selectedTargetId") { home.selectedTargetId }
    val headerText by home.bindableState("interviewsHeaderText") { home.interviewsHeaderText }
    val emptyVisible by home.bindableState("interviewsEmptyVisible") { home.interviewsEmptyVisible }
    home.changeCounter("deleteTargetEnabled")

    Column(modifier) {
        Text(
            Loc.t("interviews"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(headerText, style = IaTextStyles.hint(colors))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(IaTokens.RadiusControl))
                .background(colors.Surface)
                .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusControl))
                .padding(4.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp, max = 292.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = Loc.t("interviews") },
            ) {
                items.forEach { item ->
                    IaListItemRow(
                        selected = item.value == selectedTargetId,
                        onClick = { scope.launch { home.onTargetSelectionChanged(item.value) } },
                        description = item.label,
                    ) {
                        Text(
                            item.label,
                            style = IaTextStyles.body(colors).copy(color = colors.Text),
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
            }
        }
        if (emptyVisible) {
            Text(
                Loc.t("interviews_empty"),
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Btn.Form(
            Loc.t("new_interview"),
            onClick = { home.onNewTargetClick() },
            modifier = Modifier.fillMaxWidth(),
        )
        IaDivider()
        Btn.FormDanger(
            Loc.t("delete_interview"),
            onClick = { home.onDeleteTargetClick() },
            enabled = home.deleteTargetEnabled,
            description = Loc.t("tt_delete_interview"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TargetDetail(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val title by home.bindableState("targetTitle") { home.targetTitle }
    val company by home.bindableState("targetCompany") { home.targetCompany }
    val jd by home.bindableState("targetJd") { home.targetJd }
    val languageCode by home.bindableState("targetLanguageCode") { home.targetLanguageCode }
    val answerLanguageCode by home.bindableState("targetAnswerLanguageCode") { home.targetAnswerLanguageCode }
    val languageItems by home.bindableState("interviewLanguageItems") { home.interviewLanguageItems }
    val answerLanguageItems by home.bindableState("answerLanguageItems") { home.answerLanguageItems }
    val titleHasError by home.bindableState("targetTitleHasError") { home.targetTitleHasError }
    val prepProgress by home.bindableState("prepProgress") { home.prepProgress }
    val prepStatus by home.bindableState("prepStatusText") { home.prepStatusText }
    val prepSummary by home.bindableState("prepSummaryText") { home.prepSummaryText }
    val useEnabled by home.bindableState("useTargetEnabled") { home.useTargetEnabled }
    val prepareIsPrimary by home.bindableState("prepareIsPrimary") { home.prepareIsPrimary }
    val useIsPrimary by home.bindableState("useIsPrimary") { home.useIsPrimary }
    val prepareEnabled by home.bindableState("prepareEnabled") { home.prepareEnabled }
    val dirtyText by home.bindableState("targetDirtyText") { home.targetDirtyText }
    home.changeCounter("targetDetailEnabled")
    val enabled = home.targetDetailEnabled

    val message = home.targetMessage
    val messageText by message.bindableState("text") { message.text }
    val messageDetail by message.bindableState("detail") { message.detail }
    val messageKind by message.bindableState("kind") { message.kind }
    val messageAction by message.bindableState("actionText") { message.actionText }

    Column(modifier) {
        ReadinessCard(home)

        Row {
            IaFormField(label = Loc.t("job_title"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = title,
                    onValueChange = { home.targetTitle = it },
                    enabled = enabled,
                    hasError = titleHasError,
                    description = Loc.t("job_title"),
                    modifier = Modifier.focusRequester(focusRegistry.rememberRequester(HomeFocus.TargetTitle)),
                )
            }
            Spacer(Modifier.width(IaTokens.ColumnGap))
            IaFormField(label = Loc.t("company"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = company,
                    onValueChange = { home.targetCompany = it },
                    enabled = enabled,
                    description = Loc.t("company"),
                )
            }
        }
        Row {
            IaFormField(label = Loc.t("interview_language"), modifier = Modifier.weight(1f)) {
                IaCombo(
                    items = languageItems.map { it.label },
                    selectedIndex = languageItems.indexOfFirst { it.code == languageCode },
                    onSelect = { index -> languageItems.getOrNull(index)?.let { home.targetLanguageCode = it.code } },
                    enabled = enabled,
                    description = Loc.t("interview_language"),
                )
            }
            Spacer(Modifier.width(IaTokens.ColumnGap))
            IaFormField(label = Loc.t("answer_language"), modifier = Modifier.weight(1f)) {
                IaCombo(
                    items = answerLanguageItems.map { it.label },
                    selectedIndex = answerLanguageItems.indexOfFirst { it.code == answerLanguageCode },
                    onSelect = { index -> answerLanguageItems.getOrNull(index)?.let { home.targetAnswerLanguageCode = it.code } },
                    enabled = enabled,
                    description = Loc.t("answer_language"),
                )
            }
        }
        Text(Loc.t("language_separation_hint"), style = IaTextStyles.hint(colors))
        IaFormField(
            label = Loc.t("job_description"),
            modifier = Modifier.padding(top = 8.dp),
            accessory = {
                Btn.Form(
                    Loc.t("import_file"),
                    onClick = { home.onImportJdClick() },
                    enabled = enabled,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            },
        ) {
            Input.Multiline(
                value = jd,
                onValueChange = { home.targetJd = it },
                enabled = enabled,
                tall = true,
                description = Loc.t("job_description"),
            )
        }
        Text(
            Loc.t("documents_are_data"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(top = IaTokens.HintMarginTop),
        )
        IaInlineMessage(
            text = messageText,
            kind = messageKind,
            detail = messageDetail,
            actionText = messageAction,
        )
        Row(
            Modifier.padding(top = IaTokens.ButtonRowMarginTop),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val prepareRequester = focusRegistry.rememberRequester(HomeFocus.PrepareButton)
            if (prepareIsPrimary) {
                Btn.FormPrimary(
                    Loc.t("prepare"),
                    onClick = { home.onPrepareClick() },
                    enabled = enabled && prepareEnabled,
                    modifier = Modifier.focusRequester(prepareRequester),
                )
            } else {
                Btn.Form(
                    Loc.t("prepare"),
                    onClick = { home.onPrepareClick() },
                    enabled = enabled && prepareEnabled,
                    modifier = Modifier.focusRequester(prepareRequester),
                )
            }
            Spacer(Modifier.width(IaTokens.ButtonGap))
            Btn.Form(
                Loc.t("save"),
                onClick = { home.onSaveTargetClick() },
                enabled = enabled,
                description = Loc.t("tt_save_interview"),
            )
            Spacer(Modifier.width(IaTokens.ButtonGap))
            if (useIsPrimary) {
                Btn.FormPrimary(
                    Loc.t("start_interview"),
                    onClick = { home.onUseTargetClick() },
                    enabled = enabled && useEnabled,
                )
            } else {
                Btn.Form(
                    Loc.t("start_interview"),
                    onClick = { home.onUseTargetClick() },
                    enabled = enabled && useEnabled,
                )
            }
            if (dirtyText.isNotEmpty()) {
                Text(
                    dirtyText,
                    style = IaTextStyles.hint(colors).copy(color = colors.Thinking),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        // The preparation progress: a 4 dp Accent bar over Surface3 (the C#'s ProgressBar).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(4.dp)
                .background(colors.Surface3),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(prepProgress.toFloat().coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(colors.Accent),
            )
        }
        if (prepStatus.isNotEmpty()) {
            Text(
                prepStatus,
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(top = IaTokens.HintMarginTop),
            )
        }
        if (prepSummary.isNotEmpty()) {
            Text(
                prepSummary,
                style = IaTextStyles.hint(colors).copy(color = colors.TextDim),
                modifier = Modifier.padding(top = IaTokens.HintMarginTop),
            )
        }
    }
}

/**
 * The readiness card at the top of the interview detail (the same snapshot as the pre-interview
 * check): state badge, summary, and the Check button. A polite live region, as in the original.
 */
@Composable
private fun ReadinessCard(home: HomeViewModel) {
    val colors = LocalIaColors.current
    val badgeText by home.bindableState("ivBadgeText") { home.ivBadgeText }
    val badgeIcon by home.bindableState("ivBadgeIcon") { home.ivBadgeIcon }
    val summary by home.bindableState("ivSummaryText") { home.ivSummaryText }
    val applies by home.bindableState("ivAppliesText") { home.ivAppliesText }
    val appliesVisible by home.bindableState("ivAppliesVisible") { home.ivAppliesVisible }
    val checkText by home.bindableState("interviewCheckText") { home.interviewCheckText }
    val badgeBrush = colors.brush(ReadinessSnapshot.brushKey(home.vm.readinessState))

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The state badge: radius 10, state-colored border and text, glyph + label.
            Row(
                Modifier
                    .clip(RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                    .border(IaTokens.BorderWidth, badgeBrush, RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IaIcon(badgeGlyph(badgeIcon), 10.dp, badgeBrush, null)
                Text(
                    badgeText,
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = badgeBrush),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    Loc.t("preflight"),
                    style = IaTextStyles.label(colors),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    summary,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                )
                if (appliesVisible) {
                    Text(
                        applies,
                        style = IaTextStyles.hint(colors),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Btn.Form(
                checkText,
                onClick = { home.onReadinessClick() },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
