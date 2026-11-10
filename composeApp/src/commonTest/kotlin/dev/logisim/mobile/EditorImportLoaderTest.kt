package dev.logisim.mobile

import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorImportLoaderTest {
    private fun parsed(name: String) = CircProjectImportResult.Success(CircuitProject(listOf(CircuitDocument(name))))

    @Test
    fun onlyTheLatestOpenCanCommitEvenIfOldParsingIgnoresCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val loader = EditorImportLoader(scope, ComponentCatalog.registry, { 0L to 0 }, { true }, { throw it },
            workerDispatcher = Dispatchers.Unconfined, parse = { name ->
                if (name == "old") withContext(NonCancellable) { gate.await() }
                parsed(name)
            })
        try {
            loader.open("old", commit = { committed += (it as CircProjectImportResult.Success).project.mainCircuitName; true })
            loader.open("new", commit = { committed += (it as CircProjectImportResult.Success).project.mainCircuitName; true })
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertEquals(listOf("new"), committed)
            assertFalse(loader.loading)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun editingWhileParsingDiscardsThePreparedDocument() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var token = 0L to 0
        var committed = false
        var completed: Boolean? = null
        val loader = EditorImportLoader(scope, ComponentCatalog.registry, { token }, { true }, { throw it },
            workerDispatcher = Dispatchers.Unconfined, parse = { gate.await(); parsed(it) })
        try {
            loader.open("import", commit = { committed = true; true }, onComplete = { completed = it })
            token = 0L to 1
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertFalse(committed)
            assertEquals(false, completed)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun aDetachedNativeSessionCannotCommitItsPreparedFile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var attached = true
        var committed = false
        val loader = EditorImportLoader(scope, ComponentCatalog.registry, { 0L to 0 }, { true }, { throw it },
            workerDispatcher = Dispatchers.Unconfined, parse = { gate.await(); parsed(it) })
        try {
            loader.open("import", isRequestCurrent = { attached }, commit = { committed = true; true })
            attached = false
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertFalse(committed)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun aLateParserFailureDoesNotOpenAnErrorDuringACatalogTransaction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var enabled = true
        var failed = false
        var discarded = false
        var completed: Boolean? = null
        val loader = EditorImportLoader(scope, ComponentCatalog.registry, { 0L to 0 }, { enabled },
            { failed = true }, onDiscarded = { discarded = true }, workerDispatcher = Dispatchers.Unconfined,
            parse = { gate.await(); throw IllegalStateException("old parser failure") })
        try {
            loader.open("import", commit = { true }, onComplete = { completed = it })
            enabled = false
            gate.complete(Unit)
            scope.coroutineContext.job.children.toList().joinAll()
            assertFalse(failed)
            assertTrue(discarded)
            assertEquals(false, completed)
        } finally { loader.close(); scope.cancel() }
    }

    @Test
    fun aCatalogTransactionPreventsStartingAnotherImport() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var parseCalled = false
        val loader = EditorImportLoader(scope, ComponentCatalog.registry, { 0L to 0 }, { false }, { throw it },
            workerDispatcher = Dispatchers.Unconfined, parse = { parseCalled = true; parsed(it) })
        try {
            assertFalse(loader.open("import", commit = { true }))
            assertFalse(parseCalled)
            assertFalse(loader.loading)
        } finally { loader.close(); scope.cancel() }
    }
}
