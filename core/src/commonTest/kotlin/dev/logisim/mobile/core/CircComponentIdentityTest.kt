package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CircComponentIdentityTest {
    private fun imported(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
    private fun reopened(project: CircuitProject) = imported(CircCodec.writeProject(project))
    private val legacyInstances = """<project><main name="main"/><circuit name="main">
        <comp name="child" loc="(100,100)"/><comp name="child" loc="(200,100)"/><comp name="child" loc="(300,100)"/>
        </circuit><circuit name="child"/></project>"""
    private fun pins(vararg attributes: String) = "<project><lib name=\"w\" desc=\"#Wiring\"/><circuit name=\"main\">" +
        attributes.mapIndexed { index, extra -> "<comp lib=\"w\" name=\"Pin\" loc=\"(${index * 100},100)\" $extra/>" }.joinToString("") +
        "</circuit></project>"

    @Test
    fun removingEarlierInstanceDoesNotRetargetStoredRouteOrViewport() {
        val original = imported(legacyInstances)
        val selected = original.mainCircuit.components[1]
        assertEquals("import-2", selected.id)
        val route = workspaceNavigationKey("child", listOf(selected.id))
        val view = WorkspaceViewState(activeCircuitId = "child", activeInstancePath = listOf(selected.id),
            viewports = mapOf(route to WorkspaceViewport(2f, -10f, 30f)),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED)
        val edited = original.withCircuit(original.mainCircuit.copy(components = original.mainCircuit.components.drop(1)))
        val document = WorkspaceDocument(WorkspaceProjectInfo(WorkspaceProjectId("component-identity"), "Stable components", 1, 0, 0),
            CircCodec.writeProject(edited), view)
        val payload = WorkspaceCatalogFormat.writeDocument(document)
        assertTrue(payload.startsWith("LOGISIM-PROJECT\n4\n"))
        val stored = assertNotNull(WorkspaceCatalogFormat.readDocument(payload))
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, stored.viewState.viewportKeyFormat)
        val saved = imported(stored.xml)
        assertEquals(listOf("import-2", "import-3"), saved.mainCircuit.components.map { it.id })
        assertEquals(GridPoint(200, 100), saved.mainCircuit.components.single { it.id == selected.id }.position)
        val restoration = stored.viewState.restoreStored(saved)
        assertFalse(restoration.recovered)
        assertEquals(listOf("import-2"), restoration.viewState.activeInstancePath)
        assertEquals(mapOf(route to WorkspaceViewport(2f, -10f, 30f)), restoration.viewState.viewports)
    }

    @Test
    fun removedInstanceCannotBecomeAnotherSurvivingInstance() {
        val original = imported(legacyInstances)
        val route = workspaceNavigationKey("child", listOf("import-2"))
        val view = WorkspaceViewState(activeCircuitId = "child", activeInstancePath = listOf("import-2"),
            viewports = mapOf(route to WorkspaceViewport(2f, 10f, 20f)),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED)
        val saved = reopened(original.withCircuit(original.mainCircuit.copy(
            components = original.mainCircuit.components.filter { it.id != "import-2" })))
        assertEquals(listOf("import-1", "import-3"), saved.mainCircuit.components.map { it.id })
        val restored = view.compatibleWith(saved)
        assertEquals(emptyList(), restored.activeInstancePath)
        assertEquals(emptyMap(), restored.viewports)
    }

    @Test
    fun newlyCreatedAndMovedComponentsRetainEditorAssignedIdentities() {
        val editor = CircuitEditor(CircuitDocument("main"))
        val first = editor.add(ComponentKind.INPUT, GridPoint(100, 100))
        val second = editor.add(ComponentKind.INPUT, GridPoint(200, 100))
        editor.move(second, GridPoint(250, 150))
        val saved = reopened(CircuitProject(listOf(editor.document)))
        assertEquals(listOf(first, second), saved.mainCircuit.components.map { it.id })
        assertEquals(GridPoint(250, 150), saved.mainCircuit.components.single { it.id == second }.position)
    }

    @Test
    fun duplicatingImportedComponentOverridesStaleRawIdentityAttribute() {
        val original = imported(pins("logisim-mobile-id=\"original\""))
        val editor = CircuitEditor(original.mainCircuit)
        val duplicate = editor.paste(editor.copy(setOf("original")), GridPoint(100, 0)).single()
        assertNotEquals("original", duplicate)
        val saved = reopened(original.withCircuit(editor.document))
        assertEquals(setOf("original", duplicate), saved.mainCircuit.components.map { it.id }.toSet())
        assertEquals(GridPoint(0, 100), saved.mainCircuit.components.single { it.id == "original" }.position)
        assertEquals(GridPoint(100, 100), saved.mainCircuit.components.single { it.id == duplicate }.position)
        assertEquals(duplicate, saved.mainCircuit.components.single { it.id == duplicate }.xmlAttributes[CircCodec.COMPONENT_ID_ATTRIBUTE])
    }

    @Test
    fun componentOrderAndPositionDoNotDefineTheIdentity() {
        val original = imported(pins("logisim-mobile-id=\"alpha\"", "logisim-mobile-id=\"beta\"", "logisim-mobile-id=\"gamma\""))
        val changed = original.mainCircuit.copy(components = original.mainCircuit.components.reversed().map {
            it.copy(position = GridPoint(it.position.x + 20, it.position.y + 30))
        })
        val saved = reopened(original.withCircuit(changed))
        assertEquals(listOf("gamma", "beta", "alpha"), saved.mainCircuit.components.map { it.id })
        assertEquals(GridPoint(120, 130), saved.mainCircuit.components.single { it.id == "beta" }.position)
    }

    @Test
    fun explicitIdsAreReservedBeforeAssigningIdsToLegacyComponents() {
        val project = imported(pins("", "logisim-mobile-id=\"import-1\"", ""))
        assertEquals(listOf("import-2", "import-1", "import-3"), project.mainCircuit.components.map { it.id })
        assertEquals(project.mainCircuit.components.map { it.id }, reopened(project).mainCircuit.components.map { it.id })
        assertEquals(listOf("import-1", "import-2", "import-3"), imported(pins("", "", "")).mainCircuit.components.map { it.id })
    }

    @Test
    fun blankDuplicateAndInvalidXmlIdentitiesAreRejectedBeforeReplacingTheDocument() {
        for (value in listOf("", " ", "&#9;", "&#10;", "&#0;", "a/b")) {
            assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(pins("logisim-mobile-id=\"$value\"")))
        }
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(pins("logisim-mobile-id=\"same\"", "logisim-mobile-id=\"same\"")))
    }

    @Test
    fun writerRejectsAnIdentityContainingInvalidXmlCharacters() {
        val invalid = CircuitProject(listOf(CircuitDocument("main", listOf(
            CircuitComponent("invalid\u0000id", ComponentKind.INPUT, GridPoint(0, 0)),
        ))))
        assertFailsWith<IllegalArgumentException> { CircCodec.writeProject(invalid) }
    }

    @Test
    fun opaqueUnicodeAndPunctuationIdsAreEscapedAndPreservedExactly() {
        val ids = listOf("instância:outer .inner[2]", "ampersand & quotation \" ' < >", "line\nbreak", " space around ")
        val project = CircuitProject(listOf(CircuitDocument("main", ids.mapIndexed { index, id ->
            CircuitComponent(id, ComponentKind.INPUT, GridPoint(index * 100, 100))
        })))
        assertEquals(ids, reopened(project).mainCircuit.components.map { it.id })
        val sameLocalId = CircuitProject(listOf(CircuitDocument("first", listOf(CircuitComponent("local", ComponentKind.INPUT, GridPoint(0, 0)))),
            CircuitDocument("second", listOf(CircuitComponent("local", ComponentKind.INPUT, GridPoint(0, 0))))))
        assertEquals(listOf("local", "local"), reopened(sameLocalId).circuits.flatMap { it.components }.map { it.id })
    }

    @Test
    fun registeredMemoryIdsAndBoundPinIdsRemainStableAcrossReordering() {
        val child = CircuitDocument("child", listOf(
            CircuitComponent("pin-in", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("memory-state", ComponentKind.RAM, GridPoint(100, 100)),
            CircuitComponent("pin-out", ComponentKind.OUTPUT, GridPoint(200, 0)),
        ))
        val parent = CircuitDocument("main", listOf(CircuitComponent("instance-state", ComponentKind.SUBCIRCUIT,
            GridPoint(300, 300), circuitName = child.name)))
        val original = reopened(CircuitProject(listOf(parent, child)))
        val saved = reopened(original.withCircuit(original.circuit("child")!!.copy(components = original.circuit("child")!!.components.reversed())))
        assertEquals("instance-state", saved.mainCircuit.components.single().id)
        assertEquals(setOf("pin-in", "pin-out"), saved.mainCircuit.components.single().explicitPorts.map { it.name }.toSet())
        assertEquals("memory-state", saved.circuit("child")!!.components.single { it.kind == ComponentKind.RAM }.id)
        assertTrue(saved.mainCircuit.components.single().explicitPorts == original.mainCircuit.components.single().explicitPorts)
    }

    @Test
    fun idsAlsoSurvivePortableLibraryGraphExport() {
        val child = pins("logisim-mobile-id=\"library-pin\"").replace("name=\"main\"", "name=\"child\"")
        val root = "<project><lib name=\"x\" desc=\"file#child.circ\"/><circuit name=\"main\"><comp name=\"child\" lib=\"x\" loc=\"(100,100)\" logisim-mobile-id=\"root-instance\"/></circuit></project>"
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ ->
            CircuitLibrarySource("child", child)
        })).resolved
        val saved = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(CircLibraryCodec.bundle(loaded))).resolved.project
        assertEquals("root-instance", saved.mainCircuit.components.single().id)
        assertEquals("library-pin", saved.circuits.single { it.id != saved.mainCircuit.id }.components.single().id)
    }

    @Test
    fun foreignIdMetadataIsNotTreatedAsTheMobileIdentity() {
        val original = imported(pins("id=\"third-party-id\""))
        assertEquals("import-1", original.mainCircuit.components.single().id)
        val saved = reopened(original)
        assertEquals("import-1", saved.mainCircuit.components.single().id)
        assertEquals("third-party-id", saved.mainCircuit.components.single().xmlAttributes["id"])
    }
}
