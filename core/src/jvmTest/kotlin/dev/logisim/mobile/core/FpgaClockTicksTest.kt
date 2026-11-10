package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The FPGA panel path must produce the same files as `--test-fpga` for synthesized Clock ticks. */
class FpgaClockTicksTest {
    private val basys3Xml by lazy { checkNotNull(javaClass.getResourceAsStream("/fpga/BASYS3.xml")).use { it.readBytes() }.decodeToString() }
    private val basys3 by lazy { board(basys3Xml) }

    @Test fun panelTickPlanMatchesTheCliArchive() {
        for (language in listOf(HdlLanguage.VERILOG, HdlLanguage.VHDL)) {
            val circuit = tickCircuit("""<mc key="/source_clock" pmap="u" mobilepins="" mobileclockhz="+0"/>""")
            val cli = cliArchive(circuit, "BASYS3.xml", basys3Xml, language, "12500000")
            val plan = panelPlan(circuit, basys3, FpgaFlow.VIVADO, language, 12_500_000.0)
            assertEquals(cli, plan.files.associate { it.path to it.content }, language.name)
            assertTrue(plan.files.any { "logisim_fpga_tick" in it.content })
        }
    }

    @Test fun panelUsesTheSavedDownloadFrequencyAndStoresTheChoiceLikeEvolution() {
        val project = read(tickCircuit(""))
        val review = FpgaCircuitRules.reviewForFpga(project, HdlLanguage.VERILOG)
        val ticks = assertNotNull(review.clockTicks)
        assertEquals(10_000_000.0, ticks.savedFrequencyHz)
        assertEquals(listOf("p_clk_out"), assertNotNull(review.design).ports.map { it.name })
        assertEquals(10, ticks.reloadCycles(basys3, 10_000_000.0))
        assertEquals(25_000_000.0, ticks.effectiveFrequencyHz(basys3, 80_000_000.0))

        val document = project.mainCircuit
        val stored = document.withFpgaTickFrequency(12_500_000.0)
        assertEquals("1.25E7", stored.projectAttribute("downloadFrequency"))
        assertEquals(1, CircCodec.writeProject(project.copy(circuits = listOf(stored))).split("name=\"downloadFrequency\"").size - 1)
        assertSame(document, document.withFpgaTickFrequency(10_000_000.0))
        val simulationOnly = read(tickCircuit("").replace("""<a name="downloadFrequency" val="10000000"/>""", "")).mainCircuit
        assertSame(simulationOnly, simulationOnly.withFpgaTickFrequency(5_000_000.0))
        assertEquals("1.0E7", simulationOnly.withFpgaTickFrequency(10_000_000.0).projectAttribute("downloadFrequency"))

        val cli = cliArchive(tickCircuit(""), "BASYS3.xml", basys3Xml, HdlLanguage.VERILOG, null)
        val plan = panelPlan(tickCircuit(""), basys3, FpgaFlow.VIVADO, HdlLanguage.VERILOG, ticks.savedFrequencyHz!!)
        assertEquals(cli, plan.files.associate { it.path to it.content })
    }

    @Test fun savedPhysicalClockMapsKeepTheClockInput() {
        val project = read(tickCircuit("""<mc key="/source_clock" pmap="u" mobileclockpin="W5" mobileclockhz="100000000"/>"""))
        val review = FpgaCircuitRules.reviewForFpga(project, HdlLanguage.VERILOG)
        val legacy = review.forBoard(basys3)
        assertNull(legacy.clockTicks)
        assertEquals(setOf("p_source_clock", "p_clk_out"), assertNotNull(legacy.design).ports.map { it.name }.toSet())
        val mapping = assertIs<FpgaMappingResult.Loaded>(assertNotNull(legacy.mappingCodec(project.mainCircuit))
            .loadSaved(basys3, FpgaFlow.VIVADO)).mapping
        assertEquals(FpgaPinBinding.Physical("W5"), mapping.bindings[FpgaSignalBit("p_source_clock")])
        // Without a board, or for another board, the tick review stays selected.
        assertSame(review, review.forBoard(null))
    }

