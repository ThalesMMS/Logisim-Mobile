package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessTerminalTest {
    @Test
    fun echoesLegacyKeyboardIntoTtyWithoutLosingRepeatedOrControlBytes() = withCircuit { circuit ->
        val result = run(circuit, "AA\tB\bC".byteInputStream())
        assertEquals(0, result.code, result.error)
        assertEquals("AA\tB\bC\n", result.output)
        assertEquals("status=tick-limit ticks=20\n", result.error)
    }

    @Test
    fun legacyTtyEchoesSuccessiveChunksAndCancelsWhileInputRemainsOpen() = withCircuit { circuit ->
        withLegacyPipe(circuit) { input, output, cancel, finished ->
            for (chunk in listOf("OK\n", "YZ\n")) {
                input.write(chunk.toByteArray())
                input.flush()
                for (character in chunk) {
                    assertEquals(character.code, output.poll(5, TimeUnit.SECONDS))
                }
            }
            cancel()
            assertEquals(130, finished())
        }
    }

    @Test
    fun legacyTtyAdvancesClocksToHaltWhileInputRemainsOpen() = withCircuit { circuit ->
        circuit.writeText(circuit.readText().replace("</circuit>", """
            <comp lib="0" loc="(60,80)" name="Pin"><a name="output" val="true"/><a name="label" val="halt"/></comp>
            <wire from="(60,80)" to="(60,100)"/>
            </circuit>
        """.trimIndent()))
        withLegacyPipe(circuit, "tty,halt") { _, output, _, finished ->
            assertEquals(0, finished())
            assertEquals("halted due to halt pin\n", output.joinToString("") { it.toChar().toString() })
        }
    }

    @Test
    fun legacyTtyStillAcceptsFiniteInputWithATickLimit() = withCircuit { circuit ->
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf(circuit.path, "-tty", "tty", "--ticks", "20"),
            PrintStream(output), PrintStream(error), "OK\n".byteInputStream())
        assertEquals(0, code, error.toString())
        assertEquals("OK\n", output.toString())
    }

    private fun withLegacyPipe(
        circuit: File,
        modes: String = "tty",
        block: (PipedOutputStream, LinkedBlockingQueue<Int>, cancel: () -> Unit, finished: () -> Int) -> Unit,
    ) {
        PipedOutputStream().use { source ->
            PipedInputStream(source).use { stdin ->
                val output = LinkedBlockingQueue<Int>()
                val stdout = PrintStream(object : OutputStream() {
                    override fun write(value: Int) { output.add(value) }
                })
                val cancelled = AtomicBoolean(false)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val result = executor.submit<Int> {
                        HeadlessCircuitCli.run(arrayOf(circuit.path, "-tty", modes), stdout,
                            PrintStream(ByteArrayOutputStream()), stdin, cancelled::get)
                    }
                    block(source, output, { cancelled.set(true) }, { result.get(5, TimeUnit.SECONDS) })
                } finally {
                    cancelled.set(true)
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun streamsTtyOutputWithoutReadingStdinWhenNoKeyboardIsReachable() = withCircuit { circuit ->
        circuit.writeText("""
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#I/O" name="1"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(100,100)" name="Clock"/>
                <comp lib="0" loc="(300,90)" name="Constant"><a name="width" val="7"/><a name="value" val="0x58"/></comp>
                <comp lib="1" loc="(300,100)" name="TTY"/>
                <comp lib="0" loc="(200,100)" name="Pin"><a name="output" val="true"/><a name="label" val="halt"/></comp>
                <wire from="(100,100)" to="(200,100)"/>
                <wire from="(200,100)" to="(300,100)"/>
              </circuit>
            </project>
        """.trimIndent())
        val result = run(circuit, object : InputStream() {
            override fun read(): Int = error("Unexpected stdin read")
        })
        assertEquals(0, result.code, result.error)
        assertEquals("X\n", result.output)
        assertEquals("status=halt ticks=1\n", result.error)
    }

    @Test
    fun rejectsInvalidOrOversizedTerminalInputBeforeSimulationTicks() = withCircuit { circuit ->
        for ((input, error) in listOf(
            byteArrayOf(0x80.toByte()) to "Terminal input must be 7-bit ASCII.",
            ByteArray(64 * 1024 + 1) { 'A'.code.toByte() } to "Terminal input exceeds 64 KiB.",
        )) {
            val result = run(circuit, input.inputStream())
            assertEquals(2, result.code, result.error)
            assertEquals("", result.output)
            assertTrue(result.error.contains(error), result.error)
        }
    }

    private fun withCircuit(block: (File) -> Unit) {
        val circuit = Files.createTempFile("logisim-terminal-test", ".circ").toFile()
        try {
            val fixture = requireNotNull(javaClass.getResource("/headless/terminal-echo-271.circ"))
            circuit.writeText(fixture.readText())
            block(circuit)
        } finally {
            circuit.delete()
        }
    }

    private fun run(circuit: File, stdin: InputStream): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf("temporal", circuit.path, "--terminal", "--ticks", "20"),
            PrintStream(output), PrintStream(error), stdin)
        return Result(code, output.toString(), error.toString())
    }

    private data class Result(val code: Int, val output: String, val error: String)
}
