package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class FpgaMapping(
    val bindings: Map<FpgaSignalBit, FpgaPinBinding>,
    val clocks: Map<String, Long>,
    val clockScales: Map<String, FpgaClockScale> = emptyMap(),
)

sealed interface FpgaMappingResult {
    data class Loaded(val mapping: FpgaMapping) : FpgaMappingResult
    data class Saved(val document: CircuitDocument) : FpgaMappingResult
    data class Failure(val notices: List<LocalizedText>) : FpgaMappingResult
}

/** Evolution stores one boardmap per board in a circuit. Its older standalone XML is input-only. */
class FpgaMappingCodec(
    private val design: FpgaDesignArtifact,
    private val circuit: CircuitDocument,
    hdlPorts: List<HdlExportPort>,
    /** Port name to component ID of root Clocks fed by the tick generator. Their old entries are skipped. */
    private val skippedPorts: Map<String, String> = emptyMap(),
) {
    private val keys = fpgaMappingKeys(circuit, hdlPorts.associate { it.name to it.sourceComponentId } + skippedPorts)

    fun loadSaved(board: FpgaBoardDefinition, flow: FpgaFlow): FpgaMappingResult = attempt {
        read(fpgaMappingRoot(circuit, board, null), board, flow)
    }

    fun import(xml: String, board: FpgaBoardDefinition, flow: FpgaFlow): FpgaMappingResult = attempt {
        read(fpgaMappingRoot(circuit, board, xml), board, flow)
    }

    fun save(board: FpgaBoardDefinition, flow: FpgaFlow, mapping: FpgaMapping): FpgaMappingResult = attempt {
        validate(board, flow, mapping)
        val xml = buildString {
            append("<boardmap")
            attr("boardname", board.name)
            attr("mobilepart", board.device.part)
            attr("mobilepackage", board.device.packageName)
            append(">\n")
            for (port in design.ports) {
                val label = keys[port.name]
                val evolutionKey = label?.takeIf { it.isNotEmpty() && keys.values.count { key -> key == it } == 1 }
                append("<mc")
                attr("key", "/${evolutionKey ?: port.name}")
                if (evolutionKey == null) attr("mobileport", port.name)
                val bits = (0 until port.width).map { mapping.bindings[FpgaSignalBit(port.name, it)] }
                attr("mobilepins", bits.joinToString(",") { (it as? FpgaPinBinding.Physical)?.location.orEmpty() })
                if (bits.any { it is FpgaPinBinding.Display }) {
                    attr("mobilescan", bits.joinToString(",") { bit ->
                        (bit as? FpgaPinBinding.Display)?.let { displayCoordinate(it, board).second }.orEmpty()
                    })
                }
                val clock = mapping.clocks[port.name]
                mapping.clockScales[port.name]?.let { scale ->
                    attr("mobileclockscale", "${scale.multiplier},${scale.divider}")
                }
                val clockPin = bits.singleOrNull() as? FpgaPinBinding.Physical
                if (clock != null || clockPin?.location?.equals(board.clock?.location, true) == true)
                    attr("mobileclockhz", (clock ?: 0).toString())
                if (clock != null && clockPin != null && board.pins.none { it.location.equals(clockPin.location, true) }) {
                    attr("mobileclockpin", clockPin.location)
                    attr("pmap", "u")
                } else attr("pmap", bits.joinToString(",") { encodeBit(it, board) })
                append("/>\n")
            }
            append("</boardmap>")
        }
        val retained = circuit.extraXml.filterNot {
            val element = CircXmlParser(it).parse()
            element.name == "boardmap" && element.attributes["boardname"] == board.name
        }
        FpgaMappingResult.Saved(circuit.copy(extraXml = retained + xml))
    }

    private fun read(root: CircXmlElement, board: FpgaBoardDefinition, flow: FpgaFlow): FpgaMappingResult {
        requireLocalized(root.attributes["mobilepart"]?.let { it == board.device.part } != false &&
            root.attributes["mobilepackage"]?.let { it == board.device.packageName } != false
        ) { message("fpga_map_wrong_board", "board" to board.name) }
        val bindings = linkedMapOf<FpgaSignalBit, FpgaPinBinding>()
        val clocks = design.ports.mapNotNull { port -> port.clockFrequencyHz?.let { port.name to it } }.toMap().toMutableMap()
        val clockScales = design.ports.mapNotNull { port -> port.clockScale?.let { port.name to it } }.toMap().toMutableMap()
        val seen = mutableSetOf<FpgaSignalBit>()
        for (entry in root.children) {
            val legacy = entry.name.startsWith("MAPPEDCOMPONENT")
            if (!legacy && entry.name != "mc") continue
            val attr = entry.attributes
            val rawKey = attr[if (legacy) "Key" else "key"] ?: invalid()
            val key = if (legacy) rawKey.substringAfterLast(' ').substringBefore('#') else rawKey
            val name = attr["mobileport"]?.takeIf { key == "/$it" }
                ?: keys.entries.singleOrNull { it.value.isNotEmpty() && key.removePrefix("/") == it.value }?.key
                ?: throw LocalizedArgumentException(message("fpga_map_wrong_signal", "signal" to key))
            if (name in skippedPorts && design.ports.none { it.name == name }) continue
            val port = design.ports.singleOrNull { it.name == name } ?: invalid()
            val bits = if (legacy) {
                val rectangle = listOf("LocationX", "LocationY", "Width", "Height").map { attr[it]?.toIntOrNull() ?: invalid() }
                val io = resource(board, rectangle[0], rectangle[1], rectangle[2], rectangle[3])
                val suffix = rawKey.substringAfter('#', "")
                if (suffix.isEmpty()) whole(io, port).withIndex().toList()
                else {
                    val index = suffix.removePrefix("Pin").toIntOrNull()?.takeIf { it in 0 until port.width } ?: invalid()
                    listOf(IndexedValue(index, decodeResourcePin(io, 0, allowLogicalDisplay = true)))
                }
            } else {
                val decoded = decodeEntry(attr, port, board)
                attr["mobilepins"]?.let { expected ->
                    val actual = decoded.joinToString(",") { (it as? FpgaPinBinding.Physical)?.location.orEmpty() }
                    if (!expected.equals(actual, ignoreCase = true)) incompatiblePin()
                }
                decoded.withIndex().toList()
            }
            val explicitClock = attr["mobileclockhz"]?.let { it.toLongOrNull()?.takeIf { hz -> hz >= 0 } ?: invalid() }
            val boardClock = board.clock
            val singlePin = bits.singleOrNull()?.value as? FpgaPinBinding.Physical
            if (explicitClock == 0L) clocks.remove(name)
            else if (explicitClock != null) clocks[name] = explicitClock
            else if (port.direction == FpgaPinDirection.INPUT && port.width == 1 && boardClock != null &&
                singlePin?.location?.equals(boardClock.location, true) == true
            ) clocks[name] = boardClock.frequencyHz
            val scale = attr["mobileclockscale"]
            if (scale == null) clockScales.remove(name)
            else {
                val factors = scale.split(',')
                if (factors.size != 2) invalid()
                clockScales[name] = FpgaClockScale.parse(factors[0], factors[1]) ?: invalid()
            }
            for ((index, binding) in bits) {
                val signal = FpgaSignalBit(name, index)
                if (!seen.add(signal)) invalid()
                if (binding != null) bindings[signal] = binding
            }
        }
        return FpgaMappingResult.Loaded(FpgaMapping(bindings, clocks, clockScales).also { validate(board, flow, it) })
    }

    private fun decodeEntry(attr: Map<String, String>, port: FpgaDesignPort, board: FpgaBoardDefinition): List<FpgaPinBinding?> {
        val width = port.width
        val scanPins = attr["mobilescan"]?.split(',')
        if (scanPins != null && (scanPins.size != width || "pmap" !in attr ||
                attr.keys.any { it in setOf("mobileclockpin", "open", "vconst") })) invalid()
        return when {
            "mobileclockpin" in attr -> {
                if (width != 1 || "mobileclockhz" !in attr || attr["pmap"] != "u") invalid()
                listOf(FpgaPinBinding.Physical(attr.getValue("mobileclockpin")))
            }
            "open" in attr -> List(width) { FpgaPinBinding.Open }
            "vconst" in attr -> {
                val raw = attr.getValue("vconst")
                val value = raw.toLongOrNull()?.toULong() ?: raw.toULongOrNull() ?: invalid()
                if (width > 64 || (width < 64 && (value shr width) != 0uL)) invalid()
                List(width) { FpgaPinBinding.Constant(((value shr it) and 1uL) != 0uL) }
            }
            "pmap" in attr -> {
                val pins = attr.getValue("pmap").split(',')
                if (pins.size != width) throw LocalizedArgumentException(message("fpga_map_wrong_signal", "signal" to port.name))
                pins.mapIndexed { index, value ->
                    val scan = scanPins?.get(index)?.takeIf { it.isNotEmpty() }
                    if (scan != null) {
                        val (x, y, channel) = coordinates(scan, '_', 3)
                        val display = resource(board, x, y).scannedDisplay() ?: incompatiblePin()
                        val rgbPixel = display.isRgb && value == scan
                        if (value != (if (!display.isRgb || rgbPixel) scan else "u")) incompatiblePin()
                        return@mapIndexed FpgaPinBinding.Display(display.resource.id, channel, rgbPixel)
                    }
                    when (value) {
                        "u" -> null
                        "open" -> FpgaPinBinding.Open
                        "0", "1" -> FpgaPinBinding.Constant(value == "1")
                        else -> {
                            val (x, y, target) = coordinates(value, '_', 3)
                            decodeResourcePin(resource(board, x, y), target, allowLogicalDisplay = "mobilepins" !in attr)
                        }
                    }
                }
            }
            "map" in attr -> {
                val (x, y) = coordinates(attr.getValue("map"), ',', 2)
                whole(resource(board, x, y), port)
            }
            "valx" in attr -> {
                val values = listOf("valx", "valy", "valw", "valh").map { attr[it]?.toIntOrNull() ?: invalid() }
                whole(resource(board, values[0], values[1], values[2], values[3]), port)
            }
            else -> invalid()
        }
    }

    private fun whole(resource: FpgaBoardResource, port: FpgaDesignPort): List<FpgaPinBinding> {
        resource.scannedDisplay()?.let {
            if (port.direction != FpgaPinDirection.OUTPUT) incompatiblePin()
            return List(port.width) { channel -> FpgaPinBinding.Display(resource.id, channel, it.isRgb) }
        }
        val direct = resource.pins.filter { it.direction == port.direction }
        val bidirectional = resource.pins.filter { it.direction == FpgaPinDirection.INOUT }
        return List(port.width) { index ->
            val pin = direct.getOrNull(index) ?: bidirectional.getOrNull(index) ?: incompatiblePin()
            FpgaPinBinding.Physical(pin.location, pin.resourceId)
        }
    }

    private fun decodeResourcePin(resource: FpgaBoardResource, index: Int, allowLogicalDisplay: Boolean): FpgaPinBinding {
        if (allowLogicalDisplay) resource.scannedDisplay()?.let {
            return FpgaPinBinding.Display(resource.id, index, it.isRgb)
        }
        val pin = resource.pins.singleOrNull { it.index == index } ?: incompatiblePin()
        return FpgaPinBinding.Physical(pin.location, pin.resourceId)
    }

    private fun displayCoordinate(binding: FpgaPinBinding.Display, board: FpgaBoardDefinition): Pair<FpgaScanDisplay, String> {
        val resource = board.resources.singleOrNull { it.id == binding.resourceId } ?: incompatiblePin()
        val display = resource.scannedDisplay() ?: incompatiblePin()
        val rectangle = resource.rectangle ?: incompatiblePin()
        return display to "${rectangle.x}_${rectangle.y}_${binding.channel}"
    }

    private fun encodeBit(binding: FpgaPinBinding?, board: FpgaBoardDefinition): String = when (binding) {
        null -> "u"
        FpgaPinBinding.Open -> "open"
        is FpgaPinBinding.Constant -> if (binding.high) "1" else "0"
        is FpgaPinBinding.Display -> {
            val (display, coordinate) = displayCoordinate(binding, board)
            if (!display.isRgb || binding.rgbPixel) coordinate else "u"
        }
        is FpgaPinBinding.Physical -> {
            if (binding.invert != null || binding.electrical != null) incompatiblePin()
            val pin = board.pins.singleOrNull { it.location.equals(binding.location, ignoreCase = true) &&
                (binding.resourceId == null || it.resourceId == binding.resourceId) } ?: incompatiblePin()
            val rectangle = board.resources.single { it.id == pin.resourceId }.rectangle ?: incompatiblePin()
            "${rectangle.x}_${rectangle.y}_${pin.index}"
        }
    }

    private fun validate(board: FpgaBoardDefinition, flow: FpgaFlow, mapping: FpgaMapping) {
        if (mapping.clockScales.keys.any { name -> design.ports.none { it.name == name } }) invalid()
        val clocked = design.copy(ports = design.ports.map {
            it.copy(clockFrequencyHz = mapping.clocks[it.name], clockScale = mapping.clockScales[it.name])
        })
        val errors = FpgaDesignRules.check(clocked, board, mapping.bindings, flow)
            .filter { it.severity == FpgaDiagnosticSeverity.ERROR && it.code != "unmapped-port" }
        if (errors.isNotEmpty()) throw LocalizedArgumentException(LocalizedText.join(errors.map { it.notice }))
    }

    private fun resource(board: FpgaBoardDefinition, x: Int, y: Int, width: Int? = null, height: Int? = null): FpgaBoardResource =
        board.resources.singleOrNull { resource ->
            val rect = resource.rectangle ?: return@singleOrNull false
            if (width == null) rect.x == x && rect.y == y
            else rect == FpgaRectangle(x, y, width, height ?: invalid())
        } ?: incompatiblePin()

    private fun coordinates(raw: String, delimiter: Char, size: Int): List<Int> {
        val values = raw.split(delimiter)
        if (values.size != size) invalid()
        return values.map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: invalid() }
    }

    private fun invalid(): Nothing = throw LocalizedArgumentException(message("fpga_map_invalid"))
    private fun incompatiblePin(): Nothing = throw LocalizedArgumentException(message("fpga_map_wrong_pin"))

    private inline fun attempt(operation: () -> FpgaMappingResult): FpgaMappingResult = try {
        operation()
    } catch (error: LocalizedArgumentException) {
        FpgaMappingResult.Failure(listOf(error.text))
    }

    private fun StringBuilder.attr(name: String, value: String) {
        append(' ').append(name).append("=\"")
            .append(value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")).append('"')
    }
}

