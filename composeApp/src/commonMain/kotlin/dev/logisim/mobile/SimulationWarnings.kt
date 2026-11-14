package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.SimulationNoticeSource
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * The warnings banner above the canvas on windows tall enough for it. Its button opens [SimulationWarningsDialog],
 * the same dialog as Simulate > Simulation warnings and the compact [SimulationWarningsIndicator].
 */
@Composable
internal fun SimulationDiagnostics(
    diagnostics: List<LocalizedText>,
    sources: Map<LocalizedText, SimulationNoticeSource> = emptyMap(),
    onShow: ((List<String>) -> Boolean)? = null,
) {
    val strings = rememberLocalizer()
    val messages = diagnostics.distinct().map { strings(it) }
    if (messages.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(ChromeColors.Warning).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(messages.first(), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = strings(Messages.A11ySimulationWarning, "message" to messages.first()) })
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = strings.plural(Messages.A11ySimulationWarningCount, messages.size.toLong())
            }) { Text(strings.plural(Messages.SimulationWarningCount, messages.size.toLong())) }
    }
    if (expanded) SimulationWarningsDialog(diagnostics, sources, onShow?.let { show -> { ids -> show(ids).also { if (it) expanded = false } } }) {
        expanded = false
    }
}

/**
 * The one warnings dialog (C67): what the warnings mean, then each distinct warning. A warning whose [sources] name
 * components has a Show button, which hands them to [onShow] to select and frame; the caller closes the dialog when
 * that worked. When [onShow] returns false (the components are gone), the dialog stays and says so under the warning.
 */
@Composable
internal fun SimulationWarningsDialog(
    notices: List<LocalizedText>,
    sources: Map<LocalizedText, SimulationNoticeSource> = emptyMap(),
    onShow: ((List<String>) -> Boolean)? = null,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val distinct = notices.distinct()
    // Each warning whose Show found nothing, with the sources it tried: a refresh that names other components for the
    // same warning drops the stale "no longer in the circuit" line.
    var gone by remember { mutableStateOf(emptyMap<LocalizedText, List<String>>()) }
    AppAlertDialog(modifier = Modifier.exportEditorTestTags().testTag("simulation-warnings-dialog"), onDismissRequest = onDismiss,
        title = { Text(strings(Messages.SimulationWarningsTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(Messages.SimulationWarningsExplanation))
                distinct.forEachIndexed { index, notice ->
                    val text = strings(notice)
                    val ids = sources[notice]?.componentIds.orEmpty()
                    if (onShow == null || ids.isEmpty()) Text(text)
                    else Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text, Modifier.weight(1f))
                            val description = strings("a11y_simulation_warning_show", "message" to text)
                            TextButton(onClick = { if (!onShow(ids)) gone = gone + (notice to ids) }, modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("simulation-warning-show-$index").semantics { contentDescription = description }) {
                                Text(strings("simulation_warning_show"))
                            }
                        }
                        if (gone[notice] == ids) Text(strings("simulation_warning_source_gone"),
                            Modifier.testTag("simulation-warning-gone-$index").semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

/**
 * The warning count in the bottom status row of a short window, where the banner does not fit (C67): a 48 dp
 * button that opens [SimulationWarningsDialog].
 */
@Composable
internal fun SimulationWarningsIndicator(notices: List<LocalizedText>, onClick: () -> Unit) {
    val strings = rememberLocalizer()
    val count = notices.distinct().size
    if (count == 0) return
    val description = strings.plural(Messages.A11ySimulationWarningCount, count.toLong())
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(6.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .testTag("editor-simulation-warnings")
        .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center) {
        // The warning icon and the count; the button's description already says both.
        Row(Modifier.clip(RoundedCornerShape(6.dp)).background(ChromeColors.Warning).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.testTag("editor-simulation-warnings-icon")) { EditorIconGlyph(EditorIcon.WARNING, Modifier.size(14.dp)) }
            Text(count.toString(), Modifier.clearAndSetSemantics {}, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = ChromeColors.Ink, maxLines = 1)
        }
    }
}
