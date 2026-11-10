package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FpgaBoardWriterTest {
    @Test
    fun editsBasys3ResourcesAndPinsAndKeepsItsPicture() {
        val imported = basys3()
        val image = assertNotNull(FpgaBoardPicture.read(requireNotNull(imported.originalXml)))
        val resources = imported.resources.toMutableList()
        val button = resources.first()
        resources[0] = button.copy(rectangle = FpgaRectangle(640, 320, 26, 52), label = "Start & reset",
            pins = listOf(button.pins.single().copy(location = "V18")))
        val edited = imported.copy(resources = resources, clock = imported.clock!!.copy(location = " W6"))

        val result = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(edited, image))
        val xml = requireNotNull(result.board.originalXml)
        assertEquals("W6", result.board.clock?.location)
        assertEquals("Start & reset", result.board.resources.first().label)
        assertEquals(FpgaRectangle(640, 320, 26, 52), result.board.resources.first().rectangle)
        assertEquals("V18", result.board.resources.first().pins.single().location)
        assertEquals(imported.pins.drop(1), result.board.pins.drop(1))
        val bytes = assertNotNull(FpgaBoardPicture.read(xml)).imageBytes()
        assertContentEquals(image.imageBytes(), bytes)
        val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(740, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun keepsUndeclaredPullAndActivityAbsentLikeEvolution() {
        val imported = basys3()
        val image = assertNotNull(FpgaBoardPicture.read(requireNotNull(imported.originalXml)))
        val resources = imported.resources.toMutableList()
        val edited = resources[1]
        resources[1] = edited.copy(pins = edited.pins.map { it.copy(electrical = it.electrical.copy(pull = FpgaPull.UP, activeLow = true)) })
        resources += FpgaBoardResource("new", "Led", "New", FpgaRectangle(0, 0, 10, 10),
            listOf(FpgaBoardPin("new", 0, "J2", FpgaPinDirection.OUTPUT, FpgaElectrical("LVCMOS33"))))

        val written = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(imported.copy(resources = resources), image)).board
        val elements = Regex("<(Button|Led|Pin|DIPSwitch|SevenSegment)\\b[^>]*>").findAll(requireNotNull(written.originalXml))
            .map { it.value }.toList()
        assertTrue(elements.first().let { "PullBehavior" !in it && "ActivityLevel" !in it }, elements.first())
        assertTrue("FPGAPinPullBehavior=\"Pull Up\"" in elements[1] && "ActivityLevel=\"Active low\"" in elements[1], elements[1])
        assertTrue("FPGAPinPullBehavior=\"Float\"" in elements.last() && "ActivityLevel=\"Active high\"" in elements.last(), elements.last())
        fun pins(list: List<FpgaBoardResource>) = list.map { resource -> resource.pins.map { it.location to it.electrical } }
        assertEquals(pins(resources), pins(written.resources))
    }

    @Test
    fun createsBoardWithReplacementPicture() {
        val pictureBytes = VisualPng.encode(3, 2, IntArray(6) { 0xff008040.toInt() })
        val picture = FpgaBoardPicture.fromEncodedImage(3, 2, pictureBytes)
        val resource = FpgaBoardResource("r", "Led", "Lamp", FpgaRectangle(0, 0, 3, 2),
            listOf(FpgaBoardPin("r", 0, "J2", FpgaPinDirection.OUTPUT, FpgaElectrical("LVCMOS33"))))
        val board = FpgaBoardDefinition("Lab_Board", FpgaDevice(FpgaVendor.VIVADO, "Artix-7", "xc7a35t", "cpg236", "-1"),
            FpgaClock("W5", 100_000_000, FpgaElectrical("LVCMOS33")), listOf(resource))

        val result = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(board, picture)).board
        val reopened = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(requireNotNull(result.originalXml))).board
        assertEquals(board.device, reopened.device)
        assertEquals(board.clock, reopened.clock)
        assertEquals("J2", reopened.resources.single().pins.single().location)
        val bytes = assertNotNull(FpgaBoardPicture.read(requireNotNull(reopened.originalXml))).imageBytes()
        assertContentEquals(pictureBytes, bytes)
        val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(0xff008040.toInt(), bitmap.getRGB(2, 1))
    }

    @Test
    fun retainsScanningDisplaySettings() {
        val board = fixture("GECKO5_EDUCATION")
        val picture = assertNotNull(FpgaBoardPicture.read(requireNotNull(board.originalXml)))
        val edited = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(board, picture)).board
        val display = edited.resources.single { it.type == "SevenSegmentScanning" }
        assertEquals("4,3,SevenSegDecoded", display.attributes["ScanningSevenSegInfo"])
        assertEquals("90", display.attributes["rotation"])
        val array = edited.resources.single { it.type == "LedArray" }
        assertEquals("10,12,RgbColScanning", array.attributes["LedArrayInfo"])
        assertEquals(board.pins, edited.pins)
    }

    @Test
    fun rejectsRectangleOutsideTheImageAndInvalidPins() {
        val board = basys3()
        val picture = assertNotNull(FpgaBoardPicture.read(requireNotNull(board.originalXml)))
        val resource = board.resources.first()
        val outside = board.copy(resources = listOf(resource.copy(rectangle = FpgaRectangle(739, 0, 3, 1))))
        val badPin = board.copy(resources = listOf(resource.copy(pins = listOf(resource.pins.single().copy(location = "A,7")))))

        val bounds = assertIs<FpgaBoardImportResult.Failure>(FpgaBoardWriter.write(outside, picture))
        assertTrue(bounds.diagnosticNotices.any { it.key == "fpga_editor_resource_rectangle" })
        val pin = assertIs<FpgaBoardImportResult.Failure>(FpgaBoardWriter.write(badPin, picture))
        assertTrue(pin.diagnosticNotices.any { it.key == "fpga_editor_resource_pins" })
    }

    private fun basys3() = fixture("BASYS3")

    private fun fixture(name: String): FpgaBoardDefinition {
        val xml = requireNotNull(javaClass.getResourceAsStream("/fpga/$name.xml")).bufferedReader().use { it.readText() }
        return assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml)).board
    }
}
