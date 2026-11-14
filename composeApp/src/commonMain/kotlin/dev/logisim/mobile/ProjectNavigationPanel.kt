package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.rememberLocalizer


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitProject

@Composable
fun ProjectNavigationPanel(
    project: CircuitProject,
    activeTarget: CircuitNavigationTarget,
    onNavigate: (CircuitNavigationTarget) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onSetMain: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExport: ((String) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    val rows = remember(project) { ProjectNavigation.rows(project) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<String?>(null) }
    var deleteDialog by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        // A narrow panel or a large font puts Add circuit… on its own line instead of wrapping it word by word.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            Text(strings(Messages.ActionCircuits), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChromeColors.Muted,
                modifier = Modifier.semantics { heading() })
            // Evolution's Add Circuit…: it adds a circuit to this project, unlike File > New.
            TextButton(onClick = { nameDialog = "" }, modifier = Modifier.heightIn(min = 48.dp).testTag("circuits-add")) {
                Text(strings("project_add_circuit"), fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.key }) { row ->
                val recursive = row.kind == ProjectNavigationKind.RECURSIVE_REFERENCE
                val active = row.target == activeTarget
                // The active circuit: the selected tint and a leading accent bar, which reaches 3:1 in both themes.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .leadingSelectionCue(active)
                    .clickable(enabled = !recursive, role = Role.Button) { onNavigate(row.target) }
                    .semantics {
                        if (!recursive) selected = active
                        contentDescription = when (row.kind) {
                            // The ★ is drawn only; the main circuit says so in its description (C29).
                            ProjectNavigationKind.CIRCUIT -> strings(if (row.isMain) "a11y_open_main_definition" else Messages.A11yOpenDefinition, "name" to row.label)
                            ProjectNavigationKind.INSTANCE -> strings(Messages.A11yOpenInstance, "name" to row.label, "path" to row.breadcrumb.joinToString(" / "))
                            ProjectNavigationKind.RECURSIVE_REFERENCE -> strings(Messages.A11yRecursiveReference, "name" to row.label)
                        }
                    }.padding(start = (12 + row.depth.coerceAtMost(4) * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (row.depth > 0) Text("↳", fontSize = 13.sp, color = ChromeColors.Muted)
                        else EditorIconGlyph(EditorIcon.LIBRARY, Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(row.label, fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        if (recursive) Text(strings(Messages.RecursiveReference), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (row.isMain) Text("★", fontSize = 12.sp, color = ChromeColors.Accent, modifier = Modifier.padding(horizontal = 4.dp))
                    if (row.kind == ProjectNavigationKind.CIRCUIT) {
                        Column {
                            TextButton(onClick = { openMenu = row.target.circuitId }, contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                                    .semantics { contentDescription = strings(Messages.A11yManageCircuit, "name" to row.label) }) {
                                Text("⋮", color = ChromeColors.Muted)
                            }
                            DropdownMenu(expanded = openMenu == row.target.circuitId, onDismissRequest = { openMenu = null }) {
                                val circuitIndex = project.circuits.indexOfFirst { it.id == row.target.circuitId }
                                DropdownMenuItem(text = { Text(strings(Messages.ActionRename)) }, onClick = { openMenu = null; nameDialog = row.target.circuitId })
                                DropdownMenuItem(text = { Text(strings(Messages.ActionMoveUp)) }, enabled = circuitIndex > 0,
                                    onClick = { openMenu = null; onMove(row.target.circuitId, -1) })
                                DropdownMenuItem(text = { Text(strings(Messages.ActionMoveDown)) }, enabled = circuitIndex < project.circuits.lastIndex,
                                    onClick = { openMenu = null; onMove(row.target.circuitId, 1) })
                                DropdownMenuItem(text = { Text(strings(Messages.ActionSetMain)) }, enabled = !row.isMain,
                                    onClick = { openMenu = null; onSetMain(row.target.circuitId) })
                                if (onExport != null) {
                                    DropdownMenuItem(text = { Text(strings(Messages.ActionExportCircuit)) },
                                        onClick = { openMenu = null; onExport(row.target.circuitName) })
                                }
                                // Enabled for the main and used circuits too: the dialog explains why they stay.
                                DropdownMenuItem(text = { Text(strings(Messages.ActionDelete)) },
                                    modifier = Modifier.testTag("circuit-delete-${row.target.circuitId}"),
                                    onClick = { openMenu = null; deleteDialog = row.target.circuitId })
                            }
                        }
                    }
                }
            }
        }
    }
    nameDialog?.let { id -> CircuitNameDialog(project, id, onCreate, onRename) { nameDialog = null } }
    deleteDialog?.let { id -> CircuitRemoveDialog(project, id, onDelete) { deleteDialog = null } }
}
