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
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The platform picker returns raw ELF bytes; execution remains in the simulator event path. */
fun createSocDebuggerTool(pickExecutable: (((ByteArray) -> Unit) -> Unit)? = null): EditorTool =
    EditorTool("soc-debugger", "SoC / CPUs", titleText = message("soc_tool_title")) { context ->
        SocDebuggerPanel(context, pickExecutable = pickExecutable?.let { pick -> { onPicked: (ByteArray) -> Unit, _: (LocalizedText) -> Unit -> pick(onPicked) } })
    }

/** Native document adapters retain access only while reading; the simulator owns copied ELF bytes. */
fun createSocDebuggerTool(assets: DocumentAssetActions): EditorTool =
    EditorTool("soc-debugger", "SoC / CPUs", titleText = message("soc_tool_title")) { context ->
        SocDebuggerPanel(context, assets = assets) { onPicked, onFailure ->
            assets.open(listOf("application/octet-stream", "application/x-elf", "application/x-executable"), SocElfLoader.MAX_SEGMENT_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> onPicked(result.asset.bytes)
                    is AssetOpenResult.Failure -> { context.showNotice(result.notice); onFailure(result.notice) }
                    AssetOpenResult.Cancelled -> Unit
                }
            }
        }
    }

@Composable
private fun SocDebuggerPanel(
    context: EditorToolContext,
    assets: DocumentAssetActions? = null,
    pickExecutable: (((ByteArray) -> Unit, (LocalizedText) -> Unit) -> Unit)?,
) {
    val strings = rememberLocalizer()
    val components = context.document.components.filter { it.kind in SocComponents.kinds }
    var chosen by remember(context.document.id) { mutableStateOf("") }
    val component = components.firstOrNull { it.id == chosen } ?: context.selectedComponents.firstOrNull { it.kind in SocComponents.kinds } ?: components.firstOrNull()
    var registerIndex by remember { mutableStateOf(1) }
    var registerValue by remember { mutableStateOf("00000000") }
    // The last Read, Write or Atomic of this sheet: its reply is the first later bus entry this component started.
    var manual by remember(context.document.id) { mutableStateOf<SocManualRequest?>(null) }
    var address by remember { mutableStateOf("00000000") }
    var data by remember { mutableStateOf("00000000") }
    var accessSize by remember { mutableStateOf(SocAccessSize.WORD) }
    var elfHex by remember { mutableStateOf("") }
    // Results are shown in the sheet under the control that produced them, and still reach the status bar (#74).
    var notice by remember(context.document.id) { mutableStateOf<Pair<SocNoticeAnchor, ToolNotice>?>(null) }
    fun report(anchor: SocNoticeAnchor, text: LocalizedText, error: Boolean = true) {
        context.showNotice(text)
        notice = anchor to if (error) text.asError() else text.asResult()
    }
    @Composable
    fun NoticeAt(anchor: SocNoticeAnchor) =
        ToolNoticeText(notice?.takeIf { it.first == anchor }?.second, Modifier.testTag("soc-notice-${anchor.name.lowercase()}"))
    fun send(event: ComponentEvent, anchor: SocNoticeAnchor = SocNoticeAnchor.PROGRAM) {
        if (notice?.first == anchor) notice = null
        if (component != null && !context.sendComponentEvent(component.id, event)) report(anchor, message("soc_instance_unavailable"))
    }
    fun hex(raw: String) = raw.trim().removePrefix("0x").removePrefix("0X").toUIntOrNull(16)
    val registerValueValid = hex(registerValue) != null
    val addressValid = hex(address) != null
    val dataValid = hex(data) != null
    val elfText = elfHex.filterNot(Char::isWhitespace)
    val elfValid = elfText.isNotEmpty() && elfText.length % 2 == 0 && elfText.length <= SocElfLoader.MAX_SEGMENT_BYTES * 2 &&
        elfText.all { it.digitToIntOrNull(16) != null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings("soc_tool_title"), style = MaterialTheme.typography.titleLarge)
        if (components.isEmpty()) {
            Text(strings("soc_empty"))
            SocTraceComparison(context)
            return@Column
        }
        // Evolution names SoC components by their label; the internal id never shows. Two that read the same are
        // told apart by their position on the canvas.
        val names = socComponentNames(components) { strings("component_${it.kind.name.lowercase()}") }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in components) FilterChip(selected = item.id == component?.id, onClick = { chosen = item.id; context.selectComponent(item.id) },
                label = { Text(strings(names.getValue(item.id))) })
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
                if (pickExecutable != null) OutlinedButton(onClick = {
                    pickExecutable({ send(SocLoadExecutableEvent(it)) }) { failure -> notice = SocNoticeAnchor.PROGRAM to failure.asError() }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("soc_open_elf")) }
            }
            NoticeAt(SocNoticeAnchor.PROGRAM)
            Text(strings("soc_clock_help"), style = MaterialTheme.typography.bodySmall)
            key(context.documentIdentity, context.activeInstancePath, component.id) {
                val debugger = (state as? SocRv32ComponentState)?.debugger ?: (state as SocNios2ComponentState).debugger
                SocLoadedProgram(debugger, pc, nios2 = rv == null) { send(it) }
            }
            if (pickExecutable == null) {
                val elfError = elfText.isNotEmpty() && !elfValid
                OutlinedTextField(elfHex, { elfHex = it }, label = { Text(strings("soc_elf_hex")) }, modifier = Modifier.fillMaxWidth().testTag("soc-elf-hex"),
                    minLines = 2, maxLines = 4, keyboardOptions = FieldKeyboards.Hex, isError = elfError,
                    supportingText = if (elfError) { { Text(strings("soc_elf_hex_invalid")) } } else null)
                OutlinedButton(enabled = elfValid, onClick = {
                    if (elfValid) send(SocLoadExecutableEvent(ByteArray(elfText.length / 2) { elfText.substring(it * 2, it * 2 + 2).toInt(16).toByte() }), SocNoticeAnchor.ELF)
                    else report(SocNoticeAnchor.ELF, message("soc_elf_hex_invalid"))
                }) { Text(strings("soc_load_elf")) }
                NoticeAt(SocNoticeAnchor.ELF)
            }
            key(component.id) {
                SocAssemblerTool(selectedAttributes[SocComponents.ResetVector], nios2 = rv == null, ::send)
            }
            // The register is picked by number and ABI name (CpuDrawSupport draws both), so it cannot be out of range.
            val nios2 = rv == null
            ChoiceDropdown(strings("soc_register_choice"), (0..31).toList(), registerIndex,
                optionLabel = { socRegisterName(it, nios2) }, modifier = Modifier.fillMaxWidth(), tag = "soc-register-index") {
                registerIndex = it
            }
            // The value field says what is wrong while typing; the button stays disabled until it is valid.
            OutlinedTextField(registerValue, { registerValue = it }, label = { Text(strings("soc_hex_value")) },
                modifier = Modifier.fillMaxWidth().testTag("soc-register-value"), singleLine = true, keyboardOptions = FieldKeyboards.Hex,
                isError = !registerValueValid, supportingText = if (!registerValueValid) { { Text(strings("soc_hex_value_invalid")) } } else null)
            OutlinedButton(enabled = registerValueValid, onClick = {
                val value = hex(registerValue)
                if (value != null) send(SocSetRegisterEvent(registerIndex, value), SocNoticeAnchor.REGISTER)
                else report(SocNoticeAnchor.REGISTER, message("soc_register_invalid"))
            }) { Text(strings("soc_set_register")) }
            NoticeAt(SocNoticeAnchor.REGISTER)
            Text(strings("soc_registers"), style = MaterialTheme.typography.titleMedium)
            val registers = rv?.registers ?: nios!!.registers
            val known = rv?.knownRegisters ?: nios!!.knownRegisters
            // Four columns where they fit, two on a phone, so the ABI names need no sideways scrolling.
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("soc-register-grid")) {
                val columns = if (maxWidth >= 600.dp) 4 else 2
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (row in 0 until 32 / columns) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (column in 0 until columns) {
                            val index = row * columns + column
                            val register = socRegisterName(index, nios2).padEnd(9)
                            val value = if (known and (1u shl index) == 0u) "????????" else (if (index == 0) 0u else registers[index]).socHex()
                            Text(strings("soc_register_value", "register" to register, "value" to value), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium)
                        }
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
        val busComponent = if (component.kind == SocComponents.BUS) component else context.document.components.firstOrNull {
            it.kind == SocComponents.BUS && SocComponents.attributes(it)[SocComponents.BusIdentifier].ifBlank { it.id } == selectedBusId
        }
        val bus = busComponent?.let { (context.simulation.componentStates[it.id] as? SocBusComponentState)?.bus }
        val busTrace = bus?.trace.orEmpty() // one copy of the history per composition
        fun transact(read: Boolean, write: Boolean) {
            val at = hex(address)
            if (at == null) {
                report(SocNoticeAnchor.BUS, message("soc_address_invalid"))
                return
            }
            val value = if (write) hex(data) else 0u
            if (value == null) {
                report(SocNoticeAnchor.BUS, message("soc_data_invalid"))
                return
            }
            val request = SocBusRequest(
                address = at,
                writeData = value and accessSize.dataMask,
                size = accessSize,
                read = read,
                write = write,
                atomic = read && write,
            )
            manual = SocManualRequest(component.id, request, busTrace.lastOrNull()?.sequence ?: -1L, (manual?.number ?: 0) + 1)
            send(SocTransactionEvent(request), SocNoticeAnchor.BUS)
        }
        Text(strings("soc_bus_transaction"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(address, { address = it }, label = { Text(strings("soc_hex_address")) },
            modifier = Modifier.fillMaxWidth().testTag("soc-address"), singleLine = true, keyboardOptions = FieldKeyboards.Hex,
            isError = !addressValid, supportingText = if (!addressValid) { { Text(strings("soc_address_invalid")) } } else null)
        OutlinedTextField(data, { data = it }, label = { Text(strings("soc_hex_data")) },
            modifier = Modifier.fillMaxWidth().testTag("soc-data"), singleLine = true, keyboardOptions = FieldKeyboards.Hex,
            isError = !dataValid, supportingText = if (!dataValid) { { Text(strings("soc_data_invalid")) } } else null)
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
            OutlinedButton(enabled = addressValid, onClick = { transact(read = true, write = false) },
                modifier = Modifier.testTag("soc-read")) { Text(strings("soc_read")) }
            OutlinedButton(enabled = addressValid && dataValid, onClick = { transact(read = false, write = true) },
                modifier = Modifier.testTag("soc-write")) { Text(strings("soc_write")) }
            OutlinedButton(enabled = addressValid && dataValid, onClick = { transact(read = true, write = true) },
                modifier = Modifier.testTag("soc-atomic")) { Text(strings("soc_atomic_read_write")) }
        }
        NoticeAt(SocNoticeAnchor.BUS)
        // BusTransactionInsertionGui shows the reply under its button: here too, and announced, instead of only
        // in the bus trace at the end of the sheet. Kept composed while empty, so the first reply is announced.
        // Each reply carries its bus sequence number (and each request without a trace its count), so a second
        // identical Read is a new text that the live region announces again.
        val reply = manual?.takeIf { it.componentId == component.id }?.let { pending ->
            if (bus == null) message("soc_bus_no_trace", "number" to pending.number).asResult() else pending.reply(busTrace)?.let { entry ->
                val text = message("soc_bus_manual_result", "sequence" to entry.sequence, "operation" to entry.operationText(),
                    "address" to entry.request.address.socHex(), "result" to entry.resultText())
                if (entry.reply.error != null) text.asError() else text.asResult()
            }
        }
        ToolNoticeText(reply, Modifier.testTag("soc-bus-result"), selectable = true)
        val device = (state as? SocDeviceComponentState)?.device
        when (device) {
            is SocMemoryDevice -> {
                Text(strings("soc_stored_memory"), style = MaterialTheme.typography.titleMedium)
                for ((at, value) in device.storedWords.entries.sortedBy { it.key }.take(128)) Text(strings("soc_memory_entry", "address" to at.socHex(), "value" to value.socHex()), fontFamily = FontFamily.Monospace)
            }
            is SocPioDevice -> Text(strings("soc_pio_state", "input" to device.snapshot.inputs.socHex(), "output" to device.outputBits.socHex(), "irq" to device.irq), fontFamily = FontFamily.Monospace)
            is SocDmaDevice -> Text(strings("soc_dma_state", "bytes" to device.bytesDone, "busy" to device.isBusy, "done" to device.isDone, "irq" to device.irq))
            is SocJtagUartDevice -> Text(strings("soc_uart_state", "rx" to device.readQueue.size, "rxCapacity" to device.readCapacity, "tx" to device.writeQueue.size, "txCapacity" to device.writeCapacity, "irq" to device.irq))
            is SocVgaDevice -> OutlinedButton(onClick = { send(SocRefreshVgaEvent, SocNoticeAnchor.DEVICE) }) { Text(strings("soc_refresh_vga")) }
        }
        if (assets?.supportsBatchCreate == true && (component.kind == SocComponents.PIO || component.kind == SocComponents.VGA)) {
            OutlinedButton(onClick = {
                SocDriverExport.export(component)?.let { files ->
                    assets.createBatch(listOf(
                        DocumentAsset("${files.name}.h", "text/x-c", files.header.encodeToByteArray()),
                        DocumentAsset("${files.name}.c", "text/x-c", files.source.encodeToByteArray()),
                    )) { result ->
                        when (result) {
                            AssetActionResult.Completed -> report(SocNoticeAnchor.DEVICE, message("soc_driver_files_saved", "name" to files.name), error = false)
                            AssetActionResult.Cancelled -> Unit
                            is AssetActionResult.Failure -> report(SocNoticeAnchor.DEVICE, result.notice)
                        }
                    }
                }
            }, modifier = Modifier.testTag("soc-export-c-driver")) { Text(strings("soc_export_c_driver")) }
        }
        NoticeAt(SocNoticeAnchor.DEVICE)
        if (busComponent != null) SocMemoryMapPanel(context, busComponent)
        if (bus != null) {
            Text(strings("soc_bus_trace"), style = MaterialTheme.typography.titleMedium)
            val initiators = socComponentNames(context.document.components.filter { it.kind in SocComponents.kinds }) {
                strings("component_${it.kind.name.lowercase()}")
            }
            for (entry in busTrace.takeLast(64).asReversed()) {
                val request = entry.request
                Text(strings("soc_bus_entry", "sequence" to entry.sequence,
                    "initiator" to (socBusPartyName(request.initiator, initiators)?.let { strings(it) } ?: ""),
                    "operation" to entry.operationText(), "address" to request.address.socHex(), "result" to strings(entry.resultText())),
                    fontFamily = FontFamily.Monospace)
            }
        }
        SocTraceComparison(context, selectedBusId)
    }
}

