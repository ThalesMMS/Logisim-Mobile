package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HeadlessFpgaTest {
    @Test fun generatesTheSameVivadoArchiveFromSavedAndSeparateMaps() = withFiles { directory, project, board ->
        val embedded = directory.resolve("embedded.zip")
        val external = directory.resolve("external.zip")
        val input = project.readText()
        val withoutMapping = directory.resolve("without-map.circ").apply { writeText(input.replace(mapping, "")) }
        val first = run("--test-fpga", project.path, "main", board.path, "HDLONLY", "--output", embedded.path)
        assertEquals(0, first.code, first.error)
        assertTrue(first.output.startsWith("status=fpga-hdl-only flow=vivado files="))
        val second = run("-f", withoutMapping.path, "main", board.path, "--mapping", project.path, "HDLONLY", "--output", external.path)
        assertEquals(0, second.code, second.error)
        assertContentEquals(embedded.readBytes(), external.readBytes())
        ZipFile(embedded).use { archive ->
            assertTrue(archive.entries().asSequence().map { it.name }.toSet().containsAll(
                setOf("logisim_fpga_top.v", "pins.xdc", "build.tcl")))
            val constraints = archive.getInputStream(archive.getEntry("pins.xdc")).bufferedReader().use { it.readText() }
            assertTrue("PACKAGE_PIN V17" in constraints && "PACKAGE_PIN U16" in constraints)
            val wrapper = archive.getInputStream(archive.getEntry("logisim_fpga_top.v")).bufferedReader().use { it.readText() }
            assertTrue("1'b1" in wrapper)
        }
        assertEquals(input, project.readText())
    }

    @Test fun exportsTheDerivedClockFromSavedAndSeparateMaps() = withFiles { directory, project, board ->
        val clockMapping = mapping.replace(
            """<mc key="/a" map="647,323"/>""",
            """<mc key="/a" pmap="u" mobileclockpin="W5" mobileclockhz="100000000" mobileclockscale="8,16"/>""",
        )
        val input = project.readText()
        val mappedProject = directory.resolve("clock.circ").apply { writeText(input.replace(mapping, clockMapping)) }
        val withoutMapping = directory.resolve("without-map.circ").apply { writeText(input.replace(mapping, "")) }
        val embedded = directory.resolve("embedded.zip")
        val external = directory.resolve("external.zip")
        val saved = run("--test-fpga", mappedProject.path, "main", board.path, "HDLONLY", "--output", embedded.path)
        assertEquals(0, saved.code, saved.error)
        val separate = run("-f", withoutMapping.path, "main", board.path, "--mapping", mappedProject.path, "HDLONLY", "--output", external.path)
        assertEquals(0, separate.code, separate.error)
        assertContentEquals(embedded.readBytes(), external.readBytes())
        ZipFile(embedded).use { archive ->
            val xdc = archive.getInputStream(archive.getEntry("pins.xdc")).bufferedReader().use { it.readText() }
            assertTrue("PACKAGE_PIN W5 [get_ports {fpga_p0_b0}]" in xdc)
            assertTrue("create_clock -period 10.0 [get_ports {fpga_p0_b0}]" in xdc)
            val wrapper = archive.getInputStream(archive.getEntry("logisim_fpga_top.v")).bufferedReader().use { it.readText() }
            assertTrue("MMCME2_BASE" in wrapper)
            assertTrue(".CLKFBOUT_MULT_F(8.000)" in wrapper)
            assertTrue(".CLKOUT0_DIVIDE_F(16.000)" in wrapper)
        }
    }

    @Test fun reportsTheFailingStageAndPreservesInputAndExistingOutput() = withFiles { directory, project, board ->
        val target = directory.resolve("output.zip").apply { writeText("existing output") }
        val input = project.readText()
        val command = arrayOf("--test-fpga", project.path, "main", board.path)
        fun assertFailure(stage: String, args: Array<String>) {
            val result = run(*args)
            assertEquals(2, result.code, result.error)
            assertTrue(result.error.lineSequence().any { it.startsWith("error=fpga stage=$stage ") }, result.error)
            assertEquals("", result.output)
        }
        assertFailure("arguments", command + arrayOf("HDLONLY"))
        assertFailure("arguments", command + arrayOf("HDLONLY", "--output", target.path, "--approve-build"))
        assertFailure("build", command + arrayOf("--output", target.path))
        assertFailure("build", command + arrayOf("--output", target.path, "--companion", "http://127.0.0.1:1"))
        assertFailure("output", command + arrayOf("--output", project.path, "--companion", "http://127.0.0.1:1", "--approve-build"))
        val incompleteMap = directory.resolve("incomplete-map.circ").apply { writeText(input.replace("""<mc key="/y" pmap="649_295_0"/>""", "")) }
        assertFailure("plan", command + arrayOf("HDLONLY", "--mapping", incompleteMap.path, "--output", target.path))
        val invalidMap = directory.resolve("invalid-map.circ").apply { writeText(input.replace("boardname=\"BASYS3\"", "boardname=\"other\"")) }
        assertFailure("mapping", command + arrayOf("HDLONLY", "--mapping", invalidMap.path, "--output", target.path))
        val invalidBoard = directory.resolve("invalid-board.xml").apply { writeText("<Board/>") }
        assertFailure("board", arrayOf("--test-fpga", project.path, "main", invalidBoard.path, "HDLONLY", "--output", target.path))
        val empty = directory.resolve("empty.circ").apply {
            writeText(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("main")))))
        }
        assertFailure("drc", arrayOf("--test-fpga", empty.path, "main", board.path, "HDLONLY", "--output", target.path))
        assertFailure("output", command + arrayOf("HDLONLY", "--output", project.path))
        val sameOutputAndCancelMarker = directory.resolve("output-cancel-marker.zip")
        assertFailure("output", command + arrayOf("HDLONLY", "--output", sameOutputAndCancelMarker.path,
            "--cancel-file", sameOutputAndCancelMarker.path))
        assertFalse(sameOutputAndCancelMarker.exists())
        val marker = directory.resolve("cancel").apply { createNewFile() }
        val cancelled = directory.resolve("cancelled.zip")
        assertEquals(130, run(*command, "HDLONLY", "--output", cancelled.path, "--cancel-file", marker.path).code)
        assertFalse(cancelled.exists())
        assertEquals(input, project.readText())
        assertEquals("existing output", target.readText())
    }

    @Test fun exportsPositionalAndSavedTicksWithTheSamePhysicalClock() = withFiles { directory, project, board ->
        project.writeText(tickCircuit("""<mc key="/source_clock" pmap="u" mobilepins="" mobileclockhz="+0" future="kept"/>"""))
        val explicitZip = directory.resolve("explicit.zip")
        val savedZip = directory.resolve("saved.zip")
        val explicit = run("--test-fpga", project.path, "main", board.path, "12500000", "HDLONLY", "--output", explicitZip.path)
        assertEquals(0, explicit.code, explicit.error)
        val separate = directory.resolve("no-map.circ").apply {
            writeText(project.readText().substringBefore("<boardmap") + "</circuit></project>")
        }
        val saved = run("-f", separate.path, "main", board.path, "HDLONLY", "--mapping", project.path, "--output", savedZip.path)
        assertEquals(0, saved.code, saved.error)
        val explicitWrapper = archiveText(explicitZip, "logisim_fpga_top.v")
        val savedWrapper = archiveText(savedZip, "logisim_fpga_top.v")
        assertTrue("logisim_fpga_tick_count == 3'd0) ? 3'd7" in explicitWrapper)
        assertTrue("logisim_fpga_tick_count == 4'd0) ? 4'd9" in savedWrapper)
        val waveDeclaration = explicitWrapper.indexOf("reg logisim_fpga_clock_0_level,")
        assertTrue(waveDeclaration in 0 until explicitWrapper.indexOf(" design_instance("))
        val xdc = archiveText(explicitZip, "pins.xdc")
        assertEquals(xdc, archiveText(savedZip, "pins.xdc"))
        assertEquals(1, xdc.lineSequence().count { "PACKAGE_PIN W5 " in it })
        assertTrue("create_clock -period 10.0 " in xdc)
        assertTrue("PACKAGE_PIN U16 " in xdc)
    }

    @Test fun exportsVhdlTickArchivesWhenRequested() = withFiles { directory, project, board ->
        project.writeText(tickCircuit(""))
        val command = arrayOf("--test-fpga", project.path, "main", board.path, "--hdl-language", "vhdl")
        val explicitZip = directory.resolve("explicit-vhdl.zip")
        val savedZip = directory.resolve("saved-vhdl.zip")
        val explicit = run(*command, "12500000", "HDLONLY", "--output", explicitZip.path)
        assertEquals(0, explicit.code, explicit.error)
        val saved = run(*command, "HDLONLY", "--output", savedZip.path)
        assertEquals(0, saved.code, saved.error)
        val explicitWrapper = archiveText(explicitZip, "logisim_fpga_top.vhd")
        val savedWrapper = archiveText(savedZip, "logisim_fpga_top.vhd")
        assertTrue("to_unsigned(7, 3)" in explicitWrapper)
        assertTrue("to_unsigned(9, 4)" in savedWrapper)
        assertTrue("fpga_wave_0_level => logisim_fpga_clock_0_wave" in explicitWrapper)
        assertTrue("fpga_wave_0_level : IN std_logic" in archiveText(explicitZip, "c_main.vhd"))
        val xdc = archiveText(explicitZip, "pins.xdc")
        assertEquals(xdc, archiveText(savedZip, "pins.xdc"))
        assertEquals(1, xdc.lineSequence().count { "PACKAGE_PIN W5 " in it })
        assertTrue("create_clock -period 10.0 " in xdc)
        val unsupported = run(*command, "--companion", "http://127.0.0.1:1", "--approve-build", "--output", savedZip.path)
        assertEquals(2, unsupported.code)
        assertTrue("stage=arguments VHDL FPGA export requires HDLONLY." in unsupported.error)
    }

    @Test fun preservesMappedClocksAndRejectsAnOverrideThatWouldReplaceThem() = withFiles { directory, project, board ->
        project.writeText(tickCircuit("""<mc key="/source_clock" pmap="u" mobileclockpin="W5" mobileclockhz="100000000"/>"""))
        val existing = directory.resolve("mapped-clock.zip")
        val command = arrayOf("--test-fpga", project.path, "main", board.path, "HDLONLY")
        val mapped = run(*(command + arrayOf("--output", existing.path)))
        assertEquals(0, mapped.code, mapped.error)
        assertFalse("logisim_fpga_tick" in archiveText(existing, "logisim_fpga_top.v"))
        assertTrue("PACKAGE_PIN W5 " in archiveText(existing, "pins.xdc"))
        val defaultTick = directory.resolve("legacy-default-tick.zip")
        val defaultRun = run(*(command + arrayOf("+0", "--output", defaultTick.path)))
        assertEquals(0, defaultRun.code, defaultRun.error)
        assertContentEquals(existing.readBytes(), defaultTick.readBytes())
        val target = directory.resolve("conflict.zip")
        val conflict = run(*(command + arrayOf("12500000", "--output", target.path)))
        assertEquals(2, conflict.code)
        assertTrue("error=fpga stage=mapping Synthesized FPGA ticks cannot be combined with a mapped Clock component." in conflict.error)
        assertFalse(target.exists())
    }

    @Test fun usesTheRawOscillatorForTicksWhilePreservingADerivedInput() = withFiles { directory, project, board ->
        val mapping = """
            <mc key="/external_clock" pmap="u" mobileclockpin="W5" mobileclockhz="100000000" mobileclockscale="8,16"/>
            <mc key="/derived_out" pmap="612_295_0"/>
        """.trimIndent()
        val ports = """
            <wire from="(100,150)" to="(200,150)"/>
            <comp lib="0" loc="(100,150)" name="Pin"><a name="label" val="external_clock"/></comp>
            <comp lib="0" loc="(200,150)" name="Pin">
              <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="derived_out"/>
            </comp>
        """.trimIndent()
        project.writeText(tickCircuit(mapping).replace("<boardmap", "$ports\n    <boardmap"))
        val target = directory.resolve("shared-clock.zip")
        val result = run("-f", project.path, "main", board.path, "HDLONLY", "--output", target.path)
        assertEquals(0, result.code, result.error)
        val xdc = archiveText(target, "pins.xdc")
        val w5 = xdc.lineSequence().single { "PACKAGE_PIN W5 " in it }.substringAfter("{").substringBefore("}")
        assertEquals(listOf("create_clock -period 10.0 [get_ports {$w5}]"),
            xdc.lineSequence().filter { it.startsWith("create_clock ") }.toList())
        val wrapper = archiveText(target, "logisim_fpga_top.v")
        assertTrue("always @(posedge $w5)" in wrapper)
        assertTrue(".I($w5)" in wrapper)
        assertTrue("MMCME2_BASE" in wrapper && ".CLKOUT0_DIVIDE_F(16.000)" in wrapper)
    }

    @Test fun acceptsDrivenSetAndResetForADedicatedFpgaClock() = withFiles { directory, project, board ->
        val fixture = checkNotNull(javaClass.getResourceAsStream("/fpga/sr-dedicated.circ"))
            .bufferedReader().use { it.readText() }
        project.writeText(fixture)
        val target = directory.resolve("sr-dedicated.zip")
        val result = run("-f", project.path, "main", board.path, "HDLONLY", "12500000", "--output", target.path)
        assertEquals(0, result.code, result.error)
        assertTrue("PACKAGE_PIN U16 " in archiveText(target, "pins.xdc"))

        val imported = CircCodec.readProject(fixture) as CircProjectImportResult.Success
        val ordinary = HdlExporter.export(imported.project, HdlLanguage.VERILOG)
        assertFalse(ordinary.successful)
        assertTrue(ordinary.diagnostics.any { it.code == "HDL_UNSUPPORTED_CONFIGURATION" })
    }

    @Test fun selectsVhdlRamTimingPerInstanceOfASharedCircuit() = withFiles { directory, project, board ->
        for (variant in listOf("byte", "line")) {
            project.writeText(checkNotNull(javaClass.getResourceAsStream("/fpga/ram-mixed-$variant.circ"))
                .bufferedReader().use { it.readText() })
            val target = directory.resolve("ram-mixed-$variant.zip")
            val result = run("-f", project.path, "main", board.path, "HDLONLY", "12500000",
                "--hdl-language", "vhdl", "--output", target.path)
            assertEquals(0, result.code, result.error)
            val child = archiveText(target, "c_mem.vhd")
            assertTrue("    fpga_ram_mode_0 : boolean := false" in child, child)
            assertTrue("v_ram_dedicated_ram : IF fpga_ram_mode_0 GENERATE" in child, child)
            assertTrue("v_ram_portable_ram : IF NOT fpga_ram_mode_0 GENERATE" in child, child)
            val parent = archiveText(target, "c_main.vhd")
            assertTrue("v_a : ENTITY work.c_mem GENERIC MAP (fpga_ram_mode_0 => true)" in parent, parent)
            assertTrue("v_b : ENTITY work.c_mem GENERIC MAP (fpga_ram_mode_0 => false)" in parent, parent)
        }
    }

    @Test fun sharesTheReptarOscillatorWithASynthesizedClock() = withFiles { directory, project, _ ->
        project.writeText(checkNotNull(javaClass.getResourceAsStream("/fpga/reptar-clock.circ")).bufferedReader().use { it.readText() })
        val board = listOf("composeApp", "../composeApp").map { File(it, "src/commonMain/composeResources/files/fpga-boards/REPTAR_SPARTAN_6.xml") }
            .first(File::isFile)
        val target = directory.resolve("reptar-clock.zip")
        val result = run("-f", project.path, "main", board.path, "25000000", "HDLONLY", "--hdl-language", "vhdl", "--output", target.path)
        assertEquals(0, result.code, result.error)
        val ucf = archiveText(target, "pins.ucf")
        val oscillator = checkNotNull(Regex("""NET "(\w+)" LOC = "E16"""").find(ucf)).groupValues[1]
        assertEquals(1, ucf.lineSequence().count { "LOC = \"E16\"" in it })
        assertTrue("TIMESPEC \"TS_$oscillator\" = PERIOD \"clock_$oscillator\" 10.0 ns HIGH 50%;" in ucf, ucf)
        assertEquals(31, ucf.lineSequence().count { "IOSTANDARD = LVCMOS18" in it })
        val wrapper = archiveText(target, "logisim_fpga_top.vhd")
        assertEquals(2, Regex("""PROCESS\($oscillator\)""").findAll(wrapper).count(), wrapper)
        assertTrue("std_logic_vector(to_unsigned(3, 2))" in wrapper)
        assertTrue("fpga_oscillator => $oscillator," in wrapper)
        assertTrue("fpga_wave_0_level => logisim_fpga_clock_0_wave," in wrapper)
        assertTrue("ENTITY work.mobile_reptar_local_bus" in archiveText(target, "c_main.vhd"))
    }

    @Test fun exportsSharedBusLineRamForFpgaOnly() = withFiles { directory, project, board ->
        val fixture = checkNotNull(javaClass.getResourceAsStream("/fpga/ram-line-shared-bus.circ"))
            .bufferedReader().use { it.readText() }
        for (language in listOf("verilog", "vhdl")) {
            project.writeText(fixture)
            val target = directory.resolve("shared-$language.zip")
            val result = run("-f", project.path, "main", board.path, "HDLONLY", "12500000",
                "--hdl-language", language, "--output", target.path)
            assertEquals(0, result.code, result.error)
            val source = archiveText(target, if (language == "vhdl") "c_main.vhd" else "c_main.v")
            assertTrue((if (language == "vhdl") "v_ram_fpga_ram : BLOCK" else "v_ram_line_ticks") in source, source)
        }
        project.writeText(fixture.replace("val=\"bidir\"", "val=\"bibusControlled\""))
        val controlled = run("-f", project.path, "main", board.path, "HDLONLY", "12500000",
            "--output", directory.resolve("controlled.zip").path)
        assertEquals(2, controlled.code)
        assertTrue("HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED" in controlled.error, controlled.error)

        val imported = CircCodec.readProject(fixture) as CircProjectImportResult.Success
        assertFalse(HdlExporter.export(imported.project, HdlLanguage.VERILOG).successful)
    }

    @Test fun rejectsRepeatedOrInvalidPositionalTicksBeforeExport() = withFiles { directory, project, board ->
        val target = directory.resolve("tick.zip")
        val command = arrayOf("--test-fpga", project.path, "main", board.path, "HDLONLY")
        for (frequency in listOf("-1", "-0", "1.5", "1e6", "2147483648", "4294967295", "4294967296")) {
            val result = run(*(command + arrayOf(frequency, "--output", target.path)))
            assertEquals(2, result.code)
            assertTrue("error=fpga stage=arguments Unknown FPGA argument '$frequency'." in result.error, result.error)
        }
        val duplicate = run(*(command + arrayOf("12", "13", "--output", target.path)))
        assertEquals(2, duplicate.code)
        assertTrue("error=fpga stage=arguments Repeated FPGA tick frequency." in duplicate.error, duplicate.error)
        assertFalse(target.exists())
    }

    @Test fun bundledCmodAndZyboSelectWhitelistedVivadoBuilds() {
        data class Profile(val name: String, val part: String, val inputPin: String, val outputPin: String)
        val catalog = sequenceOf(File("."), File(".."))
            .map { it.resolve("composeApp/src/commonMain/composeResources/files/fpga-boards") }
            .first(File::isDirectory)
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(input a, output y); assign y=a; endmodule")),
            listOf(FpgaDesignPort("a", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        for (profile in listOf(
            Profile("DIGILENT_CMOD_A7_15T", "xc7a15tcpg236-1", "A18", "A17"),
            Profile("ZYBO", "xc7z010clg400-1", "T16", "D18"),
        )) {
            val board = (FpgaBoardCodec.read(catalog.resolve("${profile.name}.xml").readText()) as FpgaBoardImportResult.Success).board
            assertTrue(board.supportsCompanionBuild(FpgaFlow.VIVADO), profile.name)
            val result = FpgaProjectGenerator.generate(design, board, mapOf(
                FpgaSignalBit("a") to FpgaPinBinding.Physical(profile.inputPin),
                FpgaSignalBit("y") to FpgaPinBinding.Physical(profile.outputPin),
            ), FpgaFlow.VIVADO)
            val plan = (result as FpgaPlanResult.Success).plan
            val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
            assertEquals("fpga-vivado-build", request.operation)
            assertEquals(CompanionJsonString(profile.part), request.parameters["part"])
            assertEquals(listOf("source-0000.v", "source-0001.v", "pins.xdc"), request.inputs.map { it.name })
            val xdc = plan.files.single { it.path == "pins.xdc" }.content
            assertTrue("PACKAGE_PIN ${profile.inputPin}" in xdc && "PACKAGE_PIN ${profile.outputPin}" in xdc)
            assertFalse(plan.programsHardware)
        }
    }

    @Test fun verifiesPofInsteadOfSofForTheMaxVQuartusBuild() = runBlocking {
        val device = FpgaDevice(FpgaVendor.ALTERA, "MAX V", "5M570ZF256C5", "FBGA 256", "5")
        val board = FpgaBoardDefinition("MAX_V_CONSOLE", device, null, emptyList())
        val plan = FpgaBuildPlan(board, FpgaFlow.QUARTUS, FpgaProjectGenerator.TOP_LEVEL,
            emptyList(), emptyList(), emptyMap(), emptyList())

        fun job(name: String) = CompanionJob("quartus-job", "fpga-quartus-build", CompanionJobState.SUCCEEDED,
            CompanionJsonCodec.parseObject("""
                {"synthesized":true,"placedAndRouted":true,"bitstreamGenerated":true,
                 "programmed":false,"hardwareVerified":false,"flow":"quartus","top":"${plan.topLevelName}",
                 "artifacts":[{"name":"$name","size":3,"base64":"YWJj",
                 "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}]}
            """.trimIndent()), null, emptyList())

        val artifact = verifiedBitstream(plan, job("design.pof")).single()
        assertEquals("design.pof", artifact.name)
        assertContentEquals("abc".encodeToByteArray(), artifact.bytes)
        assertEquals("artifact", assertFailsWith<HeadlessFpgaError> {
            verifiedBitstream(plan, job("design.sof"))
        }.stage)
    }

    @Test fun verifiesJedecInsteadOfBitstreamForTheMicrolabtleBuild() = runBlocking {
        val device = FpgaDevice(FpgaVendor.XILINX, "Coolrunner", "XC2C64", "VQ44", "5")
        val board = FpgaBoardDefinition("MICROLABTLE", device, null, emptyList())
        val plan = FpgaBuildPlan(board, FpgaFlow.ISE, FpgaProjectGenerator.TOP_LEVEL,
            emptyList(), emptyList(), emptyMap(), emptyList())

        fun job(name: String) = CompanionJob("ise-cpld-job", "fpga-ise-cpld-build", CompanionJobState.SUCCEEDED,
            CompanionJsonCodec.parseObject("""
                {"synthesized":true,"placedAndRouted":true,"bitstreamGenerated":true,
                 "programmed":false,"hardwareVerified":false,"flow":"ise","top":"${plan.topLevelName}",
                 "artifacts":[{"name":"$name","size":3,"base64":"YWJj",
                 "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}]}
            """.trimIndent()), null, emptyList())

        val artifact = verifiedBitstream(plan, job("design.jed")).single()
        assertEquals("design.jed", artifact.name)
        assertContentEquals("abc".encodeToByteArray(), artifact.bytes)
        for (wrongName in listOf("design.bit", "design.pof")) {
            assertEquals("artifact", assertFailsWith<HeadlessFpgaError> {
                verifiedBitstream(plan, job(wrongName))
            }.stage)
        }
    }

    @Test fun archivesOnlyTheVerifiedBinarySofFromTheReviewedQuartusBuild() = runBlocking {
        val part = "5CSEBA6U23I7"
        val bytes = ByteArray(7_449_702) { it.toByte() }
        val unverified = CompanionArtifact.fromQuartusSof("design.sof", bytes)
        val board = FpgaBoardDefinition("DE10-Nano", FpgaDevice(FpgaVendor.ALTERA, "Cyclone V", part, "UBGA 484", "I7"), null, emptyList())
        val plan = FpgaBuildPlan(board, FpgaFlow.QUARTUS, FpgaProjectGenerator.TOP_LEVEL, emptyList(),
            listOf(FpgaBuildStage("vendor-build", "quartus_sh", emptyList(), listOf("output_files/build.sof"))),
            emptyMap(), emptyList())
        val jobId = "a".repeat(32)
        val job = CompanionJob(jobId, "fpga-quartus-build", CompanionJobState.SUCCEEDED, CompanionJsonCodec.parseObject("""
            {"flow":"quartus","top":"${plan.topLevelName}","part":"$part","synthesized":true,"placedAndRouted":true,
             "bitstreamGenerated":true,"programmed":false,"hardwareVerified":false,"artifacts":[
             {"name":"design.sof","size":${bytes.size},"sha256":"${unverified.sha256}","transport":"binary"}]}
        """.trimIndent()), null, emptyList())
        val transport = object : CompanionTransport {
            var downloads = 0
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                assertEquals("GET", request.method)
                assertTrue(request.url.endsWith("/v1/jobs/$jobId/artifacts/design.sof"))
                downloads++
                return CompanionHttpResponse(200, bytes, "application/octet-stream")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        assertFailsWith<HeadlessFpgaError> { verifiedBitstream(plan, job, client) }
        assertEquals(0, transport.downloads)
        assertFailsWith<IllegalArgumentException> { fpgaArchive(listOf(unverified)) }
        val artifacts = verifiedBitstream(plan, job, client, binarySofRequested = true)
        assertEquals(1, transport.downloads)
        assertEquals(part, artifacts.single().quartusSofPart)
        assertFailsWith<IllegalArgumentException> { fpgaArchive(artifacts + CompanionArtifact.fromBytes("other.bit", byteArrayOf(1))) }
        ZipInputStream(ByteArrayInputStream(fpgaArchive(artifacts))).use { zip ->
            assertEquals("design.sof", zip.nextEntry.name)
            assertContentEquals(bytes, zip.readBytes())
            assertEquals(null, zip.nextEntry)
        }
    }

    private data class Result(val code: Int, val output: String, val error: String)

    private fun run(vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = HeadlessCircuitCli.run(arrayOf(*args), PrintStream(output), PrintStream(error))
        return Result(code, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private fun archiveText(file: File, path: String): String = ZipFile(file).use { archive ->
        archive.getInputStream(archive.getEntry(path)).bufferedReader().use { it.readText() }
    }

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

    private fun withFiles(action: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("headless-fpga-test").toFile()
        try {
            val example = listOf(File("fixtures/user-scenarios/fpga-and.circ"), File("../fixtures/user-scenarios/fpga-and.circ")).first(File::isFile)
            val project = directory.resolve("project.circ").apply { writeText(example.readText().replace("</circuit>", "$mapping</circuit>")) }
            val board = directory.resolve("BASYS3.xml").apply {
                writeBytes(checkNotNull(HeadlessFpgaTest::class.java.getResourceAsStream("/fpga/BASYS3.xml")).use { it.readBytes() })
            }
            action(directory, project, board)
        } finally {
            directory.deleteRecursively()
        }
    }

    private val mapping = """
        <boardmap boardname="BASYS3">
            <mc key="/a" map="647,323"/>
            <mc key="/b" vconst="1"/>
            <mc key="/y" pmap="649_295_0"/>
        </boardmap>
    """.trimIndent()
}
