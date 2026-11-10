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
class KeyboardInputCallbackRaceTest {
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
        val state = withContext(Dispatchers.Default) { EditorState(File("examples/io-showcase.circ").readText()) }
        val keyboard = state.document.components.single { it.label == "IO_KEYBOARD" }
        fun buffer() = assertIs<IoKeyboardState>(state.simulation.componentStates[keyboard.id])
        fun controlledValue() = buffer().let { TextFieldValue(it.buffer, TextRange(it.cursor)) }
        assertEquals("", buffer().buffer)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val events = mutableListOf<IoInputEvent.ReplaceKeyboardText>()
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
        // Invoke the unique callback compiled from the unchanged production IoInputPanel.kt.
        val callback = Class.forName("dev.logisim.mobile.IoInputPanelKt").declaredMethods.single {
            it.parameterTypes.contentEquals(arrayOf(kotlin.jvm.functions.Function1::class.java, TextFieldValue::class.java))
        }.also { it.isAccessible = true }
        val send: (IoInputEvent) -> Unit = { event ->
            events += assertIs<IoInputEvent.ReplaceKeyboardText>(event)
            assertTrue(state.sendComponentEvent(keyboard.id, event))
        }
        val processor = EditProcessor()
        println("EditProcessor origin=" + EditProcessor::class.java.protectionDomain.codeSource.location)
        println("Production callback=" + callback.name)
        try {
            processor.reset(controlledValue(), null)
            val first = processor.apply(listOf(FinishComposingTextCommand(), CommitTextCommand("A", 1)))
            assertEquals("A", first.text)
            callback.invoke(null, send, first)
            if (blockWorker) {
                withTimeout(5_000) { entered.await() }
                assertEquals("", controlledValue().text, "The callback has not synchronously fed A back to the controlled field")
            } else {
                until { run.status.value.pendingSteps == 0 }
                assertEquals("A", controlledValue().text)
            }
            // CoreTextField resets its processor with the caller's value each recomposition.
            if (recomposeBetweenCharacters) processor.reset(controlledValue(), null)
            val second = processor.apply(listOf(FinishComposingTextCommand(), CommitTextCommand("B", 1)))
            callback.invoke(null, send, second)
            release.complete(Unit)
            until { run.status.value.pendingSteps == 0 }
            println("blockWorker=$blockWorker frame=$recomposeBetweenCharacters callbacks=${events.map { it.text }} final=${buffer().buffer}")
            assertEquals("AB", buffer().buffer, "Two input characters must survive a recomposition before asynchronous simulation publication")
        } finally {
            release.complete(Unit)
            run.close()
            scope.cancel()
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }
}
