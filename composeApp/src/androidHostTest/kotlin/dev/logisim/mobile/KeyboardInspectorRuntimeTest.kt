package dev.logisim.mobile

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

/** Exercises production session/host lifetime and the asynchronous event queue, without native widgets. */
class KeyboardInspectorRuntimeTest {
    @Test
    fun aFreshPanelLocalDraftLosesThePendingEcho() = runBlocking<Unit> {
        mounted {
            frameUntil { host != null }
            val original = assertNotNull(current)
            val event = assertNotNull(original.edit(assertNotNull(host), current,
                TextFieldValue("AB", TextRange(0, 2), TextRange(0, 2))))
            entered.await()
            assertTrue(run.isEventPending(event))
            assertEquals("AB", original.draft.value(keyboard(), run.status.value.pendingSteps, run::isEventPending).text)
            assertEquals("", KeyboardInputDraft().value(keyboard(), run.status.value.pendingSteps, run::isEventPending).text,
                "A replacement of the old panel-local remember cannot recover the pending edit")
            wide = false
            frameUntil { host == null }
            assertSame(original, current)
            assertFalse(sheet, "An unfocused resize does not open another panel")
            assertTrue(run.isEventPending(event), "Removing a host must not cancel an accepted edit")
            sheet = true
            frameUntil { host?.presentation == InspectorPresentation.SHEET && displayed.text == "AB" }
            assertEquals(TextRange(0, 2), displayed.selection)
            assertEquals(TextRange(0, 2), displayed.composition)
            assertEquals(1, eventRequests)
        }
    }

    @Test
    fun focusedHostSwapKeepsPendingSelectionAndCompositionAndPublishesExactlyOnce() = runBlocking<Unit> {
        mounted {
            frameUntil { host != null }
            val original = assertNotNull(current)
            val oldHost = assertNotNull(host)
            val value = TextFieldValue("AB", TextRange(0, 2), TextRange(0, 2))
            val event = assertNotNull(original.edit(oldHost, current, value))
            entered.await()
            original.focusChanged(oldHost, current, true)
            wide = false
            frameUntil { host?.presentation == InspectorPresentation.SHEET && displayed == value }
            assertSame(original, current)
            assertTrue(disposed.contains(oldHost))
            assertTrue(run.isEventPending(event))
            assertEquals(1, run.status.value.pendingSteps)
            assertEquals(1, eventRequests)
            assertEquals(InspectorPresentation.SHEET, original.focus.focusRequest?.presentation)
            assertNull(original.edit(oldHost, current, TextFieldValue("STALE")))
            assertFalse(original.send(oldHost, current, IoInputEvent.InsertText("STALE")))
            val sheetHost = assertNotNull(host)
            original.focusChanged(sheetHost, current, true)
            original.focus.completeFocus(assertNotNull(original.focus.focusRequest))
            wide = true
            frameUntil { host?.presentation == InspectorPresentation.INLINE && !sheet }
            assertEquals(value, displayed)
            assertEquals(1, eventRequests, "Host recreation must not submit the pending value again")
            release.complete(Unit)
            frameUntil { run.status.value.pendingSteps == 0 && displayed == TextFieldValue("AB", TextRange(2)) }
            assertEquals(1, publications)
            assertEquals(1, eventRequests)
            assertEquals(TextRange(2), displayed.selection)
            assertNull(displayed.composition, "Acknowledged values resume the existing kernel cursor semantics")
            assertFalse(editor.canUndo, "Transient keyboard input is not persisted in document history")
        }
    }

    @Test
    fun capacityApplyKeepsTheSessionReconcilesTheOldAckAndUsesCurrentCapacity() = runBlocking<Unit> {
        mounted {
            frameUntil { host != null }
            val original = assertNotNull(current)
            val binding = assertNotNull(host)
            val before = editor.exportXml()
            val event = assertNotNull(original.edit(binding, current, TextFieldValue("ABCD", TextRange(4))))
            entered.await()
            editor.updateSelectedAttribute("buflen", "2")
            frameUntil { displayed.text.isEmpty() }
            assertSame(original, current, "Attribute snapshots must not own the keyboard session")
            assertFalse(run.isEventPending(event), "Attribute invalidation revokes the old optimistic value")
            val next = assertNotNull(original.edit(binding, current, TextFieldValue("XYZ", TextRange(3))))
            frameUntil { displayed.text == "XY" }
            assertTrue(run.isEventPending(next))
            assertEquals(TextRange(2), displayed.selection)
            release.complete(Unit)
            frameUntil { run.status.value.pendingSteps == 0 && displayed.text == "XY" }
            assertEquals("XY", keyboard().buffer)
            editor.undo()
            assertEquals(before, editor.exportXml(), "Only the explicit capacity Apply belongs to undo")
        }
    }

