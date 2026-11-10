package dev.logisim.mobile

import dev.logisim.mobile.core.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real editor/controller/engine reproductions of the production request callbacks. */
class RuntimeSimulationRequestTest {
    @Test
    fun inactiveRuntimeReleaseDoesNotFallBackToSynchronousEditorComputation() = onEditor {
        val harness = Harness(coroutineContext)
        try {
            val button = harness.editor.document.components.single { it.kind == IoKinds.Button }.id
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
            until { harness.controller.status.value.pendingSteps == 0 && harness.pressed(button) }
            val before = harness.workerCalls.get()
            harness.controller.setActive(false)
            harness.editor.setInteractionEnabled(false)

            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
            val changedWithoutWorker = !harness.pressed(button) && harness.workerCalls.get() == before
            assertFalse(changedWithoutWorker,
                "Inactive runtime rejection changed real Button state without any worker invocation")
            until { !harness.pressed(button) && harness.controller.status.value.pendingSteps == 0 }
            assertEquals(before + 1, harness.workerCalls.get())
            assertEquals(SimulationRunPhase.INACTIVE, harness.controller.status.value.phase)
            assertEquals(0L, harness.editor.rootSimulation.ticks)
        } finally { harness.close() }
    }

    @Test
    fun exactToolbarStepLambdaKeepsTwoAcceptedRealTicks() = onEditor {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val harness = Harness(coroutineContext) { _, step ->
            if (step == SimulationStep.TICK && calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        try {
            // Exact compact/wide toolbar callback, including its continuous-only pause.
            val toolbarStep = { harness.controller.pauseContinuousExecution(); harness.editor.tick() }
            toolbarStep()
            withTimeout(5_000) { firstStarted.await() }
            toolbarStep()
            releaseFirst.complete(Unit)
            until { harness.controller.status.value.pendingSteps == 0 }
            assertEquals(2L, harness.editor.rootSimulation.ticks,
                "Two accepted toolbar steps must not cancel the first in-flight manual tick")
            assertEquals(listOf(1L, 2L), harness.publishedTicks)
        } finally { releaseFirst.complete(Unit); harness.close() }
    }

    @Test
    fun inactiveReleasesRemainFifoWithoutStartingContinuousExecution() = onEditor {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val cleanupCalls = AtomicInteger()
        val harness = Harness(coroutineContext) { snapshot, _ ->
            if (releasesInputs(snapshot.project, snapshot.events) && cleanupCalls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        try {
            val button = harness.button()
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
            until { harness.controller.status.value.pendingSteps == 0 }
            harness.controller.setActive(false)
            harness.editor.setInteractionEnabled(false)
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(false)))
            withTimeout(5_000) { firstStarted.await() }
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
            assertEquals(2, harness.controller.status.value.pendingSteps)
            assertEquals(SimulationRunPhase.INACTIVE, harness.controller.status.value.phase)
            assertTrue(harness.pressed(button))
            releaseFirst.complete(Unit)
            until { harness.controller.status.value.pendingSteps == 0 }
            assertEquals(listOf(IoInputEvent.Press(false), IoInputEvent.Cancel),
                harness.publishedEvents.drop(1).map { it.getValue(button).single() })
            assertFalse(harness.pressed(button))
            assertEquals(0L, harness.editor.rootSimulation.ticks)
            assertEquals(SimulationRunPhase.INACTIVE, harness.controller.status.value.phase)
            assertTrue(harness.workerThreads.isNotEmpty())
            harness.workerThreads.forEach { assertSame(harness.workerThread.get(), it) }
        } finally { releaseFirst.complete(Unit); harness.close() }
    }

    @Test
    fun acceptedReleaseSurvivesBackgroundAndForegroundTransitions() = onEditor {
        for (initiallyActive in listOf(true, false)) {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val cleanupCalls = AtomicInteger()
            val harness = Harness(coroutineContext) { snapshot, _ ->
                if (releasesInputs(snapshot.project, snapshot.events) && cleanupCalls.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
            try {
                val button = harness.button()
                assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
                until { harness.controller.status.value.pendingSteps == 0 }
                harness.controller.setActive(initiallyActive)
                assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
                withTimeout(5_000) { firstStarted.await() }
                harness.controller.setActive(!initiallyActive)
                releaseFirst.complete(Unit)
                until { harness.controller.status.value.pendingSteps == 0 }
                assertFalse(harness.pressed(button))
                assertEquals(1, harness.publishedEvents.count { it.values.flatten().contains(IoInputEvent.Cancel) })
                assertEquals(if (initiallyActive) SimulationRunPhase.INACTIVE else SimulationRunPhase.PAUSED,
                    harness.controller.status.value.phase)
                assertEquals(0L, harness.editor.rootSimulation.ticks)
            } finally { releaseFirst.complete(Unit); harness.close() }
        }
    }

    @Test
    fun runWaitsForAnAcceptedReleaseBeforeStartingItsClockLoop() = onEditor {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val harness = Harness(coroutineContext) { snapshot, _ ->
            if (snapshot.events.values.flatten().contains(IoInputEvent.Cancel)) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        try {
            val button = harness.button()
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
            until { harness.controller.status.value.pendingSteps == 0 }
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
            withTimeout(5_000) { firstStarted.await() }
            assertTrue(harness.controller.start())
            assertEquals(SimulationRunPhase.STEPPING, harness.controller.status.value.phase)
            releaseFirst.complete(Unit)
            until { harness.controller.status.value.phase == SimulationRunPhase.RUNNING }
            harness.controller.pause()
            assertFalse(harness.pressed(button))
            assertEquals(0L, harness.editor.rootSimulation.ticks)
            assertEquals(1, harness.publishedEvents.count { it.values.flatten().contains(IoInputEvent.Cancel) })
        } finally { releaseFirst.complete(Unit); harness.close() }
    }

    @Test
    fun catalogLifecyclePreservesAcceptedReleaseInEitherObserverOrder() = onEditor {
        for (appObserverFirst in listOf(false, true)) {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val cleanupCalls = AtomicInteger()
            val harness = Harness(coroutineContext) { snapshot, _ ->
                if (releasesInputs(snapshot.project, snapshot.events) && cleanupCalls.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
            val control = EditorSessionControl()
            val runtime = CatalogWorkspaceRuntime(MemoryDisk(), "release-lifecycle", control,
                deliveryContext = coroutineContext)
            try {
                runtime.start(harness.editor.exportXml())
                until { runtime.state.workspace != null }
                control.attach(harness.controller::pause, harness.controller::setActive,
                    harness.editor::exportViewState, harness.editor::importViewState,
                    identity = { harness.editor.documentIdentity }, exportXml = harness.editor::exportXml,
                    documentWritable = { harness.editor.documentWritable },
                    setInteractionEnabled = harness.editor::setInteractionEnabled)
                runtime.editorAttached(runtime.state.workspace!!.editorMount)
                until { !runtime.state.busy && control.activationAllowed && harness.editor.interactionsEnabled }
                val button = harness.button()
                assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
                until { harness.controller.status.value.pendingSteps == 0 }
                assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
                withTimeout(5_000) { firstStarted.await() }

                // CatalogWorkspaceHost and App each observe the same lifecycle event.
                if (appObserverFirst) harness.controller.setActive(false)
                runtime.foregroundChanged(false)
                if (!appObserverFirst) harness.controller.setActive(false)
                releaseFirst.complete(Unit)
                until { harness.controller.status.value.pendingSteps == 0 }
                assertFalse(harness.pressed(button), "Catalog lifecycle discarded accepted release; App first=$appObserverFirst")
                assertEquals(SimulationRunPhase.INACTIVE, harness.controller.status.value.phase)
                assertEquals(0L, harness.editor.rootSimulation.ticks)
            } finally {
                releaseFirst.complete(Unit)
                runtime.close()
                runtime.awaitClosed()
                harness.close()
            }
        }
    }

    @Test
    fun rejectedAndDetachedRuntimeRequestsNeverFallBackToStandaloneComputation() = onEditor {
        val harness = Harness(coroutineContext)
        try {
            val button = harness.button()
            assertTrue(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
            until { harness.controller.status.value.pendingSteps == 0 }
            harness.controller.setActive(false)
            val before = harness.editor.rootSimulation
            val calls = harness.workerCalls.get()
            assertFalse(harness.editor.sendComponentEvent(button, IoInputEvent.Press(true)))
            harness.editor.tick()
            assertSame(before, harness.editor.rootSimulation)
            assertEquals(calls, harness.workerCalls.get())

            harness.controller.close()
            // The production disposal callback rejects late Canvas cleanup requests.
            harness.editor.onSimulationRequested = { _, _ -> SimulationRequestResult.REJECTED }
            assertFalse(harness.editor.sendComponentEvent(button, IoInputEvent.Cancel))
            harness.editor.tick()
            harness.editor.resetSimulation()
            assertTrue(harness.editor.insertComponent(ComponentKind.CLOCK, GridPoint(200, 100)))
            assertSame(before, harness.editor.rootSimulation)
            assertEquals(calls, harness.workerCalls.get())
        } finally { harness.close() }
    }

    private class Harness(
        context: CoroutineContext,
        beforeAdvance: suspend (SimulationRunSnapshot, SimulationStep) -> Unit = { _, _ -> },
    ) {
        private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        val workerThread = AtomicReference<Thread>()
        private val worker = Executors.newSingleThreadExecutor {
            Thread(it, "runtime-request-worker").also(workerThread::set)
        }.asCoroutineDispatcher()
        val workerCalls = AtomicInteger()
        val workerThreads = mutableListOf<Thread>()
        val publishedTicks = mutableListOf<Long>()
        val publishedEvents = mutableListOf<Map<String, List<ComponentEvent>>>()
        val editor = EditorState("""<project source="5.1.0-dev" version="1.0"><lib name="0" desc="#I/O"/><main name="Held"/><circuit name="Held">
            <comp lib="0" name="Button" loc="(100,100)"><a name="label" val="BTN"/><a name="press" val="active"/></comp>
            </circuit></project>""")
        val controller = SimulationRunController(scope, editor::captureRunSnapshot,
            advance = { snapshot, step ->
                workerCalls.incrementAndGet()
                workerThreads += Thread.currentThread()
                beforeAdvance(snapshot, step)
                val job = currentCoroutineContext()[Job]
                advanceSimulation(snapshot, step, cancellationCheck = { job?.ensureActive() }).state
            },
            isCurrent = editor::isCurrentRunSnapshot,
            publish = { snapshot, result ->
                editor.publishRun(snapshot, result)
                publishedTicks += result.ticks
                publishedEvents += snapshot.events
            },
            onFailure = { throw AssertionError("Unexpected simulation failure", it) }, workerDispatcher = worker)

        init {
            editor.onSimulationInvalidated = controller::invalidate
            editor.onSimulationRequested = controller::request
        }

        fun pressed(id: String): Boolean = assertIs<IoButtonState>(editor.rootSimulation.componentStates[id]).pressed
        fun button(): String = editor.document.components.single { it.kind == IoKinds.Button }.id
        fun close() { controller.close(); scope.cancel(); worker.close() }
    }

    private class MemoryDisk : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = synchronized(files) { action() }
        override fun read(name: String): String? = synchronized(files) { files[name] }
        override fun listNames(): List<String> = synchronized(files) { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) { synchronized(files) { files[name] = contents } }
    }

    private fun onEditor(action: suspend CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor { Thread(it, "runtime-editor-owner") }.asCoroutineDispatcher().use { owner ->
            runBlocking(owner) { action() }
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }
}
