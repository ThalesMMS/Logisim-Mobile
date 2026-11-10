package dev.logisim.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

/** Diagnostic of the production callback and Compose's documented recomposition boundary.
 * This does not mount Android UI or claim the exact native event timing was recorded.
 */
class KeyboardInputCallbackTest {
    @Test
    fun typingAcrossAFrameMustPreserveBothCharactersBeforeWorkerPublishes() = runBlocking<Unit> {
        exercise(blockWorker = true, recomposeBetweenCharacters = true)
    }

    @Test
    fun controlWithoutInterveningFramePreservesBothCharacters() = runBlocking<Unit> {
        exercise(blockWorker = true, recomposeBetweenCharacters = false)
    }

    @Test
    fun controlWithFirstPublicationBeforeTheFramePreservesBothCharacters() = runBlocking<Unit> {
        exercise(blockWorker = false, recomposeBetweenCharacters = true)
    }

    private suspend fun exercise(blockWorker: Boolean, recomposeBetweenCharacters: Boolean) = coroutineScope {
        val state = withContext(Dispatchers.Default) {
            EditorState(sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ"))
                .first { it.isFile }.readText())
        }
        val keyboard = state.document.components.single { it.label == "IO_KEYBOARD" }
        fun buffer() = assertIs<IoKeyboardState>(state.simulation.componentStates[keyboard.id])
        fun controlledValue() = buffer().let { TextFieldValue(it.buffer, TextRange(it.cursor)) }
        assertEquals("", buffer().buffer)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val run = SimulationRunController(scope, state::captureRunSnapshot,
            advance = { snapshot, step ->
                if (blockWorker && snapshot.events.isNotEmpty()) {
                    entered.complete(Unit)
                    release.await()
                }
                advanceSimulation(snapshot, step).state
            }, isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            onFailure = { throw it })
        state.onSimulationInvalidated = run::invalidate
        state.onSimulationRequested = run::request
        state.selectComponent(keyboard.id)
        val session = KeyboardInspectorSession(state, assertNotNull(state.keyboardDraftContext()))
        val host = KeyboardInspectorSession.Host(InspectorPresentation.INLINE).also(session::attach)
        val current: () -> KeyboardInspectorSession? = { session }
        val draft = session.draft
        fun displayed() = draft.value(buffer(), run.status.value.pendingSteps, run::isEventPending)
        // Invoke the callback compiled from the real production panel, including its draft update.
        val callback = Class.forName("dev.logisim.mobile.IoInputPanelKt").declaredMethods.single {
            it.parameterTypes.lastOrNull() == TextFieldValue::class.java
        }.also { it.isAccessible = true }
        fun change(value: TextFieldValue) {
            val args = callback.parameterTypes.map { type -> when (type) {
                TextFieldValue::class.java -> value
                KeyboardInspectorSession::class.java -> session
                KeyboardInspectorSession.Host::class.java -> host
                kotlin.jvm.functions.Function0::class.java -> current
                else -> error("Unexpected production callback capture $type")
            } }.toTypedArray()
            callback.invoke(null, *args)
        }
        val processor = EditProcessor()
        println("EditProcessor origin=" + EditProcessor::class.java.protectionDomain.codeSource.location)
        println("Production callback=" + callback.name)
        try {
            processor.reset(displayed(), null)
            val first = processor.apply(listOf(FinishComposingTextCommand(), CommitTextCommand("A", 1)))
            assertEquals("A", first.text)
            change(first)
            if (blockWorker) {
                withTimeout(5_000) { entered.await() }
                assertEquals("", controlledValue().text)
                assertEquals("A", displayed().text, "The field must echo A before simulation publication")
            } else {
                until { run.status.value.pendingSteps == 0 }
                assertEquals("A", controlledValue().text)
            }
            // CoreTextField resets its processor with the caller's value each recomposition.
            if (recomposeBetweenCharacters) processor.reset(displayed(), null)
            val second = processor.apply(listOf(FinishComposingTextCommand(), CommitTextCommand("B", 1)))
            change(second)
            release.complete(Unit)
            until { run.status.value.pendingSteps == 0 }
            println("blockWorker=$blockWorker frame=$recomposeBetweenCharacters final=${buffer().buffer}")
            assertEquals("AB", buffer().buffer, "Two input characters must survive a recomposition before asynchronous simulation publication")
        } finally {
            release.complete(Unit)
            session.close()
            run.close()
            scope.cancel()
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }
}
