package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Parsing and initial propagation finish before an editor is attached to a window. */
internal suspend fun prepareEditorState(
    initialXml: String?,
    registry: ComponentRegistry,
    defaultTickFrequencyHz: Double,
    initialImportReport: EditorImportReport?,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialReadOnly: Boolean = false,
    clipboard: CircuitClipboard = CircuitClipboard(),
    initialize: (checkCancellation: () -> Unit) -> EditorState = { check ->
        EditorState(initialXml, registry, defaultTickFrequencyHz, initialImportReport, initializationCheck = check,
            initialReadOnly = initialReadOnly, clipboard = clipboard)
    },
): EditorState = withContext(workerDispatcher) {
    val job = currentCoroutineContext()[Job]
    val check = { job?.ensureActive(); Unit }
    try {
        initialize(check).also { check() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        check()
        EditorState(initialXml ?: "", registry, defaultTickFrequencyHz,
            initializationCheck = check,
            initializationFailure = error.localizedDescription(message("editor_initialization_failed")),
            clipboard = clipboard)
    }
}
