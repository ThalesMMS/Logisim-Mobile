package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessLibrarySubstitutionsTest {
    @Test
    fun substitutionChangesTheObservedOutputAndLeavesAllFilesIntact() = withDirectory { directory ->
        val project = directory.project()
        val library = directory.resolve("lib/source.circ").apply {
            parentFile.mkdirs()
            writeText(supplyLibrary("0"))
        }
        val alternate = directory.resolve("alternate library.circ").apply { writeText(supplyLibrary("1")) }
        val files = listOf(project, library, alternate)
        val before = files.map { it.readBytes() }

        val baseline = run("truth-table", project.path, "--sub", "lib/source.circ", library.path)
        val substituted = run("truth-table", project.path, "-sub", "lib/source.circ", alternate.path)
        assertEquals(0, baseline.code, baseline.error)
        assertEquals(0, substituted.code, substituted.error)
        assertEquals("Y\n0\n", baseline.output)
        assertEquals("Y\n1\n", substituted.output)
        files.zip(before).forEach { (file, bytes) -> assertContentEquals(bytes, file.readBytes()) }
    }

    @Test
    fun randomDelayAppliesAndReportsNoiseAfterLibrarySubstitution() = withDirectory { directory ->
        val project = directory.project()
        val original = project.readText()
        val library = directory.resolve("alternate.circ").apply { writeText(supplyLibrary("1")) }

        fun runWithRandomDelay(simrand: Int): Result {
            val options = """<options><a name="simrand" val="$simrand"/></options>"""
            project.writeText(original.replace("<main", "$options<main"))
            return run("temporal", project.path, "--ticks", "0", "--sub", "lib/source.circ", library.path)
        }

        val normal = runWithRandomDelay(0)
        val random = runWithRandomDelay(10)
        assertEquals(0, normal.code, normal.error)
        assertEquals(0, random.code, random.error)
        val csv = normal.output.trim().lines()
        assertEquals("tick,propagation_time,Y", csv.first())
        assertEquals(listOf("1"), csv.drop(1).map { it.substringAfterLast(',') })
        // Constant: 16 plus one noise unit. The subcircuit output crossing adds 16 without noise, as in Evolution.
        assertEquals("tick,propagation_time,Y\n0,33,1\n", random.output)
        assertEquals("status=tick-limit ticks=0\n", normal.error)
        assertEquals(
            "warning=Random propagation delays are enabled (simrand=10).\n" +
                normal.error,
            random.error,
        )
    }

    @Test
    fun nestedDependencyResolvesRelativeToTheReplacementButRequiresItsOwnMapping() = withDirectory { directory ->
        val project = directory.project()
        val wrapper = directory.resolve("replacement/wrapper.circ").apply {
            parentFile.mkdirs()
            writeText(dependentCircuit("Supply", "Helper", "helper.circ"))
        }
        val helper = directory.resolve("replacement/helper.circ").apply { writeText(supplyLibrary("1", "Helper")) }
        val args = arrayOf("temporal", project.path, "--ticks", "0", "--sub", "lib/source.circ", wrapper.path)

        val unauthorized = run(*args)
        assertEquals(2, unauthorized.code, unauthorized.error)
        assertTrue("helper.circ" in unauthorized.error, unauthorized.error)
        assertEquals("", unauthorized.output)

        val authorized = run(*args, "--sub", "replacement/helper.circ", helper.path)
        assertEquals(0, authorized.code, authorized.error)
        assertEquals("1", authorized.output.trim().lines().last().substringAfterLast(','))
    }

    @Test
    fun originalStatisticsExpandLocalSubcircuitsButCountExternalCircuitsAsComponents() = withDirectory { directory ->
        val project = directory.project().apply {
            writeText(readText().replace("</circuit>", """
                <comp name="Supply" loc="(100,200)"/>
                <comp name="Supply" loc="(100,300)"/>
              </circuit>
              <circuit name="Supply">
                <comp lib="0" name="Constant" loc="(100,100)"/>
              </circuit>
            """.trimIndent()))
        }
        val library = directory.resolve("alternate.circ").apply { writeText(supplyLibrary("0")) }

        val unauthorized = run("stats", project.path)
        assertEquals(2, unauthorized.code, unauthorized.error)
        assertEquals("", unauthorized.output)

        val authorized = run("stats", project.path, "--sub", "lib/source.circ", library.path)
        assertEquals(0, authorized.code, authorized.error)
        assertEquals(listOf(
            "kind,library,component,local,unique,recursive",
            "subcircuit,,Supply,2,2,2",
            "component,#Wiring,Constant,0,1,2",
            "component,#Wiring,Pin,1,1,1",
            "component,alternate,Supply,1,1,1",
            "total,,without-subcircuits,2,3,4",
            "total,,with-subcircuits,4,5,6",
        ), authorized.output.lines().filter(String::isNotEmpty))
    }

    @Test
    fun rejectsIncompleteDuplicateAndUnusedMappingsBeforeOutput() = withDirectory { directory ->
        val project = directory.project()
        val alternate = directory.resolve("alternate.circ").apply { writeText(supplyLibrary("1")) }
        val args = arrayOf("truth-table", project.path, "--sub", "lib/source.circ", alternate.path)

        val incomplete = run("truth-table", project.path, "--sub", "lib/source.circ")
        val duplicate = run(*args, "--sub", "lib/../lib/source.circ", alternate.path)
        val unused = run(*args, "--sub", "typo.circ", directory.resolve("unread.circ").path)
        for ((result, reason) in listOf(incomplete to "Missing NEW", duplicate to "Repeated library substitution",
            unused to "No library reference matched")) {
            assertEquals(2, result.code, result.error)
            assertTrue(reason in result.error, result.error)
            assertEquals("", result.output)
        }
    }

    private fun File.project(): File = resolve("main.circ").apply {
        writeText(dependentCircuit("main", "Supply", "lib/./source.circ"))
    }

    private fun dependentCircuit(name: String, dependency: String, reference: String) = """
        <project source="2.7.1" version="1.0">
          <lib name="0" desc="#Wiring"/>
          <lib name="1" desc="file#$reference"/>
          <main name="$name"/>
          <circuit name="$name">
            <comp lib="1" name="$dependency" loc="(100,100)"/>
            <wire from="(100,100)" to="(200,100)"/>
            <comp lib="0" name="Pin" loc="(200,100)">
              <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/>
            </comp>
          </circuit>
        </project>
    """.trimIndent()

    private fun supplyLibrary(value: String, name: String = "Supply") = """
        <project source="2.7.1" version="1.0">
          <lib name="0" desc="#Wiring"/>
          <main name="$name"/>
          <circuit name="$name">
            <comp lib="0" name="Constant" loc="(100,100)"><a name="value" val="0x$value"/></comp>
            <wire from="(100,100)" to="(200,100)"/>
            <comp lib="0" name="Pin" loc="(200,100)">
              <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/>
            </comp>
          </circuit>
        </project>
    """.trimIndent()

    private fun run(vararg arguments: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf(*arguments), PrintStream(output), PrintStream(error))
        return Result(code, output.toString(), error.toString())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("logisim-headless-sub").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Result(val code: Int, val output: String, val error: String)
}
