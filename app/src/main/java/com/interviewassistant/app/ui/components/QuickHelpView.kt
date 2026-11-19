package com.interviewassistant.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.uistate.QuickHelp
import com.interviewassistant.app.uistate.QuickHelpContent
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * The Quick help popover's content (MainWindow.QuickHelp.cs `BuildQuickHelp`): for each section a heading
 * (14 SemiBold, heading level 2; 12 dp above all but the first, 6 below) and its tip lines (Body, 5 dp
 * apart); then the configured-shortcuts block (a Label header, rows of label + the keys — the keys always
 * left-to-right, SemiBold 12, named "label: keys"); then the state's action link and the Full help link
 * (`qh_full_help` → the Home help page). Content is built when the popover opens and dropped when it closes,
 * so a closed popover never holds text in an old UI language — the caller rebuilds it per open, as the C# does.
 */
@Composable
fun QuickHelpContentView(
    content: QuickHelpContent,
    onAction: (route: String) -> Unit,
    onFullHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIaColors.current
    Column(modifier) {
        content.sections.forEachIndexed { i, section ->
            Text(
                Loc.t(section.titleKey),
                style = IaTextStyles.body(colors).copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                modifier = Modifier
                    .padding(top = if (i == 0) 0.dp else 12.dp, bottom = 6.dp)
                    .semantics { heading() },
            )
            section.lineKeys.forEach { key ->
                Text(Loc.t(key), style = IaTextStyles.body(colors), modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        if (content.hotkeys.isNotEmpty()) {
            Text(
                Loc.t("qh_shortcuts"),
                style = IaTextStyles.label(colors),
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            content.hotkeys.forEach { hk ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Loc.t(hk.labelKey),
                        style = IaTextStyles.body(colors).copy(fontSize = 12.5.sp),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    // a technical value: always left-to-right, also inside a right-to-left layout
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            hk.keys,
                            style = IaTextStyles.body(colors).copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.Text),
                            modifier = Modifier.semantics {
                                contentDescription = Loc.f("fmt_label_value", Loc.t(hk.labelKey), hk.keys)
                            },
                        )
                    }
                }
            }
        }
        val actionKey = QuickHelp.actionKey(content.action)
        if (actionKey != null) {
            Box(Modifier.padding(top = 10.dp)) {
                Btn.Link(Loc.t(actionKey), onClick = { QuickHelp.actionRoute(content.action)?.let(onAction) })
            }
        }
        Box(Modifier.padding(top = 4.dp)) {
            Btn.Link(Loc.t("qh_full_help"), onClick = onFullHelp)
        }
    }
}
