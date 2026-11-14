package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpgaScanDisplayTest {
    private val board = assertIs<FpgaBoardImportResult.Success>(
        FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/GECKO5_EDUCATION.xml")).readText()),
    ).board
    private val matrix = board.resources.single { it.type == "LedArray" }
    private val oscillator = FpgaDesignPort("osc", FpgaPinDirection.INPUT, clockFrequencyHz = 50_000_000)
    private val output = FpgaDesignPort("rgb", FpgaPinDirection.OUTPUT, 2)
    private val design = FpgaDesignArtifact(
        "matrix", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("matrix.v", "module matrix(input osc, output [1:0] rgb); assign rgb = 2'b11; endmodule")),
        listOf(oscillator, output),
    )
    private val bindings = mapOf(
        FpgaSignalBit("osc") to FpgaPinBinding.Physical("F1"),
        FpgaSignalBit("rgb", 0) to FpgaPinBinding.Display(matrix.id, 0),
        FpgaSignalBit("rgb", 1) to FpgaPinBinding.Display(matrix.id, 359),
    )

    @Test
    fun matrixReservesTheImportedPinsAndSharesTheExistingOscillator() {
        val plan = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(design, board, bindings, FpgaFlow.OPEN_ECP5),
        ).plan
        val constraints = plan.files.single { it.path == "pins.lpf" }.content
        assertEquals(1, constraints.lineSequence().count { it.startsWith("LOCATE") && """SITE "F1"""" in it })
        assertTrue("""LOCATE COMP "fpga_scan_0_p0" SITE "B6";""" in constraints)
        assertTrue("""LOCATE COMP "fpga_scan_0_p33" SITE "D7";""" in constraints)
        assertEquals(35, constraints.lineSequence().count { it.startsWith("LOCATE") })
        assertEquals(mapOf(FpgaSignalBit("osc") to "fpga_p0_b0"), plan.mappedPins)
    }

    @Test
    fun scanDriverUsesTheBitOfAOneBitVhdlVector() {
        val source = FpgaSourceFile("matrix.vhd", """
            library ieee;
            use ieee.std_logic_1164.all;
            entity matrix is port(rgb : out std_logic_vector(0 downto 0)); end entity;
            architecture rtl of matrix is begin rgb(0) <= '1'; end architecture;
        """.trimIndent())
        val singleOutput = design.copy(
            language = FpgaHdlLanguage.VHDL,
            files = listOf(source),
            ports = listOf(output.copy(width = 1, vhdlVector = true)),
        )
        val mapping = mapOf(FpgaSignalBit("rgb") to FpgaPinBinding.Display(matrix.id, 0, rgbPixel = true))
        val plan = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(singleOutput, board, mapping, FpgaFlow.OPEN_ECP5),
        ).plan
        val wrapper = plan.files.single { it.path == "${plan.topLevelName}.vhd" }.content
        for (channel in listOf(0, 120, 240)) {
            assertTrue("fpga_scan_0_data($channel) <= design_p0(0);" in wrapper)
        }
    }

    /** Every scanned display of the boards the app ships. */
    private val builtInDisplays: List<Pair<String, FpgaScanDisplay>> by lazy {
        val directory = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "composeApp/src/commonMain/composeResources/files/fpga-boards") }.first { it.isDirectory }
        directory.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(file.readText()), file.name).board
            board.resources.mapNotNull { resource -> resource.scannedDisplay()?.let { "${file.name} ${resource.id}" to it } }
        }
    }

    @Test
    fun digitSegmentAndRowColumnChoicesMapOneToOneOntoChannels() {
        assertTrue(builtInDisplays.size >= 4, builtInDisplays.map { it.first }.toString())
        for ((name, display) in builtInDisplays) {
            for (rgbPixel in listOf(false, true)) {
                for (channel in 0 until display.channelLimit(rgbPixel)) {
                    val position = display.coordinatesOf(channel)
                    assertEquals(channel, display.channelOf(position.row, position.column, position.plane), "$name $channel")
                    assertTrue(position.row in 0 until display.rows && position.column in 0 until display.columns &&
                        position.plane in 0 until display.planes, "$name $channel")
                }
            }
        }
    }

    @Test
    fun scanningSevenSegmentChannelsAreNamedByDigitAndSegment() {
        val sevenSegment = builtInDisplays.map { it.second }.first { it.isSevenSegment && it.rows == 4 }
        assertEquals(8, sevenSegment.columns)
        // Digit 3 (row 2), segment c (column 2) on a 4 x 8 display.
        assertEquals(18, sevenSegment.channelOf(2, 2))
        assertEquals(FpgaScanPosition(2, 2), sevenSegment.coordinatesOf(18))
        assertEquals("Digit 3 · Segment c (channel 18)", sevenSegment.channelLabel(18).resolve(AppLanguage.ENGLISH))
        assertEquals("Dígito 4 · Segmento dp (canal 31)", sevenSegment.channelLabel(31).resolve(AppLanguage.PORTUGUESE))
        val rgb = board.resources.single { it.type == "LedArray" }.scannedDisplay()!!
        assertTrue(rgb.isRgb)
        assertEquals(3, rgb.planes)
        val green = rgb.channelOf(1, 4, 1)
        assertEquals("Row 2 · Column 5 · Green (channel $green)", rgb.channelLabel(green).resolve(AppLanguage.ENGLISH))
        assertEquals("Row 2 · Column 5 (channel 16)", rgb.channelLabel(rgb.channelOf(1, 4), rgbPixel = true).resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun requiredOutputPinsFollowTheDisplayConfiguration() {
        for ((name, display) in builtInDisplays) {
            val resource = display.resource
            val key = if (resource.type.equals("LedArray", true)) "LedArrayInfo" else "ScanningSevenSegInfo"
            assertEquals(resource.pins.size, fpgaDisplayPinCount(resource.type, resource.attributes.getValue(key)), name)
        }
        assertEquals(12, fpgaDisplayPinCount("SevenSegmentScanning", "4,-1,SevenSegScanningActiveLow"))
        assertEquals(10, fpgaDisplayPinCount("SevenSegmentScanning", "4,2,SevenSegDecoded"))
        assertEquals(11, fpgaDisplayPinCount("LedArray", "8,8,LedRowScanning"))
        assertEquals(64, fpgaDisplayPinCount("LedArray", "8,8,LedDefault"))
        assertEquals(192, fpgaDisplayPinCount("LedArray", "8,8,RgbDefault"))
        assertNull(fpgaDisplayPinCount("LedArray", "8,8,Unknown"))
        assertNull(fpgaDisplayPinCount("Led", "8,8,LedRowScanning"))
    }

    @Test
    fun aChannelChosenByDigitAndSegmentSavesTheSameMapAsTheTypedChannel() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project source="5.1.0dev" version="1.0">
              <lib name="0" desc="#Wiring"/><main name="main"/>
              <circuit name="main">
                <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
                <comp lib="0" name="Pin" loc="(150,100)">
                  <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
                </comp>
                <wire from="(20,100)" to="(150,100)"/>
              </circuit>
            </project>
        """.trimIndent())).project
        val exported = HdlExporter.export(project, HdlLanguage.VERILOG)
        val codec = FpgaMappingCodec(exported.fpgaDesign(HdlLanguage.VERILOG), project.mainCircuit, exported.ports)
        val segments = board.resources.single { it.type == "SevenSegmentScanning" }
        val display = segments.scannedDisplay()!!
        fun saved(channel: Int) = assertIs<FpgaMappingResult.Saved>(codec.save(board, FpgaFlow.OPEN_ECP5,
            FpgaMapping(mapOf(FpgaSignalBit("p_y") to FpgaPinBinding.Display(segments.id, channel)), emptyMap()))).document.extraXml
        assertEquals(saved(18), saved(display.channelOf(2, 2)))
        assertTrue(saved(18).single().contains("mobilescan=\"119_68_18\""))
    }

    @Test
    fun rejectsDuplicateLogicalChannelsAndPhysicalOutputsOnTheSameDisplay() {
        val signal = FpgaSignalBit("rgb", 1)
        val duplicate = bindings + (signal to FpgaPinBinding.Display(matrix.id, 0))
        val conflictingPin = bindings + (signal to FpgaPinBinding.Physical("D7"))
        for ((mapping, expectedCode) in listOf(duplicate to "duplicate-display-channel",
            conflictingPin to "display-pin-already-used")) {
            val failure = assertIs<FpgaPlanResult.Failure>(
                FpgaProjectGenerator.generate(design, board, mapping, FpgaFlow.OPEN_ECP5),
            )
            assertTrue(failure.diagnostics.any { it.code == expectedCode }, failure.diagnostics.toString())
        }
    }
}
