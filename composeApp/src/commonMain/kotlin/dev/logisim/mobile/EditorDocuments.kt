package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.OriginalCircExport
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage

internal class EditorDocuments(
    private val state: EditorState,
    private val actions: DocumentActions,
    /** The stored project's own name, which Save As and Share prefer to the main circuit's (null outside a workspace). */
    private val projectName: () -> String? = { null },
    /** The localized "circuit" used when nothing else names the file. */
    private val defaultName: () -> String = { message(Messages.FileDefaultName).resolve(systemAppLanguage()) },
    private val importDocument: (DocumentContent, isCurrent: () -> Boolean, onComplete: (Boolean) -> Unit) -> Unit = { content, current, complete ->
        complete(current() && actions.commitOpen(content.reference) {
            state.importXml(content.text, LocalizedText.literal(content.reference.name))
        })
    },
) {
    private var attachmentGeneration = 0L
    private var referenceRevision by mutableStateOf(0)
    val reference: DocumentReference? get() {
        referenceRevision
        return actions.currentReference
    }

    val canEditInPlace: Boolean get() = actions.supportsEditInPlace
    val canSave: Boolean get() = state.documentWritable && canEditInPlace && reference?.let { it.writable && it.mode == DocumentOpenMode.EDIT_IN_PLACE } == true
    val canShareOriginalCopy: Boolean get() =
        state.projectAccess.graphAvailable && OriginalCircExport.supports(state.project)

    fun attach() {
        val generation = ++attachmentGeneration
        state.onProjectReplaced = ::unlink
        actions.setOpenHandler { result ->
            when (result) {
                is DocumentOpenResult.Opened -> importDocument(result.content, { generation == attachmentGeneration }) { accepted ->
                    if (accepted && generation == attachmentGeneration && state.interactionsEnabled) {
                        retain(result.content.reference.takeIf { it.mode == DocumentOpenMode.EDIT_IN_PLACE })
                        result.notice?.let(state::reportStatus) ?: state.reportOpened(LocalizedText.literal(result.content.reference.name))
                    }
                }
                is DocumentOpenResult.Failure -> state.reportError(result.notice)
                DocumentOpenResult.Cancelled -> Unit
            }
        }
    }

    fun detach() {
        attachmentGeneration++
        actions.setOpenHandler(null)
        state.onProjectReplaced = {}
    }

    fun openCopy() = actions.open(DocumentOpenMode.IMPORT_COPY)
    fun editFile() = actions.open(DocumentOpenMode.EDIT_IN_PLACE)
    fun saveAs() {
        val identity = state.documentIdentity
        actions.create(suggestedName(), state.saveXml()) { written(it, identity) }
    }
    fun save() {
        val current = reference ?: return
        if (canSave) {
            val identity = state.documentIdentity
            actions.save(current, state.saveXml()) { written(it, identity) }
        }
    }
    /** File > Save and Ctrl+S, as Evolution's ProjectActions.doSave: in place when a file is linked, otherwise Save As. */
    fun saveOrSaveAs() = if (canSave) save() else saveAs()
    fun share() = shareXml(suggestedName(), state.saveXml())

    fun shareOriginalCopy() {
        if (!canShareOriginalCopy) return
        val xml = try {
            OriginalCircExport.write(state.project, state.componentRegistry)
        } catch (error: LocalizedArgumentException) {
            state.reportError(error.text)
            return
        }
        shareXml(suggestedName().removeSuffix(".circ") + "-2.7.1.circ", xml)
    }

    private fun shareXml(name: String, xml: String) = actions.share(name, xml) { result ->
        when (result) {
            is DocumentShareResult.Failure -> state.reportError(result.notice)
            DocumentShareResult.Presented -> state.reportStatus(message(Messages.StatusSharePrompt))
            DocumentShareResult.Cancelled -> Unit
        }
    }

    private fun written(result: DocumentWriteResult, identity: Long) {
        if (identity != state.documentIdentity) return
        when (result) {
            is DocumentWriteResult.Saved -> {
                retain(result.reference)
                state.reportStatus(result.notice ?: message(Messages.StatusFileSaved, "name" to result.reference.name))
            }
            is DocumentWriteResult.Conflict -> state.reportError(
                message(Messages.ErrorFileConflict),
            )
            is DocumentWriteResult.Failure -> state.reportError(result.notice)
            DocumentWriteResult.Cancelled -> Unit
        }
    }

    private fun unlink() {
        retain(null)
    }

    private fun retain(reference: DocumentReference?) {
        actions.retain(reference)
        referenceRevision++
    }

    /**
     * The file name Save As and Share propose, as Evolution preselects the current file: the linked file's name, then
     * the stored project's, then the main circuit's, then the localized default.
     */
    internal fun suggestedName(): String {
        val base = listOf(reference?.name?.removeSuffix(".circ"), projectName(), state.project.mainCircuitName)
            .map { it?.replace(Regex("[\\/:*?\"<>|]"), "_")?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() } ?: defaultName()
        return "$base.circ"
    }
}
