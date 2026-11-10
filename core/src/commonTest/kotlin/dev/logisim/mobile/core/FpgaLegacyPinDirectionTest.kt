package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FpgaLegacyPinDirectionTest {
    @Test fun importsLegacyAndCurrentSectionsInDesktopOrder() {
        val xml = """
            <Legacy>
                <BoardInformation>
                    <FPGAInformation Vendor="OPENFPGA" Family="ecp5" Part="85k" Package="CABGA381" Speedgrade="5"/>
                </BoardInformation>
                <IOComponents><RGBLED Label="Unwired" Rect_x_y_w_h="15,20,10,12"/></IOComponents>
                <LEDsInformation><LED FPGAPinName="A3" LocationX="30" LocationY="0" Width="10" Height="10"/></LEDsInformation>
                <ButtonsInformation><Button FPGAPinName="A2" LocationX="20" LocationY="0" Width="10" Height="10"/></ButtonsInformation>
                <PinsInformation><Pin FPGAPinName="A1" LocationX="10" LocationY="0" Width="10" Height="10"/></PinsInformation>
            </Legacy>
        """.trimIndent()
        val imported = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml))
        assertEquals(listOf("A1", "A2", "A3"), imported.board.pins.map { it.location })
        assertEquals(
            listOf(FpgaPinDirection.INOUT, FpgaPinDirection.INPUT, FpgaPinDirection.OUTPUT),
            imported.board.pins.map { it.direction },
        )
        val placeholder = imported.board.resources.last()
        assertEquals("RGBLED", placeholder.type)
        assertEquals("Unwired", placeholder.label)
        assertEquals(FpgaRectangle(15, 20, 10, 12), placeholder.rectangle)
        assertEquals(emptyList(), placeholder.pins)
        assertEquals("fpga_core_resource_without_pins", imported.warningNotices.single().key)
        assertEquals(placeholder.id, imported.warningNotices.single().arguments["resource"])
    }

    @Test fun rejectsMalformedPinDeclarationsEvenWithAValidRectangle() {
        val malformed = mapOf(
            "FPGAPinName=''" to "fpga_core_missing_resource_pin",
            "FPGAPin_0='A1'" to "fpga_core_unrecognized_pinout",
            "NrOfPins='1'" to "fpga_core_missing_resource_pin",
            "OutputPinSet='A1,'" to "fpga_core_invalid_pin_list",
        )
        for ((attributes, diagnostic) in malformed) {
            val xml = """
                <Legacy>
                    <BoardInformation>
                        <FPGAInformation Vendor="OPENFPGA" Family="ecp5" Part="85k" Package="CABGA381" Speedgrade="5"/>
                    </BoardInformation>
                    <IOComponents><Pin Rect_x_y_w_h="1,2,3,4" $attributes/></IOComponents>
                </Legacy>
            """.trimIndent()
            val rejected = assertIs<FpgaBoardImportResult.Failure>(FpgaBoardCodec.read(xml), attributes)
            assertEquals(diagnostic, rejected.diagnosticNotices.single().key, attributes)
        }
    }

    @Test fun legacyResourceDirectionMatchesDesktopCaseInsensitiveTypeLookup() {
        for (type in listOf("Led", "LED", "LedArray", "RgbLed", "SevenSegment", "SevenSegmentScanning", "button", "DIPSwitch")) {
            val xml = """<Legacy><BoardInformation><FPGAInformation Vendor="OPENFPGA" Family="ecp5" Part="85k" Package="CABGA381" Speedgrade="5"/></BoardInformation><IOComponents><$type FPGAPinName="C3"/></IOComponents></Legacy>"""
            val imported = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml))
            val expected = if (type.lowercase() in setOf("button", "dipswitch")) FpgaPinDirection.INPUT else FpgaPinDirection.OUTPUT
            assertEquals(expected, imported.board.pins.single().direction, type)
        }
    }
}
