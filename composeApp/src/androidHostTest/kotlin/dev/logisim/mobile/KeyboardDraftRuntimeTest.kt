package dev.logisim.mobile

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

class KeyboardDraftRuntimeTest {
    @Test
    fun cancellationWithoutPublicationAbandonsTheDraft() = runBlocking<Unit> {
        mounted(block = true) {
            edit(TextFieldValue("AB", TextRange(2)))
            entered.await()
            assertEquals("AB", displayed().text)
            run.pause()
            assertEquals("", displayed().text)
            assertEquals("", keyboard().buffer)
            release.complete(Unit)
            yield()
            assertEquals(0, publications)
        }
    }

    @Test
    fun equalTextFromAnotherEventDoesNotOwnTheDraft() = runBlocking<Unit> {
        mounted(block = true) {
            val event = edit(TextFieldValue("A", TextRange(0, 1)))
            entered.await()
            val equalEvent = event.copy()
            assertEquals(event, equalEvent)
            assertTrue(run.isEventPending(event))
            assertFalse(run.isEventPending(equalEvent))
            run.pause()
            assertTrue(state.sendComponentEvent(keyboardId, equalEvent))
            assertEquals(1, run.status.value.pendingSteps)
            assertFalse(run.isEventPending(event))
            assertEquals("", displayed().text)
            release.complete(Unit)
            drain()
            assertEquals("A", displayed().text)
            assertEquals(TextRange(1), displayed().selection)
        }
    }

    @Test
    fun repeatingAnIdenticalEditAfterCancellationOwnsTheNewRequest() = runBlocking<Unit> {
        mounted(block = true) {
            val value = TextFieldValue("AB", TextRange(2))
            val old = edit(value)
            entered.await()
            run.pause()
            assertEquals("", displayed().text)
            val retry = edit(value)
            assertEquals(old, retry)
            assertFalse(run.isEventPending(old))
            assertTrue(run.isEventPending(retry))
            assertEquals("AB", displayed().text)
            release.complete(Unit)
            drain()
            assertEquals("AB", displayed().text)
        }
    }

    @Test
    fun capacityAndSelectionNormalizeBeforePublication() = runBlocking<Unit> {
        mounted(block = true) {
            val value = TextFieldValue("ABCDEFGHIJKLMNOPQ", TextRange(17))
            edit(value)
            entered.await()
            assertEquals("ABCDEFGHIJKLMNOP", displayed().text)
            assertEquals(TextRange(16), displayed().selection)
            release.complete(Unit)
            drain()
            assertEquals("ABCDEFGHIJKLMNOP", displayed().text)
            assertEquals("ABCDEFGHIJKLMNOP", keyboard().buffer)
        }
    }

    @Test
    fun filteredCapacityAndReversedCompositionUseKernelNormalization() = runBlocking<Unit> {
        mounted(block = true) {
            state.selectComponent(keyboardId)
            state.updateSelectedAttribute("buflen", "2")
            drain()
            edit(TextFieldValue("A\u0000BC", TextRange(4, 1), TextRange(1, 4)))
            entered.await()
            assertEquals("AB", displayed().text)
            assertEquals(TextRange(2, 1), displayed().selection)
            assertEquals(TextRange(1, 2), displayed().composition)
            release.complete(Unit)
            drain()
            assertEquals("AB", keyboard().buffer)
            assertEquals(TextRange(1), displayed().selection)
        }
    }

    @Test
    fun rejectedInactiveInputNeverLeavesAnOptimisticDraft() = runBlocking<Unit> {
        mounted {
            run.setActive(false)
            val event = draft.edit(TextFieldValue("AB", TextRange(2)), 16)
            assertFalse(state.sendComponentEvent(keyboardId, event))
            assertEquals("", displayed().text)
            assertEquals("", keyboard().buffer)
            assertEquals(0, publications)
        }
    }

    @Test
    fun completedDraftCannotHideClockConsumptionOrLaterUnrelatedWork() = runBlocking<Unit> {
        mounted {
            edit(TextFieldValue("AB", TextRange(2)))
            drain()
            state.tick()
            drain()
            assertEquals("B", keyboard().buffer)
            assertEquals("B", displayed().text)
            blockEvents = true
            val dip = state.document.components.single { it.label == "IO_DIP" }.id
            assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
            entered.await()
            assertEquals(1, run.status.value.pendingSteps)
            assertEquals("B", displayed().text)
            release.complete(Unit)
            drain()
        }
    }

    @Test
    fun homeEndDeleteAndAsciiControlsUseThePublishedKeyboardState() = runBlocking<Unit> {
        mounted {
            edit(TextFieldValue("AB", TextRange(2)))
            drain()
            send(IoInputEvent.EditKeyboard(KeyboardCommand.HOME)); drain()
            assertEquals(TextRange(0), displayed().selection)
            send(IoInputEvent.EditKeyboard(KeyboardCommand.DELETE)); drain()
            assertEquals("B", displayed().text)
            send(IoInputEvent.EditKeyboard(KeyboardCommand.END)); drain()
            assertEquals(TextRange(1), displayed().selection)
            send(IoInputEvent.InsertText("\b\u000c")); drain()
            assertEquals("B\b\u000c", displayed().text)
            assertEquals("B\b\u000c", keyboard().buffer)
        }
    }

