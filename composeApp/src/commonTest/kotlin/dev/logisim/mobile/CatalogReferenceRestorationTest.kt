package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CatalogReferenceRestorationTest {
    private class Native : DocumentActions {
        var reference: DocumentReference? = DocumentReference("bookmark-A", "A.circ", true, "sha-A", DocumentOpenMode.EDIT_IN_PLACE)
        private var generation = 0L
        private var handler: ((DocumentOpenResult) -> Unit)? = null
        override val currentReference get() = reference
        override fun retain(reference: DocumentReference?) { if (reference == null) generation++; this.reference = reference }
        override fun restoreReference(reference: DocumentReference?) { this.reference = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun beginSystemRead(): () -> Unit {
            val request = ++generation
            return { if (request == generation) handler?.invoke(DocumentOpenResult.Opened(DocumentContent(
                DocumentReference("bookmark-C", "C.circ", true, "sha-C", DocumentOpenMode.EDIT_IN_PLACE), xml("C")))) }
        }
    }
    private class Coordinator : WorkspaceDocumentCoordinator {
        private val b = WorkspaceDocument(WorkspaceProjectInfo(WorkspaceProjectId("B"), "B", 1, 0, 0), xml("B"))
        override val state = CatalogWorkspaceState(WorkspaceCatalogResult(listOf(b.info), b.info.id),
            WorkspaceSessionState(document = b, xml = b.xml), 1, b.xml, b.info.id)
        var current = WorkspaceEditorScope(1, b.info.id, 0, 1)
        override fun scope() = current
        override fun matches(scope: WorkspaceEditorScope) = scope == current
        override fun activeLink(): WorkspaceExternalDocument? = null
        override fun confirmedExternal(xml: String) = Unit
        override fun externalSaved(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                                   previousReference: DocumentReference?, onCommitted: () -> Unit) = onCommitted()
    }
    private companion object { fun xml(name: String) = "<project><circuit name=\"$name\"/></project>" }

    @Test fun restoringBAndClearingLegacyABookmarkDoesNotCancelTheColdSystemOpenOfC() {
        val native = Native(); val finishC = native.beginSystemRead()
        val coordinator = Coordinator(); val actions = CatalogDocumentActions(native, coordinator)
        var delivered: DocumentOpenResult? = null
        actions.setOpenHandler { delivered = it }
        actions.activate(coordinator.state.session.document)
        assertNull(native.currentReference)
        actions.editorAttached()
        finishC()
        assertEquals("C.circ", assertIs<DocumentOpenResult.Opened>(delivered).content.reference.name)
    }

    @Test fun explicitNewStillCancelsAnEarlierSystemOpen() {
        val native = Native(); val finishC = native.beginSystemRead()
        val coordinator = Coordinator(); val actions = CatalogDocumentActions(native, coordinator)
        var delivered: DocumentOpenResult? = null
        actions.setOpenHandler { delivered = it }; actions.editorAttached()
        actions.retain(null)
        finishC()
        assertNull(delivered)
    }

    @Test fun aRequestedOpenForAnEarlierWorkspaceStillCannotReplaceTheNewOne() {
        val native = Native(); val coordinator = Coordinator(); val actions = CatalogDocumentActions(native, coordinator)
        var delivered: DocumentOpenResult? = null
        actions.setOpenHandler { delivered = it }; actions.editorAttached()
        actions.open(DocumentOpenMode.EDIT_IN_PLACE)
        val finishC = native.beginSystemRead()
        coordinator.current = WorkspaceEditorScope(2, WorkspaceProjectId("D"), 0, 1)
        finishC()
        assertIs<DocumentOpenResult.Cancelled>(delivered)
    }
}
