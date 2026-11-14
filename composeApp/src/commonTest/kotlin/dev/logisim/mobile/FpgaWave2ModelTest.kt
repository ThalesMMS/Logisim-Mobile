package dev.logisim.mobile

import androidx.compose.ui.text.input.KeyboardType
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaDiagnostic
import dev.logisim.mobile.core.FpgaDiagnosticSeverity
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaMapping
import dev.logisim.mobile.core.FpgaMappingResult
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.mappingCodec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-05: the pure models behind the FPGA pin picker, mapping readiness and the interactive board editor. */
class FpgaWave2ModelTest {
    /** BASYS3's two rightmost LEDs, U16 and E19, about 15 dp apart at phone width. */
    private val u16 = FpgaRectangle(649, 295, 14, 14)
    private val e19 = FpgaRectangle(612, 295, 14, 14)

    @Test
    fun aTapNearAnLedPicksThatLedAndNeverItsNeighbour() {
        val rectangles = listOf(e19, u16)
        // 358 dp of picture across a 390 dp phone: one picture pixel is 0.48 dp; 4 dp is about 8 picture pixels.
        val dpPerPixel = 358f / 740f
        val radius = FpgaBoardHitTest.radius(FpgaBoardHitTest.TOUCH_RADIUS_DP, dpPerPixel)
        val centreX = u16.x + u16.width / 2f
        val centreY = u16.y + u16.height / 2f
        for ((dx, dy) in listOf(4f to 0f, -4f to 0f, 0f to 4f, 0f to -4f, 3f to 3f)) {
            assertEquals(1, FpgaBoardHitTest.pick(centreX + dx / dpPerPixel, centreY + dy / dpPerPixel, rectangles, radius), "$dx,$dy")
        }
        // Halfway between the LEDs the nearer one wins; far away nothing is picked.
        assertEquals(1, FpgaBoardHitTest.pick(639f, 302f, rectangles, radius))
        assertEquals(0, FpgaBoardHitTest.pick(634f, 302f, rectangles, radius))
        assertNull(FpgaBoardHitTest.pick(100f, 50f, rectangles, radius))
    }

    @Test
    fun overlappingResourcesResolveDeterministically() {
        // SLO_MAX7000S has overlapping pairs: a containing tap picks the smaller rectangle, equal ones the first.
        val large = FpgaRectangle(100, 100, 60, 60)
        val small = FpgaRectangle(120, 120, 10, 10)
        assertEquals(1, FpgaBoardHitTest.pick(125f, 125f, listOf(large, small), 20f))
        assertEquals(1, FpgaBoardHitTest.pick(125f, 125f, listOf(small, large).reversed(), 20f))
        assertEquals(0, FpgaBoardHitTest.pick(125f, 125f, listOf(small, small.copy()), 20f))
        repeat(3) { assertEquals(0, FpgaBoardHitTest.pick(105f, 105f, listOf(large, small), 20f)) }
    }

