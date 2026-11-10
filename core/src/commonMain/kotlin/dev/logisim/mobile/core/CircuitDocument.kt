package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class GridPoint(val x: Int, val y: Int)

data class ComponentKind(val name: String) {
    init { require(name.isNotBlank()) { "O tipo do componente precisa de uma identificação." } }

    companion object {
        @kotlin.jvm.JvmField val INPUT = ComponentKind("INPUT")
        @kotlin.jvm.JvmField val OUTPUT = ComponentKind("OUTPUT")
        @kotlin.jvm.JvmField val AND = ComponentKind("AND")
        @kotlin.jvm.JvmField val OR = ComponentKind("OR")
        @kotlin.jvm.JvmField val XOR = ComponentKind("XOR")
        @kotlin.jvm.JvmField val NAND = ComponentKind("NAND")
        @kotlin.jvm.JvmField val NOR = ComponentKind("NOR")
        @kotlin.jvm.JvmField val XNOR = ComponentKind("XNOR")
        @kotlin.jvm.JvmField val NOT = ComponentKind("NOT")
        @kotlin.jvm.JvmField val BUFFER = ComponentKind("BUFFER")
        @kotlin.jvm.JvmField val CLOCK = ComponentKind("CLOCK")
        @kotlin.jvm.JvmField val D_FLIP_FLOP = ComponentKind("D_FLIP_FLOP")
        @kotlin.jvm.JvmField val CONSTANT = ComponentKind("CONSTANT")
        @kotlin.jvm.JvmField val TUNNEL = ComponentKind("TUNNEL")
        @kotlin.jvm.JvmField val SPLITTER = ComponentKind("SPLITTER")
        @kotlin.jvm.JvmField val CONTROLLED_BUFFER = ComponentKind("CONTROLLED_BUFFER")
        @kotlin.jvm.JvmField val CONTROLLED_INVERTER = ComponentKind("CONTROLLED_INVERTER")
        @kotlin.jvm.JvmField val REGISTER = ComponentKind("REGISTER")
        @kotlin.jvm.JvmField val COUNTER = ComponentKind("COUNTER")
        @kotlin.jvm.JvmField val ROM = ComponentKind("ROM")
        @kotlin.jvm.JvmField val RAM = ComponentKind("RAM")
        @kotlin.jvm.JvmField val ADDER = ComponentKind("ADDER")
        @kotlin.jvm.JvmField val SUBTRACTOR = ComponentKind("SUBTRACTOR")
        @kotlin.jvm.JvmField val MULTIPLEXER = ComponentKind("MULTIPLEXER")
        @kotlin.jvm.JvmField val DECODER = ComponentKind("DECODER")
        @kotlin.jvm.JvmField val HEX_DISPLAY = ComponentKind("HEX_DISPLAY")
        @kotlin.jvm.JvmField val SEVEN_SEGMENT_DISPLAY = ComponentKind("SEVEN_SEGMENT_DISPLAY")
        @kotlin.jvm.JvmField val LED = ComponentKind("LED")
        @kotlin.jvm.JvmField val BUTTON = ComponentKind("BUTTON")
        @kotlin.jvm.JvmField val SUBCIRCUIT = ComponentKind("SUBCIRCUIT")
        @kotlin.jvm.JvmField val TEXT = ComponentKind("TEXT")
        @kotlin.jvm.JvmField val PROBE = ComponentKind("PROBE")
        @kotlin.jvm.JvmField val POWER = ComponentKind("POWER")
        @kotlin.jvm.JvmField val GROUND = ComponentKind("GROUND")
        @kotlin.jvm.JvmField val BIT_EXTENDER = ComponentKind("BIT_EXTENDER")

        val entries: List<ComponentKind> = listOf(
            INPUT, OUTPUT, AND, OR, XOR, NAND, NOR,
            XNOR, NOT, BUFFER, CLOCK, D_FLIP_FLOP, CONSTANT, TUNNEL,
            SPLITTER, CONTROLLED_BUFFER, CONTROLLED_INVERTER, REGISTER, COUNTER, ROM, RAM,
            ADDER, SUBTRACTOR, MULTIPLEXER, DECODER, HEX_DISPLAY, SEVEN_SEGMENT_DISPLAY, LED,
            BUTTON, SUBCIRCUIT, TEXT, PROBE, POWER, GROUND, BIT_EXTENDER
        )
    }
}

enum class PortDirection { INPUT, OUTPUT, INOUT }

/** Positions are relative to the east-facing component anchor. */
data class ComponentPort(
    val name: String,
    val position: GridPoint,
    val direction: PortDirection,
    val width: Int = 1,
    val label: String = "",
) {
    init { requireLocalized(width in 1..64) { message(Messages.ErrorValueWidth) } }
}

