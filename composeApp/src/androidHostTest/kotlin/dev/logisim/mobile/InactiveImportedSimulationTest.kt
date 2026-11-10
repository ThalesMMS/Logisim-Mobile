package dev.logisim.mobile

import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

class InactiveImportedSimulationTest {
    @Test
    fun activeNativeImportInitializesWithoutAnotherCommand() = runBlocking<Unit> {
        mounted { openFixture(); drain(); assertKeyboardInitialized() }
    }

    @Test
    fun catalogForegroundGateDefersNativeOpenUntilItCanInitialize() = runBlocking<Unit> {
        mounted {
            runtime.foregroundChanged(false)
            assertFalse(state.interactionsEnabled)
            native.deliver(fixture())
            yield()
            assertNotEquals("IO_Showcase", state.project.mainCircuitName)
            runtime.foregroundChanged(true)
            awaitOpened()
            drain()
            assertKeyboardInitialized()
        }
    }

    @Test
    fun callbackBeforeCatalogForegroundGateRetainsInitializationUntilReactivation() = runBlocking<Unit> {
        mounted {
            appForegroundChanged(false)
            assertTrue(state.interactionsEnabled, "The App activation and catalog interaction gates are distinct")
            openFixture()
            runtime.awaitPersistence()
            assertEquals(SimulationRunPhase.INACTIVE, run.status.value.phase)
            assertTrue(published.isEmpty(), "Catalog completion must not activate a background App")
            assertTrue(state.rootSimulation.vectorValues.isEmpty())
            appForegroundChanged(true)
            drain()
            assertKeyboardInitialized()
        }
    }

