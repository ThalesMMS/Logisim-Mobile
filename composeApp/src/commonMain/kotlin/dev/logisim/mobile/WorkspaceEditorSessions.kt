package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceViewState

/** Kept for the life of a workspace window; only its active editor is composed. */
internal class WorkspaceEditorSessions {
    private val editors = mutableMapOf<WorkspaceProjectId, EditorState>()
    var openIds by mutableStateOf<List<WorkspaceProjectId>>(emptyList())
        private set

    fun opened(id: WorkspaceProjectId) {
        if (id !in openIds) openIds = openIds + id
    }

    fun retain(id: WorkspaceProjectId, editor: EditorState) {
        editors[id] = editor
    }

    fun restore(id: WorkspaceProjectId, xml: String?, readOnly: Boolean): EditorState? =
        editors[id]?.takeIf {
            it.documentIdentity == 0L && it.documentWritable != readOnly && it.exportXml() == xml
        }

    fun close(id: WorkspaceProjectId) {
        editors.remove(id)
        openIds = openIds - id
    }
}

internal class WorkspaceEditorSessionBinding(
    val editor: EditorState?,
    val view: WorkspaceViewState,
    val retain: (EditorState) -> Unit,
    /** Opens a new session from its display name (resolved in the app language), XML and import report. */
    val open: (dev.logisim.mobile.core.localization.LocalizedText, String, EditorImportReport?) -> Boolean,
)
