package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val shapeTags = setOf("rect", "ellipse", "line", "polyline", "polygon", "path", "text", "image")
private val imageSourceAttributes = setOf("href", "xlink:href")

data class SymbolMarkerSize(val width: Double, val height: Double)

data class SymbolAnchor(val position: GridPoint, val facing: String = "east", val markerSize: SymbolMarkerSize? = null) {
    init { require(facing in setOf("east", "north", "west", "south")) }
}

data class SymbolPort(val pinId: String, val position: GridPoint, val markerSize: SymbolMarkerSize? = null)

/** Unknown SVG objects remain opaque, so editing supported shapes cannot erase them. */
sealed interface SymbolObject {
    data class Shape(val id: String, val tag: String, val attributes: Map<String, String>, val text: String = "") : SymbolObject {
        init {
            require(id.isNotBlank() && tag in shapeTags)
            require(attributes.all { (name, value) ->
                value.length <= 100_000 || tag == "image" && name in imageSourceAttributes && value.length <= 2_000_000
            })
            require(text.length <= 100_000)
        }

        internal val imageItem by lazy { SymbolVisuals.imageItem(this) }
    }
    data class Led(
        val id: String,
        val sourceId: String,
        val position: GridPoint,
        val radius: Int = 5,
        val attributes: Map<String, String> = emptyMap(),
    ) : SymbolObject

    data class Opaque(val xml: String) : SymbolObject
}

