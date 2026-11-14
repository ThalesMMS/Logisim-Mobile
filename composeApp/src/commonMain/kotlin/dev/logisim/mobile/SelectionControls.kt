package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A checkbox named by its visible label: the whole 48 dp row toggles, so TalkBack reads one "checkbox, <label>" node and
 * tapping the text works. Inside a horizontally scrolling row, pass `Modifier.wrapContentWidth()`.
 */
@Composable
internal fun LabeledCheckbox(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(title, Modifier.padding(end = 8.dp),
            color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/** One option of a radio group whose parent applies `Modifier.selectableGroup()`; the whole 48 dp row selects it. */
@Composable
internal fun RadioOption(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Row(
        modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, Modifier.padding(end = 8.dp),
            color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/** Short single-choice sets (2–4 options) show every option at once, with radio-button selection semantics. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    optionTag: ((T) -> String)? = null,
    onSelect: (T) -> Unit,
) {
    val labels = options.map { optionLabel(it) }
    // Segments share the row width and truncate, so long labels and large fonts wrap as chips instead.
    val segmented = options.size in 2..4 && LocalDensity.current.fontScale <= 1.3f &&
        labels.sumOf { it.length } <= 20 && labels.all { it.length <= 10 }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (segmented) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { if (option != selected) onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).then(optionTag?.let { Modifier.testTag(it(option)) } ?: Modifier),
                    ) { Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { if (option != selected) onSelect(option) },
                        label = { Text(labels[index]) },
                        enabled = enabled,
                        // A chip announces itself as a checkbox; in a single choice it is a radio button.
                        modifier = Modifier.semantics { role = Role.RadioButton }
                            .then(optionTag?.let { Modifier.testTag(it(option)) } ?: Modifier),
                    )
                }
            }
        }
    }
}

/** Long or open-ended single choices (circuits, generated files) as an exposed dropdown whose items carry `selected`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel).orEmpty(), onValueChange = {}, readOnly = true, singleLine = true,
            enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .then(tag?.let { Modifier.testTag(it) } ?: Modifier),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                val chosen = option == selected
                DropdownMenuItem(
                    text = { Text(optionLabel(option), fontWeight = if (chosen) FontWeight.Bold else null) },
                    onClick = { expanded = false; if (!chosen) onSelect(option) },
                    trailingIcon = if (chosen) { { Text("✓", Modifier.clearAndSetSemantics {}) } } else null,
                    modifier = Modifier.semantics { this.selected = chosen }
                        .then(tag?.let { Modifier.testTag("$it-option-$index") } ?: Modifier),
                )
            }
        }
    }
}
