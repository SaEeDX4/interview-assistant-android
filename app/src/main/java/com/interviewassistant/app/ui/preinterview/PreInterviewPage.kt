package com.interviewassistant.app.ui.preinterview

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCard
import com.interviewassistant.app.ui.components.IaDivider
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaLevelMeter
import com.interviewassistant.app.ui.home.badgeGlyph
import com.interviewassistant.app.ui.home.bindableState
import com.interviewassistant.app.ui.home.brush
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.ReadinessFix
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.launch

/**
 * Pre-interview check (Views/PreInterviewPage.xaml): header, the summary card (state badge,
 * "n of m passed", last checked, what blocks), the eight check rows with their fix buttons, and
 * the test tools (audio / transcription / AI) with the level meter and the collapsible technical
 * details. Everything renders from [PreInterviewPageControllerImpl]'s snapshot-driven state; the
 * sticky action bar (Run all / Start interview) is the shell's (P4-E).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreInterviewPage(
    home: HomeViewModel,
    controller: PreInterviewPageControllerImpl,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val scope = rememberCoroutineScope()
    val toolsRequester = remember { BringIntoViewRequester() }

    Column(modifier.widthIn(max = IaTokens.PageMaxWidth)) {
        Text(
            Loc.t("preflight"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(Loc.t("pf_subtitle"), style = IaTextStyles.hint(colors))

        SummaryCard(controller, Modifier.padding(top = 16.dp))

        Text(
            Loc.t("rd_checks_title"),
            style = IaTextStyles.label(colors),
            modifier = Modifier
                .padding(top = IaTokens.SectionMarginTop, bottom = IaTokens.SectionMarginBottom)
                .semantics { heading() },
        )
        ChecksCard(controller) { row ->
            if (controller.onFixClick(row)) {
                scope.launch { toolsRequester.bringIntoView() }
            }
        }

        Text(
            Loc.t("pf_test_tools"),
            style = IaTextStyles.label(colors),
            modifier = Modifier
                .padding(top = IaTokens.SectionMarginTop, bottom = IaTokens.SectionMarginBottom)
                .semantics { heading() },
        )
        ToolsCard(home, controller, Modifier.bringIntoViewRequester(toolsRequester))
    }
}

/** The summary: state badge, "n of m checks passed", last checked, the hint, and what blocks. */
@Composable
private fun SummaryCard(controller: PreInterviewPageControllerImpl, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val icon by controller.bindableState("summaryIcon") { controller.summaryIcon }
    val brushKey by controller.bindableState("summaryBrushKey") { controller.summaryBrushKey }
    val stateText by controller.bindableState("summaryStateText") { controller.summaryStateText }
    val line by controller.bindableState("summaryLine") { controller.summaryLine }
    val checkedAt by controller.bindableState("summaryCheckedAt") { controller.summaryCheckedAt }
    val hint by controller.bindableState("summaryHint") { controller.summaryHint }
    val blockersVisible by controller.bindableState("blockersVisible") { controller.blockersVisible }
    val blockersTitle by controller.bindableState("blockersTitle") { controller.blockersTitle }
    val blockerLines by controller.bindableState("blockerLines") { controller.blockerLines }
    val typedVisible by controller.bindableState("typedStillWorkVisible") { controller.typedStillWorkVisible }
    val accessibleName by controller.bindableState("summaryAccessibleName") { controller.summaryAccessibleName }
    val brush = colors.brush(brushKey)

    IaCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = accessibleName
            },
        ) {
            // The state badge: radius 10, state-coloured border and text, glyph + label.
            Row(
                Modifier
                    .clip(RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                    .border(IaTokens.BorderWidth, brush, RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IaIcon(badgeGlyph(icon), 12.dp, brush, null)
                Text(
                    stateText,
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = brush),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                line,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                modifier = Modifier.padding(top = 10.dp),
            )
            if (checkedAt.isNotEmpty()) {
                Text(checkedAt, style = IaTextStyles.hint(colors))
            }
            Text(hint, style = IaTextStyles.body(colors), modifier = Modifier.padding(top = 8.dp))
            if (blockersVisible) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        blockersTitle,
                        style = IaTextStyles.label(colors),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    blockerLines.forEach { blocker ->
                        Row(Modifier.padding(top = 3.dp)) {
                            Box(Modifier.padding(top = 2.dp, end = 8.dp)) {
                                IaIcon(badgeGlyph(blocker.icon), 11.dp, colors.brush(blocker.brushKey), null)
                            }
                            Text(
                                blocker.text,
                                style = IaTextStyles.body(colors).copy(fontSize = 12.5.sp),
                            )
                        }
                    }
                    if (typedVisible) {
                        Text(
                            Loc.t("pf_typed_still_work"),
                            style = IaTextStyles.hint(colors),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The checks card: eight rows, hairline separators except above the first, padding 18×12. */
@Composable
private fun ChecksCard(
    controller: PreInterviewPageControllerImpl,
    onFix: (ReadinessRowState) -> Unit,
) {
    val colors = LocalIaColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .semantics { contentDescription = Loc.t("rd_checks_title") },
    ) {
        Column {
            controller.rows.forEach { row ->
                if (!row.first) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(IaTokens.BorderWidth)
                            .background(colors.Border),
                    )
                }
                CheckRow(row, onFix)
            }
        }
    }
}

@Composable
private fun CheckRow(row: ReadinessRowState, onFix: (ReadinessRowState) -> Unit) {
    val colors = LocalIaColors.current
    val title by row.bindableState("title") { row.title }
    val detail by row.bindableState("detail") { row.detail }
    val status by row.bindableState("status") { row.status }
    val icon by row.bindableState("icon") { row.icon }
    val brushKey by row.bindableState("brushKey") { row.brushKey }
    val fixLabel by row.bindableState("fixLabel") { row.fixLabel }
    val fix by row.bindableState("fix") { row.fix }
    val brush = colors.brush(brushKey)
    val accessibleName = row.accessibleName
    val fixAccessibleName = row.fixAccessibleName

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { contentDescription = accessibleName },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(28.dp).padding(top = 2.dp)) {
            IaIcon(badgeGlyph(icon), 14.dp, brush, null)
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = TextStyle(fontSize = 12.sp, color = colors.TextDim),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // The status badge: radius 10, the outcome's glyph + label in the state colour.
        Row(
            Modifier
                .clip(RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                .border(IaTokens.BorderWidth, brush, RoundedCornerShape(IaTokens.RadiusReadinessBadge))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IaIcon(badgeGlyph(icon), 10.dp, brush, null)
            Text(
                status,
                style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = brush),
                modifier = Modifier.padding(start = 5.dp),
            )
        }
        if (fix != ReadinessFix.None) {
            Btn.Form(
                fixLabel,
                onClick = { onFix(row) },
                description = fixAccessibleName,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The test tools: one output area for the last test, with the collapsible technical details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsCard(
    home: HomeViewModel,
    controller: PreInterviewPageControllerImpl,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    val vm = home.vm
    val audioLevel by vm.bindableState("audioLevel") { vm.audioLevel }
    val toolTitle by controller.bindableState("toolTitle") { controller.toolTitle }
    val resultText by controller.bindableState("toolResultText") { controller.toolResultText }
    val resultBrushKey by controller.bindableState("toolResultBrushKey") { controller.toolResultBrushKey }
    val resultVisible by controller.bindableState("toolResultVisible") { controller.toolResultVisible }
    val output by controller.bindableState("toolOutput") { controller.toolOutput }
    val diagnosticsText by controller.bindableState("toolDiagnosticsText") { controller.toolDiagnosticsText }
    val diagnosticsVisible by controller.bindableState("toolDiagnosticsVisible") { controller.toolDiagnosticsVisible }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    IaCard(modifier.fillMaxWidth()) {
        Column {
            Text(
                Loc.t("rd_tools_hint"),
                style = IaTextStyles.body(colors).copy(fontSize = 12.5.sp),
            )
            // Wraps in long languages: the buttons first, the level meter on its own line when needed.
            FlowRow(Modifier.padding(top = 12.dp)) {
                Btn.Form(
                    Loc.t("pf_test_audio"),
                    onClick = { controller.onTestAudioClick() },
                    modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                )
                Btn.Form(
                    Loc.t("pf_test_stt"),
                    onClick = { controller.onTestTranscriptionClick() },
                    modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
                )
                Btn.Form(
                    Loc.t("pf_test_ai"),
                    onClick = { controller.onTestAiClick() },
                    modifier = Modifier.padding(end = 16.dp, bottom = IaTokens.ButtonGap),
                )
                Box(Modifier.padding(bottom = IaTokens.ButtonGap), contentAlignment = Alignment.Center) {
                    IaLevelMeter(
                        level = audioLevel,
                        modifier = Modifier.width(200.dp),
                        description = Loc.t("tt_audio_meter"),
                    )
                }
            }
            IaDivider(top = 4.dp, bottom = 12.dp)
            Text(
                toolTitle,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (resultVisible) {
                Text(
                    resultText,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brush(resultBrushKey),
                    ),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (output.isNotEmpty()) {
                Text(
                    output,
                    style = TextStyle(fontSize = 12.5.sp, color = colors.TextDim),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (diagnosticsVisible) {
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .clickable { diagnosticsExpanded = !diagnosticsExpanded }
                        .semantics { contentDescription = Loc.t("pf_technical_details") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IaIcon(IaGlyph.Chevron, 11.dp, colors.TextFaint, null)
                    Text(
                        Loc.t("pf_technical_details"),
                        style = IaTextStyles.label(colors),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (diagnosticsExpanded) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            diagnosticsText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = colors.TextFaint,
                            ),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
