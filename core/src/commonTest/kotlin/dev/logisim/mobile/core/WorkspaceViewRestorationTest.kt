package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceViewRestorationTest {
    @Test fun nestedInstanceRouteAndItsOwnViewportSurviveAStoredRevision() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
        val document = WorkspaceDocument(WorkspaceProjectInfo(WorkspaceProjectId("p1"), "A", 1, 0, 0),
            "<project><circuit name=\"main\"/></project>", WorkspaceViewState(
                activeCircuitId = "leaf", selectedComponentIds = setOf("input"),
                viewports = mapOf(workspaceNavigationKey("leaf", listOf("outer", "inner")) to WorkspaceViewport(2f, -10f, 20f)),
                activeInstancePath = listOf("outer", "inner"), selectedWires = setOf(wire),
                viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED))
        val saved = WorkspaceCatalogFormat.writeDocument(document)
        assertTrue(saved.startsWith("LOGISIM-PROJECT\n4\n"))
        assertEquals(document, WorkspaceCatalogFormat.readDocument(saved))
    }

    @Test fun restorationValidatesEachInstanceAndDropsOnlyStaleSelection() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
        val leaf = CircuitDocument("Leaf", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))),
            listOf(wire), id = "leaf:definition")
        val middle = CircuitDocument("Middle", listOf(CircuitComponent("inner:instance", ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "Leaf")), id = "middle")
        val main = CircuitDocument("main", listOf(CircuitComponent("outer", ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "Middle")))
        val project = CircuitProject(listOf(main, middle, leaf))
        val path = listOf("outer", "inner:instance")
        val route = workspaceNavigationKey(leaf.id, path)
        val definition = workspaceNavigationKey(leaf.id, emptyList())
        val invalid = workspaceNavigationKey(leaf.id, listOf("outer", "removed"))
        val restored = WorkspaceViewState(leaf.id, setOf("input", "removed"),
            mapOf(route to WorkspaceViewport(2f, 10f, 20f), definition to WorkspaceViewport(1f, 0f, 0f),
                invalid to WorkspaceViewport(3f, 0f, 0f), "$route:" to WorkspaceViewport(4f, 0f, 0f)),
            path, setOf(wire, WireSegment(GridPoint(100, 0), GridPoint(120, 0))),
            WorkspaceViewportKeyFormat.LENGTH_PREFIXED).compatibleWith(project)
        assertEquals(path, restored.activeInstancePath)
        assertEquals(setOf("input"), restored.selectedComponentIds)
        assertEquals(setOf(wire), restored.selectedWires)
        assertEquals(setOf(route, definition), restored.viewports.keys)
        assertEquals(emptyList(), restored.copy(activeInstancePath = listOf("removed")).compatibleWith(project).activeInstancePath)
    }

    @Test fun versionOnePayloadsStillOpenWithAnEmptyInstanceRoute() {
        val old = VERSION_ONE
        val restored = assertNotNull(WorkspaceCatalogFormat.readDocument(old))
        assertEquals("p1", restored.info.id.value)
        assertEquals("<project><circuit name=\"main\"/></project>", restored.xml)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(WorkspaceViewportKeyFormat.LEGACY, restored.viewState.viewportKeyFormat)
    }

    @Test fun overlappingCircuitIdsDoNotHideAValidStoredInstanceViewport() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
        val leaf = CircuitDocument("Leaf", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))),
            listOf(wire), id = "leaf:definition")
        val outerId = "outer:instance"
        val innerId = "inner:instance"
        val middle = CircuitDocument("Middle", listOf(CircuitComponent(innerId, ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "Leaf")), id = "middle:definition")
        val main = CircuitDocument("main", listOf(CircuitComponent(outerId, ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "Middle")), id = "root:definition")
        val invalidSyntaxPrefix = CircuitDocument("Prefix", id = "${leaf.id}:${outerId.length}")
        val invalidRoutePrefix = CircuitDocument("Longer prefix", id = "${leaf.id}:${outerId.length}:$outerId")
        val project = CircuitProject(listOf(main, middle, leaf, invalidSyntaxPrefix, invalidRoutePrefix))
        val path = listOf(outerId, innerId)
        val instance = legacyNavigationKey(leaf.id, path)
        val definition = legacyNavigationKey(leaf.id, emptyList())
        val prefixDefinition = legacyNavigationKey(invalidRoutePrefix.id, emptyList())
        val viewports = mapOf(instance to WorkspaceViewport(2f, -10f, 20f),
            definition to WorkspaceViewport(0.5f, 30f, -40f), prefixDefinition to WorkspaceViewport(3f, 50f, 60f))
        val invalidTarget = legacyNavigationKey(invalidRoutePrefix.id, path)
        val document = WorkspaceDocument(WorkspaceProjectInfo(WorkspaceProjectId("p1"), "A", 1, 0, 0),
            CircCodec.writeProject(project), WorkspaceViewState(leaf.id, setOf("input", "removed"),
                viewports + mapOf(invalidTarget to WorkspaceViewport(4f, 0f, 0f), "$instance:" to WorkspaceViewport(5f, 0f, 0f)),
                path, setOf(wire, WireSegment(GridPoint(100, 0), GridPoint(120, 0)))))

        val reopened = assertNotNull(WorkspaceCatalogFormat.readDocument(WorkspaceCatalogFormat.writeDocument(document)))
        val restored = reopened.viewState.compatibleWith(project)
        assertEquals(mapOf(
            workspaceNavigationKey(leaf.id, path) to WorkspaceViewport(2f, -10f, 20f),
            workspaceNavigationKey(leaf.id, emptyList()) to WorkspaceViewport(0.5f, 30f, -40f),
            workspaceNavigationKey(invalidRoutePrefix.id, emptyList()) to WorkspaceViewport(3f, 50f, 60f),
        ), restored.viewports)
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, restored.viewportKeyFormat)
        assertEquals(path, restored.activeInstancePath)
        assertEquals(setOf("input"), restored.selectedComponentIds)
        assertEquals(setOf(wire), restored.selectedWires)
    }

    @Test fun ambiguousViewportKeysAreDroppedWithoutChangingTheActiveSelection() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
        val leaf = CircuitDocument("Leaf", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))),
            listOf(wire), id = "leaf")
        val other = CircuitDocument("Other", id = "leaf:3")
        val main = CircuitDocument("main", listOf(
            CircuitComponent("1:y", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "Leaf"),
            CircuitComponent("y", ComponentKind.SUBCIRCUIT, GridPoint(100, 0), circuitName = "Other"),
        ))
        val legacy = CircuitDocument("leaf:3:1:y", id = "legacy")
        val project = CircuitProject(listOf(main, leaf, other, legacy))
        val path = listOf("1:y")
        val ambiguous = legacyNavigationKey(leaf.id, path)
        assertEquals("leaf:3:1:y", ambiguous)
        assertEquals(ambiguous, legacyNavigationKey(other.id, listOf("y")))
        val definition = workspaceNavigationKey(leaf.id, emptyList())
        val expected = mapOf(definition to WorkspaceViewport(0.5f, 10f, 20f))
        val restored = WorkspaceViewState(leaf.id, setOf("input", "removed"),
            mapOf(legacyNavigationKey(leaf.id, emptyList()) to WorkspaceViewport(0.5f, 10f, 20f),
                ambiguous to WorkspaceViewport(2f, -10f, -20f)), path,
            setOf(wire, WireSegment(GridPoint(100, 0), GridPoint(120, 0)))).compatibleWith(project)

        assertEquals(expected, restored.viewports)
        assertEquals(path, restored.activeInstancePath)
        assertEquals(setOf("input"), restored.selectedComponentIds)
        assertEquals(setOf(wire), restored.selectedWires)
    }

    companion object {
        private fun legacyNavigationKey(circuitId: String, path: List<String>): String =
            "$circuitId:${path.joinToString(":") { "${it.length}:$it" }}"

        private const val VERSION_ONE = "LOGISIM-PROJECT\n1\na6ffe6f2cdf52827\n\n2:p11:A1:11:01:05:false1:01:041:<project><circuit name=\"main\"/></project>0:1:01:0"
    }
}