data class ComponentText(val text: String, val position: GridPoint, val fontSize: Int = 12)

data class CircuitFileMetadata(
    val sourceVersion: String = "3.8.0",
    val metadata: Map<String, String> = emptyMap(),
    val libraries: Map<String, String> = emptyMap(),
    val extraXml: List<String> = emptyList(),
    val mainCircuitName: String = "main",
    val mainXml: String? = null,
)

/** '/' separates local component identities in simulation paths and event addresses. */
internal fun requireComponentIdentity(id: String) {
    requireLocalized(id.isNotBlank()) { message(Messages.ErrorComponentIdRequired) }
    requireLocalized('/' !in id) { message("component_identity_reserved_separator") }
}

data class CircuitComponent(
    val id: String,
    val kind: ComponentKind,
    val position: GridPoint,
    val label: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val circuitName: String? = null,
    val xmlAttributes: Map<String, String> = emptyMap(),
    val extraXml: List<String> = emptyList(),
    val explicitPorts: List<ComponentPort> = emptyList(),
    val explicitBounds: ComponentBounds? = null,
    val appearanceTexts: List<ComponentText> = emptyList(),
    val sharedLabel: String = "",
    val implicitAttributes: Map<String, String> = emptyMap(),
    internal val isOriginalSequential: Boolean = false,
) {
    init { requireComponentIdentity(id) }

    fun attributeInt(name: String, default: Int): Int = attributes[name]?.let {
        when {
            it.startsWith("0x", true) -> it.drop(2).toLongOrNull(16)?.toInt()
            else -> it.toIntOrNull()
        }
    } ?: default

    val width: Int get() = when (kind) {
        ComponentKind.SPLITTER -> attributeInt("incoming", 2)
        ComponentKind.ROM, ComponentKind.RAM -> attributeInt("dataWidth", 8)
        ComponentKind.HEX_DISPLAY -> 4
        ComponentKind.REGISTER, ComponentKind.COUNTER, ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> attributeInt("width", 8)
        else -> attributeInt("width", 1)
    }.coerceIn(1, 64)

    val facing: String get() = attributes["facing"] ?: if (kind == ComponentKind.TUNNEL) "west" else "east"
}

data class WireSegment(val from: GridPoint, val to: GridPoint) {
    init {
        requireLocalized(from != to) { message(Messages.ErrorWireLength) }
        requireLocalized(from.x == to.x || from.y == to.y) { message(Messages.ErrorWireOrthogonal) }
    }
    fun contains(point: GridPoint): Boolean =
        if (from.x == to.x) point.x == from.x && point.y in minOf(from.y, to.y)..maxOf(from.y, to.y)
        else point.y == from.y && point.x in minOf(from.x, to.x)..maxOf(from.x, to.x)
}

data class CircuitDocument(
    val name: String = "main",
    val components: List<CircuitComponent> = emptyList(),
    val wires: List<WireSegment> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val extraXml: List<String> = emptyList(),
    val id: String = name,
    val fileMetadata: CircuitFileMetadata? = null,
    val xmlAttributes: Map<String, String> = emptyMap(),
) {
    init {
        requireLocalized(name.isNotBlank()) { message(Messages.ErrorCircuitNameRequired) }
        requireLocalized(id.isNotBlank()) { message(Messages.ErrorCircuitIdRequired) }
        requireLocalized(components.map { it.id }.distinct().size == components.size) {
            message(Messages.ErrorComponentIdsUnique)
        }
    }
}

/** The retraced part of a single wire section, bounded by wire ends and terminals. */
fun CircuitDocument.wireBacktrack(from: GridPoint, to: GridPoint, registry: ComponentRegistry): WireSegment? {
    if (from == to || wires.none { it.contains(from) && it.contains(to) }) return null
    val retraced = WireSegment(from, to)
    val boundaries = wires.asSequence().flatMap { sequenceOf(it.from, it.to) } +
        components.asSequence().flatMap { (it.inputPorts(registry) + it.outputPorts(registry)).asSequence() }
    var touchesBoundary = false
    for (point in boundaries) {
        if (!retraced.contains(point)) continue
        if (point != from && point != to) return null
        touchesBoundary = true
    }
    return retraced.takeIf { touchesBoundary }
}

enum class LogicValue {
    LOW, HIGH, UNKNOWN, ERROR;
    fun inverted(): LogicValue = when (this) {
        LOW -> HIGH
        HIGH -> LOW
        UNKNOWN -> UNKNOWN
        ERROR -> ERROR
    }
    val display: String get() = when (this) {
        LOW -> "0"
        HIGH -> "1"
        UNKNOWN -> "?"
        ERROR -> "E"
    }
}
