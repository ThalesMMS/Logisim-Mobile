package dev.logisim.mobile

import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ProjectCircuitLibraries
import dev.logisim.mobile.core.ProjectCircuitMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectMergeTest {
    private val active = """
        <project source="3.8.0"><main name="A"/>
          <circuit name="A"><comp name="Child" loc="(50,50)"/></circuit>
          <circuit name="Child"><a name="clabel" val="Original"/></circuit>
        </project>
    """.trimIndent()

    private val incoming = """
        <project source="2.7.1"><main name="Top"/>
          <circuit name="Top">
            <comp name="Child" loc="(50,50)"/>
            <comp name="Child_2" loc="(100,50)"/>
          </circuit>
          <circuit name="Child" logisim-mobile-library-id="external" logisim-mobile-library-name="External">
            <a name="clabel" val="Imported"/><comp name="Grandchild" loc="(50,50)"/>
          </circuit>
          <circuit name="Child_2"/>
          <circuit name="Grandchild"/>
          <circuit name="Unused"/>
        </project>
    """.trimIndent()

    @Test
    fun mergesOnlySelectedCircuitsAndDependenciesAsOneUndoableEdit() {
        val state = EditorState(active)
        val before = state.project
        val identity = state.documentIdentity
        val imported = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(
            DocumentAsset("B.circ", "application/octet-stream", incoming.encodeToByteArray()), state.componentRegistry,
        )).project
        state.applyProject(ProjectCircuitMerge.copySelected(state.project, imported, setOf("Top")))

        assertEquals(identity, state.documentIdentity)
        assertEquals("A", state.document.name)
        assertEquals(before.circuits, state.project.circuits.take(before.circuits.size))
        assertEquals(listOf("A", "Child", "Grandchild", "Child_3", "Child_2", "Top"),
            state.project.circuits.map { it.name })
        assertEquals(state.project.circuits.size, state.project.circuits.map { it.id }.toSet().size)
        assertEquals(listOf("Child_3", "Child_2"), assertNotNull(state.project.circuit("Top")).components.map { it.circuitName })
        assertEquals("Grandchild", assertNotNull(state.project.circuit("Child_3")).components.single().circuitName)
        assertEquals("Imported", state.project.circuit("Child_3")?.attributes?.get("clabel"))
        assertEquals(emptyList(), ProjectCircuitLibraries.list(state.project))
        assertNull(state.project.circuit("Unused"))

        val merged = state.project
        state.undo()
        assertEquals(before, state.project)
        assertFalse(state.canUndo)
        state.redo()
        assertEquals(merged, state.project)

        val reopened = EditorState(state.exportXml())
        assertEquals("A", reopened.project.mainCircuitName)
        assertEquals("Child", reopened.project.mainCircuit.components.single().circuitName)
        assertEquals(listOf("Child_3", "Child_2"), assertNotNull(reopened.project.circuit("Top")).components.map { it.circuitName })
        assertEquals("Grandchild", reopened.project.circuit("Child_3")?.components?.single()?.circuitName)
    }
}
