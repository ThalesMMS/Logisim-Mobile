package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeadlessCircuitCliTest {
    @Test
    fun evolutionTtyFormatsEveryCombinationUsingPositionAndBusWidths() = withDirectory { directory ->
        val circuit = File(directory, "evolution-table.circ").apply { writeText(evolutionTableXml) }
        for ((format, expected) in mapOf(
            "table,csv,hex" to "A,Y,x[5],Bus[7]\n0,0,1a,UU\n1,1,1a,UU\n",
            "table,csv,tabs,hex,binary" to "A\tY\tx[5]\tBus[7]\n0\t0\t1 1010\tUUU UUUU\n1\t1\t1 1010\tUUU UUUU\n",
            "table" to "A Y  x[5] Bus[7]\n0 0 11010   0xUU\n1 1 11010   0xUU\n",
        )) {
            val actual = run("-tty", format, circuit.path, "--tty-evolution")
            assertEquals(Result(0, expected, ""), actual)
        }

        val legacy = run(circuit.path, "-tty", "table", "--ticks", "0")
        assertEquals(Result(0, "0\t1 1010\txxx xxxx\n", ""), legacy)
        assertEquals(2, run(circuit.path, "-tty", "table,csv,hex").code)
    }

    @Test
    fun evolutionTtyWithHaltKeepsTheTemporalTable() = withDirectory { directory ->
        val circuit = File(directory, "halt.circ").apply { writeText(clockXml.replace("clk", "halt")) }
        val expected = run(circuit.path, "-tty", "table,halt", "--ticks", "4")
        assertEquals(0, expected.code, expected.error)
        val evolution = run(circuit.path, "--tty-evolution", "-tty", "table,halt,csv,hex", "--ticks", "4")
        // Evolution logs the halt reason on stderr.
        assertEquals(expected.copy(output = expected.output.substringBeforeLast("halted due to "),
            error = "halted due to " + expected.output.substringAfterLast("halted due to ")), evolution)
        // Evolution spells the option -t or --tty.
        assertEquals(evolution, run(circuit.path, "-t", "table,halt,csv,hex", "--ticks", "4"))
        assertEquals(evolution, run("--tty", "table,halt", circuit.path, "--ticks", "4"))
    }

    @Test
    fun evolutionSpellingsSelectTheEvolutionProfile() = withDirectory { directory ->
        val circuit = File(directory, "inverter.circ").apply { writeText(evolutionTableXml) }
        // -t table without a halt pin is Evolution's input table, as with --tty-evolution.
        assertEquals(run(circuit.path, "--tty-evolution", "-tty", "table"), run("-t", "table", circuit.path))
        assertEquals(run(circuit.path, "--tty-evolution", "-tty", "table,csv"), run("--tty", "table,csv", circuit.path))
        // -t stats uses Evolution's captions; the literal -tty keeps 2.7.1's.
        val stats = run("-t", "stats", circuit.path)
        assertEquals(0, stats.code, stats.error)
        assertEquals(listOf("TOTAL (without project\u2019s sub circuits)", "TOTAL (with sub circuits)"),
            stats.lines.takeLast(2).map { it.substringAfterLast('\t') })
        assertEquals("TOTAL (with subcircuits)", run("-tty", "stats", circuit.path).lines.last().substringAfterLast('\t'))
        assertEquals(2, run("-t", "csv", circuit.path).code)
        assertEquals(run("--circuit", "main", "-tty", "table", circuit.path, "--ticks", "0"),
            run("--toplevel-circuit", "main", "-tty", "table", circuit.path, "--ticks", "0"))
    }

    @Test
    fun originalClockWithoutHaltKeepsTickingToTheRequestedLimit() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply { writeText(clockXml) }
        val result = run("temporal", circuit.path, "--ticks", "4")
        assertEquals(0, result.code, result.error)
        assertEquals("tick,propagation_time,clk", result.lines.first())
        assertEquals(listOf("0", "1", "0", "1", "0"), result.rows.map { it.last() })
        assertEquals(listOf("0", "1", "2", "3", "4"), result.rows.map { it.first() })
        assertEquals("status=tick-limit ticks=4\n", result.error)
    }

    @Test
    fun importedRandomDelaySettingChangesCsvTimesAndReportsNoise() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ")
        fun runClock(simrand: Int): Result {
            val options = """<options><a name="simrand" val="$simrand"/></options>"""
            circuit.writeText(clockXml.replace("<main", "$options<main"))
            return run("temporal", circuit.path, "--ticks", "4")
        }
        val normal = runClock(0)
        val imported = runClock(32)
        assertEquals(0, normal.code, normal.error)
        assertEquals(0, imported.code, imported.error)
        assertEquals(normal.rows.map { it.first() }, imported.rows.map { it.first() })
        assertEquals(listOf("0", "1", "0", "1", "0"), imported.rows.map { it.last() })
        val noisyTimes = imported.rows.map { it[1].toLong() }
        assertEquals(33L, noisyTimes.first())
        assertTrue(noisyTimes.zipWithNext().all { (before, after) -> after - before in 32L..33L })
        assertEquals("status=tick-limit ticks=4\n", normal.error)
        assertEquals("warning=Random propagation delays are enabled (simrand=32).\n" + normal.error, imported.error)
    }

    @Test
    fun haltStopsOnItsFirstSettledHighValue() = withDirectory { directory ->
        val circuit = File(directory, "halt.circ").apply { writeText(clockXml.replace("val=\"clk\"", "val=\"halt\"")) }
        val result = run("temporal", circuit.path, "--ticks", "20")
        assertEquals(0, result.code, result.error)
        assertEquals(listOf("0", "1"), result.rows.map { it.last() })
        assertEquals("status=halt ticks=1\n", result.error)
    }

    @Test
    fun originalTtyCombinesReportsWithoutChangingTemporalCsv() = withDirectory { directory ->
        val library = File(directory, "lib/source.circ").apply {
            parentFile.mkdirs()
            writeText("""
                <project source="2.7.1" version="1.0">
                  <lib name="0" desc="#Wiring"/>
                  <main name="Supply"/>
                  <circuit name="Supply">
                    <comp lib="0" name="Constant" loc="(100,100)"><a name="value" val="0x0"/></comp>
                    <wire from="(100,100)" to="(200,100)"/>
                    <comp lib="0" name="Pin" loc="(200,100)">
                      <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/>
                    </comp>
                  </circuit>
                </project>
            """.trimIndent())
        }
        val circuit = File(directory, "main-halt.circ").apply {
            writeText("""
                <project source="2.7.1" version="1.0">
                  <lib name="0" desc="#Wiring"/>
                  <lib name="1" desc="file#lib/./source.circ"/>
                  <main name="main"/>
                  <circuit name="main">
                    <comp lib="1" name="Supply" loc="(100,100)"/>
                    <wire from="(100,100)" to="(200,100)"/>
                    <comp lib="0" name="Pin" loc="(200,100)">
                      <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/>
                    </comp>
                    <comp lib="0" name="Constant" loc="(100,200)"><a name="value" val="0x1"/></comp>
                    <wire from="(100,200)" to="(200,200)"/>
                    <comp lib="0" name="Pin" loc="(200,200)">
                      <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="halt"/>
                    </comp>
                  </circuit>
                </project>
            """.trimIndent())
        }
        val result = run(circuit.path, "-tty", "table,halt,speed,stats", "-sub", "lib/source.circ", library.path)
        assertEquals(0, result.code, result.error)
        assertEquals("", result.error)
        assertEquals(listOf(
            "2\t2\tPin     \tWiring",
            "1\t1\tConstant\tWiring",
            "1\t1\tSupply  \tsource",
            "4\t4\tTOTAL (without project's subcircuits)",
            "4\t4\tTOTAL (with subcircuits)",
            "0",
            "halted due to halt pin",
        ), result.lines.dropLast(1))
        assertTrue(Regex("0 Hz \\(0 ticks in [0-9]+ milliseconds\\)").matches(result.lines.last()), result.output)
        val native = run("temporal", circuit.path, "--sub", "lib/source.circ", library.path)
        assertEquals(0, native.code, native.error)
        // The child constant settles at time 1 and crosses the subcircuit boundary one step later.
        assertEquals(listOf("tick,propagation_time,Y,halt", "0,2,0,1"), native.lines)
        assertEquals("status=halt ticks=0\n", native.error)
    }

    @Test
    fun originalTtyTableUsesPinPositionAndLegacyBitFormat() = withDirectory { directory ->
        val circuit = File(directory, "ordered.circ").apply {
            writeText("""
                <project source="2.7.1" version="1.0">
                  <lib name="0" desc="#Wiring"/>
                  <main name="main"/>
                  <circuit name="main">
                    <comp lib="0" name="Pin" loc="(200,200)">
                      <a name="output" val="true"/><a name="width" val="5"/>
                    </comp>
                    <comp lib="0" name="Constant" loc="(100,100)"><a name="value" val="0x1"/></comp>
                    <wire from="(100,100)" to="(200,100)"/>
                    <comp lib="0" name="Pin" loc="(200,100)"><a name="output" val="true"/></comp>
                    <comp lib="0" name="Clock" loc="(100,300)"/>
                    <wire from="(100,300)" to="(200,300)"/>
                    <comp lib="0" name="Pin" loc="(200,300)">
                      <a name="output" val="true"/><a name="label" val="halt"/>
                    </comp>
                  </circuit>
                </project>
            """.trimIndent())
        }
        val result = run("-tty", "table", circuit.path)
        assertEquals(0, result.code, result.error)
        assertEquals("1\tx xxxx\n", result.output)
        assertEquals("", result.error)
    }

    @Test
    fun haltWaitsForDelayedOutputAtTheEndOfTheTick() = withDirectory { directory ->
        val flipFlop = ComponentCatalog.registry.create(ComponentKind.D_FLIP_FLOP, "storage", GridPoint(200, 100))
        val layout = ComponentCatalog.registry.layout(flipFlop)
        val clock = layout.inputs.single { it.name == "clock" }.position
        val drives = layout.inputs.map { terminal ->
            if (terminal.name == "clock") CircuitComponent("clock", ComponentKind.CLOCK, terminal.position)
            else CircuitComponent("drive-${terminal.name}", ComponentKind.CONSTANT, terminal.position,
                attributes = mapOf("value" to if (terminal.name == "d") "0x1" else "0x0"))
        }
        val document = CircuitDocument(components = drives + flipFlop + listOf(
            CircuitComponent("out", ComponentKind.OUTPUT, layout.outputs.single { it.name == "q" }.position, label = "Q"),
            CircuitComponent("halt", ComponentKind.OUTPUT, clock, label = "halt"),
        ))
        val circuit = writeProject(directory, document)
        val result = run("temporal", circuit.path, "--ticks", "20")
        assertEquals(0, result.code, result.error)
        assertEquals(listOf(listOf("0", "0"), listOf("0", "1"), listOf("1", "1")),
            result.rows.map { it.takeLast(2) })
        assertEquals(listOf("0", "1", "1"), result.rows.map { it.first() })
        assertEquals("status=halt ticks=1\n", result.error)
    }

    @Test
    fun unchangedOutputDoesNotProduceOneRowPerTick() = withDirectory { directory ->
        val circuit = writeProject(directory, CircuitDocument(components = listOf(
            CircuitComponent("constant", ComponentKind.CONSTANT, GridPoint(100, 100), attributes = mapOf("value" to "0x1")),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100), label = "stable"),
        )))
        val result = run("temporal", circuit.path, "--ticks", "10")
        assertEquals(0, result.code, result.error)
        assertEquals(1, result.rows.size)
        assertEquals("1", result.rows.single().last())
        assertEquals("status=tick-limit ticks=10\n", result.error)
    }

    @Test
    fun cancellationRetainsTheAlreadyStreamedRows() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply { writeText(clockXml) }
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf("temporal", circuit.path, "--ticks", "100"),
            PrintStream(output), PrintStream(error), cancelled = { output.toString().count { it == '\n' } >= 4 })
        assertEquals(130, code)
        assertEquals(listOf("0", "1", "0"), output.toString().trim().lines().drop(1).map { it.split(',').last() })
        assertEquals("status=cancelled\n", error.toString())
    }

    @Test
    fun existingCancellationFileStopsBeforeOutput() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply { writeText(clockXml) }
        val cancel = File(directory, "cancel").apply { writeText("") }
        val result = run("temporal", circuit.path, "--cancel-file", cancel.path)
        assertEquals(130, result.code)
        assertEquals("", result.output)
    }

    @Test
    fun memoryLoadWithoutRamIsAnErrorBeforeOutput() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply { writeText(clockXml) }
        val image = File(directory, "memory.hex").apply { writeText("v2.0 raw\n12\n") }
        val result = run("temporal", circuit.path, "--load", image.path)
        assertEquals(2, result.code)
        assertEquals("", result.output)
        assertTrue(result.error.contains("--load requires a RAM"), result.error)
    }

    @Test
    fun memoryLoadChangesTheObservedRamOutput() = withDirectory { directory ->
        val circuit = writeProject(directory, memoryCircuit())
        val image = File(directory, "memory.hex").apply { writeText("v2.0 raw\n12\n") }
        val empty = run("temporal", circuit.path, "--ticks", "0")
        val loaded = run("temporal", circuit.path, "--ticks", "0", "--load", image.path)
        assertEquals(0, empty.code, empty.error)
        assertEquals(0, loaded.code, loaded.error)
        assertEquals("00000000", empty.rows.single().last())
        assertEquals("00010010", loaded.rows.single().last())
    }

    @Test
    fun memoryLoadReachesBothSubcircuitInstances() = withDirectory { directory ->
        val child = memoryCircuit().copy(name = "storage", id = "storage")
        val first = CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(100, 100),
            circuitName = child.name, explicitPorts = listOf(ComponentPort("out", GridPoint(0, 0), PortDirection.OUTPUT, 8)))
        val second = first.copy(id = "second", position = GridPoint(100, 200))
        val main = CircuitDocument(name = "main", components = listOf(first, second,
            CircuitComponent("a", ComponentKind.OUTPUT, first.position, label = "a", attributes = mapOf("width" to "8")),
            CircuitComponent("b", ComponentKind.OUTPUT, second.position, label = "b", attributes = mapOf("width" to "8"))))
        val circuit = File(directory, "nested.circ").apply {
            writeText(CircCodec.writeProject(CircuitProject(listOf(main, child), main.name)))
        }
        val image = File(directory, "memory.hex").apply { writeText("v2.0 raw\n12\n") }
        val dump = File(directory, "nested-dump.hex")
        val result = run("temporal", circuit.path, "--ticks", "0", "--load", image.path, "--save", dump.path)
        assertEquals(0, result.code, result.error)
        assertEquals(listOf("00010010", "00010010"), result.rows.single().takeLast(2))
        assertEquals("v3.0 hex words plain\n12 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n", dump.readText())
    }

    @Test
    fun labeledLoadsAndDumpUseFinalRamContentsAtHalt() = withDirectory { directory ->
        val ram = memoryCircuit("working", GridPoint(100, 100), writeOnFirstTick = true)
        val rom = memoryCircuit("firmware", GridPoint(100, 260), ComponentKind.ROM)
        val haltPosition = GridPoint(300, 100)
        val circuit = writeProject(directory, CircuitDocument(components = ram.components + rom.components + listOf(
            CircuitComponent("halt-clock", ComponentKind.CLOCK, haltPosition),
            CircuitComponent("halt", ComponentKind.OUTPUT, haltPosition, label = "halt"),
        )))
        val projectXml = circuit.readText()
        val ramImage = File(directory, "ram.hex").apply { writeText("v2.0 raw\n12 56\n") }
        val romImage = File(directory, "rom.hex").apply { writeText("v2.0 raw\n34\n") }
        val dump = File(directory, "dump.hex")
        val options = arrayOf("--load", ramImage.path, "working", "--load", romImage.path, "firmware", "--save", dump.path)
        val native = run("temporal", circuit.path, *options)
        assertEquals(0, native.code, native.error)
        assertEquals(listOf("00010010", "00110100", "0"), native.rows.first().takeLast(3))
        assertEquals(listOf("10100101", "00110100", "1"), native.rows.last().takeLast(3))
        assertEquals("status=halt ticks=1\n", native.error)
        assertEquals("v3.0 hex words plain\na5 56 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n", dump.readText())

        dump.delete()
        val legacy = run(circuit.path, "-tty", "table,halt", "--ticks", "2", *options)
        assertEquals(0, legacy.code, legacy.error)
        assertEquals(listOf("0001 0010\t0011 0100", "1010 0101\t0011 0100", "halted due to halt pin"), legacy.lines)
        val legacyDump = dump.readText()
        assertEquals("v3.0 hex words plain\na5 56 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n", legacyDump)

        dump.delete()
        val evolution = run(circuit.path, "--tty-evolution", "-tty", "table,halt,csv,hex", "--ticks", "2", *options)
        assertEquals(legacy.copy(output = legacy.output.removeSuffix("halted due to halt pin\n"),
            error = "halted due to halt pin\n"), evolution)
        dump.delete()
        // Evolution's -l spelling of --load.
        val shortLoad = run(circuit.path, "-t", "table,halt", "--ticks", "2", "-l", ramImage.path, "working",
            "-l", romImage.path, "firmware", "--save", dump.path)
        assertEquals(evolution, shortLoad)
        assertEquals(legacyDump, dump.readText())
        assertEquals(projectXml, circuit.readText())
    }

    @Test
    fun unlabeledLoadRemainsRamOnlyAndLabeledLoadOverridesIt() = withDirectory { directory ->
        val first = memoryCircuit("first", GridPoint(100, 100))
        val second = memoryCircuit("second", GridPoint(100, 260))
        val rom = memoryCircuit("firmware", GridPoint(100, 420), ComponentKind.ROM)
        val circuit = writeProject(directory, CircuitDocument(components = first.components + second.components + rom.components))
        val common = File(directory, "common.hex").apply { writeText("v2.0 raw\n12\n") }
        val specific = File(directory, "specific.hex").apply { writeText("v2.0 raw\n34\n") }
        val dump = File(directory, "dump.hex")
        val loaded = run("temporal", circuit.path, "--ticks", "0", "--load", specific.path, "second",
            "--load", common.path, "--save", dump.path)
        assertEquals(0, loaded.code, loaded.error)
        assertEquals(listOf("00010010", "00110100", "01110111"), loaded.rows.single().takeLast(3))
        assertEquals("v3.0 hex words plain\n34 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00\n", dump.readText())

        val legacy = run("-load", common.path, circuit.path, "-tty", "table", "--ticks", "0")
        assertEquals(0, legacy.code, legacy.error)
        assertEquals(listOf("0001 0010\t0001 0010\t0111 0111"), legacy.lines)

        // Evolution's -t profile also loads the unlabeled image into ROMs without a labeled override.
        val haltPosition = GridPoint(600, 600)
        val halted = writeProject(directory, CircuitDocument(components = first.components + second.components +
            rom.components + CircuitComponent("halt-one", ComponentKind.CONSTANT, haltPosition, attributes = mapOf("value" to "0x1")) +
            CircuitComponent("halt", ComponentKind.OUTPUT, haltPosition, label = "halt")))
        val evolution = run(halted.path, "-t", "table", "-l", common.path)
        assertEquals(0, evolution.code, evolution.error)
        assertEquals(listOf("0001 0010\t0001 0010\t0001 0010"), evolution.lines)
    }

    @Test
    fun labeledLoadReportsMissingAndAmbiguousTargetsBeforeOutput() = withDirectory { directory ->
        val first = memoryCircuit("shared", GridPoint(100, 100))
        val second = memoryCircuit("shared", GridPoint(100, 260))
        val circuit = writeProject(directory, CircuitDocument(components = first.components + second.components.map {
            it.copy(id = "second-${it.id}", label = if (it.kind == ComponentKind.OUTPUT) "second-data" else it.label)
        }))
        val image = File(directory, "memory.hex").apply { writeText("v2.0 raw\n12\n") }
        val dump = File(directory, "dump.hex")
        for ((label, diagnostic) in listOf("missing" to "No RAM or ROM labeled 'missing'", "shared" to "Ambiguous memory label 'shared'")) {
            val result = run("temporal", circuit.path, "--ticks", "0", "--load", image.path, label, "--save", dump.path)
            assertEquals(2, result.code)
            assertEquals("", result.output)
            assertTrue(result.error.contains(diagnostic), result.error)
            assertTrue(!dump.exists())
        }
    }

    @Test
    fun memoryDumpRejectsNoRamAndLeavesExistingFileOnCancellation() = withDirectory { directory ->
        val clock = File(directory, "clock.circ").apply { writeText(clockXml) }
        val dump = File(directory, "dump.hex").apply { writeText("keep") }
        val noRam = run("temporal", clock.path, "--ticks", "0", "--save", dump.path)
        assertEquals(2, noRam.code)
        assertEquals("", noRam.output)
        assertTrue(noRam.error.contains("--save requires a RAM"), noRam.error)

        val circuit = writeProject(directory, memoryCircuit())
        val output = ByteArrayOutputStream()
        val result = HeadlessCircuitCli.run(arrayOf("temporal", circuit.path, "--save", dump.path),
            PrintStream(output), PrintStream(ByteArrayOutputStream()), cancelled = { output.size() != 0 })
        assertEquals(130, result)
        assertEquals("keep", dump.readText())
    }

    @Test
    fun csvEscapesOutputLabelsAndRejectsReservedNames() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply {
            writeText(clockXml.replace("val=\"clk\"", "val=\"clock,&quot;bus&quot;\""))
        }
        val escaped = run("temporal", circuit.path, "--ticks", "0")
        assertEquals(0, escaped.code, escaped.error)
        assertEquals("tick,propagation_time,\"clock,\"\"bus\"\"\"", escaped.lines.first())
        circuit.writeText(clockXml.replace("val=\"clk\"", "val=\"tick\""))
        val reserved = run("temporal", circuit.path)
        assertEquals(2, reserved.code)
        assertEquals("", reserved.output)
    }

    @Test
    fun truthTableIsExplicitAndRejectsAClockCircuit() = withDirectory { directory ->
        val clock = File(directory, "clock.circ").apply { writeText(clockXml) }
        val temporal = run("truth-table", clock.path)
        assertEquals(2, temporal.code)
        assertEquals("", temporal.output)
        val circuit = writeProject(directory, CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 100), label = "a"),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100), label = "y"),
        )))
        val table = run("truth-table", circuit.path)
        assertEquals(0, table.code, table.error)
        assertEquals(listOf("a,y", "0,0", "1,1"), table.lines)
        assertEquals("status=truth-table rows=2\n", table.error)
        assertEquals(2, run("truth-table", circuit.path, "--ticks", "4").code)
    }

    @Test
    fun truthTableReadsOriginalCircuitLabelsInDeclaredXmlEncoding() = withDirectory { directory ->
        val xml = originalFixture("inverter").readText().replace("val=\"Y\"", "val=\"Saída\"")
        val cases = listOf(
            "UTF-8" to xml.encodeToByteArray(),
            "UTF-8 with BOM" to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + xml.encodeToByteArray(),
            "UTF-16 BE with BOM" to xml.replace("UTF-8", "UTF-16").toByteArray(Charsets.UTF_16),
            "UTF-16 LE with BOM" to byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                xml.replace("UTF-8", "UTF-16").toByteArray(Charsets.UTF_16LE),
            "ISO-8859-1" to xml.replace("UTF-8", "ISO-8859-1").toByteArray(Charsets.ISO_8859_1),
        )
        for ((encoding, bytes) in cases) {
            val circuit = File(directory, "encoded.circ").apply { writeBytes(bytes) }
            val result = run("truth-table", circuit.path)
            assertEquals(0, result.code, "$encoding: ${result.error}")
            assertEquals(listOf("A,Saída", "0,1", "1,0"), result.lines, encoding)
        }
    }

    @Test
    fun windows1252CircuitReopensAfterSavingAsUtf8() = withDirectory { directory ->
        val xml = originalFixture("inverter").readText()
            .replace("UTF-8", "windows-1252").replace("val=\"Y\"", "val=\"Saída €\"")
        val circuit = File(directory, "windows.circ").apply { writeBytes(xml.toByteArray(charset("windows-1252"))) }
        val imported = run("truth-table", circuit.path)
        assertEquals(0, imported.code, imported.error)
        assertEquals(listOf("A,Saída€", "0,1", "1,0"), imported.lines)

        val project = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(readHeadlessCircuitFile(circuit.toPath())),
        ).project
        assertEquals("Saída €", project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }.label)
        val utf8 = File(directory, "utf8.circ").apply { writeText(CircCodec.writeProject(project)) }
        assertTrue(utf8.readText().startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        val reopenedProject = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(readHeadlessCircuitFile(utf8.toPath())),
        ).project
        assertEquals("Saída €", reopenedProject.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }.label)
        val reopened = run("truth-table", utf8.path)
        assertEquals(0, reopened.code, reopened.error)
        assertEquals(imported.lines, reopened.lines)
    }

    @Test
    fun truthTableReportsMalformedOrUnsupportedXmlEncodingBeforePrintingRows() = withDirectory { directory ->
        val xml = originalFixture("inverter").readText()
        val malformedUtf8 = xml.replace("val=\"Y\"", "val=\"Saída\"").toByteArray(Charsets.ISO_8859_1)
        val utf16 = xml.replace("UTF-8", "UTF-16").toByteArray(Charsets.UTF_16)
        val cases = listOf(
            malformedUtf8,
            utf16.copyOf(utf16.size - 1),
            xml.toByteArray(Charsets.UTF_16),
            xml.replace("UTF-8", "unsupported-charset").encodeToByteArray(),
            xml.replace("UTF-8", "windows-1252").replace("val=\"Y\"", "val=\"\u0081\"")
                .toByteArray(Charsets.ISO_8859_1),
        )
        for (bytes in cases) {
            val circuit = File(directory, "invalid.circ").apply { writeBytes(bytes) }
            val result = run("truth-table", circuit.path)
            assertEquals(2, result.code, result.error)
            assertEquals("", result.output)
            assertTrue(result.error.contains("XML", ignoreCase = true), result.error)
            assertTrue(result.error.contains("encoding", ignoreCase = true) ||
                result.error.contains("codificação", ignoreCase = true), result.error)
        }
    }

    @Test
    fun terminalAndUnknownOptionsHaveExplicitErrors() = withDirectory { directory ->
        val circuit = File(directory, "clock.circ").apply { writeText(clockXml) }
        val terminal = run("temporal", circuit.path, "--terminal")
        assertEquals(2, terminal.code)
        assertTrue(terminal.error.contains("--terminal requires a Keyboard or TTY"))
        assertEquals("", terminal.output)
        assertEquals(2, run("truth-table", circuit.path, "--terminal").code)
        assertEquals(2, run("stats", circuit.path, "--terminal").code)
        assertEquals(2, run("temporal", circuit.path, "--ticks", "-1").code)
        assertEquals(2, run("temporal", circuit.path, "--unknown").code)
    }

    @Test
    fun statisticsCountBothPinDirectionsAsOneComponentType() {
        val circuit = originalFixture("inverter")
        val result = run("stats", circuit.path)
        assertEquals(0, result.code, result.error)
        assertEquals(listOf(
            "kind,library,component,local,unique,recursive",
            "component,#Gates,NOT Gate,1,1,1",
            "component,#Wiring,Pin,2,2,2",
            "total,,without-subcircuits,3,3,3",
            "total,,with-subcircuits,3,3,3",
        ), result.lines)
        assertEquals("status=stats\n", result.error)
        assertEquals(2, run("stats", circuit.path, "--ticks", "1").code)
    }

    @Test
    fun statisticsCountSharedDefinitionsOnceAndRepeatedInstancesRecursively() = withDirectory { directory ->
        val circuit = File(directory, "repeated.circ").apply {
            writeText(originalFixture("hierarchy").readText()
                .replace("""<comp loc="(300,140)" name="Invert"/>""",
                    """<comp loc="(300,140)" name="Invert"/><comp loc="(300,220)" name="Invert"/>""")
                .replace("</project>", """<circuit name="unused"><comp lib="0" loc="(100,100)" name="Constant"/></circuit></project>"""))
        }
        val result = run("stats", circuit.path)
        assertEquals(0, result.code, result.error)
        assertEquals(listOf(
            "subcircuit,,Invert,2,2,2",
            "component,#Gates,NOT Gate,0,1,2",
            "component,#Wiring,Pin,2,4,6",
            "total,,without-subcircuits,2,5,8",
            "total,,with-subcircuits,4,7,10",
        ), result.lines.drop(1))
        val selected = run("stats", circuit.path, "--circuit", "Invert")
        assertEquals(0, selected.code, selected.error)
        assertEquals(listOf(
            "component,#Gates,NOT Gate,1,1,1",
            "component,#Wiring,Pin,2,2,2",
            "total,,without-subcircuits,3,3,3",
            "total,,with-subcircuits,3,3,3",
        ), selected.lines.drop(1))
    }

    @Test
    fun testVectorsReportEveryInputLineAndFailOnDivergence() = withDirectory { directory ->
        val circuit = originalFixture("inverter")
        val vectors = File(directory, "vectors.txt").apply { writeText("A Y\n0 1\n# second test\n1 0\n") }
        val passed = run("--test-vector", "LegacyInverter", vectors.path, circuit.path)
        assertEquals(0, passed.code, passed.error)
        assertEquals(listOf(
            "circuit,row,line,set,seq,result,\"A[1] expected\",\"A[1] observed\",\"Y[1] expected\",\"Y[1] observed\",differences",
            "\"LegacyInverter\",1,2,0,0,PASS,\"0\",0,\"1\",1,\"\"",
            "\"LegacyInverter\",2,4,0,0,PASS,\"1\",1,\"0\",0,\"\"",
        ), passed.lines)
        assertEquals("status=test-vector passed=2 failed=0\n", passed.error)

        vectors.writeText("A Y\n0 0\n# second test\n1 0\n")
        val failed = run(circuit.path, "-w", "LegacyInverter", vectors.path)
        assertEquals(1, failed.code, failed.error)
        assertEquals("\"LegacyInverter\",1,2,0,0,FAIL,\"0\",0,\"0\",1,\"Y: 0 != 1\"", failed.lines[1])
        assertEquals(passed.lines[2], failed.lines[2])
        assertEquals("status=test-vector passed=1 failed=1\n", failed.error)

        vectors.writeText("A Y\n0\n")
        val invalid = run("--test-vector", "LegacyInverter", vectors.path, circuit.path)
        assertEquals(2, invalid.code)
        assertTrue(invalid.error.contains("2"), invalid.error)
        assertEquals("", invalid.output)
    }

    @Test
    fun testVectorsRejectTemporalMemoryOptions() = withDirectory { directory ->
        val circuit = originalFixture("inverter")
        val vectors = File(directory, "vectors.txt").apply { writeText("A Y\n0 1\n") }
        val image = File(directory, "memory.hex").apply { writeText("v2.0 raw\n12\n") }
        val dump = File(directory, "dump.hex")
        val vectorOptions = arrayOf("--test-vector", "LegacyInverter", vectors.path, circuit.path)
        for (memoryOptions in listOf(arrayOf("--load", image.path, "storage"), arrayOf("--save", dump.path))) {
            val result = run(*vectorOptions, *memoryOptions)
            assertEquals(2, result.code)
            assertEquals("", result.output)
            assertEquals("error=--test-vector accepts only --sub and --cancel-file options.\n", result.error)
        }
        assertTrue(!dump.exists())
    }

    @Test
    fun testVectorsKeepStateAcrossSequencesAndResetForTheNextSet() = withDirectory { directory ->
        val circuit = DemoCircuits.flipFlop().copy(name = "storage")
        val project = CircuitProject(listOf(CircuitDocument("main"), circuit), "main")
        val file = File(directory, "project.circ").apply { writeText(CircCodec.writeProject(project)) }
        val vectors = File(directory, "vectors.txt").apply {
            writeText("D Clock Q <set> <seq>\n1 0 0 1 1\n0 1 1 1 2\n1 0 1 1 3\n0 1 1 1 4\n0 0 0 2 1\n")
        }
        val result = run("--test-vector", "storage", vectors.path, file.path)
        assertEquals(0, result.code, result.error)
        assertEquals(listOf("0", "1", "1", "1", "0"), result.rows.map { it[11] })
        assertEquals("status=test-vector passed=5 failed=0\n", result.error)
    }

    @Test
    fun testVectorsUseTheImportedProjectOptions() = withDirectory { directory ->
        val circuit = floatingGateCircuit()
        val vectors = File(directory, "vectors.txt").apply { writeText("Y\n1\n") }
        val file = writeProject(directory, circuit, """<options><a name="gateUndefined" val="ignore"/></options>""")
        assertEquals(0, run("--test-vector", circuit.name, vectors.path, file.path).code)

        writeProject(directory, circuit, """<options><a name="gateUndefined" val="error"/></options>""")
        val result = run("--test-vector", circuit.name, vectors.path, file.path)
        assertEquals(1, result.code, result.error)
        assertEquals(listOf("\"${circuit.name}\",1,2,0,0,FAIL,\"1\",E,\"Y: 1 != E\""), result.lines.drop(1))
    }

    @Test
    fun projectGateUndefinedPolicyAppliesToBothExecutionModes() = withDirectory { directory ->
        val document = floatingGateCircuit()
        val circuit = writeProject(directory, document, """<options><a name="gateUndefined" val="ignore"/></options>""")
        val ignored = run("temporal", circuit.path, "--ticks", "1")
        assertEquals(0, ignored.code, ignored.error)
        assertEquals("1", ignored.rows.single().last())
        val table = run("truth-table", circuit.path)
        assertEquals(0, table.code, table.error)
        assertEquals(listOf("Y", "1"), table.lines)

        writeProject(directory, document, """<options><a name="gateUndefined" val="error"/></options>""")
        val strict = run("temporal", circuit.path, "--ticks", "1")
        assertEquals(0, strict.code, strict.error)
        assertEquals("E", strict.rows.single().last())
        val invalidTable = run("truth-table", circuit.path)
        assertEquals(2, invalidTable.code)
        assertEquals("", invalidTable.output)
    }

    @Test
    fun projectPropagationLimitAppliesToBothExecutionModes() = withDirectory { directory ->
        val document = floatingGateCircuit()
        val circuit = writeProject(directory, document, """<options><a name="simlimit" val="1"/></options>""")
        assertEquals(3, run("temporal", circuit.path, "--ticks", "0").code)
        assertEquals(2, run("truth-table", circuit.path).code)

        writeProject(directory, document, """<options><a name="propagationLimit" val="500"/></options>""")
        assertEquals(0, run("temporal", circuit.path, "--ticks", "0").code)
        assertEquals(0, run("truth-table", circuit.path).code)
    }

    @Test
    fun projectMemoryStartupPolicyAppliesBeforeInitialObservation() = withDirectory { directory ->
        val flipFlop = ComponentCatalog.registry.create(ComponentKind.D_FLIP_FLOP, "storage", GridPoint(200, 100))
        val layout = ComponentCatalog.registry.layout(flipFlop)
        val drives = layout.inputs.map { terminal ->
            CircuitComponent("drive-${terminal.name}", ComponentKind.CONSTANT, terminal.position,
                attributes = mapOf("value" to "0x0"))
        }
        val output = CircuitComponent("out", ComponentKind.OUTPUT, layout.outputs.first().position, label = "Q")
        val document = CircuitDocument(components = drives + flipFlop + output)
        val circuit = writeProject(directory, document, """<options><a name="memoryStartupUnknown" val="false"/></options>""")
        val cleared = run("temporal", circuit.path, "--ticks", "0")
        assertEquals(0, cleared.code, cleared.error)
        assertEquals("0", cleared.rows.single().last())

        writeProject(directory, document, """<options><a name="memoryStartupUnknown" val="true"/></options>""")
        val unknown = run("temporal", circuit.path, "--ticks", "0")
        assertEquals(0, unknown.code, unknown.error)
        assertEquals("?", unknown.rows.single().last())
    }

    private fun writeProject(directory: File, circuit: CircuitDocument, optionsXml: String? = null): File = File(directory, "project.circ").apply {
        writeText(CircCodec.writeProject(CircuitProject(listOf(circuit), circuit.name, extraXml = listOfNotNull(optionsXml))))
    }

    private fun originalFixture(name: String): File = sequenceOf(
        File("examples/compatibility/original-2.7.1/$name.circ"),
        File("../examples/compatibility/original-2.7.1/$name.circ"),
    ).first { it.isFile }

    private fun floatingGateCircuit(): CircuitDocument {
        val gate = ComponentCatalog.registry.create(ComponentKind.AND, "gate", GridPoint(200, 100), mapOf("inputs" to "2"))
        val input = ComponentCatalog.registry.layout(gate).inputs.first().position
        return CircuitDocument(components = listOf(
            gate,
            CircuitComponent("one", ComponentKind.CONSTANT, input, attributes = mapOf("value" to "0x1")),
            CircuitComponent("out", ComponentKind.OUTPUT, gate.position, label = "Y"),
        ))
    }

    private fun memoryCircuit(
        label: String = "memory",
        position: GridPoint = GridPoint(100, 100),
        kind: ComponentKind = ComponentKind.RAM,
        writeOnFirstTick: Boolean = false,
    ): CircuitDocument {
        val attributes = mapOf("addrWidth" to "4", "dataWidth" to "8", "asyncread" to "true") +
            if (kind == ComponentKind.ROM) mapOf("contents" to "addr/data: 4 8\n77") else emptyMap()
        val memory = ComponentCatalog.registry.create(kind, label, position, attributes, label = label)
        val terminals = ComponentCatalog.registry.layout(memory)
        val drives = terminals.inputs.map { terminal ->
            val id = "$label-${terminal.name}"
            if (writeOnFirstTick && terminal.name == MemoryLayouts.clockName(0)) {
                CircuitComponent(id, ComponentKind.CLOCK, terminal.position)
            } else {
                val value = when {
                    terminal.name.startsWith("outputEnable") -> "0x1"
                    writeOnFirstTick && terminal.name.startsWith("writeEnable") -> "0x1"
                    writeOnFirstTick && terminal.name.startsWith("dataIn") -> "0xa5"
                    else -> "0x0"
                }
                CircuitComponent(id, ComponentKind.CONSTANT, terminal.position,
                    attributes = mapOf("width" to terminal.width.toString(), "value" to value))
            }
        }
        val output = CircuitComponent("$label-out", ComponentKind.OUTPUT, terminals.outputs.first().position,
            label = "data-$label", attributes = mapOf("width" to "8"))
        return CircuitDocument(components = drives + memory + output)
    }

    @Test
    fun oscillatingRingStopsAtEvolutionSimulationLimitStep() = withDirectory { directory ->
        // Rows printed by Evolution d58a61e0 (scripts/oracle/project-options, #354) before it reports the
        // oscillation: Propagator.propagate steps simlimit - 1 times, so the frozen loop value follows that count.
        for ((limit, rows) in listOf(3 to "E 0", 4 to "E 0", 5 to "E 0 1", 6 to "E 0", 1000 to "E 0")) {
            val circuit = File(directory, "ring-$limit.circ").apply { writeText(ringWithHaltXml(limit)) }
            val result = run(circuit.path, "--tty-evolution", "-tty", "table,halt")
            assertEquals(1, result.code, "simlimit=$limit: ${result.error}")
            // In the Evolution profile the halt reason goes to stderr, like Evolution's logger.
            assertEquals(rows.split(' '), result.output.trim().lines(), "simlimit=$limit")
            assertEquals("halted due to detected oscillation\n", result.error, "simlimit=$limit")
        }
    }

    @Test
    fun bufferChainSettlesFromTheSameSimulationLimitAsEvolution() = withDirectory { directory ->
        // Twelve buffers: Evolution d58a61e0 leaves y at E up to simlimit 13 and settles from 14 (#354).
        for ((limit, expected) in listOf(2 to "E", 12 to "E", 13 to "E", 14 to "", 15 to "")) {
            val circuit = File(directory, "chain-$limit.circ").apply { writeText(bufferChainXml(12, limit)) }
            val result = run(circuit.path, "--tty-evolution", "-tty", "table")
            assertEquals(0, result.code, "simlimit=$limit: ${result.error}")
            val rows = if (expected == "E") listOf("a y", "0 E", "1 E") else listOf("a y", "0 0", "1 1")
            assertEquals(rows, result.output.trim().lines(), "simlimit=$limit")
        }
    }

    private fun evolutionProject(limit: Int, circuit: String) = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <options>
    <a name="gateUndefined" val="ignore"/>
    <a name="simlimit" val="$limit"/>
    <a name="simrand" val="0"/>
  </options>
  <circuit name="main">
