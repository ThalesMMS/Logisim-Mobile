package dev.logisim.mobile

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.logisim.mobile.core.WorkspaceProjectId

/**
 * Import reports the user closed in this window. The workspace binding keeps reports only in memory, so a
 * recreated Android activity (font scale, density, locale, theme) re-derives the report from the stored XML
 * and would show it again. This list travels in the saved instance state instead (#325).
 */
class ImportReportDismissals(initial: Collection<String> = emptyList()) {
    // "closed:<key>" or "open:<key>"; projects never shown carry no entry.
    private val entries = mutableStateListOf<String>().apply { addAll(initial.distinct()) }

    fun isDismissed(key: String): Boolean = "closed:$key" in entries
    fun isOpen(key: String): Boolean = "open:$key" in entries

    /** A closed report stays closed after recreation; a report still on screen stays on screen. */
    internal fun visibilityChanged(key: String, hasReport: Boolean, visible: Boolean) {
        entries.remove("closed:$key"); entries.remove("open:$key")
        if (hasReport) entries += (if (visible) "open:$key" else "closed:$key")
    }

    /**
     * Applies the saved choice to an editor prepared after recreation. An edited project re-derives a report
     * without migrations, which does not open by itself, so an open report is reopened explicitly.
     * Returns whether it changed the visibility.
     */
    internal fun restore(key: String, state: EditorState): Boolean = when {
        isDismissed(key) && state.importReportVisible -> { state.dismissImportReport(); true }
        isOpen(key) && !state.importReportVisible && state.importReport != null -> { state.showImportReport(); true }
        else -> false
    }

    fun snapshot(): List<String> = entries.toList()

    companion object {
        fun key(projectId: WorkspaceProjectId?, xml: String?): String =
            projectId?.let { "project:${it.value}" } ?: "draft:${xml.orEmpty().hashCode()}"

        internal val Saver: Saver<ImportReportDismissals, Any> = listSaver(
            save = { it.snapshot() },
            restore = { ImportReportDismissals(it) },
        )
    }
}
