package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorExampleLoadingTest {
    @Test
    fun openingExampleCreatesFreshIdentityAndUndoRestoresTheOriginalProject() {
        val state = EditorState(null)
        val original = state.project
        val identity = state.documentIdentity
        var replacements = 0
        state.onWorkspaceProjectReplaced = { replacements++ }
        val session = ExampleRunner().initialize(CircuitExamples.find("sap1")!!)
        state.installExample(session)
        assertNotEquals(identity, state.documentIdentity)
        assertEquals(1, replacements)
        assertEquals(session.state.portVectors, state.rootSimulation.portVectors)
        state.undo()
        assertEquals(original, state.project)
        assertEquals(identity, state.documentIdentity)
    }

    @Test
    fun exampleStartupSurvivesWorkspaceRecreationAndReset() {
        val original = EditorState(null)
        val example = CircuitExamples.all.first { it.startup.isNotEmpty() }
        original.installExample(ExampleRunner().initialize(example))
        val reopened = EditorState(original.exportXml())
        assertEquals(original.rootSimulation.vectorValues, reopened.rootSimulation.vectorValues)
        reopened.resetSimulation()
        assertTrue(reopened.rootSimulation.vectorValues.isNotEmpty())
        assertEquals(original.rootSimulation.vectorValues, reopened.rootSimulation.vectorValues)
    }
}
