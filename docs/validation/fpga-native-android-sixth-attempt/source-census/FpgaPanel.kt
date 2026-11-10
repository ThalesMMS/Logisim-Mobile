package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPin
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.FpgaDesignArtifact
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaDesignRules
import dev.logisim.mobile.core.FpgaDiagnosticSeverity
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaPlanResult
import dev.logisim.mobile.core.FpgaProjectGenerator
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer

internal data class FpgaClockInput(val enabled: Boolean = false, val frequency: String = "") {
    val frequencyHz: Long?
        get() = frequency.trim().takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it in 1..1_000_000_000_000L }
    val valid: Boolean get() = !enabled || frequencyHz != null
}

internal fun FpgaDesignArtifact.withClockInputs(inputs: Map<String, FpgaClockInput>): FpgaDesignArtifact = copy(
    ports = ports.map { port ->
        val clock = inputs[port.name]
        if (port.direction != FpgaPinDirection.INPUT || port.width != 1 || clock == null) port
        else port.copy(clockFrequencyHz = if (clock.enabled) clock.frequencyHz ?: 0L else null)
    },
)

class FpgaMappingSession {
    internal val boardXml = mutableStateOf("")
    internal val board = mutableStateOf<FpgaBoardDefinition?>(null)
    internal val importNotices = mutableStateOf<List<LocalizedText>>(emptyList())
    internal val importFailed = mutableStateOf(false)
    internal val showXml = mutableStateOf(true)
    private var mappingKey: Pair<FpgaDesignArtifact?, FpgaBoardDefinition?>? = null
    private val bindings = mutableStateOf<Map<FpgaSignalBit, FpgaPinBinding>>(emptyMap())
    private var flowBoard: FpgaBoardDefinition? = null
    private val flow = mutableStateOf<FpgaFlow?>(null)
    private var clockDesign: FpgaDesignArtifact? = null
    private val clockInputs = mutableStateOf<Map<String, FpgaClockInput>>(emptyMap())

    internal fun bindingsFor(design: FpgaDesignArtifact?, board: FpgaBoardDefinition?) = bindings.apply {
        val nextKey = design to board
        if (mappingKey != nextKey) { value = emptyMap(); mappingKey = nextKey }
    }

    internal fun flowFor(board: FpgaBoardDefinition?) = flow.apply {
        if (flowBoard != board) { value = board?.let(::boardFlows)?.firstOrNull(); flowBoard = board }
    }

    internal fun clocksFor(design: FpgaDesignArtifact?) = clockInputs.apply {
        if (clockDesign != design) {
            value = design?.ports.orEmpty().filter { it.direction == FpgaPinDirection.INPUT && it.width == 1 }
                .associate { it.name to FpgaClockInput(it.clockFrequencyHz != null, it.clockFrequencyHz?.toString().orEmpty()) }
            clockDesign = design
        }
    }
}

fun fpgaEditorTool(
    designProvider: (EditorToolContext) -> FpgaDesignArtifact?,
    onExport: ((FpgaBuildPlan) -> Unit)? = null,
    onSynthesize: ((FpgaBuildPlan) -> Unit)? = null,
    onPickBoard: (((String) -> Unit) -> Unit)? = null,
    companionClient: CompanionClient? = null,
    onArtifacts: ((List<CompanionArtifact>) -> Unit)? = null,
    mappingSession: FpgaMappingSession = FpgaMappingSession(),
    companionSession: FpgaCompanionSession = FpgaCompanionSession(),
): EditorTool {
    return EditorTool(id = "fpga", title = "FPGA", titleText = message("fpga_title")) { context ->
        val design = remember(context.project, context.document) { designProvider(context) }
        val pending = companionSession.displayedPlan
        if (pending != null && companionClient != null) {
            FpgaCompanionPanel(pending, companionClient, onBack = { companionSession.displayedPlan = null },
                onArtifacts = onArtifacts, session = companionSession)
        } else {
            val synthesize: ((FpgaBuildPlan) -> Unit)? = onSynthesize ?: companionClient?.let {
                { plan -> companionSession.displayedPlan = plan }
            }
            FpgaPanel(design, onExport, synthesize, onPickBoard, session = mappingSession)
        }
    }
}