data class SymbolAppearance(
    val anchor: SymbolAnchor,
    val ports: List<SymbolPort>,
    val objects: List<SymbolObject>,
) {
    init {
        requireLocalized(ports.map { it.pinId }.distinct().size == ports.size) { message("appearance_duplicate_pin") }
        require(objects.filterIsInstance<SymbolObject.Shape>().map { it.id }.distinct().size == objects.filterIsInstance<SymbolObject.Shape>().size)
    }

    fun movePort(pinId: String, to: GridPoint): SymbolAppearance {
        requireLocalized(ports.any { it.pinId == pinId }) { message("appearance_missing_port") }
        return copy(ports = ports.map { if (it.pinId == pinId) it.copy(position = to) else it })
    }

    fun addShape(shape: SymbolObject.Shape): SymbolAppearance = copy(objects = objects + shape)
    fun removeShape(id: String): SymbolAppearance = copy(objects = objects.filterNot { it is SymbolObject.Shape && it.id == id })
    fun editShape(id: String, updates: Map<String, String>, text: String? = null): SymbolAppearance {
        requireLocalized(objects.any { it is SymbolObject.Shape && it.id == id }) { message("appearance_missing_shape") }
        return copy(objects = objects.map {
            if (it is SymbolObject.Shape && it.id == id) it.copy(attributes = it.attributes + updates, text = text ?: it.text) else it
        }).also { it.validateShapes() }
    }

    fun validate(circuit: CircuitDocument) {
        val pins = circuit.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
        requireLocalized(pins.map { it.id }.toSet() == ports.map { it.pinId }.toSet()) { message("appearance_all_pins") }
        requireLocalized(ports.map { it.position }.distinct().size == ports.size) { message("appearance_port_collision") }
        objects.filterIsInstance<SymbolObject.Led>().forEach { it.validate(circuit) }
        validateShapes()
    }

    fun relativePorts(circuit: CircuitDocument): List<ComponentPort> {
        validate(circuit)
        return ports.map { port ->
            val pin = circuit.components.single { it.id == port.pinId }
            ComponentPort(pin.id, canonical(port.position), if (pin.kind == ComponentKind.INPUT) PortDirection.INPUT else PortDirection.OUTPUT, pin.width, pin.label)
        }
    }

    fun relativeBounds(): ComponentBounds {
        val boxes = objects.mapNotNull {
            when (it) {
                is SymbolObject.Shape -> shapeBounds(it)
                is SymbolObject.Led -> ComponentBounds(it.position.x, it.position.y,
                    it.position.x + 2 * it.radius, it.position.y + 2 * it.radius)
                is SymbolObject.Opaque -> null
            }
        }
        val points = boxes.flatMap { listOf(GridPoint(it.left, it.top), GridPoint(it.right, it.bottom), GridPoint(it.left, it.bottom), GridPoint(it.right, it.top)) } + ports.map { it.position }
        val transformed = (points.ifEmpty { listOf(anchor.position) }).map(::canonical)
        return ComponentBounds(transformed.minOf { it.x }, transformed.minOf { it.y }, transformed.maxOf { it.x }, transformed.maxOf { it.y })
    }

    fun relativeTexts(): List<ComponentText> = objects.filterIsInstance<SymbolObject.Shape>().filter { it.tag == "text" }.map {
        ComponentText(it.text, canonical(GridPoint(number(it, "x").toInt(), number(it, "y").toInt())), number(it, "font-size", 12.0).toInt())
    }

    private fun canonical(point: GridPoint): GridPoint = rotatePoint(GridPoint(point.x - anchor.position.x, point.y - anchor.position.y), anchor.facing, "east")

    private fun validateShapes() {
        objects.filterIsInstance<SymbolObject.Shape>().forEach {
            val bounds = shapeBounds(it)
            requireLocalized(bounds.width >= 0 && bounds.height >= 0) { message("appearance_invalid_dimensions") }
            it.attributes["stroke-width"]?.let { raw ->
                requireLocalized(raw.toDoubleOrNull()?.let { value -> value.isFinite() && value in 0.0..10_000.0 } == true) { message("appearance_invalid_dimensions") }
            }
            if (it.tag == "rect") {
                requireLocalized(number(it, "rx", 0.0) >= 0 && number(it, "ry", 0.0) >= 0) { message("appearance_invalid_dimensions") }
            }
            if (it.tag == "path") SymbolVisuals.parsePath(it.attributes["d"].orEmpty())
            for (key in listOf("fill-opacity", "stroke-opacity")) it.attributes[key]?.let { raw ->
                requireLocalized(raw.replace(',', '.').toDoubleOrNull()?.let { value -> value.isFinite() && value in 0.0..1.0 } == true) { message("appearance_invalid_opacity") }
            }
            for (key in listOf("fill", "stroke")) it.attributes[key]?.let { color ->
                requireLocalized(color == "none" || Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(color) || color in setOf("black", "white", "gray", "red", "green", "blue")) { message("appearance_svg_color", "color" to color) }
            }
        }
    }

    companion object {
        fun isCustom(circuit: CircuitDocument): Boolean {
            val stored = storedXml(circuit) != null
            return if ("circuitnamedbox" in circuit.attributes) stored else when (circuit.attributes["appearance"]) {
                null -> stored
                "custom" -> true
                else -> false
            }
        }

        private fun storedXml(circuit: CircuitDocument): String? =
            circuit.extraXml.firstOrNull { runCatching { CircXmlParser(it).parse().name == "appear" }.getOrDefault(false) }

        /** The shape used for the instance, with the Classic renderer handled by its caller. */
        fun rendered(circuit: CircuitDocument): SymbolAppearance? =
            if (isCustom(circuit)) storedXml(circuit)?.let { read(circuit) } else NamedSymbolAppearance.build(circuit)

        fun read(circuit: CircuitDocument): SymbolAppearance {
            val xml = storedXml(circuit) ?: return NamedSymbolAppearance.build(circuit) ?: classic(circuit)
            val root = CircXmlParser(xml).parse()
            val anchor = root.children.singleOrNull { it.name in setOf("circ-anchor", "circ-origin") }
                ?: throw LocalizedArgumentException(message("appearance_unique_anchor"))
            val pins = circuit.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            val ports = root.children.filter { it.name == "circ-port" }.map { node ->
                val values = node.attributes["pin"]?.split(',')?.map { it.trim().toIntOrNull() }
                    ?: throw LocalizedArgumentException(message("appearance_invalid_pin"))
                requireLocalized(values.size == 2 && values.all { it != null }) { message("appearance_invalid_pin") }
                val pin = pins.singleOrNull { it.position == GridPoint(values[0]!!, values[1]!!) }
                    ?: throw LocalizedArgumentException(message("appearance_pin_mismatch"))
                node.attributes["dir"]?.let { direction -> requireLocalized(direction == if (pin.kind == ComponentKind.INPUT) "in" else "out") { message("appearance_pin_direction") } }
                val size = markerSize(node)
                SymbolPort(pin.id, markerPosition(node, size), size)
            }
            val objects = root.children.filter { it.name !in setOf("circ-anchor", "circ-origin", "circ-port") }.mapIndexed { index, node ->
                val validImage = node.name != "image" || (imageGeometry(node.attributes) != null && node.text.isBlank())
                readSymbolLed(node, circuit, node.attributes["id"] ?: "led-$index") ?: if (node.name in shapeTags && validImage && node.children.isEmpty() && !node.rawXml.contains("<!--") && node.attributes.keys.none { it in setOf("transform", "style", "clip-path") } && (node.name != "path" || Regex("[MLQCZ0-9+.,eE\\s-]+").matches(node.attributes["d"].orEmpty()))) {
                    SymbolObject.Shape(node.attributes["id"] ?: "shape-$index", node.name, node.attributes, node.text)
                } else SymbolObject.Opaque(node.rawXml)
            } + directComments(xml).map { SymbolObject.Opaque(it) }
            val anchorSize = markerSize(anchor)
            return SymbolAppearance(SymbolAnchor(markerPosition(anchor, anchorSize), anchor.attributes["facing"] ?: "east", anchorSize), ports, objects).also { it.validate(circuit) }
        }

        fun classic(circuit: CircuitDocument): SymbolAppearance {
            val pins = circuit.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            val edges = listOf("north", "south", "east", "west").associateWith { edge ->
                pins.filter { opposite(it.facing) == edge }.sortedWith(if (edge in setOf("east", "west")) compareBy({ it.position.y }, { it.position.x }) else compareBy({ it.position.x }, { it.position.y }))
            }
            val north = edges.getValue("north").size; val south = edges.getValue("south").size
            val east = edges.getValue("east").size; val west = edges.getValue("west").size
            val vertical = maxOf(north, south); val horizontal = maxOf(east, west)
            fun dimension(count: Int, others: Int) = if (count < 3) 30 else 10 * count + if (others == 0) 0 else 10
            fun offset(count: Int, opposite: Int, others: Int): Int {
                val max = maxOf(count, opposite)
                return (when (max) { 0, 1 -> if (others == 0) 15 else 10; 2 -> 10; else -> if (others == 0) 5 else 10 }) + 10 * ((max - count) / 2)
            }
            val width = dimension(vertical, horizontal); val height = dimension(horizontal, vertical)
            val offsets = mapOf("north" to offset(north, south, horizontal), "south" to offset(south, north, horizontal), "east" to offset(east, west, vertical), "west" to offset(west, east, vertical))
            val anchor = when { east > 0 -> GridPoint(width, offsets.getValue("east")); north > 0 -> GridPoint(offsets.getValue("north"), 0)
                west > 0 -> GridPoint(0, offsets.getValue("west")); south > 0 -> GridPoint(offsets.getValue("south"), height); else -> GridPoint(0, 0) }
            val ports = edges.flatMap { (edge, edgePins) -> edgePins.mapIndexed { index, pin ->
                val along = offsets.getValue(edge) + index * 10
                SymbolPort(pin.id, when (edge) { "north" -> GridPoint(along, 0); "south" -> GridPoint(along, height); "east" -> GridPoint(width, along); else -> GridPoint(0, along) })
            } }
            return SymbolAppearance(SymbolAnchor(anchor), ports, listOf(
                SymbolObject.Shape("notch", "path", mapOf("d" to "M ${(width - 8) / 2} 1 Q ${width / 2} 11 ${(width + 8) / 2} 1", "fill" to "none", "stroke" to "#808080", "stroke-width" to "2")),
                SymbolObject.Shape("body", "rect", mapOf("x" to "0", "y" to "0", "width" to width.toString(), "height" to height.toString(), "fill" to "none", "stroke" to "#000000", "stroke-width" to "2"))))
        }

        fun shapeBounds(shape: SymbolObject.Shape): ComponentBounds {
            val points = when (shape.tag) {
                "image" -> return imageGeometry(shape.attributes) ?: throw LocalizedArgumentException(message("appearance_image_dimensions"))
                "rect" -> {
                    val x = number(shape, "x"); val y = number(shape, "y"); val width = number(shape, "width"); val height = number(shape, "height")
                    require(width >= 0 && height >= 0)
                    listOf(x to y, (x + width) to (y + height))
                }
                "ellipse" -> {
                    val x = number(shape, "cx"); val y = number(shape, "cy"); val rx = number(shape, "rx"); val ry = number(shape, "ry")
                    require(rx >= 0 && ry >= 0)
                    listOf((x - rx) to (y - ry), (x + rx) to (y + ry))
                }
                "line" -> listOf(number(shape, "x1") to number(shape, "y1"), number(shape, "x2") to number(shape, "y2"))
                "text" -> {
                    val font = number(shape, "font-size", 12.0)
                    require(font > 0)
                    val x = number(shape, "x"); val y = number(shape, "y"); val width = font * shape.text.length * 2 / 3
                    val start = when (shape.attributes["text-anchor"]) { "start" -> x; "end" -> x - width; else -> x - width / 2 }
                    val height = font * 1.25
                    val top = when (shape.attributes["dominant-baseline"]) { "top" -> y; "bottom" -> y - height; "alphabetic" -> y - font; else -> y - height / 2 }
                    listOf(start to top, (start + width) to (top + height))
                }
                else -> {
                    val raw = shape.attributes[if (shape.tag == "path") "d" else "points"].orEmpty()
                    val values = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(raw).map { it.value.toDouble() }.toList()
                    requireLocalized(values.isNotEmpty() && values.size % 2 == 0 && values.all { it.isFinite() && it in -1_000_000.0..1_000_000.0 }) { message("appearance_invalid_coordinates") }
                    values.chunked(2).map { it[0] to it[1] }
                }
            }
            return ComponentBounds(floor(points.minOf { it.first }).toInt(), floor(points.minOf { it.second }).toInt(), ceil(points.maxOf { it.first }).toInt(), ceil(points.maxOf { it.second }).toInt())
        }

        private fun imageGeometry(attributes: Map<String, String>): ComponentBounds? {
            val x = attributes["x"]?.toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 } ?: return null
            val y = attributes["y"]?.toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 } ?: return null
            val width = attributes["width"]?.toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
            val height = attributes["height"]?.toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
            return ComponentBounds(x, y, x + width, y + height)
        }

        internal fun number(shape: SymbolObject.Shape, key: String, default: Double? = null): Double =
            (shape.attributes[key]?.toDoubleOrNull() ?: default ?: throw LocalizedArgumentException(message("appearance_invalid_coordinate", "key" to key))).also {
                requireLocalized(it.isFinite() && it in -1_000_000.0..1_000_000.0) { message("appearance_coordinate_limit", "key" to key) }
            }

        internal fun rotatePoint(point: GridPoint, from: String, to: String): GridPoint {
            val directions = listOf("east", "south", "west", "north")
            var result = point
            repeat((directions.indexOf(to) - directions.indexOf(from) + 4) % 4) { result = GridPoint(-result.y, result.x) }
            return result
        }

        private fun directComments(xml: String): List<String> {
            val result = mutableListOf<String>()
            var depth = -1
            var offset = 0
            while (offset < xml.length) {
                if (xml.startsWith("<!--", offset)) {
                    val end = xml.indexOf("-->", offset + 4) + 3
                    require(end >= 3)
                    if (depth == 0) result += xml.substring(offset, end)
                    offset = end
                } else if (xml[offset] == '<') {
                    var end = offset + 1
                    var quote: Char? = null
                    while (end < xml.length) {
                        val character = xml[end]
                        if (quote != null) { if (character == quote) quote = null }
                        else if (character == '\'' || character == '"') quote = character
                        else if (character == '>') break
                        end++
                    }
                    val closing = xml.startsWith("</", offset)
                    val selfClosing = xml.substring(offset, end).trimEnd().endsWith('/')
                    if (closing) depth-- else if (!selfClosing) depth++
                    offset = end + 1
                } else offset++
            }
            return result
        }

        private fun coordinate(node: CircXmlElement, name: String): Int = node.attributes[name]?.toIntOrNull()
            ?.takeIf { it in -1_000_000..1_000_000 } ?: throw LocalizedArgumentException(message("appearance_invalid_xml_coordinate", "node" to node.name, "name" to name))

        private fun markerSize(node: CircXmlElement): SymbolMarkerSize? {
            if ("width" !in node.attributes && "height" !in node.attributes) return null
            return SymbolMarkerSize(markerNumber(node, "width"), markerNumber(node, "height"))
        }

        private fun markerPosition(node: CircXmlElement, size: SymbolMarkerSize?): GridPoint =
            if (size == null) GridPoint(coordinate(node, "x"), coordinate(node, "y"))
            else GridPoint((markerNumber(node, "x") + size.width / 2).roundToInt(), (markerNumber(node, "y") + size.height / 2).roundToInt())

        private fun markerNumber(node: CircXmlElement, name: String): Double = node.attributes[name]?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in -1_000_000.0..1_000_000.0 }
            ?: throw LocalizedArgumentException(message("appearance_invalid_xml_coordinate", "node" to node.name, "name" to name))

        private fun opposite(facing: String) = when (facing) { "north" -> "south"; "south" -> "north"; "east" -> "west"; else -> "east" }
    }
}

