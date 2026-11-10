package dev.logisim.mobile

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText

enum class DocumentOpenMode { IMPORT_COPY, EDIT_IN_PLACE }

/** [id] is an opaque platform handle, never a path to display to another application. */
data class DocumentReference(
    val id: String,
    val name: String,
    val writable: Boolean,
    val revision: String? = null,
    val mode: DocumentOpenMode = DocumentOpenMode.IMPORT_COPY,
)

data class DocumentContent(val reference: DocumentReference, val text: String)

sealed interface DocumentOpenResult {
    data class Opened(val content: DocumentContent, val notice: LocalizedText? = null) : DocumentOpenResult
    data object Cancelled : DocumentOpenResult
    data class Failure(val notice: LocalizedText) : DocumentOpenResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

sealed interface DocumentWriteResult {
    data class Saved(val reference: DocumentReference, val notice: LocalizedText? = null) : DocumentWriteResult
    data class Conflict(val current: DocumentReference) : DocumentWriteResult
    data object Cancelled : DocumentWriteResult
    data class Failure(val notice: LocalizedText) : DocumentWriteResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

sealed interface DocumentShareResult {
    /** The system share interface opened; this does not assert delivery to another app. */
    data object Presented : DocumentShareResult
    data object Cancelled : DocumentShareResult
    data class Failure(val notice: LocalizedText) : DocumentShareResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

/** Native document operations. Implementations deliver callbacks on the UI thread. */
interface DocumentActions {
    val supportsEditInPlace: Boolean get() = true
    val currentReference: DocumentReference?

    /** Set only after the editor accepts the document, so a rejected import keeps its original. */
    fun retain(reference: DocumentReference?)

    /** Restore document bookkeeping without superseding an in-flight system open request. */
    fun restoreReference(reference: DocumentReference?) = retain(reference)

    /** The handler receives picker results and files opened from other applications. */
    fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?)

    /** Scope the accepted replacement to its source after asynchronous parsing finishes. */
    fun commitOpen(reference: DocumentReference, accept: () -> Boolean): Boolean = accept()

    fun open(mode: DocumentOpenMode = DocumentOpenMode.IMPORT_COPY)
    fun reopen(reference: DocumentReference)
    fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit)
    fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit)
    fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit)
}
