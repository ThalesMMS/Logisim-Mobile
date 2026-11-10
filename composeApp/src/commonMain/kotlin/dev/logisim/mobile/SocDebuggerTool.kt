package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The platform picker returns raw ELF bytes; execution remains in the simulator event path. */
fun createSocDebuggerTool(pickExecutable: (((ByteArray) -> Unit) -> Unit)? = null): EditorTool =
    EditorTool("soc-debugger", "SoC / CPUs", titleText = message("soc_tool_title")) { context ->
        SocDebuggerPanel(context, pickExecutable = pickExecutable)
    }

/** Native document adapters retain access only while reading; the simulator owns copied ELF bytes. */
fun createSocDebuggerTool(assets: DocumentAssetActions): EditorTool =
    EditorTool("soc-debugger", "SoC / CPUs", titleText = message("soc_tool_title")) { context ->
        SocDebuggerPanel(context, assets = assets) { onPicked ->
            assets.open(listOf("application/octet-stream", "application/x-elf", "application/x-executable"), SocElfLoader.MAX_SEGMENT_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> onPicked(result.asset.bytes)
                    is AssetOpenResult.Failure -> context.showNotice(result.notice)
                    AssetOpenResult.Cancelled -> Unit
                }
            }
        }
    }

@Composable
private fun SocDebuggerPanel(
    context: EditorToolContext,
    assets: DocumentAssetActions? = null,
    pickExecutable: (((ByteArray) -> Unit) -> Unit)?,
) {
    val strings = rememberLocalizer()
    val components = context.document.components.filter { it.kind in SocComponents.kinds }
    var chosen by remember(context.document.id) { mutableStateOf("") }
    val component = components.firstOrNull { it.id == chosen } ?: context.selectedComponents.firstOrNull { it.kind in SocComponents.kinds } ?: components.firstOrNull()
    var registerIndex by remember { mutableStateOf("1") }
    var registerValue by remember { mutableStateOf("00000000") }
    var address by remember { mutableStateOf("00000000") }
    var data by remember { mutableStateOf("00000000") }
    var accessSize by remember { mutableStateOf(SocAccessSize.WORD) }
    var elfHex by remember { mutableStateOf("") }
    fun send(event: ComponentEvent) {
        if (component != null && !context.sendComponentEvent(component.id, event)) context.showNotice(message("soc_instance_unavailable"))
    }
    fun hex(raw: String) = raw.trim().removePrefix("0x").removePrefix("0X").toUIntOrNull(16)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings("soc_tool_title"), style = MaterialTheme.typography.titleLarge)
        if (components.isEmpty()) {
            Text(strings("soc_empty"))
            SocTraceComparison(context)
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in components) FilterChip(selected = item.id == component?.id, onClick = { chosen = item.id; context.selectComponent(item.id) },
                label = { Text(strings("soc_component_identity", "label" to item.label.ifBlank { strings("component_${item.kind.name.lowercase()}") }, "id" to item.id)) })
        }
        if (component == null) return@Column
        val state = context.simulation.componentStates[component.id]
        val rv = (state as? SocRv32ComponentState)?.cpu
        val nios = (state as? SocNios2ComponentState)?.cpu
        val cpu = rv != null || nios != null
        val selectedAttributes = SocComponents.attributes(component)
        val selectedBusId = if (component.kind == SocComponents.BUS) selectedAttributes[SocComponents.BusIdentifier].ifBlank { component.id } else selectedAttributes[SocComponents.BusSelection]
        Text(strings("soc_bus_binding", "id" to selectedBusId))
        if (cpu) {
            val pc = rv?.pc ?: nios!!.pc
            val stopped = rv?.stopped ?: nios!!.stopped
            Text(strings("soc_pc_state", "pc" to pc.socHex(), "state" to strings(if (stopped) "soc_stopped" else "soc_running")), fontFamily = FontFamily.Monospace)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { send(SocStepEvent) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("soc_step")) }
                Button(onClick = { send(SocSetRunningEvent(stopped)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (stopped) strings("soc_run") else strings("soc_stop")) }
                OutlinedButton(onClick = { send(SocClearTraceEvent) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("soc_clear_trace")) }
                if (pickExecutable != null) OutlinedButton(onClick = { pickExecutable { send(SocLoadExecutableEvent(it)) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("soc_open_elf")) }
            }
            Text(strings("soc_clock_help"), style = MaterialTheme.typography.bodySmall)
            key(context.documentIdentity, context.activeInstancePath, component.id) {
                val debugger = (state as? SocRv32ComponentState)?.debugger ?: (state as SocNios2ComponentState).debugger
                SocLoadedProgram(debugger, pc) { send(it) }
            }
            if (pickExecutable == null) {
                OutlinedTextField(elfHex, { elfHex = it }, label = { Text(strings("soc_elf_hex")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                OutlinedButton(onClick = {
                    val text = elfHex.filterNot(Char::isWhitespace)
                    val valid = text.length % 2 == 0 && text.length <= SocElfLoader.MAX_SEGMENT_BYTES * 2 && text.all { it.digitToIntOrNull(16) != null }
                    if (valid && text.isNotEmpty()) send(SocLoadExecutableEvent(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }))
                    else context.showNotice(message("soc_elf_hex_invalid"))
                }) { Text(strings("soc_load_elf")) }
            }
            key(component.id) {
                SocAssemblerTool(selectedAttributes[SocComponents.ResetVector], nios2 = rv == null, ::send)
            }
            OutlinedTextField(registerIndex, { registerIndex = it }, label = { Text(strings("soc_register_index")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(registerValue, { registerValue = it }, label = { Text(strings("soc_hex_value")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = {
                val index = registerIndex.toIntOrNull(); val value = hex(registerValue)
                if (index != null && index in 0..31 && value != null) send(SocSetRegisterEvent(index, value))
                else context.showNotice(message("soc_register_invalid"))
            }) { Text(strings("soc_set_register")) }
            Text(strings("soc_registers"), style = MaterialTheme.typography.titleMedium)
            val registers = rv?.registers ?: nios!!.registers
            val known = rv?.knownRegisters ?: nios!!.knownRegisters
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (row in 0..7) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (column in 0..3) {
                        val index = row * 4 + column
                        val register = (if (rv != null) "x" else "r") + index.toString().padStart(2)
                        val value = if (known and (1u shl index) == 0u) "????????" else (if (index == 0) 0u else registers[index]).socHex()
                        Text(strings("soc_register_value", "register" to register, "value" to value), fontFamily = FontFamily.Monospace)
                    }
                }
            }
            val control = rv?.csrs ?: nios!!.controlRegisters
            Text(strings("soc_control"), style = MaterialTheme.typography.titleMedium)
            for ((key, value) in control) Text(strings("soc_control_value", "register" to key.toString(16).padStart(3, '0'), "value" to value.socHex()), fontFamily = FontFamily.Monospace)
            nios?.pendingCustom?.let { Text(strings("soc_custom_wait", "number" to it.n)) }
            Text(strings("soc_instruction_trace"), style = MaterialTheme.typography.titleMedium)
            for (entry in (rv?.trace ?: nios!!.trace).take(64)) {
                Text(strings("soc_instruction_entry", "pc" to entry.pc.socHex(), "instruction" to entry.instruction.socHex(), "assembly" to entry.assembly), fontFamily = FontFamily.Monospace)
                entry.notice?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
            }
        }
        fun transact(read: Boolean, write: Boolean) {
            val at = hex(address)
            if (at == null) {
                context.showNotice(message("soc_address_invalid"))
                return
            }
            val value = if (write) hex(data) else 0u
            if (value == null) {
                context.showNotice(message("soc_data_invalid"))
                return
            }
            send(SocTransactionEvent(SocBusRequest(
                address = at,
                writeData = value and accessSize.dataMask,
                size = accessSize,
                read = read,
                write = write,
                atomic = read && write,
            )))
        }
        Text(strings("soc_bus_transaction"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(address, { address = it }, label = { Text(strings("soc_hex_address")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(data, { data = it }, label = { Text(strings("soc_hex_data")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (size in SocAccessSize.entries) {
                val label = when (size) {
                    SocAccessSize.BYTE -> "soc_access_byte"
                    SocAccessSize.HALF_WORD -> "soc_access_half_word"
                    SocAccessSize.WORD -> "soc_access_word"
                }
                FilterChip(selected = accessSize == size, onClick = { accessSize = size }, label = { Text(strings(label)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { transact(read = true, write = false) }) { Text(strings("soc_read")) }
            OutlinedButton(onClick = { transact(read = false, write = true) }) { Text(strings("soc_write")) }
            OutlinedButton(onClick = { transact(read = true, write = true) }) { Text(strings("soc_atomic_read_write")) }
        }
        val device = (state as? SocDeviceComponentState)?.device
        when (device) {
            is SocMemoryDevice -> {
                Text(strings("soc_stored_memory"), style = MaterialTheme.typography.titleMedium)
                for ((at, value) in device.storedWords.entries.sortedBy { it.key }.take(128)) Text(strings("soc_memory_entry", "address" to at.socHex(), "value" to value.socHex()), fontFamily = FontFamily.Monospace)
            }
            is SocPioDevice -> Text(strings("soc_pio_state", "input" to device.snapshot.inputs.socHex(), "output" to device.outputBits.socHex(), "irq" to device.irq), fontFamily = FontFamily.Monospace)
            is SocDmaDevice -> Text(strings("soc_dma_state", "bytes" to device.bytesDone, "busy" to device.isBusy, "done" to device.isDone, "irq" to device.irq))
            is SocJtagUartDevice -> Text(strings("soc_uart_state", "rx" to device.readQueue.size, "rxCapacity" to device.readCapacity, "tx" to device.writeQueue.size, "txCapacity" to device.writeCapacity, "irq" to device.irq))
            is SocVgaDevice -> OutlinedButton(onClick = { send(SocRefreshVgaEvent) }) { Text(strings("soc_refresh_vga")) }
        }
        if (assets?.supportsBatchCreate == true && (component.kind == SocComponents.PIO || component.kind == SocComponents.VGA)) {
            OutlinedButton(onClick = {
                SocDriverExport.export(component)?.let { files ->
                    assets.createBatch(listOf(
                        DocumentAsset("${files.name}.h", "text/x-c", files.header.encodeToByteArray()),
                        DocumentAsset("${files.name}.c", "text/x-c", files.source.encodeToByteArray()),
                    )) { result ->
                        when (result) {
                            AssetActionResult.Completed -> context.showNotice(message("soc_driver_files_saved", "name" to files.name))
                            AssetActionResult.Cancelled -> Unit
                            is AssetActionResult.Failure -> context.showNotice(result.notice)
                        }
                    }
                }
            }, modifier = Modifier.testTag("soc-export-c-driver")) { Text(strings("soc_export_c_driver")) }
        }
        val busComponent = if (component.kind == SocComponents.BUS) component else context.document.components.firstOrNull {
            it.kind == SocComponents.BUS && SocComponents.attributes(it)[SocComponents.BusIdentifier].ifBlank { it.id } == selectedBusId
        }
        if (busComponent != null) SocMemoryMapPanel(context, busComponent)
        val bus = busComponent?.let { (context.simulation.componentStates[it.id] as? SocBusComponentState)?.bus }
        if (bus != null) {
            Text(strings("soc_bus_trace"), style = MaterialTheme.typography.titleMedium)
            for (entry in bus.trace.takeLast(64).asReversed()) {
                val request = entry.request
                val operation = when {
                    request.read && request.write -> "R/W"
                    request.write -> "W"
                    else -> "R"
                } + (request.size.bytes * 8)
                val read = entry.reply.data.socHex(request.size)
                val written = request.writeData.socHex(request.size)
                val result = entry.reply.error?.notice?.let { strings(it) } ?: when {
                    request.read && request.write -> strings("soc_atomic_trace_result", "read" to read, "written" to written)
                    request.read -> read
                    else -> written
                }
                Text(strings("soc_bus_entry", "sequence" to entry.sequence, "initiator" to request.initiator,
                    "operation" to operation, "address" to request.address.socHex(), "result" to result), fontFamily = FontFamily.Monospace)
            }
        }
        SocTraceComparison(context, selectedBusId)
    }
}

private val SocAccessSize.dataMask: UInt get() = UInt.MAX_VALUE shr ((SocAccessSize.WORD.bytes - bytes) * 8)

internal fun UInt.socHex(size: SocAccessSize = SocAccessSize.WORD) =
    (this and size.dataMask).toString(16).padStart(size.bytes * 2, '0').uppercase()