    private fun board(resources: String) = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read("""
        <FIXTURE>
          <BoardInformation>
            <ClockInformation FPGApin="W5" Frequency="100000000"/>
            <FPGAInformation Family="Artix-7" Part="xc7a35t" Package="cpg236" Speedgrade="-1" Vendor="VIVADO"/>
          </BoardInformation>
          <IOComponents>
            $resources
          </IOComponents>
        </FIXTURE>
    """.trimIndent())).board

    private val busProject = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main">
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="SW"/><a name="width" val="4"/></comp>
            <comp lib="0" name="Pin" loc="(150,100)">
              <a name="label" val="LED"/><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="4"/>
            </comp>
            <wire from="(20,100)" to="(150,100)"/>
          </circuit>
        </project>
    """.trimIndent())).project

    @Test
    fun aWholePortOnADipSwitchEqualsFourManualChoicesAndSavesTheSameBoardmap() {
        val board = board("""
            <DIPSwitch Rect_x_y_w_h="10,10,80,20" InputPinSet="A1,A2,A3,A4,A5,A6,A7,A8" FPGAPinIOStandard="LVCMOS33"/>
            <Led Rect_x_y_w_h="100,10,10,10" OutputPinSet="U16" FPGAPinIOStandard="LVCMOS33"/>
        """)
        val dip = board.resources.first()
        val review = FpgaCircuitRules.reviewForFpga(busProject, HdlLanguage.VHDL)
        val design = checkNotNull(review.design)
        val port = design.ports.single { it.name == "p_sw" }
        assertEquals(4, port.width)
        val whole = checkNotNull(fpgaMapWholePort(emptyMap(), port, dip))
        // The same per-bit bindings four picks of pins 0 to 3 would give.
        val manual = (0 until 4).fold(emptyMap<FpgaSignalBit, FpgaPinBinding>()) { bindings, bit ->
            bindings + (FpgaSignalBit("p_sw", bit) to FpgaPinBinding.Physical(dip.pins[bit].location, dip.id))
        }
        assertEquals(manual, whole)
        val codec = checkNotNull(review.mappingCodec(busProject.mainCircuit))
        val saved = assertIs<FpgaMappingResult.Saved>(codec.save(board, FpgaFlow.VIVADO, FpgaMapping(whole, emptyMap())))
        val savedManual = assertIs<FpgaMappingResult.Saved>(codec.save(board, FpgaFlow.VIVADO, FpgaMapping(manual, emptyMap())))
        assertEquals(CircCodec.writeProject(busProject.withCircuit(savedManual.document)),
            CircCodec.writeProject(busProject.withCircuit(saved.document)), "Byte-identical boardmap")
        // An 8-pin switch takes the port at any of five offsets, Evolution's DIP switch rule; bit i is pin offset + i.
        assertEquals((0..4).toList(), fpgaWholePortOffsets(dip, FpgaPinDirection.INPUT, 4))
        val offset = checkNotNull(fpgaMapWholePort(emptyMap(), port, dip, offset = 3))
        assertEquals(listOf("A4", "A5", "A6", "A7"), (0 until 4).map { (offset.getValue(FpgaSignalBit("p_sw", it)) as FpgaPinBinding.Physical).location })
        assertNull(fpgaMapWholePort(emptyMap(), port, dip, offset = 5))
        // A one-pin LED cannot hold a 4-bit port, and an output never maps onto input pins.
        assertTrue(fpgaWholePortOffsets(board.resources[1], FpgaPinDirection.OUTPUT, 4).isEmpty())
        assertTrue(fpgaWholePortOffsets(dip, FpgaPinDirection.OUTPUT, 4).isEmpty())
        // So "Map whole port" is offered for the switches but not for the LED output, which only one-pin LEDs can take.
        assertTrue(fpgaWholePortFits(board, port))
        assertFalse(fpgaWholePortFits(board, design.ports.single { it.name == "p_led" }))
        // Other ports keep their bindings.
        val kept = mapOf(FpgaSignalBit("p_led", 0) to FpgaPinBinding.Physical("U16"))
        assertEquals(kept + manual, fpgaMapWholePort(kept, port, dip))
    }

    /**
     * W3-05 (C118): on a board of one-pin switches (BASYS3), the rest of a port maps onto the switches that follow the
     * tapped one in the board file, giving the same per-bit bindings, and the same boardmap, as one choice per bit.
     */
    @Test
    fun aRunOfOnePinSwitchesEqualsManualChoicesAndSavesTheSameBoardmap() {
        val board = board("""
            <Button Rect_x_y_w_h="60,10,10,10" InputPinSet="V17" FPGAPinIOStandard="LVCMOS33"/>
            <Button Rect_x_y_w_h="50,10,10,10" InputPinSet="V16" FPGAPinIOStandard="LVCMOS33"/>
            <Button Rect_x_y_w_h="40,10,10,10" InputPinSet="W16" FPGAPinIOStandard="LVCMOS33"/>
            <Button Rect_x_y_w_h="30,10,10,10" InputPinSet="W17" FPGAPinIOStandard="LVCMOS33"/>
            <Button Rect_x_y_w_h="20,10,10,10" InputPinSet="W15" FPGAPinIOStandard="LVCMOS33"/>
            <Led Rect_x_y_w_h="100,10,10,10" OutputPinSet="U16" FPGAPinIOStandard="LVCMOS33"/>
            <Button Rect_x_y_w_h="120,10,10,10" InputPinSet="T18" FPGAPinIOStandard="LVCMOS33"/>
            <DIPSwitch Rect_x_y_w_h="140,10,40,10" InputPinSet="A1,A2,A3,A4" FPGAPinIOStandard="LVCMOS33"/>
        """)
        val switches = board.resources.take(5)
        val review = FpgaCircuitRules.reviewForFpga(busProject, HdlLanguage.VHDL)
        val design = checkNotNull(review.design)
        val port = design.ports.single { it.name == "p_sw" }
        // The run stops at the first resource of another type: the LED ends the five switches.
        assertEquals(switches, fpgaResourceRun(board, switches[0], FpgaPinDirection.INPUT))
        assertEquals(switches.drop(2), fpgaResourceRun(board, switches[2], FpgaPinDirection.INPUT))
        // A multi-pin resource has its own whole-port mapping, and an output never runs over input switches.
        assertEquals(emptyList(), fpgaResourceRun(board, board.resources[7], FpgaPinDirection.INPUT))
        assertEquals(emptyList(), fpgaResourceRun(board, switches[0], FpgaPinDirection.OUTPUT))
        val run = checkNotNull(fpgaMapPortRun(emptyMap(), port, 0, fpgaResourceRun(board, switches[0], FpgaPinDirection.INPUT)))
        val manual = (0 until 4).fold(emptyMap<FpgaSignalBit, FpgaPinBinding>()) { bindings, bit ->
            bindings + (FpgaSignalBit("p_sw", bit) to FpgaPinBinding.Physical(switches[bit].pins.single().location, switches[bit].id))
        }
        assertEquals(manual, run)
        val codec = checkNotNull(review.mappingCodec(busProject.mainCircuit))
        val saved = assertIs<FpgaMappingResult.Saved>(codec.save(board, FpgaFlow.VIVADO, FpgaMapping(run, emptyMap())))
        val savedManual = assertIs<FpgaMappingResult.Saved>(codec.save(board, FpgaFlow.VIVADO, FpgaMapping(manual, emptyMap())))
        assertEquals(CircCodec.writeProject(busProject.withCircuit(savedManual.document)),
            CircCodec.writeProject(busProject.withCircuit(saved.document)), "Byte-identical boardmap")
        // From bit 2 on the third switch: bits 2 and 3 take W16 and W17, bits 0 and 1 keep their bindings.
        val kept = mapOf(FpgaSignalBit("p_sw", 0) to FpgaPinBinding.Physical("T18"))
        val tail = checkNotNull(fpgaMapPortRun(kept, port, 2, fpgaResourceRun(board, switches[2], FpgaPinDirection.INPUT)))
        assertEquals(listOf("T18", null, "W16", "W17"), (0 until 4).map { (tail[FpgaSignalBit("p_sw", it)] as? FpgaPinBinding.Physical)?.location })
        // A run shorter than the rest of the port maps the bits it can: two switches from the fourth one.
        val short = checkNotNull(fpgaMapPortRun(emptyMap(), port, 0, fpgaResourceRun(board, switches[3], FpgaPinDirection.INPUT)))
        assertEquals(listOf("W17", "W15", null, null), (0 until 4).map { (short[FpgaSignalBit("p_sw", it)] as? FpgaPinBinding.Physical)?.location })
        assertNull(fpgaMapPortRun(emptyMap(), port, 4, switches))
        assertNull(fpgaMapPortRun(emptyMap(), port, 0, emptyList()))

        // The next unmapped bit after a choice, wrapping to the start; none once every bit is mapped.
        assertEquals(1, fpgaNextUnmappedBit(port, 0, mapOf(FpgaSignalBit("p_sw", 0) to FpgaPinBinding.Physical("V17"))))
        assertEquals(0, fpgaNextUnmappedBit(port, 3, mapOf(FpgaSignalBit("p_sw", 3) to FpgaPinBinding.Physical("W17"))))
        assertEquals(3, fpgaNextUnmappedBit(port, 1, manual - FpgaSignalBit("p_sw", 3)))
        assertNull(fpgaNextUnmappedBit(port, 2, manual))
    }

    private fun error(code: String, signal: FpgaSignalBit? = null) =
        FpgaDiagnostic(code, FpgaDiagnosticSeverity.ERROR, message("fpga_core_unmapped_port"), signal)

    @Test
    fun readinessCountsClockAndTickBlockersThatTheRulesDoNotReport() {
        val zero = LocalizedText("fpga_mapping_problems", emptyMap(), 0).resolve(AppLanguage.ENGLISH)
        // A physical clock without a frequency: the rules only report the placeholder 0 Hz without naming the port.
        val clock = mapOf("p_clk" to FpgaClockInput(enabled = true, frequency = ""))
        val blocked = fpgaReadiness(false, listOf(error("invalid-clock-frequency")), clock, false, null, true)
        assertFalse(blocked.ready)
        assertEquals(1, blocked.problemCount)
        assertEquals("clock-frequency", blocked.reasons.single().code)
        assertEquals(FpgaSignalBit("p_clk"), blocked.reasons.single().signal)
        val status = LocalizedText("fpga_mapping_problems", emptyMap(), blocked.problemCount.toLong()).resolve(AppLanguage.ENGLISH)
        assertNotEquals(zero, status)
        assertTrue("p_clk" in blocked.reasons.single().text.resolve(AppLanguage.ENGLISH))
        // An invalid derived scale is its own reason.
        val scale = fpgaReadiness(false, emptyList(), mapOf("p_clk" to FpgaClockInput(true, "100", derived = true, multiplier = "x")),
            false, null, true)
        assertEquals(listOf("clock-scale"), scale.reasons.map { it.code })
        // An invalid tick and a board without an oscillator.
        assertEquals(listOf("tick-frequency"), fpgaReadiness(false, emptyList(), emptyMap(), true, null, true).reasons.map { it.code })
        assertEquals(listOf("tick-oscillator"), fpgaReadiness(false, emptyList(), emptyMap(), true, 1.0, false).reasons.map { it.code })
        // Rule errors count one by one; warnings never block.
        val rules = fpgaReadiness(false, listOf(error("unmapped-port", FpgaSignalBit("p_a")),
            FpgaDiagnostic("unused-pin-pull-not-applied", FpgaDiagnosticSeverity.WARNING, message("fpga_core_unmapped_port"))),
            emptyMap(), false, null, true)
        assertEquals(1, rules.problemCount)
        // A clean mapping is ready; a loading catalog is not, without being a problem.
        assertTrue(fpgaReadiness(false, emptyList(), mapOf("p_a" to FpgaClockInput()), true, 1.0, true).ready)
        val loading = fpgaReadiness(true, emptyList(), emptyMap(), false, null, true)
        assertFalse(loading.ready)
        assertEquals(0, loading.problemCount)
    }

    @Test
    fun multiBitPortsAreOneCollapsibleGroupWithStableKeys() {
        val ports = listOf(FpgaDesignPort("p_a", FpgaPinDirection.INPUT), FpgaDesignPort("p_sw", FpgaPinDirection.INPUT, 16))
        val collapsed = fpgaRows(null, false, true, ports, emptySet())
        assertEquals(listOf("header", "board", "actions", "sig-p_a-0", "port-p_sw", "problems", "footer"), collapsed.map { it.key })
        val expanded = fpgaRows(null, false, true, ports, setOf("p_sw"))
        assertEquals(16, expanded.count { it is FpgaRow.Signal && it.port.name == "p_sw" })
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
        assertEquals(expanded.indexOfFirst { it.key == "port-p_sw" } + 10, expanded.indexOfFirst { it.key == "sig-p_sw-9" })
        // Duplicate port names never produce duplicate keys.
        val duplicated = fpgaRows(null, false, true, ports + ports, setOf("p_sw"))
        assertEquals(duplicated.size, duplicated.map { it.key }.toSet().size)
    }

    @Test
    fun previewCoordinatesRoundTripAndDragsNormalise() {
        // 358 dp of a 390 dp phone at density 2.625: the round trip stays within one picture pixel.
        val viewWidth = 358f * 2.625f
        val viewHeight = viewWidth * 400 / 740
        for ((x, y) in listOf(0 to 0, 649 to 295, 739 to 399, 370 to 200)) {
            val (vx, vy) = FpgaBoardGeometry.toView(x.toFloat(), y.toFloat(), viewWidth, viewHeight, 740, 400)
            val (px, py) = FpgaBoardGeometry.toPicture(vx, vy, viewWidth, viewHeight, 740, 400)
            assertTrue(abs(px - x) <= 1 && abs(py - y) <= 1, "$x,$y -> $px,$py")
        }
        // A drag up and to the left still gives a positive width and height.
        assertEquals(FpgaRectangle(100, 50, 40, 30), FpgaBoardGeometry.fromDrag(140f, 80f, 100f, 50f, 740, 400))
        assertEquals(FpgaRectangle(100, 50, 2, 2), FpgaBoardGeometry.fromDrag(100f, 50f, 100.4f, 50.2f, 740, 400))
        assertEquals(FpgaRectangle(700, 380, 40, 20), FpgaBoardGeometry.fromDrag(700f, 380f, 900f, 500f, 740, 400))
        // Moves stay inside the picture; resizing keeps the opposite corner and Evolution's 2 px minimum.
        assertEquals(FpgaRectangle(726, 295, 14, 14), FpgaBoardGeometry.moved(u16, 100, 0, 740, 400))
        assertEquals(FpgaRectangle(649, 295, 2, 2),
            FpgaBoardGeometry.resized(u16, FpgaBoardGeometry.Corner.BOTTOM_RIGHT, -50, -50, 740, 400))
        assertEquals(FpgaRectangle(639, 285, 24, 24),
            FpgaBoardGeometry.resized(u16, FpgaBoardGeometry.Corner.TOP_LEFT, -10, -10, 740, 400))
        // The topmost rectangle is hit, and a corner is found within the handle radius.
        assertEquals(1, FpgaBoardGeometry.hit(655f, 300f, listOf(FpgaRectangle(600, 280, 100, 100), u16)))
        assertEquals(FpgaBoardGeometry.Corner.BOTTOM_RIGHT, FpgaBoardGeometry.corner(u16, 665f, 311f, 5f))
    }

    @Test
    fun overlapsAreFoundButBuiltInStyleBoardsStaySavable() {
        val rectangles = listOf(e19, u16, FpgaRectangle(655, 300, 30, 30), null)
        assertEquals(listOf(1), FpgaBoardGeometry.overlapping(FpgaRectangle(650, 290, 5, 10), rectangles, except = 2))
        assertEquals(listOf(1), FpgaBoardGeometry.overlapping(rectangles[2]!!, rectangles, except = 2))
        assertTrue(FpgaBoardGeometry.overlapping(e19, rectangles, except = 0).isEmpty())
        val spot = FpgaBoardGeometry.freeSpot(rectangles, 20, 20)
        assertTrue(rectangles.none { it != null && FpgaBoardGeometry.overlaps(spot, it) })
    }

    @Test
    fun clockFrequencyUnitsConvertToTheSameWholeHertz() {
        assertEquals(100_000_000L, fpgaFrequencyHz("100", FpgaFrequencyUnit.MHZ))
        assertEquals(100_000_000L, fpgaFrequencyHz("100000000", FpgaFrequencyUnit.HZ))
        assertEquals(12_500_000L, fpgaFrequencyHz("12,5", FpgaFrequencyUnit.MHZ))
        assertEquals(12_500_000L, fpgaFrequencyHz("12.5", FpgaFrequencyUnit.MHZ))
        assertEquals(32_768L, fpgaFrequencyHz("32.768", FpgaFrequencyUnit.KHZ))
        assertNull(fpgaFrequencyHz("0.0000001", FpgaFrequencyUnit.MHZ), "A fraction of a hertz is refused")
        assertNull(fpgaFrequencyHz("1.5", FpgaFrequencyUnit.HZ))
        assertNull(fpgaFrequencyHz("-5", FpgaFrequencyUnit.MHZ))
        assertNull(fpgaFrequencyHz("", FpgaFrequencyUnit.MHZ))
        assertEquals(FpgaFrequencyUnit.MHZ, FpgaFrequencyUnit.of(50_000_000))
        assertEquals("50", fpgaFrequencyText(50_000_000, FpgaFrequencyUnit.MHZ))
        assertEquals(FpgaFrequencyUnit.KHZ, FpgaFrequencyUnit.of(12_288_000))
        assertEquals(FpgaFrequencyUnit.HZ, FpgaFrequencyUnit.of(12_345))
        assertEquals("12.5", fpgaFrequencyText(12_500_000, FpgaFrequencyUnit.MHZ))
        // The draft shows a loaded 50 MHz clock as "50" MHz, and both units write the same Frequency attribute.
        val loaded = FpgaBoardEditorDraft.from(board("""<Led Rect_x_y_w_h="10,10,10,10" OutputPinSet="U16"/>""")
            .let { it.copy(clock = it.clock!!.copy(frequencyHz = 50_000_000)) })
        assertEquals("50", loaded.fields[FpgaBoardField.FREQUENCY])
        assertEquals(FpgaFrequencyUnit.MHZ, loaded.frequencyUnit)
        val inMhz = loaded.copy(fields = loaded.fields + (FpgaBoardField.FREQUENCY to "100"))
        val inHz = loaded.copy(fields = loaded.fields + (FpgaBoardField.FREQUENCY to "100000000"), frequencyUnit = FpgaFrequencyUnit.HZ)
        val picture = checkNotNull(FpgaBoardPicture.read("""<X><BoardPicture><PictureDimension Width="740" Height="400"/>""" +
            """<CompressionCodeTable TableData="${pictureTable()}"/><PixelData PixelRGB="@a"/></BoardPicture></X>"""))
        val xmlMhz = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(inMhz.board(), picture)).board.originalXml!!
        val xmlHz = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(inHz.board(), picture)).board.originalXml!!
        assertTrue("""Frequency="100000000"""" in xmlMhz)
        assertEquals(xmlHz, xmlMhz)
    }

    private fun pictureTable(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()"
        return listOf("", "+", "-", "=").flatMap { prefix -> alphabet.map { prefix + it } }.joinToString(" ")
    }

    @Test
    fun numericFieldsUseNumericKeyboards() {
        assertEquals(KeyboardType.Number, FpgaBoardField.JTAG.keyboardType())
        assertEquals(KeyboardType.Number, FpgaBoardField.FLASH_POSITION.keyboardType())
        // Decimal: "12.5" MHz needs a separator; the stored value is still whole hertz.
        assertEquals(KeyboardType.Decimal, FpgaBoardField.FREQUENCY.keyboardType())
        assertEquals(KeyboardType.Number, FpgaResourceField.RECTANGLE.keyboardType())
        for (field in FpgaBoardField.entries - setOf(FpgaBoardField.JTAG, FpgaBoardField.FLASH_POSITION, FpgaBoardField.FREQUENCY)) {
            assertEquals(KeyboardType.Text, field.keyboardType(), field.name)
        }
    }

    @Test
    fun pinSchemaGivesLabelledFieldsPerType() {
        val seven = FpgaResourceEditorDraft().withType("SevenSegment")
        val outputs = assertIs<FpgaPinLayout.PerPin>(seven.pinLayout(FpgaResourceField.OUTPUT))
        assertEquals(8, outputs.count)
        assertEquals(listOf("Segment A", "Segment B", "Segment C", "Segment D", "Segment E", "Segment F", "Segment G", "Decimal point"),
            (0 until 8).map { outputs.label(it, 8).resolve(AppLanguage.ENGLISH) })
        assertEquals(FpgaPinLayout.Hidden, seven.pinLayout(FpgaResourceField.INPUT))
        assertEquals(FpgaPinLayout.Hidden, seven.pinLayout(FpgaResourceField.INOUT))
        val button = FpgaResourceEditorDraft().withType("Button")
        assertEquals(1, assertIs<FpgaPinLayout.PerPin>(button.pinLayout(FpgaResourceField.INPUT)).count)
        assertFalse(button.showsDrive)
        assertTrue(button.showsPull)
        val led = FpgaResourceEditorDraft().withType("Led")
        assertFalse(led.showsPull)
        assertTrue(led.showsActivity)
        assertFalse(FpgaResourceEditorDraft().withType("PortIo").showsActivity)
        val dip = FpgaResourceEditorDraft().withType("DIPSwitch")
        val switches = assertIs<FpgaPinLayout.PerPin>(dip.pinLayout(FpgaResourceField.INPUT))
        assertTrue(switches.configurable)
        assertEquals("Switch 3", switches.label(2, 8).resolve(AppLanguage.ENGLISH))
        assertEquals(12, assertIs<FpgaPinLayout.PerPin>(dip.withPinCount(FpgaResourceField.INPUT, 12).pinLayout(FpgaResourceField.INPUT)).count)
        // A pin typed in the third field keeps its position; the list is joined with commas as the XML stores it.
        val typed = seven.withPin(FpgaResourceField.OUTPUT, 2, 8, " C1 ")
        assertEquals(",,C1,,,,,", typed.fields[FpgaResourceField.OUTPUT])
        assertEquals(listOf("C1"), typed.locations(FpgaResourceField.OUTPUT))
        val full = (0 until 8).fold(seven) { draft, index -> draft.withPin(FpgaResourceField.OUTPUT, index, 8, "S$index") }
        assertEquals("S0,S1,S2,S3,S4,S5,S6,S7", full.fields[FpgaResourceField.OUTPUT])
        // A list longer than the type takes falls back to a free list instead of dropping pins.
        val tooMany = seven.copy(fields = seven.fields + (FpgaResourceField.OUTPUT to (0 until 9).joinToString(",") { "S$it" }))
        assertEquals(FpgaPinLayout.FreeList(8), tooMany.pinLayout(FpgaResourceField.OUTPUT))
        assertEquals(8, tooMany.truncatedPins(FpgaResourceField.OUTPUT, 8).locations(FpgaResourceField.OUTPUT).size)
        // Pins in a direction the type does not use keep the whole resource as free lists (a custom board).
        val pinWithInput = FpgaResourceEditorDraft("Pin", mapOf(FpgaResourceField.INPUT to "V17"))
        assertEquals(FpgaPinLayout.FreeList(null), pinWithInput.pinLayout(FpgaResourceField.INPUT))
        assertEquals(FpgaPinLayout.FreeList(1), pinWithInput.pinLayout(FpgaResourceField.INOUT))
    }

    @Test
    fun validationNamesTheResourceAndFieldInsteadOfStoppingAtTheFirst() {
        val resources = (0 until 5).map { index ->
            FpgaResourceEditorDraft("Led", mapOf(FpgaResourceField.LABEL to "led$index",
                FpgaResourceField.RECTANGLE to "${10 + index * 20},10,10,10", FpgaResourceField.OUTPUT to "P$index"))
        }
        val base = FpgaBoardEditorDraft.from(null).let {
            it.copy(fields = it.fields + (FpgaBoardField.CLOCK_PIN to "W5") + (FpgaBoardField.FREQUENCY to "100"), resources = resources)
        }
        assertEquals(emptyList(), base.validate())
        val bad = base.copy(resources = resources.mapIndexed { index, resource ->
            when (index) {
                3 -> resource.copy(fields = resource.fields + (FpgaResourceField.DRIVE to "7"))
                4 -> resource.copy(fields = resource.fields + (FpgaResourceField.RECTANGLE to "730,10,50,10"))
                else -> resource
            }
        })
        val errors = bad.validate()
        assertEquals(listOf(FpgaResourceField.DRIVE to 3, FpgaResourceField.RECTANGLE to 4), errors.map { it.field to it.resourceIndex })
        val named = message("fpga_editor_resource_error", "resource" to bad.resources[3].menuName(3), "detail" to errors[0].text)
        assertEquals("4. led3: Choose one of the listed values.", named.resolve(AppLanguage.ENGLISH))
        // A missing value is marked only after Save; a duplicate pin is named.
        val blank = base.copy(fields = base.fields + (FpgaBoardField.CLOCK_PIN to ""), resources = resources.take(2).map {
            it.copy(fields = it.fields + (FpgaResourceField.OUTPUT to "P0"))
        })
        val found = blank.validate()
        assertTrue(found.single { it.field == FpgaBoardField.CLOCK_PIN }.blank)
        assertEquals(0, found.count { it.field == FpgaResourceField.OUTPUT }, "Two resources may share a location, as the writer allows")
        val duplicate = FpgaResourceEditorDraft("DIPSwitch", mapOf(FpgaResourceField.RECTANGLE to "10,10,10,10", FpgaResourceField.INPUT to "A1,a1"))
            .validate(0, 740, 400).single()
        assertEquals(FpgaResourceField.INPUT to 1, duplicate.field to duplicate.pin)
        assertEquals("Pin a1 is already used in this resource.", duplicate.text.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun identifiersHaveDisplayNamesInEveryPublishedLanguage() {
        val texts = FpgaVendor.entries.map(FpgaLabels::vendor) + FpgaBoardWriter.resourceTypes.map(FpgaLabels::resourceType) +
            CompanionJobState.entries.map(FpgaLabels::jobState) +
            listOf("fpga-synthesize", "fpga-ise-build", "fpga-ise-cpld-build", "fpga-quartus-build", "fpga-program").map(FpgaLabels::operation) +
            listOf("openfpgaloader", "ise", "ise-cpld", "quartus", "quartus-cpld").map(FpgaLabels::transport)
        for (text in texts) {
            assertTrue(text.key.isNotEmpty(), "No key for $text")
            for (language in AppLanguage.entries) assertNotEquals(text.key, text.resolve(language), "${text.key} in $language")
        }
        assertEquals("FUTURE-VENDOR", FpgaLabels.operation("FUTURE-VENDOR").resolve(AppLanguage.PORTUGUESE))
        // The type a localized label names is stored with Evolution's element name.
        assertEquals("DIPSwitch", FpgaBoardWriter.resourceTypes.single { FpgaLabels.resourceType(it).resolve(AppLanguage.ENGLISH) == "DIP switch" })
        assertEquals("DIPSwitch", FpgaResourceEditorDraft().withType("DIPSwitch").type)
        assertEquals("LED", FpgaLabels.resourceType("LED").resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun companionFailuresReadAsLocalizedGuidanceWithTheirDetailsKept() {
        val flash = fpgaFailureNotice(CompanionProtocolException("fpga-flash-not-allowed", "The host no longer approves flash for this board and cable."))
        assertEquals("fpga_failure_flash_not_allowed", flash.text.key)
        val portuguese = flash.text.resolve(AppLanguage.PORTUGUESE)
        assertFalse("host" in portuguese || "approves" in portuguese, portuguese)
        assertEquals("fpga-flash-not-allowed: The host no longer approves flash for this board and cable.", flash.detail)
        assertEquals("fpga_companion_transport_error", fpgaFailureNotice(CompanionTransportException("timeout")).text.key)
        assertEquals("fpga_companion_transport_error", fpgaCompanionFailure(CompanionTransportException("timeout")).key)
        val unknown = fpgaFailureNotice(CompanionProtocolException("new-code", "Something new."))
        assertEquals("fpga_companion_error_detail", unknown.text.key)
        assertEquals("fpga_program_target_unavailable_now",
            fpgaFailureNotice(LocalizedArgumentException(message("fpga_program_target_unavailable_now"))).text.key)
        for (code in listOf("fpga-flash-not-allowed", "invalid-plan", "invalid-fpga-job", "invalid-fpga-result", "invalid-artifact",
            "confirmation-required", "fpga-part-mismatch", "invalid-response")) {
            val text = fpgaFailureNotice(CompanionProtocolException(code, "English detail.")).text
            assertTrue(text.key.startsWith("fpga_failure_"), code)
            assertNotEquals(text.key, text.resolve(AppLanguage.PORTUGUESE))
        }
    }

    @Test
    fun recentCompanionHostsRoundTripWithoutTheToken() {
        val preferences = EditorPreferences().withCompanionRecentHost("https://mac.local:8042")
            .withCompanionRecentHost("https://lab.example:8443").withCompanionRecentHost("https://mac.local:8042")
            .withCompanionRecentHost("http://127.0.0.1:8042").withCompanionRecentHost("https://other:1")
        assertEquals(listOf("https://other:1", "http://127.0.0.1:8042", "https://mac.local:8042"), preferences.companionRecentHosts)
        val encoded = EditorPreferencesCodec.write(preferences)
        val loaded = EditorPreferencesCodec.read(encoded).preferences
        assertEquals(preferences.companionRecentHosts, loaded.companionRecentHosts)
        assertFalse("token" in encoded.lowercase())
        assertTrue(EditorPreferences().companionRecentHosts.isEmpty())
    }
}
