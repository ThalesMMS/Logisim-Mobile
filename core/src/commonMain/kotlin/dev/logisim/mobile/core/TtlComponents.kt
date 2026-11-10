package dev.logisim.mobile.core

/** The 85 actively registered desktop TTL models, with physical one-bit terminals. */
object TtlComponents {
    private val explicitPower = AttributeKey.boolean("VccGndPorts", false)
    private val internalStructure = AttributeKey.boolean("ShowInternalStructure", false)
    private val resistor1 = AttributeKey.int("1Rext", 10, 2..1000)
    private val resistor2 = AttributeKey.int("2Rext", 10, 2..1000)
    private val capacitor1 = AttributeKey.int("1Cext", 100000, 10000..1000000000)
    private val capacitor2 = AttributeKey.int("2Cext", 100000, 10000..1000000000)

    val definitions: List<ComponentDefinition> = TtlCatalog.specs.map { spec ->
        val keys = listOf(CommonAttributes.Facing, CommonAttributes.Label, explicitPower, internalStructure) +
            if (spec.model == "74123") listOf(resistor1, resistor2, capacitor1, capacitor2) else emptyList()
        ComponentDefinition(
            kind = ComponentKind("TTL_${spec.model}"),
            fileName = spec.model,
            library = "#TTL",
            defaultAttributes = AttributeSchema(keys).defaults,
            schema = AttributeSchema(keys),
            layout = { component, attributes ->
                ComponentLayout(TtlGeometry.pins(spec.model, component.position,
                    attributes[CommonAttributes.Facing].serialized, attributes[explicitPower]).map {
                    ComponentTerminal("pin${it.number}", it.position, it.direction, 1, it.name)
                }, TtlGeometry.bounds(spec.model, component.position, attributes[CommonAttributes.Facing].serialized))
            },
            behavior = Behavior(spec.model),
            isCombinational = spec.model in TtlCombinational.supportedModels,
        )
    }

    private data class State(val value: TtlState = TtlState()) : ComponentState

    private class Behavior(private val model: String) : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = State()
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val pins = context.inputs.mapNotNull { (name, value) ->
                name.removePrefix("pin").toIntOrNull()?.let { it to value.bit(0) }
            }.toMap()
            val attrs = context.options + context.attributes.raw
            val result = TtlLogic.evaluate(model, pins, (context.state as? State)?.value ?: TtlState(),
                attrs, context.elapsedTimeMillis)
            return ComponentEvaluation(result.outputs.mapKeys { "pin${it.key}" }.mapValues { LogicVector.fromBit(it.value) },
                State(result.state), result.delay, result.nextWakeupMillis,
                outputDelays = result.outputDelays.mapKeys { "pin${it.key}" })
        }
    }
}
