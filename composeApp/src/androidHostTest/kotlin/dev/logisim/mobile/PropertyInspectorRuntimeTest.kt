package dev.logisim.mobile

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PropertyInspectorRuntimeTest {
    @Test
    fun focusedLabelMovesAfterTheOldHostHasBeenDisposed() = runBlocking<Unit> {
        mounted {
            frameUntil { host == InspectorPresentation.INLINE }
            val original = current
            val before = editor.exportXml()
            original.label = TextFieldValue("Re", TextRange(1, 2), TextRange(0, 2))
            original.focus.focusChanged(InspectorPresentation.INLINE, true)
            wide = false
            frameUntil { host == InspectorPresentation.SHEET }
            assertTrue(disposed.contains(InspectorPresentation.INLINE))
            assertSame(original, current)
            assertEquals(TextFieldValue("Re", TextRange(1, 2), TextRange(0, 2)), current.label)
            assertEquals(InspectorPresentation.SHEET, current.focus.focusRequest?.presentation)
            assertEquals(before, editor.exportXml())
            current.focus.focusChanged(InspectorPresentation.SHEET, true)
            current.focus.completeFocus(assertNotNull(current.focus.focusRequest))
            assertNull(current.focus.focusRequest)
            wide = true
            frameUntil { host == InspectorPresentation.INLINE && !sheet }
            assertEquals(InspectorPresentation.INLINE, current.focus.focusRequest?.presentation)
            assertSame(original, current)
        }
    }

    @Test
    fun unfocusedResizeDoesNotOpenAnInspectorOrRequestFocus() = runBlocking<Unit> {
        mounted {
            frameUntil { host == InspectorPresentation.INLINE }
            wide = false
            frameUntil { host == null }
            assertFalse(sheet)
            assertNull(current.focus.focusRequest)
        }
    }

    @Test
    fun theHoistedPropertyDraftSurvivesWithoutOpeningAnotherSheet() = runBlocking<Unit> {
        mounted {
            frameUntil { host == InspectorPresentation.INLINE }
            val session = current
            val before = editor.exportXml()
            session.open(PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "width" })
            val edit = assertNotNull(session.editing)
            edit.value = TextFieldValue("0", TextRange(0, 1), TextRange(0, 1))
            wide = false
            frameUntil { host == null }
            assertSame(session, current)
            assertSame(edit, current.editing)
            assertEquals(TextFieldValue("0", TextRange(0, 1), TextRange(0, 1)), edit.value)
            assertIs<PropertyEditResult.Invalid>(current.validation(edit))
            assertFalse(sheet)
            assertNull(current.focus.focusRequest)
            assertEquals(before, editor.exportXml())
        }
    }

    @Test
    fun selectionAndEditorReplacementCreateNewDraftsAndRejectOldActions() = runBlocking<Unit> {
        mounted {
            frameUntil { host == InspectorPresentation.INLINE }
            val old = current
            val oldEditor = editor
            old.label = TextFieldValue("WRONG")
            editor.selectComponent("two")
            frameUntil { current !== old }
            assertEquals("B", current.label.text)
            assertFalse(old.applyLabel(editor, current))
            assertFalse(old.applyLabel(oldEditor, old), "Disposal also rejects a callback carrying its original owner and session")
            val second = current
            editor = createEditor()
            frameUntil { current !== second }
            assertNotSame(second, current)
            assertEquals("A", current.label.text)
            assertFalse(second.applyLabel(editor, current))
        }
    }

    private class View {
        var editor by mutableStateOf(createEditor())
        var wide by mutableStateOf(true)
        var sheet by mutableStateOf(false)
        lateinit var current: PropertyInspectorSession
        var host: InspectorPresentation? = null
        val disposed = mutableListOf<InspectorPresentation>()
        lateinit var frameUntil: suspend (() -> Boolean) -> Unit
    }

    @Composable
    private fun SessionView(view: View) {
        val session = rememberPropertyInspectorSession(view.editor)
        val presentation = if (view.wide) InspectorPresentation.INLINE else InspectorPresentation.SHEET
        TrackInspectorPresentation(session.focus, presentation) { view.sheet = it == InspectorPresentation.SHEET }
        SideEffect { view.current = session }
        if (view.wide) Host(view, session, InspectorPresentation.INLINE)
        else if (view.sheet) Host(view, session, InspectorPresentation.SHEET)
    }

    @Composable
    private fun Host(view: View, session: PropertyInspectorSession, presentation: InspectorPresentation) {
        SideEffect { view.host = presentation }
        DisposableEffect(session, presentation) {
            onDispose {
                view.disposed += presentation
                view.host = null
                session.focus.focusChanged(presentation, false)
            }
        }
    }

    private suspend fun mounted(block: suspend View.() -> Unit) = coroutineScope {
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frames)
        val composition = Composition(EmptyApplier(), recomposer)
        val view = View()
        val recomposing = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
        view.frameUntil = { condition ->
            withTimeout(5_000) {
                do {
                    Snapshot.sendApplyNotifications()
                    frames.sendFrame(System.nanoTime())
                    yield()
                } while (!condition())
            }
        }
        try {
            composition.setContent { SessionView(view) }
            view.block()
        } finally {
            composition.dispose()
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
        private fun createEditor() = EditorState(CircCodec.writeProject(CircuitProject(listOf(
            CircuitDocument(components = listOf(
                CircuitComponent("one", ComponentKind.AND, GridPoint(100, 100), "A"),
                CircuitComponent("two", ComponentKind.AND, GridPoint(300, 100), "B"),
            )),
        )))).also { it.selectComponent("one") }
    }
}