    @Test fun tickModeSavesMapsWithoutTheClockAndRejectsBoardsWithoutOscillator() {
        val project = read(tickCircuit("""<mc key="/source_clock" pmap="u" mobilepins="" mobileclockhz="+0"/>"""))
        val review = FpgaCircuitRules.reviewForFpga(project, HdlLanguage.VERILOG).forBoard(basys3)
        val codec = assertNotNull(review.mappingCodec(project.mainCircuit))
        val loaded = assertIs<FpgaMappingResult.Loaded>(codec.loadSaved(basys3, FpgaFlow.VIVADO)).mapping
        val saved = assertIs<FpgaMappingResult.Saved>(codec.save(basys3, FpgaFlow.VIVADO, loaded)).document
        val xml = CircCodec.writeProject(project.copy(circuits = listOf(saved)))
        assertTrue("key=\"/clk_out\"" in xml && "key=\"/source_clock\"" !in xml, xml)
        val reread = read(xml)
        assertEquals(loaded, assertIs<FpgaMappingResult.Loaded>(assertNotNull(FpgaCircuitRules.reviewForFpga(reread, HdlLanguage.VERILOG)
            .mappingCodec(reread.mainCircuit)).loadSaved(basys3, FpgaFlow.VIVADO)).mapping)

        val design = assertNotNull(review.design)
        val noClock = basys3.copy(clock = null)
        val failure = assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(design, noClock, loaded.bindings,
            FpgaFlow.VIVADO, review.clockTicks, 1.0))
        assertEquals(listOf("tick-oscillator"), failure.diagnostics.map { it.code })
    }

    @Test fun reptarAndTickShareTheOscillatorLikeTheCli() {
        val circuit = checkNotNull(javaClass.getResourceAsStream("/fpga/reptar-clock.circ")).bufferedReader().use { it.readText() }
        val boardXml = listOf("composeApp", "../composeApp")
            .map { File(it, "src/commonMain/composeResources/files/fpga-boards/REPTAR_SPARTAN_6.xml") }.first(File::isFile).readText()
        val reptar = board(boardXml)
        val cli = cliArchive(circuit, "REPTAR_SPARTAN_6.xml", boardXml, HdlLanguage.VHDL, "25000000")
        assertEquals(cli, panelPlan(circuit, reptar, FpgaFlow.ISE, HdlLanguage.VHDL, 25_000_000.0).files.associate { it.path to it.content })
    }

    @Test fun circuitsWithoutDedicatedClocksKeepThePlainReview() {
        val example = listOf(File("fixtures/user-scenarios/fpga-and.circ"), File("../fixtures/user-scenarios/fpga-and.circ")).first(File::isFile)
        val project = read(example.readText())
        assertEquals(FpgaCircuitRules.review(project, HdlLanguage.VERILOG), FpgaCircuitRules.reviewForFpga(project, HdlLanguage.VERILOG))
    }

    private fun panelPlan(circuit: String, board: FpgaBoardDefinition, flow: FpgaFlow, language: HdlLanguage, hz: Double): FpgaBuildPlan {
        val project = read(circuit)
        val review = FpgaCircuitRules.reviewForFpga(project, language).forBoard(board)
        val design = assertNotNull(review.design, review.toString())
        val mapping = assertIs<FpgaMappingResult.Loaded>(assertNotNull(review.mappingCodec(project.mainCircuit)).loadSaved(board, flow)).mapping
        val effective = design.copy(ports = design.ports.map { port ->
            port.copy(clockFrequencyHz = mapping.clocks[port.name], clockScale = mapping.clockScales[port.name])
        })
        return assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(effective, board, mapping.bindings, flow,
            review.clockTicks, hz)).plan
    }

    private fun cliArchive(circuit: String, boardName: String, boardXml: String, language: HdlLanguage, tick: String?): Map<String, String> {
        val directory = Files.createTempDirectory("fpga-ticks").toFile()
        try {
            val project = directory.resolve("project.circ").apply { writeText(circuit) }
            val board = directory.resolve(boardName).apply { writeText(boardXml) }
            val target = directory.resolve("out.zip")
            val error = ByteArrayOutputStream()
            val args = listOfNotNull("--test-fpga", project.path, "main", board.path, tick, "HDLONLY",
                "--hdl-language", if (language == HdlLanguage.VHDL) "vhdl" else "verilog", "--output", target.path)
            val code = HeadlessCircuitCli.run(args.toTypedArray(), PrintStream(ByteArrayOutputStream()), PrintStream(error))
            assertEquals(0, code, error.toString(Charsets.UTF_8))
            return ZipFile(target).use { zip ->
                zip.entries().toList().filter { !it.isDirectory }.associate { entry ->
                    entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun board(xml: String) = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml)).board

    private fun tickCircuit(clockMapping: String): String = """
        <project source="2.7.1" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <main name="main"/>
          <circuit name="main">
            <a name="simulationFrequency" val="5000000"/>
            <a name="downloadFrequency" val="10000000"/>
            <wire from="(100,100)" to="(200,100)"/>
            <comp lib="0" loc="(100,100)" name="Clock"><a name="label" val="source_clock"/></comp>
            <comp lib="0" loc="(200,100)" name="Pin">
              <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="clk_out"/>
            </comp>
            <boardmap boardname="BASYS3">
              <mc key="/clk_out" pmap="649_295_0"/>
              $clockMapping
            </boardmap>
          </circuit>
        </project>
    """.trimIndent()
}
