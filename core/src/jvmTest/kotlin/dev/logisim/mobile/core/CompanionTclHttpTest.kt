package dev.logisim.mobile.core

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompanionTclHttpTest {
    @Test
    fun realKmpTransportAndTclExecutorReturnConsoleSignalsAndExplicitFailures() = runBlocking {
        Fixture().use { fixture ->
            val client = fixture.client
            val console = client.runConsole(TclConsoleRequest("real-console", TclSourceFile("console.tcl", "puts olá; expr {6 * 7}")))
            assertEquals("42", console.result)
            assertEquals("olá\n", console.stdout)
            val script = TclSourceFile("counter.tcl", """
                set count 0
                proc enableLogisim {enabled} {}
                proc logisimExamine {} {}
                proc logisimForce {} {
                    incr ::count
                    set bits ""
                    for {set i 3} {${'$'}i >= 0} {incr i -1} {append bits [expr {(${ '$'}::count >> ${'$'}i) & 1}]}
                    force cpt_o ${'$'}bits
                    if {${'$'}::count == 2} {run}
                }
            """.trimIndent())
            val face = TclInterface(listOf(TclSignalPort("sysclk_i", PortDirection.INPUT, 1), TclSignalPort("cpt_o", PortDirection.OUTPUT, 4)))
            val frames = List(258) { index ->
                TclInputFrame(mapOf("sysclk_i" to LogicVector.of(1, (index % 2).toULong())))
            }
            val reply = client.step(TclStepRequest("real-component", script, face, frames))
            val outputs = reply.steps.map { it.outputs.getValue("cpt_o").display }
            assertEquals(258, outputs.size)
            assertEquals(listOf("0001", "0010", "0011"), outputs.take(3))
            assertEquals(listOf("0000", "0001", "0010"), outputs.takeLast(3))
            assertEquals("0010", reply.outputs.getValue("cpt_o").display)
            assertEquals(setOf(1), reply.tickRequestFrames)
            assertEquals(1L, reply.delay)
            val denied = assertFailsWith<CompanionProtocolException> {
                client.runConsole(TclConsoleRequest("real-denied", TclSourceFile("unsafe.tcl", "exec echo denied")))
            }
            assertEquals("tcl-script-error", denied.code)
        }
    }

    private class Fixture : AutoCloseable {
        private val root = sequenceOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "scripts/tcl-client-fixture.py").isFile }
        private val temporary = Files.createTempDirectory("tcl-http-test-").toFile()
        private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private val process: Process
        val client: CompanionTclClient
        init {
            val log = File(temporary, "server.log")
            val builder = ProcessBuilder("python3", "-u", File(root, "scripts/tcl-client-fixture.py").path).redirectError(log)
            builder.environment()["LOGISIM_TCL_TEST_TOKEN"] = token
            process = builder.start()
            try {
                val reader = process.inputStream.bufferedReader()
                val firstLine = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
                assumeTrue("tclsh is unavailable for real executor checks", firstLine?.startsWith("SKIP:") != true)
                val port = firstLine?.toIntOrNull() ?: error("Tcl fixture did not start: ${log.readText()}")
                val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:$port"), CompanionToken.parse(token))
                client = CompanionTclClient(CompanionClient(connection), "Loopback fixture")
            } catch (failure: Throwable) {
                process.destroyForcibly()
                temporary.deleteRecursively()
                throw failure
            }
        }
        override fun close() {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            temporary.deleteRecursively()
        }
    }
}