/**
 * A Read, Write or Atomic sent from the sheet. Its reply is the first bus entry after [after] that [componentId]
 * started for the same request; a trace cleared or reset since then is searched from its start.
 */
internal data class SocManualRequest(val componentId: String, val request: SocBusRequest, val after: Long, val number: Int = 1) {
    fun reply(trace: List<SocBusTrace>): SocBusTrace? {
        val from = if ((trace.lastOrNull()?.sequence ?: -1L) < after) -1L else after
        return trace.firstOrNull {
            it.sequence > from && it.request.initiator == componentId && it.request.address == request.address &&
                it.request.size == request.size && it.request.read == request.read && it.request.write == request.write &&
                (!request.write || it.request.writeData == request.writeData)
        }
    }
}

/** `R32`, `W8` or `R/W16`, as the bus trace names an operation. */
internal fun SocBusTrace.operationText(): String = when {
    request.read && request.write -> "R/W"
    request.write -> "W"
    else -> "R"
} + (request.size.bytes * 8)

/** The data read, the data written, both for an atomic access, or the bus error. */
internal fun SocBusTrace.resultText(): LocalizedText {
    val read = reply.data.socHex(request.size)
    val written = request.writeData.socHex(request.size)
    return reply.error?.notice ?: when {
        request.read && request.write -> message("soc_atomic_trace_result", "read" to read, "written" to written)
        request.read -> LocalizedText.literal(read)
        else -> LocalizedText.literal(written)
    }
}

