package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessAutomationTest {
    @Test
    fun testBenchWaitsForDoneAndReportsTheFirstResult() = withDirectory { directory ->
        val source = File(directory, "bench.circ")
        source.writeText(benchXml)
        assertEquals(Result(0, "Test bench pass\n\n", ""), run("--test-circuit", source.path))
        assertEquals(Result(1, "Test bench fail\n\n", ""), run("-b", source.path, "--ticks", "0"))

        source.writeText(benchXml.replace("val=\"0x1\"", "val=\"0x0\""))
        assertEquals(Result(1, "Test bench fail\n\n", ""), run("--test-circuit", source.path))

        source.writeText(benchXml.replace("test_bench_ok_o", "other"))
        val invalid = run("--test-circuit", source.path)
        assertEquals(2, invalid.code)
        assertTrue(invalid.error.contains("one one-bit output named 'test_bench_ok_o'"))
    }

    @Test
    fun conversionPreservesTheInputAndWritesAReopenableProject() = withDirectory { directory ->
        val source = File(directory, "input.circ").apply { writeText(benchXml) }
        val target = File(directory, "output.circ").apply { writeText("old data") }
        assertEquals(Result(0, "", ""), run("-n", source.path, target.path))
        assertEquals(benchXml, source.readText())
        assertTrue(target.readText().startsWith("<?xml"))
        assertEquals(Result(0, "Test bench pass\n\n", ""), run("--test-circuit", target.path))

        assertEquals(2, run("--new-file-format", source.path, source.path).code)
        assertEquals(benchXml, source.readText())
    }

    @Test
    fun failedConversionDoesNotCreateOrReplaceTheDestination() = withDirectory { directory ->
        val source = File(directory, "broken.circ").apply { writeText("<project>") }
        val existing = File(directory, "existing.circ").apply { writeText("keep me") }
        val absent = File(directory, "absent.circ")
        assertEquals(2, run("--new-file-format", source.path, existing.path).code)
        assertEquals("keep me", existing.readText())
        assertEquals(2, run("--new-file-format", source.path, absent.path).code)
        assertFalse(absent.exists())

        source.writeText(benchXml)
        val targetDirectory = File(directory, "target-directory").apply { mkdir() }
        assertEquals(2, run("--new-file-format", source.path, targetDirectory.path).code)
        assertTrue(targetDirectory.isDirectory)
        assertEquals(setOf(source.name, existing.name, targetDirectory.name), directory.list()!!.toSet())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("logisim-headless-automation").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun run(vararg arguments: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf(*arguments), PrintStream(output), PrintStream(error))
        return Result(code, output.toString(), error.toString())
    }

    private data class Result(val code: Int, val output: String, val error: String)

    private val benchXml = """
        <project source="5.1.0dev" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <main name="main"/>
          <circuit name="main"/>
          <circuit name="logisim_test_verif">
            <comp lib="0" loc="(100,100)" name="Clock"/>
            <comp lib="0" loc="(200,100)" name="Pin">
              <a name="type" val="output"/>
              <a name="facing" val="west"/>
              <a name="label" val="test_bench_done_o"/>
            </comp>
            <wire from="(100,100)" to="(200,100)"/>
            <comp lib="0" loc="(100,200)" name="Constant"><a name="value" val="0x1"/></comp>
            <comp lib="0" loc="(200,200)" name="Pin">
              <a name="type" val="output"/>
              <a name="facing" val="west"/>
              <a name="label" val="test_bench_ok_o"/>
            </comp>
            <wire from="(100,200)" to="(200,200)"/>
          </circuit>
        </project>
    """.trimIndent()
}