$circuit  </circuit>
</project>
"""

    private fun outputPin(x: Int, y: Int, label: String) = """    <comp lib="0" loc="($x,$y)" name="Pin">
      <a name="facing" val="west"/>
      <a name="output" val="true"/>
      <a name="label" val="$label"/>
    </comp>
"""

    private fun xmlWire(x1: Int, y1: Int, x2: Int, y2: Int) = "    <wire from=\"($x1,$y1)\" to=\"($x2,$y2)\"/>\n"

    /** Same circuit as scripts/oracle/project-options/generate.py ring_with_halt(). */
    private fun ringWithHaltXml(limit: Int) = evolutionProject(limit,
        "    <comp lib=\"0\" loc=\"(200,80)\" name=\"Clock\"/>\n" + xmlWire(200, 80, 250, 80) +
            "    <comp lib=\"1\" loc=\"(300,100)\" name=\"NOR Gate\">\n      <a name=\"size\" val=\"50\"/>\n" +
            "      <a name=\"inputs\" val=\"2\"/>\n    </comp>\n" +
            xmlWire(300, 100, 320, 100) + xmlWire(320, 100, 320, 160) + xmlWire(320, 160, 230, 160) +
            xmlWire(230, 160, 230, 120) + xmlWire(230, 120, 250, 120) + xmlWire(320, 100, 400, 100) +
            outputPin(400, 100, "y") + outputPin(400, 300, "halt") + xmlWire(380, 300, 400, 300))

    /** Same circuit as scripts/oracle/project-options/generate.py buffer_chain(). */
    private fun bufferChainXml(length: Int, limit: Int): String {
        val xs = (0 until length).map { 150 + 30 * it }
        val body = StringBuilder("    <comp lib=\"0\" loc=\"(100,100)\" name=\"Pin\">\n      <a name=\"label\" val=\"a\"/>\n    </comp>\n")
        body.append(xmlWire(100, 100, 130, 100))
        xs.forEachIndexed { index, x ->
            body.append("    <comp lib=\"1\" loc=\"($x,100)\" name=\"Buffer\"/>\n")
            if (index + 1 < length) body.append(xmlWire(x, 100, x + 10, 100))
        }
        body.append(xmlWire(xs.last(), 100, xs.last() + 60, 100)).append(outputPin(xs.last() + 60, 100, "y"))
        return evolutionProject(limit, body.toString())
    }

    private fun run(vararg arguments: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf(*arguments), PrintStream(output), PrintStream(error))
        return Result(code, output.toString(), error.toString())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("logisim-headless-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Result(val code: Int, val output: String, val error: String) {
        val lines: List<String> get() = output.trim().lines()
        val rows: List<List<String>> get() = lines.drop(1).map { it.split(',') }
    }

    private val evolutionTableXml = """
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" name="Pin" loc="(220,300)">
              <a name="output" val="true"/><a name="facing" val="west"/>
              <a name="width" val="7"/><a name="label" val="Bus"/>
            </comp>
            <comp lib="0" name="Pin" loc="(220,200)">
              <a name="output" val="true"/><a name="facing" val="west"/>
              <a name="width" val="5"/><a name="label" val="Bus"/>
            </comp>
            <comp lib="0" name="Constant" loc="(100,200)"><a name="width" val="5"/><a name="value" val="0x1a"/></comp>
            <wire from="(100,200)" to="(220,200)"/>
            <comp lib="0" name="Pin" loc="(220,100)">
              <a name="output" val="true"/><a name="facing" val="west"/><a name="label" val="Y"/>
            </comp>
            <comp lib="0" name="Pin" loc="(100,100)"><a name="label" val="A"/></comp>
            <wire from="(100,100)" to="(220,100)"/>
          </circuit>
        </project>
    """.trimIndent()

    private val clockXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project source="2.7.1" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(100,100)" name="Clock"/>
            <comp lib="0" loc="(200,100)" name="Pin">
              <a name="output" val="true"/>
              <a name="label" val="clk"/>
            </comp>
            <wire from="(100,100)" to="(200,100)"/>
          </circuit>
        </project>
    """.trimIndent()
}
