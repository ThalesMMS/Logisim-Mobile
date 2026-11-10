package dev.logisim.mobile.core

object ComponentCatalog {
    private val legacyDefinitions: List<ComponentDefinition> = listOf(
        ComponentDefinition(ComponentKind.INPUT, "Pin", "#Wiring"),
        ComponentDefinition(ComponentKind.OUTPUT, "Pin", "#Wiring", mapOf("type" to "output")),
        ComponentDefinition(ComponentKind.CLOCK, "Clock", "#Wiring"),
        ComponentDefinition(ComponentKind.CONSTANT, "Constant", "#Wiring"),
        ComponentDefinition(ComponentKind.TUNNEL, "Tunnel", "#Wiring"),
        ComponentDefinition(ComponentKind.SPLITTER, "Splitter", "#Wiring"),
        ComponentDefinition(ComponentKind.PROBE, "Probe", "#Wiring"),
        ComponentDefinition(ComponentKind.POWER, "Power", "#Wiring"),
        ComponentDefinition(ComponentKind.GROUND, "Ground", "#Wiring"),
        ComponentDefinition(ComponentKind.BIT_EXTENDER, "Bit Extender", "#Wiring"),
        ComponentDefinition(ComponentKind.AND, "AND Gate", "#Gates"),
        ComponentDefinition(ComponentKind.OR, "OR Gate", "#Gates"),
        ComponentDefinition(ComponentKind.XOR, "XOR Gate", "#Gates"),
        ComponentDefinition(ComponentKind.NAND, "NAND Gate", "#Gates"),
        ComponentDefinition(ComponentKind.NOR, "NOR Gate", "#Gates"),
        ComponentDefinition(ComponentKind.XNOR, "XNOR Gate", "#Gates"),
        ComponentDefinition(ComponentKind.NOT, "NOT Gate", "#Gates"),
        ComponentDefinition(ComponentKind.BUFFER, "Buffer", "#Gates"),
        ComponentDefinition(ComponentKind.CONTROLLED_BUFFER, "Controlled Buffer", "#Gates"),
        ComponentDefinition(ComponentKind.CONTROLLED_INVERTER, "Controlled Inverter", "#Gates"),
        ComponentDefinition(ComponentKind.D_FLIP_FLOP, "D Flip-Flop", "#Memory", mapOf("appearance" to "classic")),
        ComponentDefinition(ComponentKind.REGISTER, "Register", "#Memory", mapOf("appearance" to "classic")),
        ComponentDefinition(ComponentKind.COUNTER, "Counter", "#Memory", mapOf("appearance" to "classic")),
        ComponentDefinition(ComponentKind.ROM, "ROM", "#Memory", mapOf("appearance" to "classic")),
        ComponentDefinition(ComponentKind.RAM, "RAM", "#Memory", mapOf("appearance" to "classic")),
        ComponentDefinition(ComponentKind.ADDER, "Adder", "#Arithmetic"),
        ComponentDefinition(ComponentKind.SUBTRACTOR, "Subtractor", "#Arithmetic"),
        ComponentDefinition(ComponentKind.MULTIPLEXER, "Multiplexer", "#Plexers"),
        ComponentDefinition(ComponentKind.DECODER, "Decoder", "#Plexers"),
        ComponentDefinition(ComponentKind.HEX_DISPLAY, "Hex Digit Display", "#I/O"),
        ComponentDefinition(ComponentKind.SEVEN_SEGMENT_DISPLAY, "7-Segment Display", "#I/O"),
        ComponentDefinition(ComponentKind.LED, "LED", "#I/O"),
        ComponentDefinition(ComponentKind.BUTTON, "Button", "#I/O"),
        ComponentDefinition(ComponentKind.TEXT, "Text", "#Base"),
    ).map { definition -> definition.copy(
        schema = legacyAttributeSchema(definition.kind),
        layout = { component, _ -> legacyComponentLayout(component) },
        supportsRotation = definition.kind !in fixedGeometryKinds,
        matchesFileAttributes = when (definition.kind) {
            ComponentKind.INPUT -> { attributes -> attributes["type"] == "input" ||
                attributes["type"] == null && attributes["output"] != "true" }
            ComponentKind.OUTPUT -> { attributes -> attributes["type"] == "output" ||
                attributes["type"] == null && attributes["output"] == "true" }
            else -> { _ -> true }
        },
        isCombinational = definition.kind !in setOf(ComponentKind.CLOCK, ComponentKind.D_FLIP_FLOP,
            ComponentKind.REGISTER, ComponentKind.COUNTER, ComponentKind.RAM, ComponentKind.BUTTON),
    ) }
    val registry: ComponentRegistry = listOf(
        WiringLibrary.definitions(),
        GatesLibrary.definitions(),
        ArithmeticComponents.definitions(),
        FloatingPointLibrary.definitions(),
        PlexersLibrary.definitions,
        MemoryComponents.definitions(),
        SequentialLibrary.definitions,
        IoLibrary.definitions,
        ExtraIoComponents.definitions(),
        ExternalIoComponents.definitions(),
        TtlComponents.definitions,
        AnnotationLibrary.definitions(),
        SocComponents.definitions(),
        TclComponents.definitions(),
        HdlIpLibrary.definitions,
    ).fold(ComponentRegistry(legacyDefinitions)) { registry, family -> registry.withDefinitions(family) }
    val definitions: List<ComponentDefinition> get() = registry.definitions
    fun definition(kind: ComponentKind): ComponentDefinition? = registry.definition(kind)
    fun definition(fileName: String, attributes: Map<String, String> = emptyMap()): ComponentDefinition? =
        if (fileName == "Pin" && (attributes["type"] == "output" || attributes["output"] == "true")) definition(ComponentKind.OUTPUT)
        else definitions.firstOrNull { it.fileName == fileName }
}