/**
 * What the sheet calls each SoC component: its label, or its localized kind. Evolution never shows internal ids;
 * components that would read the same get their canvas position, and the id only if they also share a position.
 */
internal fun socComponentNames(components: List<CircuitComponent>, kindName: (CircuitComponent) -> String): Map<String, LocalizedText> {
    val base = components.associate { it.id to it.label.ifBlank { kindName(it) } }
    val shared = base.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val stacked = components.filter { base.getValue(it.id) in shared }.groupingBy { base.getValue(it.id) to it.position }
        .eachCount().filterValues { it > 1 }.keys
    return components.associate { component ->
        val name = base.getValue(component.id)
        component.id to when {
            name !in shared -> LocalizedText.literal(name)
            (name to component.position) in stacked -> message("soc_component_identity", "label" to name, "id" to component.id)
            else -> message("soc_component_chip_position", "label" to name, "x" to component.position.x, "y" to component.position.y)
        }
    }
}

/**
 * A bus initiator or responder by name ([names] from [socComponentNames]): a component, or a processor's interrupt
 * controller (device id "<processor>/plic") as "PLIC of <processor>". An id no component explains is shown as it
 * is; null for none.
 */
internal fun socBusPartyName(id: String?, names: Map<String, LocalizedText>): LocalizedText? {
    if (id.isNullOrBlank()) return null
    names[id]?.let { return it }
    val owner = id.substringBeforeLast('/', "")
    val processor = names[owner]
    if (processor != null && id.substringAfterLast('/') == "plic") {
        return message("soc_memory_map_device_of", "device" to message("soc_memory_map_plic"), "component" to processor)
    }
    return LocalizedText.literal(id)
}

/** Where an SoC result is shown: under the program, ELF, register, bus or device controls. */
private enum class SocNoticeAnchor { PROGRAM, ELF, REGISTER, BUS, DEVICE }

private val SocAccessSize.dataMask: UInt get() = UInt.MAX_VALUE shr ((SocAccessSize.WORD.bytes - bytes) * 8)

internal fun UInt.socHex(size: SocAccessSize = SocAccessSize.WORD) =
    (this and size.dataMask).toString(16).padStart(size.bytes * 2, '0').uppercase()
