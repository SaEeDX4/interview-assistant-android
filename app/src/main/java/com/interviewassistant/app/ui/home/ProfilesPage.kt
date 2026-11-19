package com.interviewassistant.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.app.ui.components.Btn
import com.interviewassistant.app.ui.components.IaDivider
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.IaIcon
import com.interviewassistant.app.ui.components.IaInlineMessage
import com.interviewassistant.app.ui.components.IaListItemRow
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.ui.components.Seg
import com.interviewassistant.app.ui.components.iaButtonFocusRing
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.viewmodels.FactRow
import com.interviewassistant.app.viewmodels.HomeFocus
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.launch

private const val PERSONAL_FIELD_MAX = 600

/**
 * Profiles (HomeWindow.xaml PageProfiles): the profile list with its actions, and the detail —
 * name / preferred name / headline, the Facts | Personal segmented tabs, the fact review list,
 * the generated interview-profile fields, and Save with its dirty note. Two panes side by side at
 * 720 dp and above; below that, the detail replaces the list in place (DC-142, §3.3).
 */
@Composable
fun ProfilesPage(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val selectedProfileId by home.bindableState("selectedProfileId") { home.selectedProfileId }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier) {
        val mode = HomeLayout.paneMode(maxWidth.value, selectedProfileId != null)
        when (mode) {
            PaneMode.Split -> Row {
                ProfileListColumn(home, Modifier.width(IaTokens.SidePaneWidth))
                Spacer(Modifier.width(IaTokens.PaneGap))
                ProfileDetail(home, focusRegistry, Modifier.weight(1f).widthIn(max = IaTokens.PageMaxWidth))
            }
            PaneMode.List -> ProfileListColumn(home, Modifier.fillMaxWidth())
            PaneMode.Detail -> {
                BackHandler { scope.launch { home.onProfileSelectionChanged(null) } }
                Column {
                    Btn.Link(
                        Loc.t("profiles"),
                        onClick = { scope.launch { home.onProfileSelectionChanged(null) } },
                    )
                    ProfileDetail(home, focusRegistry, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ProfileListColumn(home: HomeViewModel, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val scope = rememberCoroutineScope()
    val items by home.bindableState("profileItems") { home.profileItems }
    val selectedProfileId by home.bindableState("selectedProfileId") { home.selectedProfileId }
    val profilesEmpty by home.bindableState("profilesEmpty") { home.profilesEmpty }
    home.changeCounter("exportProfileEnabled", "deleteProfileEnabled")

    Column(modifier) {
        Text(
            Loc.t("profiles"),
            style = IaTextStyles.h1(colors),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        // The C#'s List style: Surface box, 1 dp border, 4 dp padding, 120–300 tall.
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
                    .semantics { contentDescription = Loc.t("profiles") },
            ) {
                items.forEach { item ->
                    IaListItemRow(
                        selected = item.value == selectedProfileId,
                        onClick = { scope.launch { home.onProfileSelectionChanged(item.value) } },
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
        if (profilesEmpty) {
            Text(
                Loc.t("warn_create_profile_first"),
                style = IaTextStyles.hint(colors),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Btn.Form(
            Loc.t("import_resume"),
            onClick = { home.onNewProfileClick() },
            description = Loc.t("tt_import_resume"),
            modifier = Modifier.fillMaxWidth().padding(bottom = IaTokens.StackGap),
        )
        Btn.Form(
            Loc.t("add_document"),
            onClick = { home.onAddResumeClick() },
            description = Loc.t("tt_add_document"),
            modifier = Modifier.fillMaxWidth().padding(bottom = IaTokens.StackGap),
        )
        Btn.Form(
            Loc.t("new_empty_profile"),
            onClick = { home.onNewEmptyProfileClick() },
            description = Loc.t("tt_new_empty_profile"),
            modifier = Modifier.fillMaxWidth().padding(bottom = IaTokens.StackGap),
        )
        Btn.Form(
            Loc.t("export_ellipsis"),
            onClick = { home.onExportProfileClick() },
            enabled = home.exportProfileEnabled,
            description = Loc.t("exp_tt_export_profile"),
            modifier = Modifier.fillMaxWidth().padding(bottom = IaTokens.StackGap),
        )
        Btn.Form(
            Loc.t("exp_import_backup"),
            onClick = { home.onImportProfileBackupClick() },
            description = Loc.t("exp_tt_import_backup"),
            modifier = Modifier.fillMaxWidth(),
        )
        IaDivider()
        Btn.FormDanger(
            Loc.t("delete_profile"),
            onClick = { home.onDeleteProfileClick() },
            enabled = home.deleteProfileEnabled,
            description = Loc.t("tt_delete_profile"),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            Loc.t("profile_local_hint"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ProfileDetail(home: HomeViewModel, focusRegistry: FocusRegistry, modifier: Modifier = Modifier) {
    val colors = LocalIaColors.current
    val name by home.bindableState("profileName") { home.profileName }
    val preferred by home.bindableState("profilePreferredName") { home.profilePreferredName }
    val headline by home.bindableState("profileHeadline") { home.profileHeadline }
    val tab by home.bindableState("profileTab") { home.profileTab }
    val dirtyText by home.bindableState("profileDirtyText") { home.profileDirtyText }
    home.changeCounter("profileDetailEnabled")
    val enabled = home.profileDetailEnabled

    val message = home.profileMessage
    val messageText by message.bindableState("text") { message.text }
    val messageDetail by message.bindableState("detail") { message.detail }
    val messageKind by message.bindableState("kind") { message.kind }
    val messageAction by message.bindableState("actionText") { message.actionText }

    Column(modifier) {
        Row {
            IaFormField(label = Loc.t("profile_name"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = name,
                    onValueChange = { home.profileName = it },
                    enabled = enabled,
                    description = Loc.t("profile_name"),
                    modifier = Modifier.focusRequester(focusRegistry.rememberRequester(HomeFocus.ProfileName)),
                )
            }
            Spacer(Modifier.width(IaTokens.ColumnGap))
            IaFormField(label = Loc.t("profile_preferred_name"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = preferred,
                    onValueChange = { home.profilePreferredName = it },
                    enabled = enabled,
                    description = Loc.t("profile_preferred_name"),
                )
            }
        }
        IaFormField(label = Loc.t("profile_headline")) {
            Input.Single(
                value = headline,
                onValueChange = { home.profileHeadline = it },
                enabled = enabled,
                description = Loc.t("profile_headline"),
            )
        }

        Seg.Track(
            labels = listOf(Loc.t("profile_tab_facts"), Loc.t("profile_tab_personal")),
            selectedIndex = if (tab == "personal") 1 else 0,
            onSelect = { home.selectProfileTab(if (it == 1) "personal" else "facts") },
            description = Loc.t("profile_sections"),
            modifier = Modifier.padding(top = 24.dp),
        )

        if (tab == "personal") PersonalPanel(home, enabled) else FactsPanel(home, enabled)

        IaInlineMessage(
            text = messageText,
            kind = messageKind,
            detail = messageDetail,
            actionText = messageAction,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            Modifier.padding(top = IaTokens.ButtonRowMarginTop),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn.FormPrimary(
                Loc.t("save_profile"),
                onClick = { home.onSaveProfileClick() },
                enabled = enabled,
            )
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
    }
}

@Composable
private fun FactsPanel(home: HomeViewModel, enabled: Boolean) {
    val colors = LocalIaColors.current
    val facts by home.bindableState("facts") { home.facts }
    val filter by home.bindableState("factFilter") { home.factFilter }
    val headerText by home.bindableState("factsHeaderText") { home.factsHeaderText }
    val summaryText by home.bindableState("factSummaryText") { home.factSummaryText }
    val factsEmpty by home.bindableState("factsEmptyVisible") { home.factsEmptyVisible }
    // visibleFacts is computed from the two collected bindables above (the VM raises it too).
    home.changeCounter("visibleFacts")
    val visible = home.visibleFacts

    Column {
        Row(
            Modifier.padding(top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headerText,
                style = IaTextStyles.label(colors).copy(color = colors.TextDim),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Btn.Form(
                Loc.t("fact_add"),
                onClick = { home.onAddFactClick() },
                enabled = enabled,
                description = Loc.t("tt_fact_add"),
            )
            Spacer(Modifier.width(8.dp))
            Btn.Form(
                Loc.t("confirm_all"),
                onClick = { home.onConfirmAllClick() },
                enabled = enabled,
                description = Loc.t("tt_confirm_all"),
            )
        }
        Text(
            Loc.t("facts_hint"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            Modifier.padding(top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The active filter chip is fully opaque, the others at 0.65 (DC-140).
            FilterChip(home, "all", Loc.t("filter_all"), filter)
            FilterChip(home, "review", Loc.t("filter_review"), filter)
            FilterChip(home, "confirmed", Loc.t("filter_confirmed"), filter)
            Spacer(Modifier.weight(1f))
            Text(summaryText, style = IaTextStyles.hint(colors).copy(fontSize = 11.5.sp))
        }
        Column(Modifier.semantics { contentDescription = Loc.t("profile_tab_facts") }) {
            visible.forEach { row ->
                FactRowItem(
                    row = row,
                    enabled = enabled,
                    onEdit = { home.onEditFactClick(row) },
                    onDelete = { home.onDeleteFactClick(row) },
                )
            }
        }
        if (factsEmpty) {
            Text(Loc.t("facts_empty"), style = IaTextStyles.hint(colors))
        }
    }
}

@Composable
private fun FilterChip(home: HomeViewModel, tag: String, label: String, activeFilter: String) {
    val active = activeFilter == tag
    Box(
        Modifier
            .alpha(if (active) 1f else 0.65f)
            .semantics { selected = active },
    ) {
        Btn.Chip(label, onClick = { home.onFactFilterClick(tag) }, description = label)
    }
}

@Composable
private fun FactRowItem(row: FactRow, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalIaColors.current
    val confirmed by row.bindableState("confirmed") { row.confirmed }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FactCheckBox(
                checked = confirmed,
                onCheckedChange = { row.confirmed = it },
                enabled = enabled,
                name = row.text,
                description = Loc.t("tt_fact_confirm"),
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(row.text, style = IaTextStyles.body(colors).copy(fontSize = 13.sp, color = colors.Text))
                Text(
                    row.meta,
                    style = IaTextStyles.hint(colors).copy(fontSize = 11.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Icon-only ghost actions (the C#'s Btn.Ghost with a glyph): drawn here with the
            // ghost treatment (transparent, 6x2 padding, 11 dp glyph).
            Spacer(Modifier.width(8.dp))
            FactIconButton(IaGlyph.Edit, Loc.t("tt_fact_edit"), enabled, onEdit)
            FactIconButton(IaGlyph.Delete, Loc.t("tt_fact_delete"), enabled, onDelete)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.Border),
        )
    }
}

@Composable
private fun FactIconButton(glyph: IaGlyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(IaTokens.RadiusControl))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { onClick() }
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        IaIcon(glyph, 11.dp, if (enabled) colors.TextDim else colors.TextFaint, null)
    }
}

/**
 * The fact row's confirm box: IaCheck's box drawing without its visible label (the C#'s fact
 * checkbox carries the fact text as its automation name only — the text itself is the row beside
 * it). Toggling writes the row's bindable, which the view model observes (dirty + summary).
 */
@Composable
private fun FactCheckBox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    name: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .iaButtonFocusRing(focused, colors.Accent)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) colors.AccentStrong else colors.Surface2)
            .border(
                IaTokens.BorderWidth,
                if (checked) colors.AccentStrong else colors.InputBorder,
                RoundedCornerShape(4.dp),
            )
            .size(18.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) IaIcon(IaGlyph.Check, 12.dp, colors.OnAccentStrong, null)
    }
}

@Composable
private fun PersonalPanel(home: HomeViewModel, enabled: Boolean) {
    val colors = LocalIaColors.current
    val sections by home.bindableState("personalSections") { home.personalSections }
    val selectedProfileId by home.bindableState("selectedProfileId") { home.selectedProfileId }

    Column {
        Text(
            Loc.t("personal_intro"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        sections.forEach { section ->
            Text(
                Loc.t(section.headingKey),
                style = IaTextStyles.label(colors).copy(color = colors.TextDim, fontSize = 12.sp),
                modifier = Modifier
                    .padding(top = IaTokens.SectionMarginTop, bottom = IaTokens.SectionMarginBottom)
                    .semantics { heading() },
            )
            section.fields.forEach { field ->
                // The C# binds each field two-way to the VM's per-key text store; the Compose
                // counterpart holds the field's text locally (keyed by profile + field, so a
                // profile switch rebuilds it from the store) and writes every edit through.
                var text by remember(selectedProfileId, field.key) {
                    mutableStateOf(home.personalText(field.key))
                }
                IaFormField(label = Loc.t(field.labelKey), hint = Loc.t(field.promptKey)) {
                    Input.Multiline(
                        value = text,
                        onValueChange = {
                            if (it.length <= PERSONAL_FIELD_MAX) {
                                text = it
                                home.setPersonalText(field.key, it)
                            }
                        },
                        enabled = enabled,
                        description = Loc.t(field.labelKey),
                    )
                }
            }
        }
    }
}
