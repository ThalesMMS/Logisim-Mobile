package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.WindowInfo
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoButtonState
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasWindowFocusTest {
    @Test
    fun losingAnIdleWindowPreservesTheOpenedStatusAndDocument() = runBlocking {
        val window = TestWindowInfo(true)
        val editor = EditorState(CircCodec.write(CircuitDocument()))
        editor.reportStatus(message(Messages.StatusFileOpened, "name" to "fixture.circ"))
        val before = editor.exportXml()
        val lost = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeCanvasWindowFocus(window, { null }) {
                cancelCanvasInteractionOnFocusLoss(null, false, {}, editor::handleCanvasCommand)
                lost.complete(Unit)
            }
        }
        try {
            assertEquals("File fixture.circ opened.", editor.statusText.resolve(AppLanguage.ENGLISH))
            window.focused = false
            Snapshot.sendApplyNotifications()
            withTimeout(5_000) { lost.await() }
            assertEquals("File fixture.circ opened.", editor.statusText.resolve(AppLanguage.ENGLISH))
            assertEquals(before, editor.exportXml())
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun losingFocusBetweenWireTapsStillCancelsThePendingConnection() {
        val editor = EditorState(null)
        editor.changeMode(EditorMode.WIRE)
        editor.tap(GridPoint(20, 20))
        assertNotNull(editor.pendingWire)
        val before = editor.exportXml()

        cancelCanvasInteractionOnFocusLoss(null, editor.pendingWire != null, {}, editor::handleCanvasCommand)

        assertNull(editor.pendingWire)
        assertEquals("Operation cancelled.", editor.statusText.resolve(AppLanguage.ENGLISH))
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun focusLossCancelsPressedDraggedAndTransformingGesturesWithoutCommit() {
        for (action in CanvasDragAction.entries) {
            for (pointerCount in 0..2) {
                val gesture = CanvasGestureMachine(Offset.Zero, action)
                if (pointerCount != 0) gesture.move(Offset(50f, 20f), 8f, pointerCount)
                val editor = selectedNot()
                val before = editor.exportXml()

                cancelCanvasInteractionOnFocusLoss(gesture, false, {}, editor::handleCanvasCommand)

                assertEquals(CanvasGesturePhase.CANCELLED, gesture.phase)
                assertNull(gesture.finish())
                assertEquals("Operation cancelled.", editor.statusText.resolve(AppLanguage.ENGLISH))
                assertEquals(before, editor.exportXml())
            }
        }
    }

    @Test
    fun losingFocusReleasesHeldIoOnceBeforeCancellingTheOperation() {
        val editor = EditorState(null)
        editor.newDocument()
        assertTrue(editor.insertComponent(IoKinds.Button, GridPoint(100, 100)))
        val button = editor.document.components.single().id
        assertTrue(editor.sendComponentEvent(button, IoInputEvent.Press(true)))
        var held = true
        var releases = 0
        var commands = 0
        val clearInteraction = {
            if (held) {
                held = false
                releases++
                assertTrue(editor.sendComponentEvent(button, IoInputEvent.Cancel))
            }
        }
        val onCommand: (CanvasCommand) -> Unit = { command ->
            commands++
            assertFalse(held)
            editor.handleCanvasCommand(command)
        }

        cancelCanvasInteractionOnFocusLoss(null, held, clearInteraction, onCommand)
        cancelCanvasInteractionOnFocusLoss(null, held, clearInteraction, onCommand)

        assertFalse(assertIs<IoButtonState>(editor.rootSimulation.componentStates[button]).pressed)
        assertEquals(1, releases)
        assertEquals(1, commands)
        assertEquals("Operation cancelled.", editor.statusText.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun explicitEscapeStillCancelsAnIdleEditor() {
        val editor = selectedNot()
        val before = editor.exportXml()

        editor.handleCanvasCommand(assertNotNull(fixedCanvasShortcut(Key.Escape)))

        assertEquals("Operation cancelled.", editor.statusText.resolve(AppLanguage.ENGLISH))
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun anInitiallyUnfocusedIdleWindowDoesNotCancelTheEditor() = runBlocking {
        var cleanupCount = 0
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeCanvasWindowFocus(TestWindowInfo(false), { null }) { cleanupCount++ }
        }
        try {
            assertEquals(0, cleanupCount)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun losingAndRegainingWindowFocusCannotCommitTheHeldDragOnUp() = runBlocking {
        val window = TestWindowInfo(true)
        val gesture = draggedGesture()
        val editor = selectedNot()
        val before = editor.exportXml()
        val lost = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeCanvasWindowFocus(window, { gesture }) {
                cleanupCount++
                lost.complete(Unit)
            }
        }
        try {
            window.focused = false
            Snapshot.sendApplyNotifications()
            withTimeout(5_000) { lost.await() }
            assertEquals(CanvasGesturePhase.CANCELLED, gesture.phase)
            window.focused = true
            Snapshot.sendApplyNotifications()
            yield()
            assertEquals(CanvasGesturePhase.CANCELLED, gesture.phase)

            assertEquals(0, releaseIntoEditor(gesture, editor))
            assertEquals(before, editor.exportXml())
            assertEquals(1, cleanupCount)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun aFocusedWindowCommitsExactlyOneNormalDragOnUp() = runBlocking {
        val window = TestWindowInfo(true)
        val gesture = draggedGesture()
        val editor = selectedNot()
        var cleanupCount = 0
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeCanvasWindowFocus(window, { gesture }) { cleanupCount++ }
        }
        try {
            assertEquals(1, releaseIntoEditor(gesture, editor))
            assertEquals(GridPoint(250, 120), editor.document.components.single().position)
            assertNull(gesture.finish())
            assertEquals(0, cleanupCount)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun observingAnAlreadyUnfocusedWindowCancelsTheCurrentGesture() = runBlocking {
        val gesture = draggedGesture()
        val lost = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeCanvasWindowFocus(TestWindowInfo(false), { gesture }) { lost.complete(Unit) }
        }
        try {
            withTimeout(5_000) { lost.await() }
            gesture.move(release(), PointerId(0), 8f)
            assertNull(gesture.finish())
        } finally {
            observer.cancelAndJoin()
        }
    }

    private fun selectedNot(): EditorState {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("gate", ComponentKind.NOT, GridPoint(200, 100)),
        ))
        return EditorState(CircCodec.write(document)).apply { selectComponent("gate") }
    }

    private fun draggedGesture() = CanvasGestureMachine(Offset.Zero, CanvasDragAction.MOVE_SELECTION).apply {
        move(Offset(50f, 20f), touchSlop = 8f, pointerCount = 1)
    }

    private fun releaseIntoEditor(gesture: CanvasGestureMachine, editor: EditorState): Int {
        gesture.move(release(), PointerId(0), 8f)
        val completion = gesture.finish() as? CanvasGestureCompletion.Drag ?: return 0
        val delta = completion.to - completion.from
        editor.moveSelected(GridPoint(delta.x.toInt(), delta.y.toInt()))
        return 1
    }

    private fun release() = PointerEvent(listOf(PointerInputChange(
        id = PointerId(0), uptimeMillis = 100L, position = Offset(50f, 20f), pressed = false, pressure = 0f,
        previousUptimeMillis = 90L, previousPosition = Offset(50f, 20f), previousPressed = true,
        isInitiallyConsumed = false, type = PointerType.Touch,
    )))

    private class TestWindowInfo(initiallyFocused: Boolean) : WindowInfo {
        var focused by mutableStateOf(initiallyFocused)
        override val isWindowFocused: Boolean get() = focused
    }
}
