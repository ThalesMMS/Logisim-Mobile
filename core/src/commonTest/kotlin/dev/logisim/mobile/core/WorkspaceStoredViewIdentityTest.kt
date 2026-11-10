package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStoredViewIdentityTest {
    private val wire = WireSegment(GridPoint(0, 0), GridPoint(20, 0))
    private val rootViewport = WorkspaceViewport(0.5f, 10f, 20f)
    private val definitionViewport = WorkspaceViewport(1f, 30f, 40f)
    private val instanceViewport = WorkspaceViewport(2f, -50f, 60f)
    private val marked = mapOf("import-1" to "import-1", "import-2" to "import-2", "import-3" to "import-3")

    @Test
    fun liveCompatibilityKeepsUnmarkedInstanceAndComponentIdentities() {
        val view = nestedView()

        val compatible = view.compatibleWith(nestedProject())

        assertEquals(view, compatible)
        assertEquals(listOf("import-1", "import-2"), compatible.activeInstancePath)
        assertEquals(setOf("import-3"), compatible.selectedComponentIds)
        assertEquals(setOf(wire), compatible.selectedWires)
    }

    @Test
    fun storedUnmarkedContextDropsInstanceStateButKeepsDefinitionViews() {
        val restored = nestedView().restoreStored(nestedProject())

        assertTrue(restored.recovered)
        assertEquals("leaf:definition", restored.viewState.activeCircuitId)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
        ), restored.viewState.viewports)
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, restored.viewState.viewportKeyFormat)
    }

    @Test
    fun storedContextWithMatchingMarkersKeepsRouteSelectionAndViews() {
        val view = nestedView()

        val restored = view.restoreStored(nestedProject(marked))

        assertFalse(restored.recovered)
        assertEquals(view, restored.viewState)
    }

    @Test
    fun partiallyMarkedNestedRouteDoesNotRetargetSelectionToItsMarkedPrefix() {
        val prefixViewport = WorkspaceViewport(3f, 70f, -80f)
        val view = nestedView().copy(viewports = nestedView().viewports +
            ("17:middle:definition:8:import-1" to prefixViewport))
        val project = nestedProject(mapOf("import-1" to "import-1", "import-3" to "import-3"))

        val restored = view.restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals("leaf:definition", restored.viewState.activeCircuitId)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
            "17:middle:definition:8:import-1" to prefixViewport,
        ), restored.viewState.viewports)
    }

    @Test
    fun aMarkedInnerInstanceCannotProveAnUnmarkedOuterInstance() {
        val project = nestedProject(mapOf("import-2" to "import-2", "import-3" to "import-3"))

        val restored = nestedView().restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals("leaf:definition", restored.viewState.activeCircuitId)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
        ), restored.viewState.viewports)
    }

    @Test
    fun aProvenInstanceRouteDoesNotProveAnUnmarkedSelectedComponent() {
        val project = nestedProject(mapOf("import-1" to "import-1", "import-2" to "import-2"))

        val restored = nestedView().restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals("leaf:definition", restored.viewState.activeCircuitId)
        assertEquals(listOf("import-1", "import-2"), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
            "15:leaf:definition:8:import-1:8:import-2" to instanceViewport,
        ), restored.viewState.viewports)
    }

    @Test
    fun rootSelectionWithoutAnIdentityMarkerIsClearedWithoutLosingItsViewport() {
        val view = rootView(setOf("import-1"))

        val restored = view.restoreStored(rootProject())

        assertTrue(restored.recovered)
        assertEquals("root:definition", restored.viewState.activeCircuitId)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf("15:root:definition" to rootViewport), restored.viewState.viewports)
    }

    @Test
    fun mismatchedInstanceMarkerCannotProveAStoredRoute() {
        val project = nestedProject(marked + ("import-1" to "import-9"))

        val restored = nestedView().restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals("leaf:definition", restored.viewState.activeCircuitId)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
        ), restored.viewState.viewports)
    }

    @Test
    fun rootSelectionKeepsOnlyMatchingMarkersAndClearsWiresAfterIdentityRecovery() {
        val view = rootView(setOf("import-1", "import-2"))
        val project = rootProject(mapOf("import-1" to "import-9", "import-2" to "import-2"))

        val restored = view.restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals(setOf("import-2"), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf("15:root:definition" to rootViewport), restored.viewState.viewports)
    }

    @Test
    fun rootWireOnlySelectionDoesNotRequireComponentIdentityMarkers() {
        val view = rootView(emptySet())

        val restored = view.restoreStored(rootProject())

        assertFalse(restored.recovered)
        assertEquals(view, restored.viewState)
        assertEquals(setOf(wire), restored.viewState.selectedWires)
    }

    @Test
    fun anUnmarkedInactiveInstanceViewDoesNotClearTheRootsWireSelection() {
        val view = rootView(emptySet()).copy(viewports = nestedView().viewports)

        val restored = view.restoreStored(nestedProject())

        assertTrue(restored.recovered)
        assertEquals("root:definition", restored.viewState.activeCircuitId)
        assertEquals(emptyList(), restored.viewState.activeInstancePath)
        assertEquals(setOf(wire), restored.viewState.selectedWires)
        assertEquals(mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
        ), restored.viewState.viewports)
    }

    @Test
    fun aDeletedActiveCircuitCannotRetargetItsSelectionToTheFallbackCircuit() {
        val project = rootProject(marked)
        val view = rootView(setOf("import-1")).copy(activeCircuitId = "deleted")

        val restored = view.restoreStored(project)

        assertTrue(restored.recovered)
        assertEquals("root:definition", restored.viewState.activeCircuitId)
        assertEquals(emptySet(), restored.viewState.selectedComponentIds)
        assertEquals(emptySet(), restored.viewState.selectedWires)
        assertEquals(mapOf("15:root:definition" to rootViewport), restored.viewState.viewports)
    }

    private fun nestedView() = WorkspaceViewState(
        activeCircuitId = "leaf:definition",
        selectedComponentIds = setOf("import-3"),
        viewports = mapOf(
            "15:root:definition" to rootViewport,
            "15:leaf:definition" to definitionViewport,
            "15:leaf:definition:8:import-1:8:import-2" to instanceViewport,
        ),
        activeInstancePath = listOf("import-1", "import-2"),
        selectedWires = setOf(wire),
        viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED,
    )

    private fun rootView(selected: Set<String>) = WorkspaceViewState(
        activeCircuitId = "root:definition",
        selectedComponentIds = selected,
        viewports = mapOf("15:root:definition" to rootViewport),
        selectedWires = setOf(wire),
        viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED,
    )

    private fun nestedProject(markers: Map<String, String> = emptyMap()): CircuitProject {
        val leaf = CircuitDocument("Leaf", listOf(
            component("import-3", ComponentKind.INPUT, markers = markers),
        ), listOf(wire), id = "leaf:definition")
        val middle = CircuitDocument("Middle", listOf(
            component("import-2", ComponentKind.SUBCIRCUIT, "Leaf", markers),
        ), id = "middle:definition")
        val root = CircuitDocument("main", listOf(
            component("import-1", ComponentKind.SUBCIRCUIT, "Middle", markers),
        ), listOf(wire), id = "root:definition")
        return CircuitProject(listOf(root, middle, leaf))
    }

    private fun rootProject(markers: Map<String, String> = emptyMap()) = CircuitProject(listOf(
        CircuitDocument("main", listOf(
            component("import-1", ComponentKind.INPUT, markers = markers),
            component("import-2", ComponentKind.INPUT, markers = markers),
        ), listOf(wire), id = "root:definition"),
    ))

    private fun component(
        id: String,
        kind: ComponentKind,
        circuitName: String? = null,
        markers: Map<String, String>,
    ) = CircuitComponent(
        id, kind, GridPoint(0, 0), circuitName = circuitName,
        xmlAttributes = markers[id]?.let { mapOf(CircCodec.COMPONENT_ID_ATTRIBUTE to it) }.orEmpty(),
    )
}