    @Test
    fun replacingTheDocumentCannotReuseThePreviousEventDraft() = runBlocking<Unit> {
        mounted(block = true) {
            edit(TextFieldValue("AB", TextRange(2)))
            entered.await()
            val oldIdentity = state.documentIdentity
            val oldCircuit = state.document.id
            state.newDocument()
            assertNotEquals(oldIdentity, state.documentIdentity)
            assertFalse(state.sendComponentEvent(keyboardId, IoInputEvent.ReplaceKeyboardText("WRONG", 5),
                emptyList(), oldCircuit, oldIdentity))
            assertEquals("", draft.value(IoKeyboardState(), run.status.value.pendingSteps, run::isEventPending).text)
            release.complete(Unit)
            drain()
            assertTrue(state.document.components.isEmpty())
        }
    }

    fun strongSkippingReevaluatesCancelledDraftWhenThePendingCountStaysOne() = runBlocking<Unit> {
        mounted(block = true) {
            val frames = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + frames)
            val composition = Composition(EmptyApplier(), recomposer)
            val seen = mutableListOf<String>()
            val read = ::keyboard
            val record: (String) -> Unit = { seen += it }
            val recomposing = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
            try {
                composition.setContent {
                    val status by run.status.collectAsState()
                    DraftProbe(status, draft, run, read, record)
                }
                suspend fun frameUntil(value: String, after: Int = -1) {
                    withTimeout(5_000) {
                        while (seen.size <= after || seen.lastOrNull() != value) {
                            Snapshot.sendApplyNotifications()
                            frames.sendFrame(System.nanoTime())
                            yield()
                        }
                    }
                }
                frameUntil("")
                edit(TextFieldValue("AB", TextRange(2)))
                entered.await()
                frameUntil("AB")
                val before = run.status.value
                val count = seen.size
                run.pause()
                val dip = state.document.components.single { it.label == "IO_DIP" }.id
                assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
                val after = run.status.value
                assertEquals(1, before.pendingSteps)
                assertEquals(1, after.pendingSteps)
                assertEquals(before.phase, after.phase)
                println("Pending substitution before=$before after=$after")
                frameUntil("", after = count)
                assertEquals("", keyboard().buffer)
                assertEquals(0, publications, "Cancellation must be observable without a kernel publication")
            } finally {
                composition.dispose()
                recomposer.close()
                recomposing.cancelAndJoin()
                release.complete(Unit)
            }
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Mounted(val state: EditorState, val run: SimulationRunController) {
        val keyboardId = state.document.components.single { it.label == "IO_KEYBOARD" }.id
        val draft = KeyboardInputDraft()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockEvents = false
        var publications = 0
        fun keyboard() = assertIs<IoKeyboardState>(state.simulation.componentStates[keyboardId])
        fun displayed() = draft.value(keyboard(), run.status.value.pendingSteps, run::isEventPending)
        fun send(event: IoInputEvent) { assertTrue(state.sendComponentEvent(keyboardId, event)) }
        fun edit(value: TextFieldValue): IoInputEvent.ReplaceKeyboardText =
            draft.edit(value, state.document.components.single { it.id == keyboardId }.attributeInt("buflen", 16)).also(::send)
        suspend fun drain() = withTimeout(5_000) { while (run.status.value.pendingSteps > 0) delay(1) }
    }

    private suspend fun mounted(block: Boolean = false, action: suspend Mounted.() -> Unit) = coroutineScope {
        val state = withContext(Dispatchers.Default) {
            EditorState(sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ"))
                .first { it.isFile }.readText())
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var mounted: Mounted
        val run = SimulationRunController(scope, state::captureRunSnapshot,
            advance = { snapshot, step ->
                if (mounted.blockEvents && snapshot.events.isNotEmpty()) {
                    mounted.entered.complete(Unit)
                    mounted.release.await()
                }
                advanceSimulation(snapshot, step).state
            }, isCurrent = state::isCurrentRunSnapshot, publish = { snapshot, result ->
                state.publishRun(snapshot, result); mounted.publications++
            }, onFailure = { throw it })
        mounted = Mounted(state, run).also { it.blockEvents = block }
        state.onSimulationInvalidated = run::invalidate
        state.onSimulationRequested = run::request
        try { mounted.action() } finally { mounted.release.complete(Unit); run.close(); scope.cancel() }
    }
}

@Composable
private fun DraftProbe(status: SimulationRunStatus, draft: KeyboardInputDraft, run: SimulationRunController,
                       read: () -> IoKeyboardState, record: (String) -> Unit) {
    val text = draft.value(read(), status.pendingSteps, run::isEventPending).text
    SideEffect { record(text) }
}
