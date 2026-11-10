package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitFileMetadata
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #321: the editor refuses the circuit names and labels Evolution d58a61e0 refuses, and keeps the previous value. */
class EvolutionNameEntryTest {
    private fun state(source: String = "3.8.0") = EditorState(CircCodec.write(CircuitDocument("main", listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(100, 100), "A"),
    ), fileMetadata = CircuitFileMetadata(sourceVersion = source))))

    @Test
    fun labelsEvolutionRefusesAreNotApplied() {
        val state = state()
        state.selectComponent("a")
        for ((value, key) in listOf("Visão" to "evolution_name_invalid_character", "two words" to "evolution_name_invalid_character",
            "9lives" to "evolution_name_starts_with_digit", "a__b" to "evolution_name_double_underscore",
            "end_" to "evolution_name_ends_with_underscore", "signal" to "evolution_name_vhdl_keyword",
            "pin" to "evolution_label_matches_component")) {
            assertFalse(state.updateSelectedLabel(value), value)
            assertEquals(key, state.statusText.key, value)
            assertEquals("A", state.document.components.single().label)
        }
        assertTrue(state.updateSelectedLabel("Data_in"))
        assertEquals("Data_in", state.document.components.single().label)
        assertTrue(state.updateSelectedLabel(""))
    }

    @Test
    fun circuitNamesEvolutionRefusesAreNotApplied() {
        val state = state()
        assertFalse(state.createCircuit("Minha primeira porta AND"))
        assertEquals("evolution_name_invalid_character", state.statusText.key)
        assertFalse(state.renameCircuit(state.document.id, "Visão"))
        assertFalse(state.renameCircuit(state.document.id, "a"), "a circuit cannot take one of its pin labels")
        assertEquals("evolution_circuit_name_matches_pin", state.statusText.key)
        assertEquals(listOf("main"), state.project.circuits.map { it.name })
        assertTrue(state.createCircuit("child_1"))
        assertTrue(state.renameCircuit(state.document.id, "top"))
    }

    @Test
    fun originalLogisimProjectsKeepFreeFormNames() {
        val state = state(source = "2.7.1")
        state.selectComponent("a")
        assertTrue(state.updateSelectedLabel("A'"))
        assertTrue(state.createCircuit("Meu circuito"))
    }
}
