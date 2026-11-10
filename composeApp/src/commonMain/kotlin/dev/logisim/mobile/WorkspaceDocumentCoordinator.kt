package dev.logisim.mobile

/** Native document callbacks stay on the UI dispatcher; persistence may acknowledge later. */
internal interface WorkspaceDocumentCoordinator {
    val state: CatalogWorkspaceState
    fun scope(): WorkspaceEditorScope
    fun matches(scope: WorkspaceEditorScope): Boolean
    fun activeLink(): WorkspaceExternalDocument?
    fun activeDocumentStamp(): String? = null
    fun externalSaved(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                      previousReference: DocumentReference?, onCommitted: () -> Unit)
    fun confirmedExternal(xml: String)
    fun nativeWriteStarted() {}
    fun nativeWriteFinished() {}
}
