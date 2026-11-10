package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingTruthTableTest {
    @Test
    fun truthTableIncludesFloatingAndDrivenRowsFromOriginalCircuit() {
        val fixture = checkNotNull(javaClass.getResource("/headless/tristate-271.circ"))
        val circuit = File(fixture.toURI())
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()

        val code = HeadlessCircuitCli.run(
            arrayOf("truth-table", circuit.path), PrintStream(output), PrintStream(error),
        )

        assertEquals(0, code, error.toString())
        assertEquals("EN,Y\n0,x\n1,1\n", output.toString().replace("\r\n", "\n"))
        assertEquals("status=truth-table rows=2", error.toString().trim())
    }
}