private fun fpgaMappingKeys(circuit: CircuitDocument, portComponents: Map<String, String>): Map<String, String> {
    val labels = circuit.components.associate { it.id to it.label }
    return portComponents.mapValues { (_, component) ->
        val label = labels[component].orEmpty().replace(' ', '_').replace('-', '_')
        if (label.firstOrNull()?.let { it in '0'..'9' } == true) "L_$label" else label
    }
}

private fun fpgaMappingRoot(circuit: CircuitDocument, board: FpgaBoardDefinition, xml: String?): CircXmlElement {
    fun findBoard(children: List<CircXmlElement>): CircXmlElement =
        children.singleOrNull { it.name == "boardmap" && it.attributes["boardname"] == board.name }
            ?: throw LocalizedArgumentException(message("fpga_map_wrong_board", "board" to board.name))

    if (xml == null) return findBoard(circuit.extraXml.map { CircXmlParser(it).parse() })
    val root = CircXmlParser(xml).parse()
    return when (root.name) {
        "project" -> {
            val source = root.children.singleOrNull { it.name == "circuit" && it.attributes["name"] == circuit.name }
                ?: throw LocalizedArgumentException(message("fpga_map_wrong_circuit", "circuit" to circuit.name))
            findBoard(source.children)
        }
        "LogisimGoesFPGABoardMapInformation" -> {
            val header = root.children.singleOrNull { it.name == "GlobalMapInformation" }
                ?: throw LocalizedArgumentException(message("fpga_map_invalid"))
            requireLocalized(header.attributes["ToplevelCircuitName"] == circuit.name) {
                message("fpga_map_wrong_circuit", "circuit" to circuit.name)
            }
            requireLocalized(header.attributes["BoardName"] == board.name) {
                message("fpga_map_wrong_board", "board" to board.name)
            }
            root
        }
        else -> throw LocalizedArgumentException(message("fpga_map_invalid"))
    }
}