object AppearanceEdits {
    /** Keep pin references and parent terminals valid after editing a circuit's real pins. */
    fun reconcileCircuit(project: CircuitProject, previous: CircuitDocument, updated: CircuitDocument): CircuitProject {
        fun pins(document: CircuitDocument) = document.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
        val nextProject = project.withCircuit(updated)
        fun ledLocations(document: CircuitDocument) = document.components.filter { it.kind == IoKinds.Led }.associate { it.id to it.position }
        val sameAppearance = previous.attributes == updated.attributes && previous.extraXml == updated.extraXml
        if (pins(previous) == pins(updated) && sameAppearance &&
            (ledLocations(previous) == ledLocations(updated) || !SymbolAppearance.isCustom(previous))) return nextProject
        val customXml = updated.extraXml.any { runCatching { CircXmlParser(it).parse().name == "appear" }.getOrDefault(false) }
        fun rebindDefault(document: CircuitDocument): CircuitProject {
            val appearance = NamedSymbolAppearance.build(document) ?: SymbolAppearance.classic(document)
            val rebound = apply(nextProject.withCircuit(document), document.name, appearance)
            return rebound.copy(circuits = rebound.circuits.map { if (it.id != document.id) it else it.copy(attributes = document.attributes, extraXml = document.extraXml) })
        }
        if (!customXml) return rebindDefault(updated)
        val appearance = if (previous.extraXml == updated.extraXml) SymbolAppearance.read(previous) else SymbolAppearance.read(updated)
        if (pins(previous) == pins(updated) && sameAppearance && appearance.objects.none { it is SymbolObject.Led }) return nextProject
        val actualPins = pins(updated)
        val actualIds = actualPins.map { it.id }.toSet()
        val ports = appearance.ports.filter { it.pinId in actualIds }.toMutableList()
        val classic = SymbolAppearance.classic(updated).relativePorts(updated).associateBy { it.name }
        val boundedMarkers = appearance.anchor.markerSize != null || appearance.ports.any { it.markerSize != null }
        for (pin in actualPins.filter { candidate -> ports.none { it.pinId == candidate.id } }) {
            val relative = SymbolAppearance.rotatePoint(classic.getValue(pin.id).position, "east", appearance.anchor.facing)
            var point = GridPoint(relative.x + appearance.anchor.position.x, relative.y + appearance.anchor.position.y)
            while (ports.any { it.position == point }) point = GridPoint(point.x, point.y + 10)
            val size = if (boundedMarkers) {
                val diameter = if (pin.kind == ComponentKind.INPUT) 8.0 else 10.0
                SymbolMarkerSize(diameter, diameter)
            } else null
            ports += SymbolPort(pin.id, point, size)
        }
        val leds = updated.components.filter { it.kind == IoKinds.Led }.groupBy { it.position }
            .values.filter { it.size == 1 }.map { it.single().id }.toSet()
        val reconciled = appearance.copy(ports = ports, objects = appearance.objects.filter {
            it !is SymbolObject.Led || it.sourceId in leds
        })
        if (SymbolAppearance.isCustom(updated)) return apply(nextProject, updated.name, reconciled)
        val stored = updated.copy(extraXml = updated.extraXml.filterNot { runCatching { CircXmlParser(it).parse().name == "appear" }.getOrDefault(false) } + xml(reconciled, updated))
        return rebindDefault(stored)
    }

