package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** Read-only parsed projects still have a usable graph; preserved raw files do not. */
enum class EditorProjectAccess(val graphAvailable: Boolean, val writable: Boolean) {
    EDITABLE(true, true),
    READ_ONLY(true, false),
    PRESERVED_RAW(false, false),
}

/** An export keeps its immutable snapshot but must still belong to a live editor document. */
class ProjectGraphExportPolicy(
    val access: EditorProjectAccess = EditorProjectAccess.EDITABLE,
    private val isCurrent: () -> Boolean = { true },
) {
    val available: Boolean get() = access.graphAvailable && isCurrent()

    fun ensureAvailable(onUnavailable: (LocalizedText) -> Unit): Boolean {
        if (available) return true
        onUnavailable(message(if (access.graphAvailable) "project_export_obsolete" else "project_graph_unavailable"))
        return false
    }

    fun export(onUnavailable: (LocalizedText) -> Unit, action: () -> Unit): Boolean {
        if (!ensureAvailable(onUnavailable)) return false
        action()
        return true
    }
}
