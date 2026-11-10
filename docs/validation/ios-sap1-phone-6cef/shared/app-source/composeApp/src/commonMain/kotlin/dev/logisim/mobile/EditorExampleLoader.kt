package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ExampleRunner
import dev.logisim.mobile.core.ExampleSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Latest-request wins; computation owns no editor or file association. */
internal class EditorExampleLoader(
    private val scope: CoroutineScope,
    registry: ComponentRegistry,
    private val currentToken: () -> Pair<Long, Int>,
    private val install: (ExampleSession) -> Unit,
    private val failure: (Throwable) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val initialize: suspend (CircuitExample) -> ExampleSession = { example ->
        val job = currentCoroutineContext()[Job]
        ExampleRunner(CircuitSimulator(registry = registry, cancellationCheck = { job?.ensureActive() }), registry).initialize(example)
    },
) {
    private var generation = 0L
    private var work: Job? = null

    fun open(example: CircuitExample): Boolean {
        if (!scope.isActive) return false
        val request = ++generation
        val token = currentToken()
        work?.cancel()
        work = scope.launch {
            try {
                val session = withContext(workerDispatcher) { initialize(example) }
                if (request == generation && token == currentToken()) install(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation && token == currentToken()) failure(error)
            }
        }
        return true
    }

    fun close() { generation++; work?.cancel(); work = null }
}
