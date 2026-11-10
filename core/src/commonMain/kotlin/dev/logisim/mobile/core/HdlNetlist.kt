package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class HdlSourceFormat { VERILOG, SYSTEM_VERILOG, BLIF }
enum class HdlUndefinedPolicy { REJECT, ZERO, ONE }

private val hdlPathSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

data class HdlInputFile(val name: String, val text: String) {
    init {
        requireLocalized(name.length <= 255 && name.split('/').all(hdlPathSegment::matches)) {
            message(Messages.HdlImportInputName)
        }
        requireLocalized(text.encodeToByteArray(throwOnInvalidSequence = true).size <= 128 * 1024) { message(Messages.HdlImportSourceSize) }
        requireLocalized(text.all { it.code >= 32 || it in "\t\r\n" }) { message(Messages.HdlImportSourceControlCharacters) }
    }
}

sealed interface HdlBit {
    data class Net(val id: Int) : HdlBit { init { requireLocalized(id >= 0) { message(Messages.HdlImportNetIdentifierNonnegative) } } }
    data class Constant(val value: Char) : HdlBit { init { requireLocalized(value in "01xz") { message(Messages.HdlImportConstantInvalid) } } }
}

data class HdlNetlistPort(val name: String, val direction: PortDirection, val bits: List<HdlBit>)
data class HdlNetlistCell(
    val name: String,
    val type: String,
    val ports: Map<String, List<HdlBit>>,
    val parameters: Map<String, String> = emptyMap(),
)
data class HdlNetlistModule(
    val name: String,
    val ports: List<HdlNetlistPort>,
    val cells: List<HdlNetlistCell>,
    val attributes: Map<String, String> = emptyMap(),
)
data class HdlNetlist(val top: String, val modules: List<HdlNetlistModule>, val creator: String)

data class HdlImportDiagnostic(
    val code: String,
    val notice: LocalizedText,
    val module: String? = null,
    val cell: String? = null,
) {
    /** Provider and tool output remains literal data. Internal diagnostics use notices. */
    constructor(code: String, message: String, module: String? = null, cell: String? = null) :
        this(code, LocalizedText.literal(message), module, cell)

    val message: String get() = notice.resolve(AppLanguage.ENGLISH)
}

sealed interface HdlNetlistResult {
    data class Success(val netlist: HdlNetlist) : HdlNetlistResult
    data class Failure(val diagnostics: List<HdlImportDiagnostic>) : HdlNetlistResult
}

/** Reads Yosys' bit-level JSON after proc/memory/techmap/dffunmap/simplemap normalization. */
object YosysNetlistCodec {
    fun read(json: String, requestedTop: String? = null): HdlNetlistResult = try {
        val root = HdlJsonParser(json).parse().objectValues()
        val rawModules = root["modules"]?.objectValues()
            ?: throw LocalizedArgumentException(message(Messages.HdlImportModulesMissing))
        requireLocalized(rawModules.size in 1..512) { message(Messages.HdlImportModulesLimit) }
        var cellCount = 0
        val modules = rawModules.map { (name, value) ->
            val module = value.objectValues()
            val attributes = strings(module["attributes"])
            requireLocalized(attributes["blackbox"]?.any { it == '1' } != true) { message(Messages.HdlImportBlackBox, "module" to name) }
            val ports = module["ports"]?.objectValues().orEmpty().map { (portName, portValue) ->
                val port = portValue.objectValues()
                val direction = when (port["direction"]?.stringValue()) {
                    "input" -> PortDirection.INPUT
                    "output" -> PortDirection.OUTPUT
                    "inout" -> PortDirection.INOUT
                    else -> throw LocalizedArgumentException(message(Messages.HdlImportPortDirection, "module" to name, "port" to portName))
                }
                HdlNetlistPort(portName, direction, bits(port["bits"]))
            }
            requireLocalized(ports.size <= 256) { message(Messages.HdlImportPortsLimit, "module" to name) }
            val cells = module["cells"]?.objectValues().orEmpty().mapNotNull { (cellName, cellValue) ->
                requireLocalized(++cellCount <= 16_384) { message(Messages.HdlImportCellsLimit) }
                val cell = cellValue.objectValues()
                val type = cell["type"]?.stringValue()
                    ?: throw LocalizedArgumentException(message(Messages.HdlImportCellTypeMissing, "module" to name, "cell" to cellName))
                if (type == "\$scopeinfo") null else HdlNetlistCell(
                    cellName, type,
                    cell["connections"]?.objectValues().orEmpty().mapValues { bits(it.value, allowEmpty = type == HDL_MEMORY_CELL) },
                    strings(cell["parameters"]),
                )
            }
            for ((netName, netValue) in module["netnames"]?.objectValues().orEmpty()) {
                val initial = strings(netValue.objectValues()["attributes"])["init"]
                requireLocalized(initial == null || initial.all { it == '0' || it == 'x' }) {
                    message(Messages.HdlImportNonzeroInitialization, "module" to name, "net" to netName)
                }
            }
            HdlNetlistModule(name, ports, cells, attributes)
        }
        val names = modules.map { it.name }.toSet()
        val top = requestedTop?.takeIf { it.isNotBlank() } ?: modules.singleOrNull {
            it.attributes["top"]?.any { c -> c == '1' } == true
        }?.name ?: run {
            val used = modules.flatMap { it.cells }.map { it.type }.toSet()
            modules.singleOrNull { it.name !in used }?.name
                ?: throw LocalizedArgumentException(message(Messages.HdlImportTopUnique))
        }
        requireLocalized(top in names) { message(Messages.HdlImportTopMissing, "module" to top) }
        val byName = modules.associateBy { it.name }
        val visited = mutableSetOf<String>()
        fun visit(name: String, ancestry: Set<String>) {
            requireLocalized(name !in ancestry) { message(Messages.HdlImportRecursiveHierarchy, "module" to name) }
            if (!visited.add(name)) return
            byName.getValue(name).cells.filter { it.type in names }.forEach { visit(it.type, ancestry + name) }
        }
        modules.forEach { visit(it.name, emptySet()) }
        HdlNetlistResult.Success(HdlNetlist(top, modules, root["creator"]?.stringValue().orEmpty()))
    } catch (error: IllegalArgumentException) {
        HdlNetlistResult.Failure(listOf(HdlImportDiagnostic("invalid-netlist", error.localizedDescription(message(Messages.HdlImportNetlistInvalid)))))
    }

    private fun strings(value: HdlJsonValue?): Map<String, String> =
        value?.objectValues().orEmpty().mapValues { it.value.stringValue() }

    private fun bits(value: HdlJsonValue?, allowEmpty: Boolean = false): List<HdlBit> {
        val values = value?.arrayValues() ?: throw LocalizedArgumentException(message(Messages.HdlImportConnectionMissing))
        requireLocalized(values.size <= 64 && (allowEmpty || values.isNotEmpty())) { message(Messages.HdlImportConnectionWidth) }
        return values.map {
            when (it) {
                is HdlJsonValue.Number -> HdlBit.Net(it.text.toIntOrNull()?.takeIf { id -> id >= 0 }
                    ?: throw LocalizedArgumentException(message(Messages.HdlImportNetIdentifierInvalid)))
                is HdlJsonValue.StringValue -> HdlBit.Constant(it.value.singleOrNull()?.lowercaseChar()
                    ?: throw LocalizedArgumentException(message(Messages.HdlImportConstantInvalid)))
                else -> throw LocalizedArgumentException(message(Messages.HdlImportConnectionInvalid))
            }
        }
    }
}
