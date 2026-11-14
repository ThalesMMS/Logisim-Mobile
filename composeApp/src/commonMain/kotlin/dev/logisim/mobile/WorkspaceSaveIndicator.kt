package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceSaveStatus
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages

/** What the workspace tells the user about the open project's autosave. */
enum class WorkspaceSaveIndicator {
    SAVING, SAVED, PENDING, FAILED, CONFLICT, READ_ONLY, DRAFT;

    /** A failure or conflict needs the user's action, so it gets the persistent banner. */
    val needsAction: Boolean get() = this == FAILED || this == CONFLICT
}

/**
 * Precedence: CONFLICT > FAILED > READ_ONLY > SAVING > DRAFT > PENDING > SAVED. A failed write stays FAILED
 * ([CatalogWorkspaceState.saveFailed]) while later edits retry it, until a write succeeds, so the label does not
 * flicker between Saving and Not saved, and pending writes never mask it.
 *
 * Only a failed write counts: the session also reports FAILED after an open, a create or a copy fails, while the open
 * project's own last write succeeded. Then it is pending only if its buffer differs from what storage holds.
 */
fun workspaceSaveIndicator(state: CatalogWorkspaceState, runtime: CatalogWorkspaceRuntimeState): WorkspaceSaveIndicator = when {
    state.session.conflict != null && state.editorProjectId != null -> WorkspaceSaveIndicator.CONFLICT
    state.saveFailed -> WorkspaceSaveIndicator.FAILED
    state.readOnly -> WorkspaceSaveIndicator.READ_ONLY
    runtime.pendingWrites -> WorkspaceSaveIndicator.SAVING
    state.editorProjectId == null -> WorkspaceSaveIndicator.DRAFT
    state.session.dirty && (state.session.saveStatus != WorkspaceSaveStatus.FAILED ||
        state.session.xml != state.session.document?.xml) -> WorkspaceSaveIndicator.PENDING
    else -> WorkspaceSaveIndicator.SAVED
}

enum class WorkspaceNoticeSeverity { INFO, WARNING, ERROR }

data class WorkspaceNotice(val text: LocalizedText, val severity: WorkspaceNoticeSeverity)

private val informational = setOf(Messages.WorkspaceMigrated, Messages.WorkspaceCatalogRecovered,
    Messages.WorkspaceViewIdentityRecovered)
private val warnings = setOf(Messages.WorkspaceReadOnly, Messages.WorkspaceRevisionRecovered,
    Messages.WorkspaceDocumentUnavailable, "workspace_external_link_mismatch")

fun workspaceNoticeSeverity(text: LocalizedText): WorkspaceNoticeSeverity = when (text.key) {
    in informational -> WorkspaceNoticeSeverity.INFO
    in warnings -> WorkspaceNoticeSeverity.WARNING
    else -> WorkspaceNoticeSeverity.ERROR
}

/** Binding, catalog and session notices once each, with the notice the save banner already shows left out. */
fun workspaceNotices(state: CatalogWorkspaceState, banner: LocalizedText? = null): List<WorkspaceNotice> =
    (state.notices + state.session.notices).distinct().filter { it != banner }
        .map { WorkspaceNotice(it, workspaceNoticeSeverity(it)) }

/** The notice the save banner explains a failure or conflict with, if the indicator needs action. */
fun workspaceBannerNotice(state: CatalogWorkspaceState, indicator: WorkspaceSaveIndicator): LocalizedText? =
    if (!indicator.needsAction) null
    else (state.session.notices + state.notices).distinct().firstOrNull { workspaceNoticeSeverity(it) == WorkspaceNoticeSeverity.ERROR }
