package dev.logisim.mobile.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Streams electrical TTY writes independently of the component's visible scrollback. */
internal class HeadlessTerminal(
    project: CircuitProject,
    initial: SimulationState,
    stdin: InputStream,
    private val stdout: PrintStream,
    private val checkCancellation: () -> Unit,
    interactiveInput: Boolean = false,
) : Closeable {
    private data class Keyboard(val path: String, val id: String, val capacity: Int, var offset: Int = 0) {
        val eventKey: String get() = if (path.isEmpty()) id else "$path/$id"
    }

    private val keyboards = mutableListOf<Keyboard>()
    private val batchInput by lazy { readInput(stdin, checkCancellation) }
    private val liveInput: LiveInput?
    private var written = 0
    private var lastWasNewline = true

    init {
        var foundTty = false
        for (instance in sequenceOf(initial) + initial.circuitStates.values.asSequence()) {
            val circuit = requireNotNull(project.circuit(instance.circuitName))
            for (component in circuit.components) {
                when (component.kind) {
                    IoKinds.Tty -> foundTty = true
                    IoKinds.Keyboard -> keyboards += Keyboard(instance.instancePath, component.id,
                        ComponentCatalog.registry.attributes(component)[IoAttributes.BufferLength])
                }
            }
        }
        if (!foundTty && keyboards.isEmpty()) throw IOException("--terminal requires a Keyboard or TTY.")
        liveInput = if (interactiveInput && keyboards.isNotEmpty()) LiveInput(stdin) else null
    }

    fun keyboardEvents(state: SimulationState): Map<String, List<ComponentEvent>> {
        if (keyboards.isEmpty()) return emptyMap()
        val live = liveInput ?: return keyboardEvents(state, batchInput)
        return live.withInput(keyboards.minOf { it.offset }, checkCancellation) { keyboardEvents(state, it) }
    }

    private fun keyboardEvents(state: SimulationState, input: CharSequence): Map<String, List<ComponentEvent>> = buildMap {
        for (keyboard in keyboards) {
            if (keyboard.offset >= input.length) continue
            val instance = if (keyboard.path.isEmpty()) state else state.circuitStates.getValue(keyboard.path)
            val size = (instance.componentStates.getValue(keyboard.id) as IoKeyboardState).buffer.length
            val end = minOf(input.length, keyboard.offset + (keyboard.capacity - size).coerceAtLeast(0))
            if (end > keyboard.offset) {
                put(keyboard.eventKey, listOf(IoTerminalText(input.subSequence(keyboard.offset, end).toString())))
                keyboard.offset = end
            }
        }
    }

    fun write(character: Char) {
        check(written < LIMIT_BYTES) { "Terminal output exceeds 64 KiB." }
        stdout.write(character.code)
        stdout.flush()
        check(!stdout.checkError()) { "Cannot write terminal output." }
        written++
        lastWasNewline = character == '\n'
    }

    fun finish() {
        if (!lastWasNewline) write('\n')
    }

    override fun close() {
        liveInput?.close()
    }

    private class LiveInput(private val stdin: InputStream) : Closeable {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val received = StringBuilder()
        private var closed = false
        private var finished = false
        private var failure: Exception? = null
        private val reader = Thread(::read, "logisim-tty-stdin").apply { isDaemon = true }

        fun <T> withInput(offset: Int, checkCancellation: () -> Unit, consume: (CharSequence) -> T): T =
            lock.withLock {
                if (reader.state == Thread.State.NEW) reader.start()
                if (offset >= received.length && !finished) {
                    // Let stdin wake the simulator while still advancing clocks with an open, idle pipe.
                    try {
                        changed.await(2L, TimeUnit.MILLISECONDS)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        checkCancellation()
                        throw IOException("Terminal input interrupted.", error)
                    }
                }
                failure?.let { throw it }
                consume(received)
            }

        private fun read() {
            val chunk = ByteArray(4096)
            var length = 0
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val text = readChunk(stdin, chunk, length) ?: break
                    lock.withLock {
                        if (closed) return
                        received.append(text)
                        changed.signal()
                    }
                    length += text.length
                }
            } catch (error: Exception) {
                lock.withLock { failure = error }
            } finally {
                lock.withLock {
                    finished = true
                    changed.signal()
                }
            }
        }

        override fun close() {
            lock.withLock { closed = true }
            // Some streams cannot interrupt a blocking read. The daemon cannot keep the CLI alive.
            reader.interrupt()
        }
    }

    private companion object {
        const val LIMIT_BYTES = 64 * 1024

        fun readInput(stdin: InputStream, checkCancellation: () -> Unit): String {
            val input = StringBuilder()
            val chunk = ByteArray(4096)
            while (true) {
                checkCancellation()
                input.append(readChunk(stdin, chunk, input.length) ?: return input.toString())
            }
        }

        private fun readChunk(stdin: InputStream, chunk: ByteArray, length: Int): String? {
            val count = stdin.read(chunk, 0, minOf(chunk.size, LIMIT_BYTES - length + 1))
            if (count < 0) return null
            if (length + count > LIMIT_BYTES) throw IOException("Terminal input exceeds 64 KiB.")
            return buildString(count) {
                for (i in 0 until count) {
                    val character = chunk[i].toInt()
                    if (character !in 0..127) throw IOException("Terminal input must be 7-bit ASCII.")
                    append(character.toChar())
                }
            }
        }
    }
}
