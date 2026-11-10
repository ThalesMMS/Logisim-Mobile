package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.SocRuntimeScope
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun SocMemoryMapPanel(context: EditorToolContext, busComponent: CircuitComponent) {
    val strings = rememberLocalizer()
    var expanded by remember(context.documentIdentity, context.activeInstancePath, busComponent.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("soc-memory-map-toggle")) {
        Text(strings(if (expanded) "soc_memory_map_hide" else "soc_memory_map_show"))
    }
    if (expanded) {
        val components = context.document.components
        val states = context.simulation.componentStates
        val rows = remember(components, states, busComponent) {
            SocRuntimeScope(components, states).memoryMap(busComponent)
        }
        Column(Modifier.fillMaxWidth().testTag("soc-memory-map")) {
            Text(strings("soc_memory_map_title"), style = MaterialTheme.typography.titleMedium)
            for (row in rows) {
                HorizontalDivider()
                Text("${row.startAddress.mapAddress()} – ${row.endAddress.mapAddress()}", fontFamily = FontFamily.Monospace)
                val device = row.device
                val name = when {
                    device == null -> strings("soc_memory_map_unmapped")
                    device.component.kind == SocComponents.RV32 -> strings("soc_memory_map_plic")
                    else -> device.component.label.ifBlank { strings("component_${device.component.kind.name.lowercase()}") }
                }
                Text(if (device == null) name else strings("soc_component_identity", "label" to name, "id" to device.id))
            }
        }
    }
}

private fun UInt.mapAddress() = "0x" + toString(16).padStart(8, '0').uppercase()
