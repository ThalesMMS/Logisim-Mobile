package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerComponentStateTest {
    private val bankKind = ComponentKind("PEER_TEST_BANK")
    private val masterKind = ComponentKind("PEER_TEST_MASTER")
    private data class BankState(val value: Int = 0, val transactions: List<Pair<String, Int>> = emptyList()) : ComponentState
    private data class MasterState(val transactions: Int = 0) : ComponentState
    private data class Add(val value: Int, val target: String = "bank") : ComponentEvent

    private fun registry() = ComponentCatalog.registry.withAdditional(listOf(
        ComponentDefinition(bankKind, "Peer Bank", "#Test", layout = { c, _ ->
            ComponentLayout(listOf(ComponentTerminal("out", c.position, PortDirection.OUTPUT, 8)),
                ComponentBounds(c.position.x - 10, c.position.y - 10, c.position.x, c.position.y + 10))
        }, behavior = object : ComponentBehavior {
            override fun initialState(component: CircuitComponent, attributes: AttributeValues) = BankState()
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                assertTrue(context.peers.isEmpty())
                assertTrue(context.peerStates.isEmpty())
                val state = context.state as BankState
                return ComponentEvaluation(mapOf("out" to LogicVector.of(8, state.value.toULong())), state)
            }
        }),
        ComponentDefinition(masterKind, "Peer Master", "#Test", layout = { c, _ ->
            ComponentLayout(emptyList(), ComponentBounds(c.position.x, c.position.y, c.position.x + 10, c.position.y + 10))
        }, behavior = object : ComponentBehavior {
            override val usesPeerStates = true
            override fun initialState(component: CircuitComponent, attributes: AttributeValues) = MasterState()
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val state = context.state as MasterState
                val event = context.events.filterIsInstance<Add>().singleOrNull()
                    ?: return ComponentEvaluation(emptyMap(), state)
                assertEquals(setOf("bank", "first", "second"), context.peers.map { it.id }.toSet())
                val bank = context.peerStates.getValue("bank") as BankState
                val updated = bank.copy(value = bank.value + event.value,
                    transactions = bank.transactions + (context.component.id to bank.value))
                return ComponentEvaluation(emptyMap(), state.copy(transactions = state.transactions + 1),
                    peerStateUpdates = mapOf(event.target to updated))
            }
        }),
    ))

    private fun document(name: String = "main") = CircuitDocument(name, components = listOf(
        CircuitComponent("bank", bankKind, GridPoint(0, 0)),
        CircuitComponent("first", masterKind, GridPoint(40, 0)),
        CircuitComponent("second", masterKind, GridPoint(80, 0)),
    ))

    @Test
    fun mastersSeeThePreviousTransactionInDeterministicDocumentOrder() {
        val simulator = CircuitSimulator(registry = registry())
        val circuit = document()
        val initial = simulator.settle(circuit)
        val state = simulator.settle(circuit, initial, events = mapOf(
            "first" to listOf(Add(3)), "second" to listOf(Add(7))))
        assertFalse(state.oscillating)
        assertEquals(BankState(10, listOf("first" to 0, "second" to 3)), state.componentStates["bank"])
        assertEquals(MasterState(1), state.componentStates["first"])
        assertEquals(MasterState(1), state.componentStates["second"])
        assertEquals(10uL, state.vectorValues.getValue("bank").toULongOrNull())
        val repeated = simulator.settle(circuit, state)
        assertEquals(state.componentStates, repeated.componentStates)
        assertEquals(10uL, repeated.vectorValues.getValue("bank").toULongOrNull())
    }

    @Test
    fun peerUpdatesStayInsideTheirHierarchicalInstance() {
        val simulator = CircuitSimulator(registry = registry())
        val child = document("child")
        val main = CircuitDocument("main", components = listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"),
        ))
        val project = CircuitProject(listOf(main, child))
        val initial = simulator.settle(project)
        val state = simulator.settle(project, initial, events = mapOf("left/first" to listOf(Add(13))))
        assertFalse(state.oscillating)
        assertEquals(BankState(13, listOf("first" to 0)), state.circuitStates.getValue("left").componentStates["bank"])
        assertEquals(BankState(), state.circuitStates.getValue("right").componentStates["bank"])
        assertEquals(13uL, state.circuitStates.getValue("left").vectorValues.getValue("bank").toULongOrNull())
        assertEquals(0uL, state.circuitStates.getValue("right").vectorValues.getValue("bank").toULongOrNull())
    }

    @Test
    fun aBehaviorCannotUpdateAnUnrelatedInstancePath() {
        val simulator = CircuitSimulator(registry = registry())
        val circuit = document()
        val initial = simulator.settle(circuit)
        assertFailsWith<IllegalArgumentException> {
            simulator.settle(circuit, initial, events = mapOf("first" to listOf(Add(5, "other/bank"))))
        }
        assertEquals(BankState(), initial.componentStates["bank"])
        assertEquals(0uL, simulator.settle(circuit, initial).vectorValues.getValue("bank").toULongOrNull())
    }

    @Test
    fun peerWritesRequireTheExplicitBehaviorCapability() {
        val unscopedKind = ComponentKind("PEER_TEST_UNSCOPED")
        val unscoped = ComponentDefinition(unscopedKind, "Unscoped", "#Test", layout = { c, _ ->
            ComponentLayout(emptyList(), ComponentBounds(c.position.x, c.position.y, c.position.x + 10, c.position.y + 10))
        }, behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(emptyMap(),
                peerStateUpdates = mapOf("bank" to BankState(99)))
        })
        val simulator = CircuitSimulator(registry = registry().withAdditional(listOf(unscoped)))
        val circuit = document()
        val initial = simulator.settle(circuit)
        val modified = circuit.copy(components = circuit.components +
            CircuitComponent("unscoped", unscopedKind, GridPoint(120, 0)))
        assertFailsWith<IllegalArgumentException> { simulator.settle(modified, initial) }
        assertEquals(BankState(), initial.componentStates["bank"])
    }
}
