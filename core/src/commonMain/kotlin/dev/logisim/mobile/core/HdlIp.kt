package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class HdlIpBackend { VHDL, BLIF }

data class HdlIpPort(
    val name: String,
    val direction: PortDirection,
    val width: Int,
    val kind: String = if (width == 1) "std_logic" else "std_logic_vector",
) {
    init { requireLocalized(name.isNotBlank() && width in 1..64) { message(Messages.HdlIpInvalidPort) } }
}

data class HdlIpDiagnostic(val code: String, val notice: LocalizedText) {
    constructor(code: String, message: String) : this(code, LocalizedText.literal(message))
    val message: String get() = notice.resolve(AppLanguage.ENGLISH)
}

/**
 * A generic as the entity declares it, before any override: its name with the declared case, its type (integer,
 * natural, positive, boolean, time, std_logic or std_logic_vector) and its default text, empty when it has none.
 */
data class HdlGenericDeclaration(val name: String, val kind: String, val declaredDefault: String)

data class HdlIpDesign(
    val backend: HdlIpBackend,
    val source: String,
    val topLevel: String,
    val ports: List<HdlIpPort>,
    val parameters: Map<String, String> = emptyMap(),
    val diagnostics: List<HdlIpDiagnostic> = emptyList(),
    /** VHDL generics of type time; their [parameters] value is in femtoseconds, as in Evolution. */
    val timeParameters: Set<String> = emptySet(),
    /** The supported generic declarations in source order, as Evolution's VhdlEntityAttributes lists one per generic. */
    val genericDeclarations: List<HdlGenericDeclaration> = emptyList(),
) {
    /** The VHDL actual of each generic, with the fs unit Evolution's generator writes for time generics. */
    val vhdlGenericActuals: Map<String, String> get() =
        parameters.mapValues { (name, value) -> if (name in timeParameters) "$value fs" else value }

    val isExecutable: Boolean get() = diagnostics.isEmpty() &&
        (backend == HdlIpBackend.BLIF || topLevel.isNotBlank() && ports.isNotEmpty())
}

/** Reads declarations only. Compilation and execution require an explicit companion command. */
object HdlIpLibrary {
    val VHDL = ComponentKind("VHDL_ENTITY")
    val BLIF = ComponentKind("BLIF_CIRCUIT")
    val Content = AttributeKey.text("content", validate = { '\u0000' !in it })
    val Schema = AttributeSchema(listOf(Content, CommonAttributes.Label))
    val definitions: List<ComponentDefinition> = listOf(
        definition(VHDL, "VHDL Entity", HdlIpTemplates.VHDL),
        definition(BLIF, "BLIFCircuit", HdlIpTemplates.BLIF, BlifCircuitBehavior),
    )
    private data class GenericOverride(val name: String, val value: String, val explicit: Boolean)

    fun parse(component: CircuitComponent): HdlIpDesign {
        val candidates = component.attributes.mapNotNull { (name, value) -> when {
            name.startsWith("generic.") -> GenericOverride(name.removePrefix("generic."), value, true)
            name.startsWith("vhdl_") -> GenericOverride(name.removePrefix("vhdl_"), value, false)
            else -> null
        } }.groupBy { it.name.lowercase() }
        val conflicts = candidates.filterValues { entries -> entries.map { it.value }.distinct().size > 1 }
            .keys.map { HdlIpDiagnostic("conflicting-generic", message(Messages.HdlIpConflictingGeneric, "name" to it)) }
        val overrides = candidates.mapValues { (_, entries) -> (entries.firstOrNull { it.explicit } ?: entries.first()).value }
        val content = component.attributes["content"]
        if (component.kind == BLIF) return HdlIpDeclarations.blif(content ?: HdlIpTemplates.BLIF)
        val design = when (component.kind) {
            VHDL -> HdlIpDeclarations.vhdl(content ?: HdlIpTemplates.VHDL, overrides)
            else -> throw LocalizedArgumentException(message(Messages.HdlIpInvalidComponent))
        }
        return design.copy(diagnostics = design.diagnostics + conflicts)
    }

    private fun definition(kind: ComponentKind, fileName: String, template: String,
                           behavior: ComponentBehavior = HdlIpBehavior) = ComponentDefinition(
        kind = kind,
        fileName = fileName,
        library = "#HDL-IP",
        schema = Schema,
        defaultAttributes = mapOf(Content.name to template),
        supportsRotation = false,
        layout = { component, _ -> layout(component, parse(component)) },
        behavior = behavior,
    )

    /** Shared VHDL uses the Evolution anchor. Inline HDL-IP uses its original layout. */
    fun layout(component: CircuitComponent, design: HdlIpDesign): ComponentLayout {
        component.xmlAttributes[SharedVhdlDefinition.appearanceAttribute]?.let { appearance ->
            val name = component.xmlAttributes[SharedVhdlDefinition.referenceAttribute] ?: design.topLevel.ifBlank { "VHDL" }
            val pins = design.ports.mapIndexed { index, port ->
                val input = port.direction != PortDirection.OUTPUT
                CircuitComponent("pin-$index", if (input) ComponentKind.INPUT else ComponentKind.OUTPUT,
                    GridPoint(100, index * 10), port.name,
                    mapOf("facing" to if (input) "east" else "west", "width" to port.width.toString()))
            }
            val symbol = CircCodec.bindSubcircuit(component, CircuitDocument(name = name, components = pins,
                attributes = mapOf("appearance" to appearance, "circuitnamedboxfixedsize" to "true")))
            val bounds = checkNotNull(symbol.explicitBounds)
            val first = symbol.relativePoint(GridPoint(bounds.left, bounds.top))
            val second = symbol.relativePoint(GridPoint(bounds.right, bounds.bottom))
            return ComponentLayout(symbol.explicitPorts.map { port ->
                ComponentTerminal(port.label, symbol.relativePoint(port.position), port.direction, port.width, port.label)
            }, ComponentBounds(minOf(first.x, second.x), minOf(first.y, second.y),
                maxOf(first.x, second.x), maxOf(first.y, second.y)))
        }
        val inputs = design.ports.filter { it.direction != PortDirection.OUTPUT }
        val outputs = design.ports.filter { it.direction == PortDirection.OUTPUT }
        val terminals = inputs.mapIndexed { index, port ->
            ComponentTerminal(port.name, GridPoint(component.position.x, component.position.y + 40 + index * 10),
                port.direction, port.width, port.name)
        } + outputs.mapIndexed { index, port ->
            ComponentTerminal(port.name, GridPoint(component.position.x + 140, component.position.y + 40 + index * 10),
                port.direction, port.width, port.name)
        }
        return ComponentLayout(terminals, ComponentBounds(component.position.x, component.position.y,
            component.position.x + 140, component.position.y + 40 + maxOf(inputs.size, outputs.size) * 10))
    }
}
