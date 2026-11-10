package dev.logisim.mobile.core

internal val fixedGeometryKinds = setOf(
    ComponentKind.D_FLIP_FLOP, ComponentKind.REGISTER, ComponentKind.COUNTER,
    ComponentKind.ROM, ComponentKind.RAM, ComponentKind.ADDER, ComponentKind.SUBTRACTOR,
    ComponentKind.HEX_DISPLAY, ComponentKind.SEVEN_SEGMENT_DISPLAY, ComponentKind.TEXT,
)

internal fun legacyAttributeSchema(kind: ComponentKind): AttributeSchema {
    val keys = mutableListOf<AttributeKey<*>>(CommonAttributes.Label)
    if (kind !in fixedGeometryKinds) keys += AttributeKey.direction(default =
        if (kind == ComponentKind.TUNNEL) Direction.WEST else Direction.EAST)
    when (kind) {
        ComponentKind.SPLITTER -> keys += listOf(
            AttributeKey.int("incoming", 2, 1..64), AttributeKey.int("fanout", 2, 1..64),
            AttributeKey.int("spacing", 1, 1..9),
        )
        ComponentKind.ROM, ComponentKind.RAM -> keys += listOf(
            AttributeKey.int("dataWidth", 8, 1..64), AttributeKey.int("addrWidth", 8, 1..24),
        )
        ComponentKind.REGISTER, ComponentKind.COUNTER, ComponentKind.ADDER, ComponentKind.SUBTRACTOR ->
            keys += AttributeKey.int("width", 8, 1..64)
        else -> keys += CommonAttributes.Width
    }
    if (kind in setOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.XOR,
            ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR)) {
        keys += CommonAttributes.Inputs
        keys += CommonAttributes.Size
    }
    if (kind == ComponentKind.NOT) keys += AttributeKey.int("size", 30, 1..1024)
    if (kind in setOf(ComponentKind.D_FLIP_FLOP, ComponentKind.REGISTER, ComponentKind.COUNTER)) {
        keys += AttributeKey.choice("trigger", "rising", setOf("rising", "falling", "high", "low"))
    }
    if (kind == ComponentKind.CLOCK) keys += listOf(
        AttributeKey.int("highDuration", 1, 1..Int.MAX_VALUE),
        AttributeKey.int("lowDuration", 1, 1..Int.MAX_VALUE),
        AttributeKey.int("phaseOffset", 0),
    )
    return AttributeSchema(keys)
}
