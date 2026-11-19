package com.interviewassistant.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaDivider
import com.interviewassistant.app.ui.components.IaEmptyStateIcon
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaListItemRow
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc

/**
 * Reports (HomeWindow.xaml PageReports): the session list (≈0.32 of the body width on wide
 * layouts, DC-145 for the exact share rule) and the preview of the selected report, with
 * Open / Copy as text / Save as HTML / Delete. The preview shows the report's questions (the
 * view model's preview body); the full report — questions and suggestions — is what Open, Copy
 * and Save as HTML hand over. No scores and no hiring predictions exist anywhere in this page,
 * exactly as in the original. Below 720 dp the preview replaces the list in place (DC-142).
 */
@Composable
fun ReportsPage(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val reportsEmpty by home.bindableState("reportsEmpty") { home.reportsEmpty }
    val selectedIndex by home.bindableState("selectedReportIndex") { home.selectedReportIndex }

    Column(modifier) {
        Text(
            Loc.t("reports"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(Loc.t("reports_hint"), style = IaTextStyles.hint(colors))

        if (reportsEmpty) {
            // No reports at all: the centered empty state in a card.
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(IaTokens.RadiusCard))
                    .background(colors.Surface)
                    .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
                    .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IaEmptyStateIcon(IaGlyph.Report, glyphSize = 22.dp)
                    Text(
                        Loc.t("reports_empty_title"),
                        style = IaTextStyles.emptyTitle(colors),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        Loc.t("reports_empty_hint"),
                        style = IaTextStyles.hint(colors),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
        BoxWithConstraints(Modifier.padding(top = 12.dp)) {
            val width = maxWidth.value
            val mode = HomeLayout.paneMode(width, selectedIndex >= 0)
            when (mode) {
                PaneMode.Split -> {
                    val share = HomeLayout.reportsListShare(width)
                    Row {
                        ReportList(home, focusRegistry, Modifier.weight(share))
                        Spacer(Modifier.width(IaTokens.PaneGap))
                        ReportPreview(home, Modifier.weight(1f - share))
                    }
                }
                PaneMode.List -> ReportList(home, focusRegistry, Modifier.fillMaxWidth())
                PaneMode.Detail -> Column {
                    BackHandler { home.onReportSelectionChanged(-1) }
                    Btn.Link(Loc.t("reports"), onClick = { home.onReportSelectionChanged(-1) })
                    ReportPreview(home, Modifier.fillMaxWidth())
                }
            }
        }
        }
    }
}

@Composable
private fun ReportList(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val rows by home.bindableState("reportRows") { home.reportRows }
    val selectedIndex by home.bindableState("selectedReportIndex") { home.selectedReportIndex }

    Box(
        modifier
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusControl))
            .padding(4.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .focusRequester(focusRegistry.rememberRequester(HomeFocus.ReportList))
                .focusable()
                .onPreviewKeyEvent { event ->
                    // The C#'s list keys: Enter opens the selected report, Delete asks to delete it.
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            home.onOpenReportClick()
                            true
                        }
                        Key.Delete -> {
                            home.onDeleteReportClick()
                            true
                        }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription = Loc.t("reports")
                },
        ) {
            rows.forEachIndexed { index, row ->
                IaListItemRow(
                    selected = index == selectedIndex,
                    onClick = { home.onReportSelectionChanged(index) },
                    description = row.toString(),
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text(
                            row.whenText,
                            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                            maxLines = 1,
                        )
                        Text(
                            row.title,
                            style = TextStyle(fontSize = 12.sp, color = colors.TextDim),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            row.questions,
                            style = TextStyle(fontSize = 11.5.sp, color = colors.TextFaint),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPreview(home: HomeViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val selectVisible by home.bindableState("reportSelectStateVisible") { home.reportSelectStateVisible }
    val previewVisible by home.bindableState("reportPreviewVisible") { home.reportPreviewVisible }
    val title by home.bindableState("reportPreviewTitle") { home.reportPreviewTitle }
    val meta by home.bindableState("reportPreviewMeta") { home.reportPreviewMeta }
    val body by home.bindableState("reportPreviewBody") { home.reportPreviewBody }
    val openEnabled by home.bindableState("reportOpenEnabled") { home.reportOpenEnabled }
    val copyEnabled by home.bindableState("reportCopyEnabled") { home.reportCopyEnabled }
    val exportEnabled by home.bindableState("reportExportEnabled") { home.reportExportEnabled }
    val deleteEnabled by home.bindableState("reportDeleteEnabled") { home.reportDeleteEnabled }

    Box(
        modifier
            .clip(RoundedCornerShape(IaTokens.RadiusCard))
            .background(colors.Surface)
            .border(IaTokens.BorderWidth, colors.Border, RoundedCornerShape(IaTokens.RadiusCard))
            .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = IaTokens.CardPaddingVertical),
    ) {
        if (selectVisible) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 320.dp)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IaEmptyStateIcon(IaGlyph.List, glyphSize = 22.dp)
                Text(
                    Loc.t("reports_select_title"),
                    style = IaTextStyles.emptyTitle(colors),
                    textAlign = TextAlign.Center,
                )
                Text(
                    Loc.t("reports_select_hint"),
                    style = IaTextStyles.hint(colors),
                    textAlign = TextAlign.Center,
                )
            }
            return@Box
        }
        if (!previewVisible) return@Box
        Column {
            // The action group (its own accessible group, as in the original): the secondary
            // actions first, the destructive Delete set apart at the end behind a divider.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .semantics { contentDescription = Loc.t("report_actions") },
                verticalAlignment = Alignment.Top,
            ) {
                Btn.Form(
                    Loc.t("open"),
                    onClick = { home.onOpenReportClick() },
                    enabled = openEnabled,
                    description = Loc.t("tt_open_report"),
                )
                Spacer(Modifier.width(IaTokens.ButtonGap))
                Btn.Form(
                    Loc.t("copy_as_text"),
                    onClick = { home.onCopyReportClick() },
                    enabled = copyEnabled,
                    description = Loc.t("tt_copy_report"),
                )
                Spacer(Modifier.width(IaTokens.ButtonGap))
                Btn.Form(
                    Loc.t("save_as_html"),
                    onClick = { home.onExportReportClick() },
                    enabled = exportEnabled,
                    description = Loc.t("tt_save_report_html"),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(colors.Border),
                )
                Btn.FormDanger(
                    Loc.t("delete"),
                    onClick = { home.onDeleteReportClick() },
                    enabled = deleteEnabled,
                    description = Loc.t("tt_delete_report"),
                )
            }
            IaDivider(top = 0.dp, bottom = 12.dp)
            Text(
                title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
            )
            Text(meta, style = IaTextStyles.hint(colors))
            Text(
                body,
                style = IaTextStyles.body(colors),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