    @Test
    fun acceptedInitializationInterruptedByBackgroundFinishesOnForeground() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mounted(beforeAdvance = { step ->
            if (step == SimulationStep.RESET) { entered.complete(Unit); release.await() }
        }) {
            native.deliver(fixture())
            withTimeout(5_000) { entered.await() }
            runtime.foregroundChanged(false)
            release.complete(Unit)
            runtime.foregroundChanged(true)
            awaitOpened()
            drain()
            assertKeyboardInitialized()
        }
    }

    @Test
    fun repeatedActivationDoesNotRepeatDocumentInitialization() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            control.setActive(true)
            control.setActive(true)
            drain()
            assertKeyboardInitialized()
            control.setActive(true)
            drain()
            assertEquals(1, published.count { it.purpose == SimulationRequestPurpose.DOCUMENT_INITIALIZATION })
            assertTrue(requests.all { it.second == SimulationRequestResult.ACCEPTED })
        }
    }

    @Test
    fun newerDocumentSupersedesDeferredImportedInitialization() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            val old = state.captureRunSnapshot().copy(purpose = SimulationRequestPurpose.DOCUMENT_INITIALIZATION)
            state.newDocument()
            assertEquals(SimulationRequestResult.REJECTED, run.request(old, SimulationStep.RESET))
            control.setActive(true)
            drain()
            assertTrue(state.document.components.isEmpty())
            assertTrue(state.simulation.vectorValues.isEmpty())
            assertEquals(1, published.size)
            assertTrue(published.none { it.project.mainCircuitName == "IO_Showcase" })
            assertSame(state.project, published.single().project)
        }
    }

    @Test
    fun documentUndoSupersedesDeferredInitializationWithItsRestoredGraph() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            state.newDocument()
            state.undo()
            control.setActive(true)
            drain()
            assertKeyboardInitialized()
            assertEquals(1, published.size)
            assertSame(state.project, published.single().project)
        }
    }

    @Test
    fun explicitInvalidationDoesNotReviveCancelledInitialization() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            run.invalidate()
            control.setActive(true)
            drain()
            assertTrue(published.isEmpty())
            assertTrue(state.rootSimulation.vectorValues.isEmpty())
        }
    }

    @Test
    fun ordinaryInactiveCommandsRemainRejectedAndNeverRunOnForeground() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            val before = state.rootSimulation
            for (step in SimulationStep.entries) {
                assertEquals(SimulationRequestResult.REJECTED, run.request(state.captureRunSnapshot(), step))
            }
            control.setActive(true)
            drain()
            assertSame(before, state.rootSimulation)
            assertTrue(published.isEmpty())
        }
    }

    @Test
    fun rejectedInactiveCommandsCannotDiscardDeferredDocumentInitialization() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            for (step in SimulationStep.entries) {
                assertEquals(SimulationRequestResult.REJECTED, run.request(state.captureRunSnapshot(), step))
            }
            control.setActive(true)
            drain()
            assertKeyboardInitialized()
            assertEquals(1, published.size)
            assertTrue(published.single().events.isEmpty())
        }
    }

    @Test
    fun initializationAndAcceptedCleanupSurviveEitherLifecycleObserverOrder() = runBlocking<Unit> {
        for (appFirst in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            mounted(beforeAdvance = { step ->
                if (step == SimulationStep.RESET) { entered.complete(Unit); release.await() }
            }) {
                native.deliver(fixture())
                withTimeout(5_000) { entered.await() }
                val button = state.document.components.single { it.label == "IO_BUTTON" }.id
                assertTrue(state.sendComponentEvent(button, IoInputEvent.Cancel))
                if (appFirst) appForegroundChanged(false)
                runtime.foregroundChanged(false)
                if (!appFirst) appForegroundChanged(false)
                release.complete(Unit)
                if (appFirst) appForegroundChanged(true)
                runtime.foregroundChanged(true)
                if (!appFirst) appForegroundChanged(true)
                drain()
                assertKeyboardInitialized()
                assertEquals(1, published.count { it.purpose == SimulationRequestPurpose.DOCUMENT_INITIALIZATION })
                assertEquals(1, published.count { it.events[button] == listOf(IoInputEvent.Cancel) })
            }
        }
    }

    @Test
    fun initializationCannotSmuggleClockCommandsOrInputEvents() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            openFixture()
            val initialization = state.captureRunSnapshot().copy(purpose = SimulationRequestPurpose.DOCUMENT_INITIALIZATION)
            for (step in listOf(SimulationStep.EVENT, SimulationStep.TICK)) {
                assertEquals(SimulationRequestResult.REJECTED, run.request(initialization, step))
            }
            val button = state.document.components.single { it.label == "IO_BUTTON" }.id
            val pressed = initialization.copy(events = mapOf(button to listOf(IoInputEvent.Press(true))))
            assertEquals(SimulationRequestResult.REJECTED, run.request(pressed, SimulationStep.SETTLE))
            control.setActive(true)
            drain()
            assertKeyboardInitialized()
            assertEquals(1, published.size)
            assertTrue(published.single().events.isEmpty())
            run.close()
            assertEquals(SimulationRequestResult.REJECTED, run.request(initialization, SimulationStep.RESET))
        }
    }

    @Test
    fun installedComputedExampleSupersedesTheDeferredReset() = runBlocking<Unit> {
        mounted {
            control.setActive(false)
            val session = withContext(Dispatchers.Default) { ExampleRunner().initialize(CircuitExamples.find("sap1")!!) }
            state.installExample(session)
            val installed = state.rootSimulation
            control.setActive(true)
            drain()
            assertSame(installed, state.rootSimulation)
            assertTrue(published.isEmpty())
            assertTrue(installed.pendingEvents.isEmpty())
        }
    }

    @Test
    fun inactiveSapStartupMatchesTheActiveInitialization() = runBlocking<Unit> {
        var activeValues: Map<String, LogicVector>? = null
        mounted {
            state.loadSap1()
            drain()
            activeValues = state.rootSimulation.vectorValues
            assertTrue(published.single().startupResetIds.isNotEmpty())
        }
        mounted {
            control.setActive(false)
            state.loadSap1()
            control.setActive(true)
            drain()
            assertEquals(activeValues, state.rootSimulation.vectorValues)
            assertTrue(published.single().startupResetIds.isNotEmpty())
            assertEquals(0L, state.simulation.ticks)
            assertTrue(state.simulation.pendingEvents.isEmpty())
        }
    }

    private class MemoryDisk(private val uiThread: Long) : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private fun worker() = assertNotEquals(uiThread, Thread.currentThread().threadId())
        override fun <T> withExclusiveAccess(action: () -> T): T { worker(); return synchronized(files) { action() } }
        override fun read(name: String): String? { worker(); return synchronized(files) { files[name] } }
        override fun listNames(): List<String> { worker(); return synchronized(files) { files.keys.toList() } }
        override fun writeAtomically(name: String, contents: String) { worker(); synchronized(files) { files[name] = contents } }
    }

    private class Native : DocumentActions {
        private var handler: ((DocumentOpenResult) -> Unit)? = null
        override val currentReference: DocumentReference? get() = null
        override fun retain(reference: DocumentReference?) = Unit
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        fun deliver(xml: String) {
            val reference = DocumentReference("content://fixture/io.circ", "io.circ", false,
                "revision-${xml.length}", DocumentOpenMode.IMPORT_COPY)
            checkNotNull(handler)(DocumentOpenResult.Opened(DocumentContent(reference, xml)))
        }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
    }

    private class Mounted(
        val state: EditorState,
        val run: SimulationRunController,
        val runtime: CatalogWorkspaceRuntime,
        val control: EditorSessionControl,
        val native: Native,
        val loader: EditorImportLoader,
        val requests: List<Pair<SimulationStep, SimulationRequestResult>>,
        val published: List<SimulationRunSnapshot>,
        private val setAppActive: (Boolean) -> Unit,
    ) {
        fun appForegroundChanged(active: Boolean) = setAppActive(active)
        suspend fun openFixture() { native.deliver(fixture()); awaitOpened() }
        suspend fun awaitOpened() = until { state.project.mainCircuitName == "IO_Showcase" && !loader.loading }
        suspend fun drain() {
            runtime.awaitPersistence()
            until { !loader.loading && !runtime.state.busy && run.status.value.pendingSteps == 0 }
            assertNull(run.status.value.failure)
        }
        fun assertKeyboardInitialized() {
            fun output(label: String) = state.document.components.single { it.label == label }.let { state.simulation.vectorValues[it.id]?.display }
            assertEquals("0000000", output("OUT_KEYBOARD_DATA"), "Initialization must not require an Event Step after foreground")
            assertEquals("0", output("OUT_KEYBOARD_AVAILABLE"))
            val expected = mapOf(
                "OUT_BUTTON" to "0", "OUT_LED_LEVEL" to "0", "OUT_DIP0" to "0", "OUT_DIP1" to "0",
                "OUT_JOY_X" to "1000", "OUT_JOY_Y" to "1000", "OUT_RGB_R" to "1", "OUT_RGB_G" to "1", "OUT_RGB_B" to "0",
                "OUT_BAR_VALUE" to "10100101", "OUT_SEGMENTS" to "00111111", "OUT_KEYBOARD_DATA" to "0000000",
                "OUT_KEYBOARD_AVAILABLE" to "0", "OUT_CLOCK" to "0", "OUT_MATRIX_ROW0" to "01110",
                "OUT_KEYPAD_C0" to "1", "OUT_KEYPAD_C1" to "1", "OUT_KEYPAD_C2" to "1", "OUT_KEYPAD_C3" to "1",
            )
            assertEquals(expected, state.document.components.filter { it.kind == ComponentKind.OUTPUT }
                .associate { it.label to state.simulation.vectorValues[it.id]?.display })
            assertEquals(0L, state.simulation.ticks)
            assertTrue(state.simulation.pendingEvents.isEmpty())
            assertFalse(state.simulation.oscillating)
            assertEquals(SimulationRunPhase.PAUSED, run.status.value.phase)
        }
    }

    private suspend fun mounted(beforeAdvance: suspend (SimulationStep) -> Unit = {}, action: suspend Mounted.() -> Unit) = coroutineScope {
        val uiThread = Thread.currentThread().threadId()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val native = Native()
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(MemoryDisk(uiThread), "inactive-native-open", control,
            nativeDocuments = native, deliveryContext = coroutineContext)
        var run: SimulationRunController? = null
        var loader: EditorImportLoader? = null
        var documents: EditorDocuments? = null
        try {
            runtime.start(CircCodec.write(DemoCircuits.andGate()))
            until { runtime.state.workspace != null }
            val workspace = runtime.state.workspace!!
            val state = withContext(Dispatchers.Default) { EditorState(workspace.editorXml) }
            val published = mutableListOf<SimulationRunSnapshot>()
            val controller = SimulationRunController(scope, state::captureRunSnapshot,
                advance = { snapshot, step ->
                    assertNotEquals(uiThread, Thread.currentThread().threadId())
                    beforeAdvance(step)
                    advanceSimulation(snapshot, step, autoPropagating = SimulationProjectOptions.read(snapshot.project).autoPropagating).state
                }, isCurrent = state::isCurrentRunSnapshot, publish = { snapshot, result ->
                    state.publishRun(snapshot, result); published += snapshot
                },
                onFailure = { throw it })
            run = controller
            val requests = mutableListOf<Pair<SimulationStep, SimulationRequestResult>>()
            state.onSimulationInvalidated = controller::invalidate
            state.onSimulationRequested = { snapshot, step -> controller.request(snapshot, step).also { requests += step to it } }
            state.onWorkspaceProjectReplaced = { runtime.projectReplaced(workspace.editorMount, it) }
            val importLoader = EditorImportLoader(scope, ComponentCatalog.registry,
                { state.documentIdentity to state.revision }, { state.interactionsEnabled }, { throw it })
            loader = importLoader
            val actions = runtime.documents!!
            documents = EditorDocuments(state, actions) { content, current, complete ->
                controller.pause()
                if (!importLoader.open(content.text, isRequestCurrent = current,
                        commit = { result -> actions.commitOpen(content.reference) { state.acceptImport(result) } },
                        onComplete = complete)) complete(false)
            }
            documents.attach()
            var appActive = true
            val setAppActive: (Boolean) -> Unit = { active ->
                appActive = active
                controller.setActive(active && control.activationAllowed)
            }
            control.attach(controller::pause, { requested -> controller.setActive(requested && appActive) },
                state::exportViewState, state::importViewState,
                identity = { state.documentIdentity }, exportXml = state::exportXml,
                documentWritable = { state.documentWritable }, setInteractionEnabled = state::setInteractionEnabled)
            runtime.editorAttached(workspace.editorMount)
            until { !runtime.state.busy && state.interactionsEnabled && control.activationAllowed }
            Mounted(state, controller, runtime, control, native, importLoader, requests, published, setAppActive).action()
        } finally {
            documents?.detach(); loader?.close(); run?.close(); scope.cancel()
            runtime.close(); runtime.awaitClosed()
        }
    }

    private companion object {
        fun fixture() = sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ")).first { it.isFile }.readText()
        suspend fun until(condition: () -> Boolean) { withTimeout(5_000) { while (!condition()) delay(1) } }
    }
}
