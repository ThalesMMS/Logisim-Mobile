package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaBoardFixtureTest {
    @Test
    fun gecko5RetainsDeviceClockAndProgrammingMetadata() {
        val board = fixture("GECKO5_EDUCATION")

        assertEquals("GECKO5_EDUCATION", board.name)
        assertEquals(
            FpgaDevice(
                vendor = FpgaVendor.OPEN_FPGA,
                family = "epc5",
                part = "85k",
                packageName = "CABGA381",
                speedGrade = "5",
                jtagPosition = 1,
                flashPosition = 2,
                flashName = "W25Q128",
                usbTmc = false,
            ),
            board.device,
        )
        assertEquals(
            FpgaClock("F1", 50_000_000L, FpgaElectrical(ioStandard = "LVCMOS33")),
            board.clock,
        )
        assertEquals(FpgaPull.UP, board.unusedPinPull)
    }

    @Test
    fun gecko5ImportsPhysicalDisplayPinsAndMixedVoltageInputs() {
        val board = fixture("GECKO5_EDUCATION")

        assertEquals(30, board.resources.size)
        assertEquals(80, board.pins.size)
        assertEquals(
            mapOf(FpgaPinDirection.INPUT to 19, FpgaPinDirection.OUTPUT to 45, FpgaPinDirection.INOUT to 16),
            board.pins.groupingBy { it.direction }.eachCount(),
        )

        val display = board.resources.single { it.type == "SevenSegmentScanning" }
        assertEquals("SevenSegDisplay", display.label)
        assertEquals(FpgaRectangle(119, 68, 85, 270), display.rectangle)
        assertEquals("4,3,SevenSegDecoded", display.attributes["ScanningSevenSegInfo"])
        assertEquals("90", display.attributes["rotation"])
        assertEquals(
            listOf("D14", "C13", "E14", "D15", "C15", "E15", "B13", "B15", "C17", "D16", "C16"),
            display.pins.map { it.location },
        )
        assertEquals((0..10).toList(), display.pins.map { it.index })
        assertEquals(setOf(FpgaPinDirection.OUTPUT), display.pins.map { it.direction }.toSet())
        assertEquals(
            setOf(FpgaElectrical(ioStandard = "LVCMOS33", driveMilliamps = 4, activeLow = true)),
            display.pins.map { it.electrical }.toSet(),
        )

        val ledArray = board.resources.single { it.type == "LedArray" }
        assertEquals("10,12,RgbColScanning", ledArray.attributes["LedArrayInfo"])
        assertEquals(
            listOf("B6", "J3", "K3", "B17", "A7", "C6", "D13", "B12", "C11", "C10", "E9", "B9",
                "C8", "E7", "C7", "D6", "E13", "C12", "E11", "A11", "A10", "A9", "D8", "B8",
                "E6", "J4", "D12", "D11", "B11", "B10", "D9", "E8", "A8", "D7"),
            ledArray.pins.map { it.location },
        )
        assertEquals((0..33).toList(), ledArray.pins.map { it.index })

        val switches = board.resources.single { it.type == "DIPSwitch" }
        assertEquals(listOf("D19", "D17", "J16", "H16", "C20", "C18", "D18", "D20"),
            switches.pins.map { it.location })
        assertEquals(setOf(FpgaPinDirection.INPUT), switches.pins.map { it.direction }.toSet())
        assertEquals(
            setOf(FpgaElectrical(ioStandard = "LVCMOS18", pull = FpgaPull.UP, activeLow = true)),
            switches.pins.map { it.electrical }.toSet(),
        )
        assertEquals(FpgaElectrical(ioStandard = "LVCMOS33", pull = FpgaPull.UP, activeLow = true),
            board.pins.single { it.location == "G1" }.electrical)
        val gpio = board.pins.single { it.location == "C3" }
        assertEquals(FpgaPinDirection.INOUT, gpio.direction)
        assertEquals(FpgaElectrical(ioStandard = "LVCMOS33"), gpio.electrical)
    }

    @Test
    fun basys3RetainsVivadoDeviceAndHundredMegahertzClock() {
        val board = fixture("BASYS3")

        assertEquals("BASYS3", board.name)
        assertEquals(
            FpgaDevice(
                vendor = FpgaVendor.VIVADO,
                family = "Artix-7",
                part = "xc7a35t",
                packageName = "cpg236",
                speedGrade = "-1",
                jtagPosition = 0,
                flashPosition = 1,
            ),
            board.device,
        )
        assertEquals(
            FpgaClock("W5", 100_000_000L, FpgaElectrical(ioStandard = "LVCMOS33")),
            board.clock,
        )
        assertEquals(FpgaPull.FLOAT, board.unusedPinPull)
    }

    @Test
    fun basys3ImportsButtonsLedsBidirectionalPinsAndPullups() {
        val board = fixture("BASYS3")

        assertEquals(100, board.resources.size)
        assertEquals(100, board.pins.size)
        assertEquals(
            mapOf(FpgaPinDirection.INPUT to 21, FpgaPinDirection.OUTPUT to 28, FpgaPinDirection.INOUT to 51),
            board.pins.groupingBy { it.direction }.eachCount(),
        )
        assertEquals(
            listOf("V17", "V16", "W16", "W17", "W15", "V15", "W14", "W13", "V2", "T3", "T2", "R3",
                "W2", "U1", "T1", "R2", "T18", "T17", "U17", "W19", "U18"),
            board.pins.filter { it.direction == FpgaPinDirection.INPUT }.map { it.location },
        )
        assertEquals(FpgaRectangle(647, 323, 30, 54), board.resources.first().rectangle)
        assertEquals(FpgaElectrical(ioStandard = "LVCMOS33"), board.pins.single { it.location == "V17" }.electrical)
        val led = board.pins.single { it.location == "U16" }
        assertEquals(FpgaPinDirection.OUTPUT, led.direction)
        assertFalse(led.electrical.activeLow)
        assertTrue(board.pins.single { it.location == "W7" }.electrical.activeLow)
        assertEquals(FpgaPinDirection.INOUT, board.pins.single { it.location == "J1" }.direction)
        assertEquals(FpgaPinDirection.INOUT, board.pins.single { it.location == "W5" }.direction)
        assertEquals(FpgaElectrical(ioStandard = "LVCMOS33", pull = FpgaPull.UP),
            board.pins.single { it.location == "C17" }.electrical)
        assertEquals(FpgaElectrical(ioStandard = "LVCMOS33", pull = FpgaPull.UP),
            board.pins.single { it.location == "B17" }.electrical)
        assertEquals(100, board.pins.map { it.location }.distinct().size)
    }

    private fun fixture(name: String): FpgaBoardDefinition {
        val xml = requireNotNull(javaClass.getResourceAsStream("/fpga/$name.xml")) {
            "Missing FPGA fixture: $name"
        }.bufferedReader().use { it.readText() }
        val result = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml))
        assertEquals(emptyList(), result.warnings)
        return result.board
    }
}
