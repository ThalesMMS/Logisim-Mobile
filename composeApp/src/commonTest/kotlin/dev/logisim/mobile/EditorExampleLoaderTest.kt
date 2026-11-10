package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorExampleLoaderTest {
    private fun example(id: String) = CircuitExample(id, id, "test", "", emptyList())
    private fun session(example: CircuitExample) = ExampleSession(example,
        CircuitProject(listOf(CircuitDocument())), SimulationState())

    @Test
    fun latestRequestWinsEvenWhenTheOlderInitializerIgnoresCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val installed = mutableListOf<String>()
        var token = 0L to 0
        val loader = EditorExampleLoader(scope, ComponentCatalog.registry, { token }, {
            installed += it.example.id
            token = token.first + 1 to token.second
        }, { throw it }, Dispatchers.Unconfined, initialize = { example ->
            if (example.id == "a") withContext(NonCancellable) { gate.await() }
            session(example)
        })
        try {
            assertTrue(loader.open(example("a")))
            assertTrue(loader.open(example("b")))
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertEquals(listOf("b"), installed)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun editingTheCurrentDocumentDiscardsAPreparedExample() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var token = 0L to 0
        var installed = false
        val loader = EditorExampleLoader(scope, ComponentCatalog.registry, { token }, { installed = true },
            { throw it }, Dispatchers.Unconfined, initialize = { gate.await(); session(it) })
        try {
            loader.open(example("a"))
            token = token.first to token.second + 1
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertFalse(installed)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun aClosedWindowCannotAcceptAnotherExampleRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val loader = EditorExampleLoader(scope, ComponentCatalog.registry, { 0L to 0 }, {}, { throw it })
        assertFalse(loader.open(example("a")))
    }
}
