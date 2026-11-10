package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditorInitializationTest {
    @Test
    fun projectConstructionWaitsForTheWorkerInsteadOfRunningInTheCaller() {
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var constructed = false
        var published = false
        try {
            val job = scope.launch {
                prepareEditorState(null, ComponentCatalog.registry, 4.0, null, worker) {
                    constructed = true
                    EditorState(null)
                }
                published = true
            }
            assertFalse(constructed)
            assertFalse(published)
            worker.runAll()
            assertTrue(job.isCompleted)
            assertTrue(constructed)
            assertTrue(published)
        } finally { scope.cancel() }
    }

    @Test
    fun closingTheWindowCancelsInitializationWithoutPublishingAFallback() {
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var constructed = false
        var published = false
        scope.launch {
            prepareEditorState(null, ComponentCatalog.registry, 4.0, null, worker) {
                constructed = true
                EditorState(null)
            }
            published = true
        }
        scope.cancel()
        worker.runAll()
        assertFalse(constructed)
        assertFalse(published)
    }

    @Test
    fun cancellationThrownDuringPropagationIsNotConvertedToAReadOnlyProject() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            prepareEditorState(null, ComponentCatalog.registry, 4.0, null, Dispatchers.Unconfined) {
                throw CancellationException("window closed")
            }
        }
    }

    @Test
    fun anUnexpectedInitializationFailurePreservesTheExactOriginal() = runBlocking {
        val original = "<unparsed keep=\"this exact payload\"/>\n"
        val state = prepareEditorState(original, ComponentCatalog.registry, 4.0, null, Dispatchers.Unconfined) {
            throw IllegalStateException("initial propagation failed")
        }
        assertFalse(state.documentWritable)
        assertTrue(state.document.components.isEmpty())
        assertEquals(original, state.exportXml())
        assertTrue(state.importErrorText != null)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
