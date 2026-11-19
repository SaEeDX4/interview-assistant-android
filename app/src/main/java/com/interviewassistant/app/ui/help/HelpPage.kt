package com.interviewassistant.app.ui.help

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaCard
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.home.badgeGlyph
import com.interviewassistant.app.ui.home.bindableState
import com.interviewassistant.app.ui.home.brush
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.uistate.HealthRow
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc

/**
 * Help & about (HomeWindow.xaml PageHelp + HomeWindow.Help.cs): the five help sections, the
 * diagnostics card (connection / audio / latency / questions — a friendly summary, no raw errors),
 * the health-check run with its result rows, and the About card. All values come from the view
 * model's help region; the page's visibility drives its 2-second ticker through
 * [HomeViewModel.onHelpVisibilityChanged].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpPage(home: HomeViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val connection by home.bindableState("helpConnectionText") { home.helpConnectionText }
    val audio by home.bindableState("helpAudioText") { home.helpAudioText }
    val latency by home.bindableState("helpLatencyText") { home.helpLatencyText }
    val questions by home.bindableState("helpQuestionsText") { home.helpQuestionsText }
    val healthRows by home.bindableState("healthRows") { home.healthRows }
    val healthStatus by home.bindableState("healthStatusText") { home.healthStatusText }
    val healthStatusVisible by home.bindableState("healthStatusVisible") { home.healthStatusVisible }
    val healthRunEnabled by home.bindableState("healthRunEnabled") { home.healthRunEnabled }
    val aboutProduct by home.bindableState("aboutProductText") { home.aboutProductText }
    val aboutVersion by home.bindableState("aboutVersionText") { home.aboutVersionText }
    val aboutChannel by home.bindableState("aboutChannelText") { home.aboutChannelText }
    val aboutBuild by home.bindableState("aboutBuildText") { home.aboutBuildText }
    val aboutInstall by home.bindableState("aboutInstallText") { home.aboutInstallText }
    val aboutSigning by home.bindableState("aboutSigningText") { home.aboutSigningText }
    val aboutService by home.bindableState("aboutServiceText") { home.aboutServiceText }

    DisposableEffect(home) {
        home.onHelpVisibilityChanged(true)
        onDispose { home.onHelpVisibilityChanged(false) }
    }

    Column(modifier.widthIn(max = IaTokens.PageMaxWidth)) {
        Text(
            Loc.t("nav_help"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.semantics { heading() },
        )
        for ((headingKey, bodyKey) in listOf(
            "help_getting_started" to "help_getting_started_body",
            "help_before" to "help_before_body",
            "help_controls" to "help_controls_body",
            "help_audio" to "help_audio_body",
            "help_honesty" to "help_honesty_body",
        )) {
            HelpSection(headingKey)
            Text(Loc.t(bodyKey), style = IaTextStyles.body(colors))
        }

        HelpSection("help_diag")
        Text(
            Loc.t("help_diag_hint"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        IaCard(Modifier.fillMaxWidth()) {
            Column {
                KeyValueRow("help_diag_connection", connection, polite = true)
                KeyValueRow("help_diag_audio", audio)
                KeyValueRow("help_diag_latency", latency)
                KeyValueRow("help_diag_questions", questions)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp)) {
            Btn.Form(
                Loc.t("help_diag_copy"),
                onClick = { home.onCopyDiagnosticsClick() },
                description = Loc.t("tt_help_diag_copy"),
                modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
            )
            Btn.Form(
                Loc.t("open_log_folder"),
                onClick = { home.onOpenLogsClick() },
                description = Loc.t("tt_logs"),
                modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
            )
            Btn.Form(
                Loc.t("help_diag_run"),
                onClick = { home.onRunHealthCheckClick() },
                enabled = healthRunEnabled,
                description = Loc.t("tt_help_diag_run"),
                modifier = Modifier.padding(end = IaTokens.ButtonGap, bottom = IaTokens.ButtonGap),
            )
        }
        if (healthStatusVisible) {
            Text(
                healthStatus,
                style = IaTextStyles.hint(colors),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Column(Modifier.padding(top = 6.dp)) {
            healthRows.forEach { row -> HealthRowView(home, row) }
        }

        HelpSection("help_about")
        IaCard(Modifier.fillMaxWidth()) {
            Column {
                KeyValueRow("about_product", aboutProduct)
                KeyValueRow("about_version", aboutVersion, ltrValue = true)
                KeyValueRow("about_channel", aboutChannel)
                KeyValueRow("about_build", aboutBuild, ltrValue = true)
                KeyValueRow("about_install", aboutInstall)
                KeyValueRow("about_signing", aboutSigning)
                KeyValueRow("about_service", aboutService)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp)) {
            Btn.Form(
                Loc.t("copy_build_info"),
                onClick = { home.onCopyBuildInfoClick() },
                description = Loc.t("tt_copy_build_info"),
                modifier = Modifier.padding(end = 8.dp, bottom = IaTokens.ButtonGap),
            )
        }
    }
}

/** A Help section heading (the XAML's Section style: the label style with the section margin). */
@Composable
private fun HelpSection(key: String) {
    val colors = LocalIaColors.current
    Text(
        Loc.t(key),
        style = IaTextStyles.label(colors),
        modifier = Modifier
            .padding(top = IaTokens.SectionMarginTop, bottom = IaTokens.SectionMarginBottom)
            .semantics { heading() },
    )
}

/** One About/diagnostics row: the key column (140–260 wide) and its value. */
@Composable
private fun KeyValueRow(key: String, value: String, polite: Boolean = false, ltrValue: Boolean = false) {
    val colors = LocalIaColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            Loc.t(key),
            style = IaTextStyles.metaKey(colors),
            modifier = Modifier.widthIn(min = 140.dp, max = 260.dp).padding(end = 16.dp),
        )
        val valueModifier = if (polite) {
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            Modifier
        }
        if (ltrValue) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(value, style = IaTextStyles.metaValue(colors), modifier = valueModifier)
            }
        } else {
            Text(value, style = IaTextStyles.metaValue(colors), modifier = valueModifier)
        }
    }
}

/** One health-check result row: outcome glyph, the SemiBold label and its detail line. */
@Composable
private fun HealthRowView(home: HomeViewModel, row: HealthRow) {
    val colors = LocalIaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = home.healthRowName(row) },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 1.dp, end = 10.dp)) {
            IaIcon(
                badgeGlyph(home.healthRowIcon(row)),
                13.dp,
                colors.brush(home.healthRowBrushKey(row)),
                null,
            )
        }
        Column {
            Text(
                Loc.t(row.labelKey),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
            )
            Text(Loc.t(row.detailKey), style = IaTextStyles.body(colors))
        }
    }
}