    @Test
    fun selectionDocumentAndOwnerReplacementDisposeTheOldCallbacks() = runBlocking<Unit> {
        mounted {
            frameUntil { host != null }
            val old = assertNotNull(current)
            val oldHost = assertNotNull(host)
            val oldEditor = editor
            editor.selectComponent(editor.document.components.single { it.label == "IO_DIP" }.id)
            frameUntil { current == null && host == null }
            assertNull(old.edit(oldHost, old, TextFieldValue("STALE")))
            editor.selectComponent(keyboardId)
            frameUntil { current != null && host != null }
            val second = assertNotNull(current)
            assertNotSame(old, second)
            assertNull(old.edit(oldHost, current, TextFieldValue("ABA")))
            val secondHost = assertNotNull(host)
            assertTrue(editor.importXml(oldEditor.exportXml()))
            editor.selectComponent(keyboardId)
            frameUntil { current !== second && host != null }
            assertNull(second.edit(secondHost, second, TextFieldValue("OLD DOC")))
            val third = assertNotNull(current)
            val thirdHost = assertNotNull(host)
            editor = createEditor()
            frameUntil { current !== third && host != null }
            assertNull(third.edit(thirdHost, third, TextFieldValue("OLD OWNER")))
            assertEquals(0, eventRequests)
        }
    }

    private class View(val run: SimulationRunController) {
        var editor by mutableStateOf(createEditor())
        val keyboardId = editor.selected!!.id
        var wide by mutableStateOf(true)
        var sheet by mutableStateOf(false)
        var current: KeyboardInspectorSession? = null
        var host: KeyboardInspectorSession.Host? = null
        var displayed = TextFieldValue()
        val disposed = mutableListOf<KeyboardInspectorSession.Host>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var eventRequests = 0
        var publications = 0
        lateinit var frameUntil: suspend (() -> Boolean) -> Unit
        fun keyboard() = assertIs<IoKeyboardState>(editor.simulation.componentStates[keyboardId])
    }

    @Composable
    private fun SessionView(view: View) {
        val session = rememberKeyboardInspectorSession(view.editor)
        val status by view.run.status.collectAsState()
        val presentation = if (view.wide) InspectorPresentation.INLINE else InspectorPresentation.SHEET
        session?.let { TrackInspectorPresentation(it.focus, presentation) { view.sheet = it == InspectorPresentation.SHEET } }
        SideEffect { view.current = session }
        if (session != null && (view.wide || view.sheet)) {
            key(presentation) {
                val host = rememberKeyboardHost(session, presentation)
                val value = session.draft.value(view.keyboard(), status.pendingSteps, view.run::isEventPending)
                SideEffect { view.host = host; view.displayed = value }
                DisposableEffect(host) { onDispose { view.disposed += host; view.host = null } }
            }
        }
    }

    private suspend fun mounted(block: suspend View.() -> Unit) = coroutineScope {
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frames)
        val composition = Composition(EmptyApplier(), recomposer)
        lateinit var view: View
        val run = SimulationRunController(this, { view.editor.captureRunSnapshot() },
            advance = { snapshot, step ->
                if (snapshot.events.isNotEmpty()) { view.entered.complete(Unit); view.release.await() }
                advanceSimulation(snapshot, step).state
            }, isCurrent = { view.editor.isCurrentRunSnapshot(it) }, publish = { snapshot, result ->
                view.editor.publishRun(snapshot, result); view.publications++
            }, onFailure = { throw it })
        view = View(run)
        view.editor.onSimulationInvalidated = run::invalidate
        view.editor.onSimulationRequested = { snapshot, step ->
            if (snapshot.events.isNotEmpty()) view.eventRequests++
            run.request(snapshot, step)
        }
        val recomposing = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
        view.frameUntil = { condition -> withTimeout(5_000) {
            do { Snapshot.sendApplyNotifications(); frames.sendFrame(System.nanoTime()); yield() } while (!condition())
        } }
        try {
            composition.setContent { SessionView(view) }
            view.block()
        } finally {
            view.release.complete(Unit)
            composition.dispose()
            run.close()
            recomposer.close()
            recomposing.cancelAndJoin()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    companion object {
        private fun createEditor(): EditorState = EditorState(sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ"))
            .first { it.isFile }.readText()).also { state ->
            state.selectComponent(state.document.components.single { it.label == "IO_KEYBOARD" }.id)
        }
    }
}