/** Synthesis is an explicit host action. The panel never programs a physical device. */
@Composable
fun FpgaPanel(
    design: FpgaDesignArtifact?,
    onExport: ((FpgaBuildPlan) -> Unit)? = null,
    onSynthesize: ((FpgaBuildPlan) -> Unit)? = null,
    onPickBoard: (((String) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
    session: FpgaMappingSession = remember { FpgaMappingSession() },
) {
    val text = rememberLocalizer()
    var boardXml by session.boardXml
    var board by session.board
    var importNotices by session.importNotices
    var importFailed by session.importFailed
    var showXml by session.showXml
    var bindings by remember(design, board) { session.bindingsFor(design, board) }
    var flow by remember(board) { session.flowFor(board) }
    var clocks by remember(design) { session.clocksFor(design) }
    val effectiveDesign = remember(design, clocks) { design?.withClockInputs(clocks) }
    var validated by remember(effectiveDesign, board, bindings, flow) { mutableStateOf(false) }
    var actionNotices by remember(effectiveDesign, board, bindings, flow) { mutableStateOf<List<LocalizedText>>(emptyList()) }
    val currentBoard = board
    val currentFlow = flow
    val diagnostics = remember(effectiveDesign, currentBoard, bindings, currentFlow) {
        if (effectiveDesign != null && currentBoard != null && currentFlow != null)
            FpgaDesignRules.check(effectiveDesign, currentBoard, bindings, currentFlow)
        else emptyList()
    }
    val errors = diagnostics.count { it.severity == FpgaDiagnosticSeverity.ERROR }
    val ready = effectiveDesign != null && currentBoard != null && currentFlow != null && errors == 0 && clocks.values.all { it.valid }
    val signals = remember(effectiveDesign) {
        effectiveDesign?.ports.orEmpty().asSequence().flatMap { port ->
            (0 until port.width.coerceIn(0, 4096)).asSequence().map { port to it }
        }.take(8192).toList()
    }

    fun importXml(xml: String) {
        boardXml = xml
        when (val result = FpgaBoardCodec.read(xml)) {
            is FpgaBoardImportResult.Success -> {
                board = result.board
                importNotices = result.warningNotices
                importFailed = false
                showXml = false
            }
            is FpgaBoardImportResult.Failure -> {
                importNotices = result.diagnosticNotices
                importFailed = true
            }
        }
    }

    fun generate(action: (FpgaBuildPlan) -> Unit) {
        if (!ready || effectiveDesign == null || currentBoard == null || currentFlow == null) return
        validated = true
        when (val result = FpgaProjectGenerator.generate(effectiveDesign, currentBoard, bindings, currentFlow)) {
            is FpgaPlanResult.Success -> {
                action(result.plan)
                actionNotices = emptyList()
            }
            is FpgaPlanResult.Failure -> actionNotices = result.diagnostics.map { it.notice }
        }
    }

    LazyColumn(modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text("fpga_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(text("fpga_setup_intro"))
                if (onPickBoard != null) {
                    OutlinedButton(onClick = { onPickBoard(::importXml) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_import_board_file"))
                    }
                }
                TextButton(onClick = { showXml = !showXml }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showXml) text("fpga_hide_board_xml") else text("fpga_paste_board_xml"))
                }
                if (showXml) {
                    OutlinedTextField(boardXml, { boardXml = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                        label = { Text(text("fpga_board_xml_label")) },
                        minLines = 3, maxLines = 7, isError = importFailed)
                    Button(enabled = boardXml.isNotBlank(), onClick = { importXml(boardXml) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_read_board"))
                    }
                }
                importNotices.forEach { notice ->
                    Text(text(notice), color = if (importFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (currentBoard != null) {
                    Text(currentBoard.name, style = MaterialTheme.typography.titleMedium)
                    Text("${currentBoard.device.vendor} · ${currentBoard.device.family} · ${currentBoard.device.part} · ${currentBoard.device.packageName}")
                    Text(text.plural("fpga_board_pins", currentBoard.pins.size.toLong()))
                    currentBoard.clock?.let { clock ->
                        Text(text("fpga_board_clock", "pin" to clock.location, "frequency" to clock.frequencyHz))
                    }
                    val flows = boardFlows(currentBoard)
                    if (flows.isEmpty()) Text(text("fpga_vendor_unavailable"), color = MaterialTheme.colorScheme.error)
                    else FpgaChoice(text("fpga_flow"), currentFlow?.let { flowName(it, text) }.orEmpty(),
                        flows.map { it to flowName(it, text) }, { flow = it })
                }
                HorizontalDivider()
                if (design == null) {
                    Text(text("fpga_no_hdl"))
                } else {
                    Text("${design.topLevelName} · ${design.language}", style = MaterialTheme.typography.titleMedium)
                    Text(text.plural("fpga_physical_signals", design.ports.sumOf { it.width.toLong() }))
                }
            }
        }
        if (currentBoard != null) {
            itemsIndexed(signals) { _, (port, bit) ->
                val signal = FpgaSignalBit(port.name, bit)
                FpgaSignalMapping(port, bit, bindings[signal], currentBoard, text,
                    clockInput = clocks[port.name]?.takeIf { bit == 0 },
                    onClockChange = { clock ->
                        clocks = clocks + (port.name to clock)
                        if (clock.enabled && bindings[signal] is FpgaPinBinding.Constant) bindings = bindings - signal
                    },
                    onUseBoardClock = {
                        currentBoard.clock?.let { clock ->
                            clocks = clocks + (port.name to FpgaClockInput(true, clock.frequencyHz.toString()))
                            bindings = bindings + (signal to FpgaPinBinding.Physical(clock.location))
                        }
                    },
                    onBinding = { binding ->
                        bindings = if (binding == null) bindings - signal else bindings + (signal to binding)
                    })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (design != null && currentBoard != null && currentFlow != null) {
                    OutlinedButton(onClick = { validated = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_validate_mapping"))
                    }
                    Text(if (ready) text("fpga_mapping_valid")
                        else text.plural("fpga_mapping_problems", errors.toLong()),
                        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (validated) diagnostics.forEach { diagnostic ->
                        Text(text("fpga_diagnostic", "code" to diagnostic.code, "detail" to diagnostic.notice),
                            color = if (diagnostic.severity == FpgaDiagnosticSeverity.ERROR) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onExport != null) Button(enabled = ready, onClick = { generate(onExport) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_export_project"))
                    }
                    if (onSynthesize != null && currentFlow in setOf(FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40)) {
                        Button(enabled = ready, onClick = { generate(onSynthesize) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_prepare_synthesis"))
                        }
                    }
                }
                actionNotices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
                Text(text("fpga_synthesis_intro"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FpgaSignalMapping(
    port: FpgaDesignPort,
    bit: Int,
    binding: FpgaPinBinding?,
    board: FpgaBoardDefinition,
    text: Localizer,
    clockInput: FpgaClockInput?,
    onClockChange: (FpgaClockInput) -> Unit,
    onUseBoardClock: () -> Unit,
    onBinding: (FpgaPinBinding?) -> Unit,
) {
    val title = if (port.width == 1) port.name else "${port.name}[$bit]"
    val direction = when (port.direction) {
        FpgaPinDirection.INPUT -> text("fpga_direction_input")
        FpgaPinDirection.OUTPUT -> text("fpga_direction_output")
        FpgaPinDirection.INOUT -> text("fpga_direction_inout")
    }
    val options = buildList {
        add("unmapped" to text("fpga_unmapped"))
        add("physical" to text("fpga_physical_pin"))
        if (port.direction == FpgaPinDirection.INPUT && port.clockFrequencyHz == null) {
            add("zero" to text("fpga_constant_zero"))
            add("one" to text("fpga_constant_one"))
        }
        if (port.direction != FpgaPinDirection.INPUT) add("open" to text("fpga_open"))
    }
    val mode = when (binding) {
        null -> "unmapped"
        is FpgaPinBinding.Physical -> "physical"
        is FpgaPinBinding.Constant -> if (binding.high) "one" else "zero"
        FpgaPinBinding.Open -> "open"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title · $direction", style = MaterialTheme.typography.titleSmall)
        if (clockInput != null) {
            FpgaChoice(text("fpga_clock_role"), text(if (clockInput.enabled) "fpga_clock_role_clock" else "fpga_clock_role_signal"),
                listOf(false to text("fpga_clock_role_signal"), true to text("fpga_clock_role_clock")),
                { onClockChange(clockInput.copy(enabled = it)) })
            if (clockInput.enabled) {
                OutlinedTextField(clockInput.frequency, { onClockChange(clockInput.copy(frequency = it)) },
                    label = { Text(text("fpga_clock_frequency_input")) },
                    supportingText = { Text(text(if (clockInput.valid) "fpga_clock_frequency_help" else "fpga_clock_frequency_invalid")) },
                    isError = !clockInput.valid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
            board.clock?.let { clock ->
                OutlinedButton(onClick = onUseBoardClock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_clock_use_board", "pin" to clock.location, "frequency" to clock.frequencyHz))
                }
            }
        }
        port.clockFrequencyHz?.takeIf { clockInput?.valid != false }?.let {
            Text(text("fpga_signal_clock", "frequency" to it), style = MaterialTheme.typography.bodySmall)
        }
        FpgaChoice(text("fpga_connection"), options.first { it.first == mode }.second, options, { choice ->
            onBinding(when (choice) {
                "physical" -> FpgaPinBinding.Physical("")
                "zero" -> FpgaPinBinding.Constant(false)
                "one" -> FpgaPinBinding.Constant(true)
                "open" -> FpgaPinBinding.Open
                else -> null
            })
        })
        if (binding is FpgaPinBinding.Physical) {
            val clock = board.clock
            if (port.clockFrequencyHz != null && clock != null) {
                OutlinedButton(onClick = { onBinding(FpgaPinBinding.Physical(clock.location)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_use_clock", "pin" to clock.location))
                }
                Text(text("fpga_selected_pin", "pin" to binding.location.ifBlank { "?" }))
            } else {
                val pins = board.pins.filter { it.direction == port.direction || it.direction == FpgaPinDirection.INOUT }
                FpgaPhysicalPinPicker(binding, pins, board, text, onBinding)
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FpgaPhysicalPinPicker(
    binding: FpgaPinBinding.Physical,
    pins: List<FpgaBoardPin>,
    board: FpgaBoardDefinition,
    text: Localizer,
    onBinding: (FpgaPinBinding) -> Unit,
) {
    var showPins by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val selected = pins.firstOrNull { it.location.equals(binding.location, true) &&
        (binding.resourceId == null || it.resourceId == binding.resourceId) }
    val matching = pins.filter { pin ->
        val resource = board.resources.first { it.id == pin.resourceId }
        filter.isBlank() || "${pin.location} ${resource.label} ${resource.type}".contains(filter, true)
    }
    OutlinedTextField(binding.location, { location -> onBinding(binding.copy(location = location, resourceId = null)) },
        modifier = Modifier.fillMaxWidth(), label = { Text(text("fpga_physical_pin")) }, singleLine = true)
    Column {
        OutlinedButton(onClick = { filter = ""; showPins = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(text.plural("fpga_choose_compatible_pin", pins.size.toLong()))
        }
        DropdownMenu(expanded = showPins, onDismissRequest = { showPins = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            OutlinedTextField(filter, { filter = it }, modifier = Modifier.padding(8.dp),
                label = { Text(text("fpga_filter_pins")) }, singleLine = true)
            matching.take(60).forEach { pin ->
                val resource = board.resources.first { it.id == pin.resourceId }
                DropdownMenuItem(text = { Text("${pin.location} · ${resource.label.ifBlank { resource.type }} [${pin.index}]") },
                    onClick = { onBinding(FpgaPinBinding.Physical(pin.location, pin.resourceId)); showPins = false },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
            if (matching.size > 60) Text(text("fpga_refine_pin_filter"), modifier = Modifier.padding(12.dp))
            if (matching.isEmpty()) Text(text("fpga_no_compatible_pin"), modifier = Modifier.padding(12.dp))
        }
    }
    selected?.let { pin ->
        val electrical = pin.electrical
        val polarity = if (electrical.activeLow) text("fpga_active_low") else text("fpga_active_high")
        Text(listOfNotNull(electrical.ioStandard, polarity,
            electrical.driveMilliamps?.let { "$it mA" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> FpgaChoice(label: String, selected: String, choices: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    val text = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(text("fpga_choice_label", "label" to label, "selection" to selected))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onChoose(value); expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

private fun boardFlows(board: FpgaBoardDefinition): List<FpgaFlow> = when (board.device.vendor) {
    FpgaVendor.ALTERA -> listOf(FpgaFlow.QUARTUS)
    FpgaVendor.XILINX -> listOf(FpgaFlow.ISE)
    FpgaVendor.VIVADO -> listOf(FpgaFlow.VIVADO)
    FpgaVendor.OPEN_FPGA -> if (board.device.family.contains("ice", true)) listOf(FpgaFlow.OPEN_ICE40, FpgaFlow.OPEN_ECP5)
        else listOf(FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40)
    FpgaVendor.UNKNOWN -> emptyList()
}

private fun flowName(flow: FpgaFlow, text: Localizer): String = when (flow) {
    FpgaFlow.OPEN_ECP5 -> text("fpga_ecp5_flow")
    FpgaFlow.OPEN_ICE40 -> text("fpga_ice40_flow")
    FpgaFlow.VIVADO -> text("fpga_vivado_export")
    FpgaFlow.ISE -> text("fpga_ise_export")
    FpgaFlow.QUARTUS -> text("fpga_quartus_export")
}
