package dev.logisim.mobile.core

/**
 * The layout preferences Evolution applies only to components and circuits created afterwards:
 * AppPreferences.getDefaultAppearance(), getDefaultCircuitAppearance(), NEW_INPUT_OUTPUT_SHAPES
 * (ProbeAttributes.getDefaultProbeAppearance()) and NAMED_CIRCUIT_BOXES_FIXED_SIZE, plus
 * REMOVE_UNUSED_LIBRARIES, which XmlWriter applies while saving. [textColor] is TEXT_TOOL_COLOR,
 * which TextTool and AddTool give the Text component they create.
 */
data class CreationDefaults(
    val componentAppearance: String = "logisim_evolution",
    val circuitAppearance: String = "logisim_evolution",
    val newInputOutputShapes: Boolean = true,
    val namedCircuitBoxesFixedSize: Boolean = true,
    val removeUnusedLibraries: Boolean = false,
    val textColor: String = DEFAULT_TEXT_COLOR,
) {
    /** Attribute values a new component of [definition] takes instead of its catalog defaults. */
    fun componentAttributes(definition: ComponentDefinition): Map<String, String> {
        if (definition.kind == ComponentKind.TEXT) return textAttributes()
        val appearance = definition.schema.keys.firstOrNull { it.name == APPEARANCE } ?: return emptyMap()
        val value = when {
            appearance.accepts(NEW_PINS) -> if (newInputOutputShapes) NEW_PINS else CLASSIC
            else -> componentAppearance
        }
        return if (appearance.accepts(value)) mapOf(APPEARANCE to value) else emptyMap()
    }

    /** The Text color a new label takes; the catalog default is left implicit. */
    fun textAttributes(): Map<String, String> =
        if (textColor.equals(DEFAULT_TEXT_COLOR, ignoreCase = true)) emptyMap() else mapOf(TEXT_COLOR to textColor)

    /** Circuit attributes Evolution's CircuitAttributes and Circuit constructor give a new circuit. */
    fun circuitAttributes(): Map<String, String> = mapOf(
        APPEARANCE to circuitAppearance,
        "circuitnamedboxfixedsize" to namedCircuitBoxesFixedSize.toString(),
    )

    companion object {
        const val DEFAULT_TEXT_COLOR = "#000000"
        private const val TEXT_COLOR = "color"
        private const val APPEARANCE = "appearance"
        private const val NEW_PINS = "NewPins"
        private const val CLASSIC = "classic"
    }
}

/** Creates a component as Evolution's AddTool would with the given layout preferences. */
fun ComponentRegistry.createNew(
    kind: ComponentKind,
    id: String,
    position: GridPoint,
    defaults: CreationDefaults,
): CircuitComponent = create(kind, id, position, definition(kind)?.let(defaults::componentAttributes).orEmpty())
