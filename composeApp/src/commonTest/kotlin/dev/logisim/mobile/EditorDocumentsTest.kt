package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDocumentsTest {
    private val reference = DocumentReference("content://project", "project.circ", true, "v1", DocumentOpenMode.EDIT_IN_PLACE)

    @Test
    fun rejectedInitialDocumentCannotOverwriteItsAssociatedFile() {
        val original = "<not-a-circuit/>\n"
        val actions = FakeDocuments(reference)
        val state = EditorState(original)
        val session = EditorDocuments(state, actions)
        session.attach()
        assertFalse(session.canSave)
        session.save()
        assertEquals(0, actions.saveCalls)
        assertEquals(original, state.exportXml())
    }

    @Test
    fun restoredWorkspaceAssociationIsImmediatelyUsedByTheFileMenu() {
        val actions = FakeDocuments(null)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        assertFalse(session.canSave)
        actions.retain(reference)
        assertEquals(reference, session.reference)
        assertTrue(session.canSave)
        session.save()
        assertEquals(1, actions.saveCalls)
    }

    @Test
    fun nativeOpenFailureKeepsProjectAndFileAssociation() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val original = state.project
        actions.deliver(DocumentOpenResult.Failure("permission revoked"))
        assertEquals(original, state.project)
        assertEquals(reference, session.reference)
        assertEquals("permission revoked", state.importError)
    }

    @Test
    fun rejectedImportKeepsProjectAndFileAssociation() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val original = state.project
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(reference.copy(id = "bad"), "<broken/>")))
        assertEquals(original, state.project)
        assertEquals(reference, session.reference)
        assertEquals(reference, actions.currentReference)
        assertNotNull(state.importError)
    }

    @Test
    fun legacyFileOpensWithConversionReportAndLossyFileLeavesTheSessionUntouched() {
        val state = EditorState(null)
        val actions = FakeDocuments(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val legacy = """
            <project source="2.0.3" version="1.0">
              <lib name="0" desc="#Base"/>
              <circuit name="Legacy"><comp lib="0" name="Pin" loc="(100,100)"/></circuit>
            </project>
        """.trimIndent()

        actions.deliver(DocumentOpenResult.Opened(DocumentContent(reference, legacy)))

        assertEquals("Legacy", state.project.mainCircuitName)
        assertEquals("2.7.1", state.project.sourceVersion)
        assertTrue(state.importReportVisible)
        assertTrue(assertNotNull(state.importReport).migrations.any { it.rule == "legacy-project-converted" })
        val saved = state.exportXml()
        val invalid = legacy.replace("#Base", "#Legacy")
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(reference.copy(id = "obsolete"), invalid)))

        assertEquals(saved, state.exportXml())
        assertEquals(reference, session.reference)
        assertEquals(0, actions.saveCalls)
        assertTrue(state.importError.orEmpty().contains("Legacy"))
    }

    @Test
    fun importCopyDoesNotPermitOverwritingSource() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val xml = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("imported"))))
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(reference.copy(mode = DocumentOpenMode.IMPORT_COPY), xml)))
        assertEquals("imported", state.document.name)
        assertNull(session.reference)
        assertFalse(session.canSave)
        session.save()
        assertEquals(0, actions.saveCalls)
    }

    @Test
    fun fileAssociationSurvivesEditsButNotReplacingOrUndoingReplacement() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val xml = state.exportXml()
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(reference, xml)))
        state.selectComponent(state.document.components.first().id)
        state.updateSelectedLabel("changed")
        state.undo()
        assertEquals(reference, session.reference)
        state.newDocument()
        assertNull(session.reference)
        state.undo()
        assertNull(session.reference)
    }

    @Test
    fun saveConflictReportsErrorWithoutReplacingTheProject() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        val original = state.project
        session.save()
        actions.writeCallback?.invoke(DocumentWriteResult.Conflict(reference.copy(revision = "v2")))
        assertEquals(original, state.project)
        assertTrue(state.importError.orEmpty().contains("outro aplicativo"))
    }

    @Test
    fun originalCopySharesConvertedBytesAndLeavesTheNativeDocumentAlone() {
        val state = EditorState("""
            <project source="2.7.1" version="1.0">
              <lib desc="#I/O" name="0"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(300,300)" name="Hex Digit Display">
                  <a name="logisim-mobile-original-hex-digit" val="false"/>
                </comp>
                <wire from="(320,300)" to="(320,400)"/>
              </circuit>
            </project>
        """.trimIndent())
        val actions = FakeDocuments(reference)
        val session = EditorDocuments(state, actions)
        val nativeXml = state.exportXml()
        assertTrue(session.canShareOriginalCopy)

        session.shareOriginalCopy()

        val (name, xml) = assertNotNull(actions.shared)
        assertEquals("main-2.7.1.circ", name)
        val exported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))
        assertTrue(exported.project.mainCircuit.wires.any { it.contains(GridPoint(310, 300)) })
        assertEquals(nativeXml, state.exportXml())
        assertEquals(reference, session.reference)
    }

    @Test
    fun incompatibleMultiplierPreventsSharingOnlyTheOriginalCopy() {
        val state = EditorState("""
            <project source="2.7.1" version="1.0">
              <lib desc="#Arithmetic" name="0"/>
              <main name="main"/>
              <circuit name="main"/>
              <circuit name="multiply">
                <comp lib="0" loc="(400,300)" name="Multiplier">
                  <a name="width" val="32"/>
                  <a name="logisim-mobile-original-multiplier" val="false"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent())
        val actions = FakeDocuments(reference)
        val session = EditorDocuments(state, actions)
        val nativeXml = state.exportXml()
        assertTrue(session.canShareOriginalCopy)

        session.shareOriginalCopy()

        assertNull(actions.shared)
        assertTrue(state.importError.orEmpty().contains("(400, 300)"))
        assertTrue(state.importError.orEmpty().contains("multiply"))
        assertEquals(nativeXml, state.exportXml())
        session.share()
        val (_, sharedXml) = assertNotNull(actions.shared)
        assertEquals(nativeXml, sharedXml)
        val saved = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(sharedXml))
        val multiplier = saved.project.circuits.single { it.name == "multiply" }.components.single()
        assertEquals("false", multiplier.attributes["logisim-mobile-original-multiplier"])
        assertEquals(reference, session.reference)
    }

    @Test
    fun completingAnOldSaveDoesNotAssociateTheNewProjectWithItsFile() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        val session = EditorDocuments(state, actions)
        session.attach()
        session.saveAs()
        state.newDocument()
        val newProject = state.project
        actions.writeCallback?.invoke(DocumentWriteResult.Saved(reference))
        assertNull(session.reference)
        assertEquals(newProject, state.project)
    }

    @Test
    fun toolProjectEditsShareTheUndoHistory() {
        val state = EditorState(null)
        val original = state.project
        state.applyProject(original.addCircuit(CircuitDocument("generated")))
        assertEquals(2, state.project.circuits.size)
        state.undo()
        assertEquals(original, state.project)
        state.redo()
        assertEquals(listOf(original.mainCircuitName, "generated"), state.project.circuits.map { it.name })
    }

    @Test
    fun aLateNativeCompletionCannotRetainAFileAfterDetach() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        var current: (() -> Boolean)? = null
        var complete: ((Boolean) -> Unit)? = null
        val session = EditorDocuments(state, actions) { _, isCurrent, onComplete ->
            current = isCurrent
            complete = onComplete
        }
        session.attach()
        val imported = reference.copy(id = "late")
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(imported, state.exportXml())))
        assertTrue(current!!.invoke())
        session.detach()
        assertFalse(current!!.invoke())
        complete!!.invoke(true)
        assertEquals(reference, actions.currentReference)
    }

    @Test
    fun theFileReferenceIsScopedAroundAcceptanceAfterTheNativeHandlerReturns() {
        val actions = FakeDocuments(reference)
        val state = EditorState(null)
        var observedReference: DocumentReference? = null
        state.onWorkspaceProjectReplaced = { observedReference = actions.activeImportReference }
        var finishParsing: (() -> Unit)? = null
        val session = EditorDocuments(state, actions) { content, current, complete ->
            finishParsing = {
                complete(current() && actions.commitOpen(content.reference) {
                    state.acceptImport(CircCodec.readProject(content.text),
                        dev.logisim.mobile.core.localization.LocalizedText.literal(content.reference.name))
                })
            }
        }
        session.attach()
        val imported = reference.copy(id = "prepared", name = "prepared.circ")
        val xml = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("prepared"))))
        actions.deliver(DocumentOpenResult.Opened(DocumentContent(imported, xml)))
        assertEquals(null, observedReference)
        finishParsing!!.invoke()
        assertEquals(imported, observedReference)
        assertEquals(imported, actions.currentReference)
        assertEquals(null, actions.activeImportReference)
        assertEquals("prepared", state.document.name)
    }

    private class FakeDocuments(override var currentReference: DocumentReference?) : DocumentActions {
        var handler: ((DocumentOpenResult) -> Unit)? = null
        var writeCallback: ((DocumentWriteResult) -> Unit)? = null
        var saveCalls = 0
        var shared: Pair<String, String>? = null
        var activeImportReference: DocumentReference? = null
        override fun commitOpen(reference: DocumentReference, accept: () -> Boolean): Boolean {
            activeImportReference = reference
            return try { accept() } finally { activeImportReference = null }
        }
        fun deliver(result: DocumentOpenResult) { handler?.invoke(result) }
        override fun retain(reference: DocumentReference?) { currentReference = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) { writeCallback = onResult }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) { saveCalls++; writeCallback = onResult }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) {
            shared = suggestedName to text
        }
    }
}
