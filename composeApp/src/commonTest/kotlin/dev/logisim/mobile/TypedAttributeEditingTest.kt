package dev.logisim.mobile

import dev.logisim.mobile.core.AttributeKey
import dev.logisim.mobile.core.AttributeSchema
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedAttributeEditingTest {
    private fun state(): EditorState {
        val originalDefinition = ComponentCatalog.registry.requireDefinition(ComponentKind.AND)
        val definition = originalDefinition.copy(
            schema = AttributeSchema(originalDefinition.schema.keys.filterNot { it.name == "width" } +
                AttributeKey.int("width", 1, 1..8)),
        )
        val registry = ComponentCatalog.registry.withDefinitions(listOf(definition))
        val document = CircuitDocument(components = listOf(
            CircuitComponent("one", ComponentKind.AND, GridPoint(100, 100)),
            CircuitComponent("two", ComponentKind.AND, GridPoint(300, 100)),
        ))
        return EditorState(CircCodec.writeProject(CircuitProject(listOf(document))), registry).also { it.selectAll() }
    }

    @Test
    fun commonAttributesChangeEverySelectedComponentInOneUndoAction() {
        val state = state()
        val original = state.document
        state.updateSelectedAttributes(mapOf("width" to "8"))
        assertTrue(state.document.components.all { it.attributes["width"] == "8" })
        state.undo()
        assertEquals(original, state.document)
    }

    @Test
    fun invalidTypedValueCannotPartiallyModifyTheSelection() {
        val state = state()
        val original = state.project
        state.updateSelectedAttributes(mapOf("width" to "64"))
        assertEquals(original, state.project)
        assertTrue(state.status.contains("width"))
    }

    @Test
    fun labelsCanBeEditedAcrossTheSelectionAndUndone() {
        val state = state()
        val original = state.document
        state.updateSelectedLabel("Banco_A")
        assertTrue(state.document.components.all { it.label == "Banco_A" })
        state.undo()
        assertEquals(original, state.document)
    }
}
