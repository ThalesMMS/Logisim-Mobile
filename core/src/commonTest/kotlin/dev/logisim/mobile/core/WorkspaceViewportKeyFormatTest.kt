package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceViewportKeyFormatTest {
    @Test fun theExplicitFormatDistinguishesLegacyIdsFromFramedCircuitIds() {
        val leaf = CircuitDocument("Leaf", id = "leaf")
        val literal = CircuitDocument("Literal", id = "4:leaf")
        val project = CircuitProject(listOf(leaf, literal))
        val viewport = WorkspaceViewport(2f, -10f, 20f)
        val legacy = document(WorkspaceViewState(leaf.id, viewports = mapOf("4:leaf" to viewport)))
        val framed = legacy.copy(viewState = legacy.viewState.copy(viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED))

        val legacyBytes = WorkspaceCatalogFormat.writeDocument(legacy)
        val framedBytes = WorkspaceCatalogFormat.writeDocument(framed)
        assertTrue(legacyBytes.startsWith("LOGISIM-PROJECT\n4\n"))
        assertTrue(framedBytes.startsWith("LOGISIM-PROJECT\n4\n"))
        val oldView = assertNotNull(WorkspaceCatalogFormat.readDocument(legacyBytes)).viewState
        val newView = assertNotNull(WorkspaceCatalogFormat.readDocument(framedBytes)).viewState
        assertEquals(WorkspaceViewportKeyFormat.LEGACY, oldView.viewportKeyFormat)
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, newView.viewportKeyFormat)
        val migrated = oldView.compatibleWith(project)
        assertEquals(mapOf(workspaceNavigationKey(literal.id, emptyList()) to viewport), migrated.viewports)
        assertEquals(mapOf(workspaceNavigationKey(leaf.id, emptyList()) to viewport), newView.compatibleWith(project).viewports)
        assertEquals(migrated, migrated.compatibleWith(project))
    }

    @Test fun newKeysKeepBothInstancesWhoseLegacyKeysWereIdentical() {
        val leaf = CircuitDocument("Leaf", id = "leaf")
        val other = CircuitDocument("Other", id = "leaf:3")
        val main = CircuitDocument("main", listOf(
            CircuitComponent("1:y", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "Leaf"),
            CircuitComponent("y", ComponentKind.SUBCIRCUIT, GridPoint(100, 0), circuitName = "Other"),
        ))
        val project = CircuitProject(listOf(main, leaf, other))
        val first = workspaceNavigationKey(leaf.id, listOf("1:y"))
        val second = workspaceNavigationKey(other.id, listOf("y"))
        assertEquals("4:leaf:3:1:y", first)
        assertEquals("6:leaf:3:1:y", second)
        assertNotEquals(first, second)
        val expected = mapOf(first to WorkspaceViewport(2f, -10f, 20f), second to WorkspaceViewport(3f, 30f, -40f),
            workspaceNavigationKey(leaf.id, emptyList()) to WorkspaceViewport(0.5f, 50f, 60f))
        val stored = document(WorkspaceViewState(leaf.id, viewports = expected + mapOf(
            workspaceNavigationKey(leaf.id, listOf("y")) to WorkspaceViewport(4f, 0f, 0f),
            "$first:" to WorkspaceViewport(5f, 0f, 0f)), activeInstancePath = listOf("1:y"),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED))

        val reopened = assertNotNull(WorkspaceCatalogFormat.readDocument(WorkspaceCatalogFormat.writeDocument(stored)))
        val restored = reopened.viewState.compatibleWith(project)
        assertEquals(expected, restored.viewports)
        assertEquals(listOf("1:y"), restored.activeInstancePath)
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, restored.viewportKeyFormat)
    }

    @Test fun malformedFramedKeysNeverFallBackToLegacyIds() {
        val project = CircuitProject(listOf(CircuitDocument("main"), CircuitDocument("Literal", id = "not-a-frame")))
        val view = WorkspaceViewState("main", viewports = mapOf("not-a-frame" to WorkspaceViewport(2f, 10f, 20f)),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED)
        assertEquals(emptyMap(), view.compatibleWith(project).viewports)
    }

    @Test fun metadataOnlyRewritesKeepTheLegacyEncodingUntilMigration() {
        val original = document(WorkspaceViewState("main", viewports = mapOf("4:leaf" to WorkspaceViewport(2f, 10f, 20f))))
        val reopened = assertNotNull(WorkspaceCatalogFormat.readDocument(WorkspaceCatalogFormat.writeDocument(original)))
        val renamed = reopened.copy(info = reopened.info.copy(name = "Renamed"))
        val rewritten = WorkspaceCatalogFormat.writeDocument(renamed)
        assertTrue(rewritten.startsWith("LOGISIM-PROJECT\n4\n"))
        val saved = assertNotNull(WorkspaceCatalogFormat.readDocument(rewritten))
        assertEquals(original.xml, saved.xml)
        assertEquals(original.viewState, saved.viewState)
    }

    @Test fun conflictingLegacyAliasesAreDroppedInsteadOfChoosingTheLastValue() {
        val project = CircuitProject(listOf(CircuitDocument("Leaf", id = "leaf")))
        val viewport = WorkspaceViewport(2f, 10f, 20f)
        val view = WorkspaceViewState("leaf", viewports = mapOf("leaf" to viewport, "Leaf" to WorkspaceViewport(3f, 30f, 40f)))
        assertEquals(emptyMap(), view.compatibleWith(project).viewports)
        val matching = view.copy(viewports = mapOf("leaf" to viewport, "Leaf" to viewport)).compatibleWith(project)
        assertEquals(mapOf(workspaceNavigationKey("leaf", emptyList()) to viewport), matching.viewports)
    }

    @Test fun activeCircuitIdsTakePrecedenceOverAnotherCircuitsLegacyName() {
        val named = CircuitDocument("target", listOf(CircuitComponent("other", ComponentKind.INPUT, GridPoint(100, 0))), id = "old")
        val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
        val target = CircuitDocument("Actual", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))),
            listOf(wire), id = "target")
        val project = CircuitProject(listOf(named, target))
        val key = workspaceNavigationKey(target.id, emptyList())
        val viewports = mapOf(key to WorkspaceViewport(2f, 10f, 20f))
        val restored = WorkspaceViewState(target.id, setOf("input", "other"), viewports,
            selectedWires = setOf(wire, WireSegment(GridPoint(100, 0), GridPoint(120, 0))),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED).compatibleWith(project)

        assertEquals(target.id, restored.activeCircuitId)
        assertEquals(setOf("input"), restored.selectedComponentIds)
        assertEquals(setOf(wire), restored.selectedWires)
        assertEquals(viewports, restored.viewports)
        val legacy = WorkspaceViewState(target.name, setOf("input"))
        assertEquals(target.id, legacy.compatibleWith(project).activeCircuitId)
        val current = legacy.copy(viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED).compatibleWith(project)
        assertEquals(project.mainCircuit.id, current.activeCircuitId)
        assertEquals(emptySet(), current.selectedComponentIds)
    }

    private fun document(view: WorkspaceViewState) = WorkspaceDocument(
        WorkspaceProjectInfo(WorkspaceProjectId("p1"), "A", 1, 0, 0),
        "<project><circuit name=\"main\"><comp name=\"Future component\"/></circuit></project>", view)
}