internal fun mappedFpgaClockPorts(
    circuit: CircuitDocument,
    board: FpgaBoardDefinition,
    xml: String?,
    rootClockPorts: Map<String, String>,
): Set<String> {
    if (rootClockPorts.isEmpty()) return emptySet()
    val keys = fpgaMappingKeys(circuit, rootClockPorts.entries.associate { (component, port) -> port to component })
    return buildSet {
        for (entry in fpgaMappingRoot(circuit, board, xml).children) {
            val legacy = entry.name.startsWith("MAPPEDCOMPONENT")
            if (!legacy && entry.name != "mc") continue
            val attributes = entry.attributes
            val rawKey = attributes[if (legacy) "Key" else "key"] ?: continue
            val key = if (legacy) rawKey.substringAfterLast(' ').substringBefore('#') else rawKey
            val namedPort = attributes["mobileport"]?.takeIf { key == "/$it" }
            val ports = if (namedPort != null) listOf(namedPort).filter { it in keys }
                else keys.filterValues { it.isNotEmpty() && it == key.removePrefix("/") }.keys
            val placeholder = !legacy && attributes["pmap"] == "u" &&
                attributes.keys.none { it in setOf("open", "vconst", "mobileclockpin", "mobileclockscale") } &&
                attributes["mobilepins"].isNullOrEmpty() && attributes["mobilescan"].isNullOrEmpty() &&
                (attributes["mobileclockhz"] ?: "0").toLongOrNull() == 0L
            if (!placeholder) addAll(ports)
        }
    }
}
