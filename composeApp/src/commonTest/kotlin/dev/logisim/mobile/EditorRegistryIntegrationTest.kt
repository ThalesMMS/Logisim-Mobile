package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorRegistryIntegrationTest {
    private val kind = ComponentKind("TEST_REGISTERED_SOURCE")
    private val level = AttributeKey.int("level", 1, 0..1)
    private val definition = ComponentDefinition(kind, "Test Source", "#Test", schema = AttributeSchema(listOf(level)),
        layout = { component, _ -> ComponentLayout(
            listOf(ComponentTerminal("out", component.position, PortDirection.OUTPUT)),
            ComponentBounds(component.position.x - 70, component.position.y - 20, component.position.x, component.position.y + 20)) },
        behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation = ComponentEvaluation(
                mapOf("out" to LogicVector.of(1, context.attributes[level].toULong())))
        })
    private val registry = ComponentCatalog.registry.withAdditional(listOf(definition))

    @Test
    fun oneRegistryControlsPaletteImportPropertiesInsertionGeometryAndSimulation() {
        val project = CircuitProject(listOf(CircuitDocument(components = listOf(
            registry.create(kind, "source", GridPoint(100, 100), mapOf("level" to "1"))))))
        val state = EditorState(CircCodec.writeProject(project, registry), registry)
        assertEquals(kind, state.document.components.single().kind)
        assertEquals(1uL, state.rootSimulation.vectorValues.values.single().toULongOrNull())
        assertTrue(registryComponentGroups(registry, state.project).any { (_, kinds) -> kind in kinds })
        state.selectAll()
        assertEquals(listOf("level"), PropertyEditorModel.fields(registry, listOf(state.selected!!)).map { it.name })
        val original = state.document
        state.updateSelectedAttributes(mapOf("level" to "0"))
        assertEquals(0uL, state.rootSimulation.vectorValues.values.single().toULongOrNull())
        state.undo()
        assertEquals(original, state.document)
        state.selectAll()
        state.updateSelectedAttributes(mapOf("level" to "2"))
        assertEquals(original, state.document)
        state.updateSelectedAttributes(mapOf("opaque" to "should not write"))
        assertFalse(state.document.components.single().attributes.containsKey("opaque"))
        assertTrue(state.insertComponent(kind, GridPoint(300, 100)))
        assertEquals(2, state.document.components.size)
        assertTrue(state.selected!!.contains(GridPoint(240, 100), registry))
        val reopened = EditorState(state.exportXml(), registry)
        assertTrue(reopened.document.components.all { it.kind == kind })
    }
}
