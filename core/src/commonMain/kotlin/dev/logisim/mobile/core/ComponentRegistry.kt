package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText

/** A resolved terminal uses absolute circuit coordinates. Its name survives attribute edits. */
data class ComponentTerminal(
    val name: String,
    val position: GridPoint,
    val direction: PortDirection,
    val width: Int = 1,
    val label: String = "",
) {
    init {
        require(name.isNotBlank()) { "O terminal precisa de um nome." }
        require(width in 0..64) { "A largura do terminal deve estar entre 0 e 64; zero infere a largura da rede." }
    }
}

data class ComponentLayout(
    val ports: List<ComponentTerminal>,
    val bounds: ComponentBounds,
) {
    init { require(ports.map { it.name }.distinct().size == ports.size) { "Nomes de terminais duplicados." } }

    val inputs: List<ComponentTerminal> get() = ports.filter { it.direction != PortDirection.OUTPUT }
    val outputs: List<ComponentTerminal> get() = ports.filter { it.direction != PortDirection.INPUT }
}

/** IDs are allocated for the complete fragment before a family remaps its internal references. */
data class ComponentCopyContext(
    val source: CircuitComponent,
    val copied: CircuitComponent,
    val originals: List<CircuitComponent>,
    val copiesBySourceId: Map<String, CircuitComponent>,
    val existingComponents: List<CircuitComponent>,
)

data class ComponentDefinition(
    val kind: ComponentKind,
    val fileName: String,
    val library: String,
    val defaultAttributes: Map<String, String> = emptyMap(),
    val schema: AttributeSchema = AttributeSchema(),
    val layout: (CircuitComponent, AttributeValues) -> ComponentLayout = { component, _ -> legacyComponentLayout(component) },
    val behavior: ComponentBehavior? = null,
    val supportsRotation: Boolean = true,
    val matchesFileAttributes: (Map<String, String>) -> Boolean = { true },
    val pullDrivers: ((CircuitComponent, AttributeValues) -> Map<String, LogicVector>)? = null,
    val defaultsForVersion: (String) -> Map<String, String> = { emptyMap() },
    val isCombinational: Boolean = false,
    val copyAttributes: (ComponentCopyContext) -> Map<String, String> = { it.copied.attributes },
    val reconfigureAttributes: (CircuitComponent, Map<String, String>) -> Map<String, String> =
        { component, updates -> component.attributes + updates },
    /**
     * How an attribute editor presents [name] for a component, like Evolution's attribute sets that list only
     * the attributes that apply (a gate's negations up to its inputs, a splitter's bits up to its width). Null
     * hides the attribute from editors; it stays in the schema and the file.
     */
    val presentAttribute: (CircuitComponent, String) -> AttributePresentation? = { _, _ -> AttributePresentation() },
    /**
     * The attributes of a component placed beside the circuit's other components, like Evolution's attribute sets
     * that give each new SoC bus its own identifier. Null keeps the attributes as they are.
     */
    val placedAttributes: ((CircuitComponent, List<CircuitComponent>) -> Map<String, String>)? = null,
) {
    init {
        require(fileName.isNotBlank() && library.isNotBlank()) { "A identidade de arquivo precisa de nome e biblioteca." }
        schema.parse(defaultAttributes)
    }
}

/**
 * An editor's view of one attribute: [value] replaces the stored or default value shown (a splitter bit's
 * effective branch), and [options] lists accepted values that depend on other attributes.
 */
data class AttributePresentation(
    val value: String? = null,
    val options: List<String>? = null,
    val optionLabels: Map<String, LocalizedText> = emptyMap(),
)

/** Registries are immutable so independent projects and tests cannot change each other's catalog. */
class ComponentRegistry(definitions: List<ComponentDefinition>) {
    val definitions: List<ComponentDefinition> = definitions.toList()
    private val byKind = definitions.associateBy { it.kind }

    init { require(byKind.size == definitions.size) { "Tipos de componentes duplicados no registro." } }

    fun definition(kind: ComponentKind): ComponentDefinition? = byKind[kind]

    fun requireDefinition(kind: ComponentKind): ComponentDefinition = definition(kind)
        ?: throw IllegalArgumentException("Tipo de componente não registrado: ${kind.name}.")

    fun withAdditional(definitions: List<ComponentDefinition>): ComponentRegistry =
        ComponentRegistry(this.definitions + definitions)

    /** Replaces specified kinds explicitly, while keeping every unrelated definition. */
    fun withDefinitions(definitions: List<ComponentDefinition>): ComponentRegistry {
        require(definitions.map { it.kind }.distinct().size == definitions.size) { "Tipos duplicados na atualização do registro." }
        val replaced = definitions.map { it.kind }.toSet()
        return ComponentRegistry(this.definitions.filterNot { it.kind in replaced } + definitions)
    }

    fun resolve(library: String, fileName: String, raw: Map<String, String>): ComponentDefinition? {
        val matches = definitions.filter {
            it.library == library && it.fileName == fileName && it.matchesFileAttributes(raw)
        }
        require(matches.size <= 1) { "Identidade de componente ambígua: $library / $fileName." }
        return matches.singleOrNull()
    }

    fun attributes(component: CircuitComponent): AttributeValues =
        requireDefinition(component.kind).schema.parse(component.attributes)

    fun layout(component: CircuitComponent): ComponentLayout =
        requireDefinition(component.kind).layout(component, attributes(component))

    fun create(
        kind: ComponentKind,
        id: String,
        position: GridPoint,
        attributes: Map<String, String> = emptyMap(),
        label: String = "",
    ): CircuitComponent {
        val definition = requireDefinition(kind)
        val values = definition.defaultAttributes + attributes
        definition.schema.parse(values)
        return CircuitComponent(id, kind, position, label, values)
    }

    fun reconfigure(component: CircuitComponent, updates: Map<String, String>): CircuitComponent {
        val result = component.copy(attributes = requireDefinition(component.kind).reconfigureAttributes(component, updates))
        attributes(result)
        layout(result)
        return result
    }
}