    fun useClassic(project: CircuitProject, circuitName: String): CircuitProject {
        val circuit = project.circuit(circuitName) ?: throw LocalizedArgumentException(message("appearance_missing_circuit"))
        val rebound = apply(project, circuitName, SymbolAppearance.classic(circuit))
        return rebound.copy(circuits = rebound.circuits.map { document ->
            if (document.id != circuit.id) document else document.copy(
                attributes = (document.attributes - "circuitnamedbox") + ("appearance" to "classic"),
                extraXml = circuit.extraXml,
            )
        })
    }

    /** Desktop curves have one quadratic segment. Other imported paths keep their SVG editor. */
    fun controlPoints(shape: SymbolObject.Shape): List<GridPoint>? {
        val points = when (shape.tag) {
            "polygon" -> SymbolVisuals.polygonPoints(shape.attributes["points"].orEmpty()).takeIf { it.size >= 3 }
            "path" -> {
                val commands = SymbolVisuals.parsePath(shape.attributes["d"].orEmpty())
                val start = commands.firstOrNull() as? VisualPathCommand.MoveTo
                val curve = commands.getOrNull(1) as? VisualPathCommand.QuadTo
                if (commands.size == 2 && start != null && curve != null) listOf(start.point, curve.control, curve.end) else null
            }
            else -> null
        } ?: return null
        return points.map { point ->
            val x = point.x.toInt()
            val y = point.y.toInt()
            if (x.toDouble() != point.x || y.toDouble() != point.y) return null
            GridPoint(x, y)
        }
    }

