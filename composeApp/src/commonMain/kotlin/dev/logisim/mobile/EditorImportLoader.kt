package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prepare without mutating the editor; commit only the latest request for the same document. */
internal class EditorImportLoader(
    private val scope: CoroutineScope,
    registry: ComponentRegistry,
    private val currentToken: () -> Pair<Long, Int>,
    private val interactionsEnabled: () -> Boolean,
    private val onFailure: (Throwable) -> Unit,
    private val onDiscarded: () -> Unit = {},
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val parse: suspend (String) -> CircProjectImportResult = { CircCodec.readProject(it, registry) },
) {
    private var generation = 0L
    private var work: Job? = null
    var loading by mutableStateOf(false)
        private set

    fun open(
        xml: String,
        isRequestCurrent: () -> Boolean = { true },
        commit: (CircProjectImportResult) -> Boolean,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (!scope.isActive || !interactionsEnabled() || !isRequestCurrent()) return false
        val request = ++generation
        val token = currentToken()
        work?.cancel()
        loading = true
        work = scope.launch {
            try {
                val result = withContext(workerDispatcher) { parse(xml) }
                if (request == generation && token == currentToken() && interactionsEnabled() && isRequestCurrent()) {
                    onComplete(commit(result))
                } else if (request == generation) {
                    onDiscarded()
                    onComplete(false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) {
                    if (token == currentToken() && interactionsEnabled() && isRequestCurrent()) onFailure(error)
                    else onDiscarded()
                    onComplete(false)
                }
            } finally {
                if (request == generation) loading = false
            }
        }
        return true
    }

    fun cancel() { generation++; work?.cancel(); work = null; loading = false }
    fun close() = cancel()
}
