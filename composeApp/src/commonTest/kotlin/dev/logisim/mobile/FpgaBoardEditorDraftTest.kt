package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaBoardEditorDraftTest {
    /** A stored picture in Evolution's encoding; the writer copies it and nothing decodes it here. */
    private val picture = run {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()"
        val table = listOf("", "+", "-", "=").flatMap { prefix -> alphabet.map { prefix + it } }.joinToString(" ")
        checkNotNull(FpgaBoardPicture.read("""<X><BoardPicture><PictureDimension Width="740" Height="400"/>""" +
            """<CompressionCodeTable TableData="$table"/><PixelData PixelRGB="@a"/></BoardPicture></X>"""))
    }

    private fun boardXml(resource: String) = """
        <FIXTURE>
          <BoardInformation>
            <ClockInformation FPGApin="W5" Frequency="100000000"/>
            <FPGAInformation Family="Artix-7" Part="xc7a35t" Package="cpg236" Speedgrade="-1" Vendor="VIVADO"/>
          </BoardInformation>
          <IOComponents>
            $resource
          </IOComponents>
        </FIXTURE>
    """.trimIndent()

    private fun draftOf(resource: String) = FpgaBoardEditorDraft.from(
        assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(boardXml(resource))).board)

    private fun write(draft: FpgaBoardEditorDraft) = FpgaBoardWriter.write(draft.board(), picture)

    private fun FpgaResourceEditorDraft.with(field: FpgaResourceField, value: String) = copy(fields = fields + (field to value))

    private fun withResource(draft: FpgaBoardEditorDraft, resource: FpgaResourceEditorDraft) = draft.copy(resources = listOf(resource))

    @Test
    fun choiceListsAreTheSetsTheBoardReaderAccepts() {
        val base = draftOf("""<Led Rect_x_y_w_h="10,10,10,10" OutputPinSet="U16"/>""")
        val led = base.resources.single()
        for (option in FpgaBoardChoices.ioStandard("")) {
            val saved = assertIs<FpgaBoardImportResult.Success>(write(withResource(base, led.with(FpgaResourceField.STANDARD, option.value))))
            assertEquals(option.value.ifEmpty { null }, saved.board.pins.single().electrical.ioStandard)
        }
        assertEquals(listOf("", "LVCMOS12", "LVCMOS15", "LVCMOS18", "LVCMOS25", "LVCMOS33", "LVTTL"),
            FpgaBoardChoices.ioStandard("").map { it.value })
        for (option in FpgaBoardChoices.drive("")) {
            val saved = assertIs<FpgaBoardImportResult.Success>(write(withResource(base, led.with(FpgaResourceField.DRIVE, option.value))))
            assertEquals(option.value.toIntOrNull(), saved.board.pins.single().electrical.driveMilliamps)
        }
        assertEquals(listOf("Default", "2 mA", "4 mA", "8 mA", "16 mA", "24 mA"),
            FpgaBoardChoices.drive("").map { it.title.resolve(AppLanguage.ENGLISH) })
        // A drive the reader rejects is caught in the draft, not only at Save.
        assertFailsWith<LocalizedArgumentException> { withResource(base, led.with(FpgaResourceField.DRIVE, "12")).board() }
        val dip = draftOf("""<DIPSwitch Rect_x_y_w_h="10,10,40,10" InputPinSet="A1,A2"/>""")
        for (option in FpgaBoardChoices.rotation("DIPSwitch", "")) {
            val resource = dip.resources.single().with(FpgaResourceField.ROTATION, option.value)
            val saved = assertIs<FpgaBoardImportResult.Success>(write(withResource(dip, resource)))
            assertEquals(option.value.ifEmpty { null }, saved.board.resources.single().attributes["rotation"])
        }
        val array = draftOf("""<LedArray Rect_x_y_w_h="10,10,40,40" OutputPinSet="A1" LedArrayInfo="1,1,LedDefault"/>""")
        for ((mode, _) in FpgaBoardChoices.ledArrayModes) {
            val resource = array.resources.single().with(FpgaResourceField.DISPLAY, "1,1,$mode")
                .with(FpgaResourceField.OUTPUT, List(checkNotNull(dev.logisim.mobile.core.fpgaDisplayPinCount("LedArray", "1,1,$mode"))) { "P$it" }.joinToString(","))
            assertIs<FpgaBoardImportResult.Success>(write(withResource(array, resource)), mode)
        }
        val scanning = draftOf("""<SevenSegmentScanning Rect_x_y_w_h="10,10,40,40" OutputPinSet="A1" ScanningSevenSegInfo="2,-1,SevenSegScanningActiveLow"/>""")
        for ((mode, _) in FpgaBoardChoices.scanningModes) {
            val settings = FpgaDisplaySettings.parse("2,-1,SevenSegScanningActiveLow").withMode(mode, true)
            val count = checkNotNull(dev.logisim.mobile.core.fpgaDisplayPinCount("SevenSegmentScanning", settings.encoded))
            val resource = scanning.resources.single().with(FpgaResourceField.DISPLAY, settings.encoded)
                .with(FpgaResourceField.OUTPUT, List(count) { "P$it" }.joinToString(","))
            assertIs<FpgaBoardImportResult.Success>(write(withResource(scanning, resource)), mode)
        }
    }

    @Test
    fun rotationUsesEvolutionsSignConventionAndKeepsAnExplicitZero() {
        val options = FpgaBoardChoices.rotation("SevenSegment", "")
        assertEquals(listOf("" to "Segment a top", "-90" to "Segment a right", "90" to "Segment a left", "180" to "Segment a bottom"),
            options.map { it.value to it.title.resolve(AppLanguage.ENGLISH) })
        assertEquals(listOf("Switch 1 left", "Switch 1 top", "Switch 1 bottom", "Switch 1 right"),
            FpgaBoardChoices.rotation("DIPSwitch", "").map { it.title.resolve(AppLanguage.ENGLISH) })
        assertEquals("0", FpgaBoardChoices.rotation("DIPSwitch", "0").first().value)
        val odd = FpgaBoardChoices.rotation("DIPSwitch", "45").last()
        assertTrue(odd.fromFile)
        assertEquals("45 (from file)", odd.title.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun rotationAndDriveShowOnlyWhereEvolutionShowsThemButSurviveATypeChange() {
        val dip = FpgaResourceEditorDraft("DIPSwitch").with(FpgaResourceField.ROTATION, "-90")
        assertTrue(dip.showsRotation)
        assertFalse(dip.showsDrive)
        val button = dip.withType("Button")
        assertFalse(button.showsRotation)
        assertEquals("-90", button.fields[FpgaResourceField.ROTATION])
        assertEquals("-90", button.withType("DIPSwitch").fields[FpgaResourceField.ROTATION])
        // As in FpgaIoInformationContainer, only a type with a rotation writes it: the hidden value is not saved.
        assertEquals(null, button.resource("resource-0").attributes["rotation"])
        assertEquals("-90", button.withType("DIPSwitch").resource("resource-0").attributes["rotation"])
        val written = assertIs<FpgaBoardImportResult.Success>(write(draftOf(
            """<DIPSwitch Rect_x_y_w_h="10,10,40,10" InputPinSet="A1" rotation="-90"/>""").let { draft ->
            draft.copy(resources = draft.resources.map { it.withType("Button") })
        }))
        assertFalse("rotation" in checkNotNull(written.board.originalXml), "A Button never carries a rotation")
        assertTrue(FpgaResourceEditorDraft("Led").showsDrive)
        assertTrue(FpgaResourceEditorDraft("Button").with(FpgaResourceField.DRIVE, "8").showsDrive, "A loaded value stays visible")
        assertFalse(FpgaResourceEditorDraft("Pin").showsRotation)
    }

    @Test
    fun structuredDisplaySettingsWriteEvolutionsAttribute() {
        val array = FpgaResourceEditorDraft().withType("LedArray")
        assertEquals("8,8,LedDefault", array.fields[FpgaResourceField.DISPLAY])
        val settings = FpgaDisplaySettings.parse(array.fields.getValue(FpgaResourceField.DISPLAY))
            .withRows("8", false).copy(columns = "8").withMode("LedRowScanning", false)
        assertEquals("8,8,LedRowScanning", settings.encoded)

        val scanning = FpgaDisplaySettings.parse("4,-1,SevenSegScanningActiveLow")
        val decoded = scanning.withMode("SevenSegDecoded", true)
        assertEquals("4,2,SevenSegDecoded", decoded.encoded, "Four digits need two address bits")
        assertEquals("6,3,SevenSegDecoded", decoded.withRows("6", true).encoded)
        // The address bits menu follows updateScanningRequirements: fewer bits than the digits need are raised.
        val eight = decoded.withRows("8", true)
        assertEquals("8,3,SevenSegDecoded", eight.withColumns("1", true).encoded)
        assertEquals("8,4,SevenSegDecoded", eight.withColumns("4", true).encoded)
        assertEquals("8,1,LedDefault", FpgaDisplaySettings.parse("8,8,LedDefault").withColumns("1", false).encoded,
            "An LED array's columns have no minimum")
        assertEquals("6,-1,SevenSegScanningActiveHi", decoded.withRows("6", true).withMode("SevenSegScanningActiveHi", true).encoded)
        // A type change between display kinds drops the other kind's configuration.
        assertEquals("4,-1,SevenSegScanningActiveLow",
            array.withType("SevenSegmentScanning").fields[FpgaResourceField.DISPLAY])
        assertEquals(null, array.withType("Led").fields[FpgaResourceField.DISPLAY])
    }

    @Test
    fun loadedValuesOutsideTheMenusArePreserved() {
        val rows = FpgaBoardChoices.numbers(FpgaBoardChoices.arraySizes, "64")
        assertEquals((1..32).map(Int::toString) + "64", rows.map { it.value })
        assertTrue(rows.last().fromFile)
        assertEquals(32, FpgaBoardChoices.numbers(FpgaBoardChoices.arraySizes, "8").size)
        val modes = FpgaBoardChoices.modes(FpgaBoardChoices.scanningModes, "SevenSegFuture")
        assertEquals("SevenSegFuture (from file)", modes.last().title.resolve(AppLanguage.ENGLISH))
        val settings = FpgaDisplaySettings.parse(" 64 , 2 , RgbColScanning ")
        assertEquals("64,2,RgbColScanning", settings.encoded)
        assertEquals("64,4,RgbColScanning", settings.copy(columns = "4").encoded)
    }

    @Test
    fun displayPinsMustMatchTheConfiguration() {
        val resource = FpgaResourceEditorDraft().withType("LedArray")
            .with(FpgaResourceField.DISPLAY, "8,8,LedRowScanning")
            .with(FpgaResourceField.OUTPUT, List(10) { "P$it" }.joinToString(","))
        assertEquals(11, resource.requiredDisplayPins)
        val error = assertFailsWith<LocalizedArgumentException> { resource.resource("resource-0") }
        assertEquals("LedArray needs 11 output pins for its display configuration.", error.text.resolve(AppLanguage.ENGLISH))
        val fixed = resource.with(FpgaResourceField.OUTPUT, List(11) { "P$it" }.joinToString(","))
        assertEquals(11, fixed.resource("resource-0").pins.count { it.direction == FpgaPinDirection.OUTPUT })
        assertEquals(12, FpgaResourceEditorDraft().withType("SevenSegmentScanning").requiredDisplayPins)
        // The editor's error mark and Save make the same check: a stray comma adds no pin, other directions do not fit.
        val trailing = resource.with(FpgaResourceField.OUTPUT, List(11) { "P$it" }.joinToString(",") + ",")
        assertEquals(true, trailing.displayPinsMatch)
        assertEquals(11, trailing.resource("resource-0").pins.size)
        assertEquals(false, resource.displayPinsMatch)
        val withInput = fixed.with(FpgaResourceField.INPUT, "Q1")
        assertEquals(false, withInput.displayPinsMatch, "A display drives output pins only")
        assertFailsWith<LocalizedArgumentException> { withInput.resource("resource-0") }
        assertEquals(null, FpgaResourceEditorDraft("Led").displayPinsMatch)
        assertEquals(360, FpgaResourceEditorDraft().withType("LedArray").with(FpgaResourceField.DISPLAY, "10,12,RgbDefault").requiredDisplayPins)
    }

    @Test
    fun aLoadedBoardWithoutOptionalAttributesKeepsThemAbsent() {
        val draft = draftOf("""<Button Rect_x_y_w_h="10,10,10,10" InputPinSet="V17"/>""")
        val resource = draft.resources.single()
        assertEquals("", resource.fields[FpgaResourceField.STANDARD])
        assertEquals("Default", FpgaBoardChoices.ioStandard("").first().title.resolve(AppLanguage.ENGLISH))
        val xml = checkNotNull(assertIs<FpgaBoardImportResult.Success>(write(draft)).board.originalXml)
        val button = xml.lineSequence().single { it.startsWith("<Button") }
        assertFalse("FPGAPinIOStandard" in button || "FPGAPinDriveStrength" in button || "rotation" in button, button)
    }
}