    fun controlPointAttributes(tag: String, points: List<GridPoint>): Map<String, String> {
        fun GridPoint.pair() = "$x,$y"
        return when (tag) {
            "polygon" -> {
                require(points.size >= 3)
                mapOf("points" to points.joinToString(" ") { it.pair() })
            }
            "path" -> {
                val (start, control, end) = points.also { require(it.size == 3) }
                mapOf("d" to "M${start.pair()} Q${control.pair()} ${end.pair()}")
            }
            else -> error("Unsupported point-based shape: $tag")
        }
    }

    fun translate(shape: SymbolObject.Shape, dx: Int, dy: Int): SymbolObject.Shape {
        controlPoints(shape)?.let { points ->
            val translated = points.map { GridPoint(it.x + dx, it.y + dy) }
            return shape.copy(attributes = shape.attributes + controlPointAttributes(shape.tag, translated))
        }
        fun shifted(key: String, by: Int) = (SymbolAppearance.number(shape, key) + by).toString()
        val updates = when (shape.tag) {
            "image" -> mapOf("x" to (shape.attributes.getValue("x").toInt() + dx).toString(),
                "y" to (shape.attributes.getValue("y").toInt() + dy).toString())
            "rect", "text" -> mapOf("x" to shifted("x", dx), "y" to shifted("y", dy))
            "ellipse" -> mapOf("cx" to shifted("cx", dx), "cy" to shifted("cy", dy))
            "line" -> mapOf("x1" to shifted("x1", dx), "x2" to shifted("x2", dx), "y1" to shifted("y1", dy), "y2" to shifted("y2", dy))
            else -> {
                val key = if (shape.tag == "path") "d" else "points"
                var coordinate = 0
                val moved = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?").replace(shape.attributes[key].orEmpty()) {
                    (it.value.toDouble() + if (coordinate++ % 2 == 0) dx else dy).toString()
                }
                mapOf(key to moved)
            }
        }
        return shape.copy(attributes = shape.attributes + updates)
    }

    fun xml(appearance: SymbolAppearance, circuit: CircuitDocument): String {
        appearance.validate(circuit)
        return buildString {
            append("<appear>\n")
            for (obj in appearance.objects) when (obj) {
                is SymbolObject.Opaque -> append(obj.xml).append('\n')
                is SymbolObject.Led -> {
                    append("<visible-led")
                    val source = circuit.components.single { it.id == obj.sourceId }
                    val attributes = obj.attributes + mapOf(
                        "id" to obj.id, "x" to obj.position.x.toString(), "y" to obj.position.y.toString(),
                        "width" to (2 * obj.radius).toString(), "height" to (2 * obj.radius).toString(),
                        "value-radius" to obj.radius.toString(), "path" to symbolLedPath(source),
                    )
                    for ((name, value) in attributes) appendXmlAttribute(name, value)
                    append("/>\n")
                }
                is SymbolObject.Shape -> {
                    append('<').append(obj.tag)
                    val attributes = obj.attributes.toMutableMap()
                    if (obj.tag == "image") {
                        attributes.remove("xlink:href")?.takeIf { it.isNotBlank() }?.let { attributes["href"] = it }
                    }
                    for (key in listOf("fill", "stroke")) {
                        val value = attributes[key] ?: continue
                        if (value == "none") continue
                        if (!value.startsWith('#') || value.length == 9) {
                            val color = requireNotNull(SymbolVisuals.paintColor(value, attributes["$key-opacity"]))
                            attributes[key] = "#" + (color and 0x00ffffffu).toString(16).padStart(6, '0')
                            attributes["$key-opacity"] = ((color shr 24).toDouble() / 255).toString()
                        }
                    }
                    for ((name, value) in attributes + ("id" to obj.id)) appendXmlAttribute(name, value)
                    if (obj.text.isEmpty()) append("/>\n") else append('>').append(xmlEscape(obj.text)).append("</").append(obj.tag).append(">\n")
                }
            }
            for (port in appearance.ports) {
                val pin = circuit.components.single { it.id == port.pinId }
                append("<circ-port")
                appendMarkerPosition(port.position, port.markerSize)
                append(" dir=\"").append(if (pin.kind == ComponentKind.INPUT) "in" else "out")
                    .append("\" pin=\"").append(pin.position.x).append(',').append(pin.position.y).append("\"/>\n")
            }
            append("<circ-anchor")
            appendMarkerPosition(appearance.anchor.position, appearance.anchor.markerSize)
            append(" facing=\"").append(appearance.anchor.facing).append("\"/>\n</appear>")
        }
    }

    /** Rebind every affected instance and its boundary wires before publishing any change. */
    fun apply(project: CircuitProject, circuitName: String, appearance: SymbolAppearance): CircuitProject {
        val circuit = project.circuit(circuitName) ?: throw LocalizedArgumentException(message("appearance_missing_circuit"))
        appearance.validate(circuit)
        val updated = circuit.copy(attributes = circuit.attributes + ("appearance" to "custom"), extraXml =
            circuit.extraXml.filterNot { runCatching { CircXmlParser(it).parse().name == "appear" }.getOrDefault(false) } + xml(appearance, circuit))
        val documents = project.circuits.map { parent ->
            val editor = CircuitEditor(if (parent.id == circuit.id) updated else parent)
            for (instance in parent.components.filter { it.circuitName == circuitName }) {
                val derived = bind(instance, circuit, appearance)
                editor.updateComponent(instance.id) { derived }
                requireLocalized(editor.document.components.single { it.id == instance.id } == derived) { message("appearance_connection_loss", "circuit" to parent.name) }
            }
            editor.document
        }
        return project.copy(circuits = documents)
    }

    fun bind(instance: CircuitComponent, circuit: CircuitDocument, appearance: SymbolAppearance): CircuitComponent {
        val ports = appearance.relativePorts(circuit)
        val order = instance.explicitPorts.map { it.name }
        val ordered = ports.sortedBy { order.indexOf(it.name).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        return instance.copy(explicitPorts = ordered, explicitBounds = appearance.relativeBounds(), appearanceTexts = appearance.relativeTexts(),
            sharedLabel = circuit.attributes["clabel"].orEmpty(), attributes = instance.attributes + ("facing" to (instance.attributes["facing"] ?: appearance.anchor.facing)))
    }

    private fun StringBuilder.appendXmlAttribute(name: String, value: String) {
        require(Regex("[A-Za-z_][A-Za-z0-9_.:-]*").matches(name))
        append(' ').append(name).append("=\"").append(xmlEscape(value)).append('"')
    }

    private fun StringBuilder.appendMarkerPosition(position: GridPoint, size: SymbolMarkerSize?) {
        appendXmlAttribute("x", size?.let { position.x - it.width / 2 }?.toString() ?: position.x.toString())
        appendXmlAttribute("y", size?.let { position.y - it.height / 2 }?.toString() ?: position.y.toString())
        if (size != null) {
            appendXmlAttribute("width", size.width.toString())
            appendXmlAttribute("height", size.height.toString())
        }
    }

    private fun xmlEscape(value: String): String = buildString {
        for (character in value) append(when (character) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> character.toString() })
    }
}
