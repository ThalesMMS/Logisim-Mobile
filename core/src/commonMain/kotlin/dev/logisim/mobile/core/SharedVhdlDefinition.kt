package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** The project owns the source. A component carries it at runtime for the existing HDL-IP engine. */
@ConsistentCopyVisibility
data class SharedVhdlDefinition internal constructor(
    val name: String,
    val source: String,
    internal val xmlAttributes: Map<String, String>,
) {
    fun instance(id: String, position: GridPoint, generics: Map<String, String> = emptyMap()): CircuitComponent =
        bind(CircuitComponent(id, HdlIpLibrary.VHDL, position, attributes = generics))

    internal fun bind(component: CircuitComponent): CircuitComponent = component.copy(
        attributes = component.attributes + ("content" to source),
        xmlAttributes = component.xmlAttributes + mapOf(
            referenceAttribute to name,
            appearanceAttribute to (xmlAttributes["appearance"] ?: "logisim_evolution"),
        ),
    )

    internal fun matches(component: CircuitComponent): Boolean = component.kind == HdlIpLibrary.VHDL &&
        component.xmlAttributes[referenceAttribute] == name && component.attributes["content"] == source

    companion object {
        internal const val referenceAttribute = "logisim-mobile-vhdl-model"
        internal const val appearanceAttribute = "logisim-mobile-vhdl-appearance"

        internal fun read(element: CircXmlElement): SharedVhdlDefinition {
            val name = element.attributes["name"].orEmpty()
            requireLocalized(name.isNotBlank() && element.children.isEmpty()) { message("shared_vhdl_invalid") }
            return SharedVhdlDefinition(name, element.text, element.attributes)
        }
    }
}

fun CircuitProject.sharedVhdlDefinitions(): List<SharedVhdlDefinition> = extraXml.mapNotNull { xml ->
    CircXmlParser(xml).parse().takeIf { it.name == "vhdl" }?.let(SharedVhdlDefinition::read)
}

fun CircuitProject.sharedVhdlDefinition(component: CircuitComponent): SharedVhdlDefinition? =
    if (component.kind == HdlIpLibrary.VHDL && SharedVhdlDefinition.referenceAttribute in component.xmlAttributes)
        sharedVhdlDefinitions().firstOrNull { it.matches(component) } else null

fun CircuitProject.addSharedVhdlDefinition(source: String): Pair<CircuitProject, SharedVhdlDefinition> {
    requireSharedVhdlSource(source)
    val design = HdlIpDeclarations.vhdl(source)
    val name = design.topLevel
    requireLocalized(name.isNotBlank()) { message("shared_vhdl_invalid") }
    requireLocalized(circuit(name) == null && sharedVhdlDefinitions().none { it.name == name }) {
        message("shared_vhdl_name_conflict")
    }
    val definition = SharedVhdlDefinition(name, source, mapOf("name" to name, "appearance" to "logisim_evolution"))
    return copy(extraXml = extraXml + CircCodec.writeVhdlDefinition(definition)) to definition
}

fun CircuitProject.updateSharedVhdlSource(name: String, source: String): CircuitProject {
    val previous = sharedVhdlDefinitions().firstOrNull { it.name == name }
    requireLocalized(previous != null) { message("shared_vhdl_missing", "name" to name) }
    requireSharedVhdlSource(source)
    if (previous.source == source) return this
    requireLocalized(HdlIpDeclarations.vhdl(source).topLevel == name) { message("shared_vhdl_entity_name", "name" to name) }
    val replacement = previous.copy(source = source)
    val definitionXml = CircCodec.writeVhdlDefinition(replacement)
    return copy(
        extraXml = extraXml.map { xml ->
            val element = CircXmlParser(xml).parse()
            if (element.name == "vhdl" && element.attributes["name"] == name) definitionXml else xml
        },
        circuits = circuits.map { circuit ->
            circuit.copy(components = circuit.components.map { component ->
                if (previous.matches(component)) replacement.bind(component) else component
            })
        },
    )
}

private fun requireSharedVhdlSource(source: String) =
    requireLocalized(source.isNotBlank() && source.encodeToByteArray().size <= 128 * 1024 && '\u0000' !in source) {
        message("hdl_tools_sim_source_limit")
    }
