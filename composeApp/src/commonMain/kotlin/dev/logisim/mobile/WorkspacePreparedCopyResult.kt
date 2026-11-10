package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.LocalizedText

/** Opened confirms both durable catalog storage and the replacement editor's readiness. */
sealed interface WorkspacePreparedCopyResult {
    data class Opened(
        val projectId: WorkspaceProjectId,
        val mount: Long,
        val documentIdentity: Long,
    ) : WorkspacePreparedCopyResult
    data object Cancelled : WorkspacePreparedCopyResult
    data class Failure(val notice: LocalizedText) : WorkspacePreparedCopyResult
}
