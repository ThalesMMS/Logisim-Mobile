package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The test-vector text and run live with the tool, not with the sheet (#59). */
class TestVectorToolStateTest {
    private val registry = ComponentCatalog.registry
    private fun project(vararg extra: String) = CircuitProject(listOf(
        CircuitDocument("main", listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "A"),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(100, 0), "Y"),
        )),
    ) + extra.map { CircuitDocument(it) })

    @Test
    fun textAndRunSurviveClosingAndReopeningTheTool() {
        val state = TestVectorToolState()
        val project = project("half")
        state.bind(1, project)
        state.text = "A Y\n0 1"
        state.circuitName = "main"
        val session = assertNotNull(state.load(project, registry))
        // Reopening the sheet composes the same tool state again with the same document and project.
        state.bind(1, project)
        assertEquals("A Y\n0 1", state.text)
        assertEquals("main", state.circuitName)
        assertSame(session, state.session)
    }

    @Test
    fun aProjectChangeDropsTheRunSessionButKeepsTheText() {
        val state = TestVectorToolState()
        val before = project("half")
        state.bind(1, before)
        state.text = "A Y\n0 1"
        state.circuitName = "main"
        state.running = true
        assertNotNull(state.load(before, registry))

        val edited = project("half", "added")
        state.bind(1, edited)
        assertNull(state.session, "The session is bound to the previous project instance")
        assertEquals(false, state.running)
        assertEquals("A Y\n0 1", state.text)
        assertEquals("main", state.circuitName)

        // The chosen circuit is kept while it exists; a removed circuit falls back to the main circuit.
        state.circuitName = "half"
        state.bind(1, project("half"))
        assertEquals("half", state.circuit(project("half")))
        state.bind(1, project())
        assertNull(state.circuitName)
        assertEquals("main", state.circuit(project()))
    }

    @Test
    fun aDifferentDocumentClearsEverything() {
        val state = TestVectorToolState()
        val project = project()
        state.bind(1, project)
        state.text = "A Y\n0 1"
        state.circuitName = "main"
        state.notice = dev.logisim.mobile.core.localization.message("vector_file_opened", "name" to "and.txt").asResult()
        assertNotNull(state.load(project, registry))

        state.bind(2, project)
        assertEquals("", state.text)
        assertNull(state.circuitName)
        assertNull(state.session)
        assertNull(state.failure)
        assertNull(state.notice)
    }

    @Test
    fun aFailedCheckKeepsTheTextAndReportsTheError() {
        val state = TestVectorToolState()
        val project = project()
        state.bind(1, project)
        state.text = "A MISSING\n0 1"
        assertNull(state.load(project, registry))
        assertNotNull(state.failure)
        assertEquals("A MISSING\n0 1", state.text)
    }

    @Test
    fun vectorFilesAreUtf8WithAnOptionalByteOrderMark() {
        assertEquals("A B Y\n0 0 0", decodeVectorFile("\uFEFFA B Y\n0 0 0".encodeToByteArray()))
        assertEquals("A B Y\n0 0 0", decodeVectorFile("A B Y\n0 0 0".encodeToByteArray()))
        assertNull(decodeVectorFile(byteArrayOf(0x41, 0xC3.toByte(), 0x28)))
    }

    @Test
    fun anAbandonedCompositionDoesNotLeaveTheStateHalfBound() {
        val state = TestVectorToolState()
        val project = project()
        state.bind(1, project)
        state.text = "A Y\n0 1"
        // A composition for another document is abandoned: its snapshot, with every write bind() made, is discarded.
        val abandoned = Snapshot.takeMutableSnapshot()
        abandoned.enter { state.bind(2, project) }
        abandoned.dispose()
        assertEquals("A Y\n0 1", state.text)
        assertEquals(1L, state.documentIdentity)
        // The next composition for that document still clears the previous document's vectors.
        state.bind(2, project)
        assertEquals("", state.text)
        assertEquals(2L, state.documentIdentity)
    }

    @Test
    fun typedTextIsUnsavedUntilItIsOpenedFromOrSavedToAFile() {
        val state = TestVectorToolState()
        state.bind(1, project())
        assertFalse(state.hasUnsavedText, "An empty editor has nothing to lose")
        state.text = "A Y\n0 1"
        assertTrue(state.hasUnsavedText)
        state.opened("A Y\n1 1")
        assertEquals("A Y\n1 1", state.text)
        assertFalse(state.hasUnsavedText, "Opened text is kept in its file")
        state.text = "A Y\n1 0"
        assertTrue(state.hasUnsavedText)
        state.markSaved("A Y\n1 0")
        assertFalse(state.hasUnsavedText, "Text exported with Export vectors is kept in that file")
        state.text = "   \n"
        assertFalse(state.hasUnsavedText, "Blank text is not worth a confirmation")
        state.text = "A Y\n0 0"
        state.bind(2, project())
        assertFalse(state.hasUnsavedText, "Another document starts with nothing to lose")
    }

    @Test
    fun onlyTheCurrentFileRequestEndsOpening() {
        val state = TestVectorToolState()
        state.bind(1, project())
        val first = state.beginOpen()
        assertTrue(state.opening)
        val second = state.beginOpen()
        state.finishOpen(first)
        assertTrue(state.opening, "A replaced request cannot re-enable the panel while the newer picker is up")
        state.finishOpen(second)
        assertFalse(state.opening)

        val pending = state.beginOpen()
        state.bind(2, project())
        assertFalse(state.opening, "A document change ends the request")
        val next = state.beginOpen()
        state.finishOpen(pending)
        assertTrue(state.opening, "The previous document's late answer does not end the new request")
        state.finishOpen(next)
        assertFalse(state.opening)
    }

    /** Counts the deep comparisons of a project that holds it: a data class compares its circuit lists. */
    private class CountingCircuits(private val circuits: List<CircuitDocument>) : List<CircuitDocument> by circuits {
        var comparisons = 0
        override fun equals(other: Any?): Boolean { comparisons++; return circuits == other }
        override fun hashCode(): Int = circuits.hashCode()
    }

    /**
     * W2-10 R12-2: an equal project that is a new instance (an applyProject that changed nothing) is bound in place of
     * the old one, so binding it again on every recomposition stops at the identity check instead of comparing projects.
     */
    @Test
    fun anEqualProjectInstanceIsBoundSoLaterBindsCompareByIdentity() {
        val circuits = CountingCircuits(project("half").circuits)
        val first = CircuitProject(circuits)
        val same = first.copy()
        val state = TestVectorToolState()
        state.bind(1, first)
        state.text = "A Y\n0 1"
        assertNotNull(state.load(first, registry))
        state.bind(1, same)
        assertNotNull(state.session, "An equal project keeps the run session")
        circuits.comparisons = 0
        repeat(3) { state.bind(1, same) }
        assertEquals(0, circuits.comparisons, "Each recomposition compared the whole project again")
        assertEquals("A Y\n0 1", state.text)
        assertNotNull(state.session)
    }
}
