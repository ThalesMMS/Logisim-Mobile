package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class ExampleProbe(val id: String, val position: GridPoint, val width: Int)

data class ExampleAction(
    val name: String,
    val inputs: Map<String, String> = emptyMap(),
    val ticks: Int = 0,
    val expected: Map<String, String> = emptyMap(),
)

data class CircuitExample(
    val id: String,
    val title: String,
    val category: String,
    val xml: String,
    val probes: List<ExampleProbe>,
    val startup: List<ExampleAction> = emptyList(),
    val actions: List<ExampleAction> = emptyList(),
)

object CircuitExamples {
    val all: List<CircuitExample> get() = BundledCircuitExamples.all
    val sourceHash: String get() = BundledCircuitExamples.sourceHash
    fun find(id: String): CircuitExample? = all.firstOrNull { it.id == id }
}

data class ExampleSession(
    val example: CircuitExample,
    val project: CircuitProject,
    val state: SimulationState,
    val inputs: Map<String, LogicVector> = emptyMap(),
)

data class ExampleObservation(val action: String, val values: Map<String, String>, val ticks: Long)
data class ExampleResult(
    val observations: List<ExampleObservation>,
    val failures: List<String>,
    val notices: List<LocalizedText> = failures.map(LocalizedText::literal),
)

/** The same initializer and actions drive common tests, the CLI corpus and the editor. */
class ExampleRunner(
    simulator: CircuitSimulator? = null,
    private val registry: ComponentRegistry = ComponentCatalog.registry,
) {
    private val simulator = simulator ?: CircuitSimulator(registry = registry)
    fun initialize(example: CircuitExample): ExampleSession {
        val imported = CircCodec.readProject(example.xml, registry)
        val project = when (imported) {
            is CircProjectImportResult.Success -> imported.project
            is CircProjectImportResult.Failure -> throw LocalizedArgumentException(message("help_reference_import_failed",
                "id" to example.id, "details" to LocalizedText.join(imported.notices)))
        }
        var session = ExampleSession(example, project, simulator.settle(project))
        for (action in example.startup) session = advance(session, action)
        return session
    }

    fun advance(session: ExampleSession, action: ExampleAction): ExampleSession {
        requireLocalized(action.ticks >= 0) { message("help_reference_negative_ticks", "name" to action.name) }
        val inputs = session.inputs.toMutableMap()
        for ((label, text) in action.inputs) {
            val pins = session.project.mainCircuit.components.filter {
                it.label == label && it.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)
            }
            requireLocalized(pins.size == 1) { message("help_reference_input_count", "label" to label, "count" to pins.size) }
            val pin = pins.single()
            requireLocalized(text.length == pin.width) { message("help_reference_input_width", "label" to label, "width" to pin.width) }
            inputs[pin.id] = parseBits(text)
        }
        var state = simulator.settle(session.project, session.state, vectorInputs = inputs)
        repeat(action.ticks) { state = simulator.settle(session.project, state, tick = true, vectorInputs = inputs) }
        return session.copy(state = state, inputs = inputs)
    }

    fun observe(session: ExampleSession): Map<String, String> = session.example.probes.associate { probe ->
        val value = session.state.portVectors[probe.position]
        requireLocalized(value != null && value.width == probe.width) { message("help_reference_probe_missing", "id" to probe.id, "width" to probe.width) }
        probe.id to value.display
    }

    fun run(example: CircuitExample): ExampleResult {
        var session = initialize(example)
        val observations = mutableListOf<ExampleObservation>()
        val notices = mutableListOf<LocalizedText>()
        for (action in example.actions) {
            session = advance(session, action)
            val values = observe(session)
            observations += ExampleObservation(action.name, values, session.state.ticks)
            for ((probe, expected) in action.expected) {
                if (values[probe] != expected) notices += message("help_reference_mismatch", "id" to example.id,
                    "action" to action.name, "probe" to probe, "expected" to expected, "actual" to values[probe])
            }
            if (session.state.oscillating) notices += message("help_reference_unsettled", "id" to example.id, "action" to action.name)
        }
        return ExampleResult(observations, notices.map { it.resolve(AppLanguage.ENGLISH) }, notices)
    }

    private fun parseBits(text: String): LogicVector = LogicVector.fromBits(text.reversed().map {
        when (it.uppercaseChar()) {
            '0' -> LogicValue.LOW
            '1' -> LogicValue.HIGH
            '?', 'X', 'U' -> LogicValue.UNKNOWN
            'E' -> LogicValue.ERROR
            else -> throw LocalizedArgumentException(message("help_reference_logic_digit", "digit" to it))
        }
    })
}
