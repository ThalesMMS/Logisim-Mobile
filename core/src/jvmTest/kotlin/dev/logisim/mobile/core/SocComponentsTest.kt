package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocComponentsTest {
    private val registry = ComponentRegistry(SocComponents.definitions())
    private fun component(kind: ComponentKind, vararg attributes: Pair<String, String>) =
        registry.create(kind, "device", GridPoint(100, 200), mapOf(*attributes))

    @Test fun fixtureDescriptorsAndAbsoluteGeometryMatchDesktop() {
        val fixture = listOf(File("fixtures/soc/all-components.circ"), File("../fixtures/soc/all-components.circ"))
            .first { it.isFile }.readText()
        val root = CircXmlParser(fixture).parse()
        val library = root.children.first { it.name == "lib" }
        assertEquals("#Soc", library.attributes["desc"])
        val components = root.children.first { it.name == "circuit" }.children.filter { it.name == "comp" }
        assertEquals(8, components.size)
        val expectedBounds = mapOf(
            "SocBus" to ComponentBounds(20, 20, 660, 200),
            "Socmem" to ComponentBounds(20, 250, 340, 310),
            "SocPio" to ComponentBounds(20, 350, 400, 470),
            "SocVga" to ComponentBounds(20, 550, 190, 710),
            "SocDma" to ComponentBounds(750, 20, 1070, 140),
            "SocJtagUart" to ComponentBounds(750, 250, 1050, 310),
            "Rv32im" to ComponentBounds(1200, 20, 1840, 660),
            "Nios2" to ComponentBounds(1900, 20, 2540, 670),
        )
        val expectedCounts = mapOf("SocBus" to 1, "Socmem" to 0, "SocPio" to 6,
            "SocVga" to 0, "SocDma" to 3, "SocJtagUart" to 10, "Rv32im" to 4, "Nios2" to 16)
        for ((index, xml) in components.withIndex()) {
            val name = xml.attributes.getValue("name")
            val attributes = xml.children.associate { it.attributes.getValue("name") to it.attributes.getValue("val") }
            val definition = registry.resolve("#Soc", name, attributes) ?: error(name)
            val position = xml.attributes.getValue("loc").trim('(', ')').split(',').map(String::toInt)
            val c = registry.create(definition.kind, "fixture$index", GridPoint(position[0], position[1]), attributes)
            assertEquals(expectedBounds[name], registry.layout(c).bounds, name)
            assertEquals(expectedCounts[name], registry.layout(c).ports.size, name)
            assertFalse(definition.supportsRotation, name)
            assertEquals(if (name == "SocBus") "test-bus" else null, c.attributes["SocBusIdentifier"])
            assertEquals(if (name == "SocBus") null else "test-bus", c.attributes["SocBusSelection"])
        }
        assertEquals(8, registry.definitions.map { it.kind }.distinct().size)
    }

    @Test fun niosPortsKeepDesktopIndicesIncludingResultBeforeDone() {
        val layout = registry.layout(component(SocComponents.NIOS2, "irqWidth" to "2"))
        assertEquals(listOf("clock", "reset", "dataA", "dataB", "start", "n", "a", "readRA",
            "b", "readRB", "c", "writeRC", "result", "done", "irq0", "irq1"), layout.ports.map { it.name })
        assertEquals(listOf(GridPoint(100, 840), GridPoint(100, 820), GridPoint(130, 200), GridPoint(180, 200),
            GridPoint(230, 200), GridPoint(280, 200), GridPoint(330, 200), GridPoint(380, 200),
            GridPoint(430, 200), GridPoint(480, 200), GridPoint(530, 200), GridPoint(580, 200),
            GridPoint(710, 200), GridPoint(660, 200), GridPoint(100, 240), GridPoint(100, 250)), layout.ports.map { it.position })
        assertEquals(listOf(1, 1, 32, 32, 1, 8, 5, 1, 5, 1, 5, 1, 32, 1, 1, 1), layout.ports.map { it.width })
        assertEquals(listOf(PortDirection.INPUT, PortDirection.INPUT) + List(10) { PortDirection.OUTPUT } +
            List(4) { PortDirection.INPUT }, layout.ports.map { it.direction })
    }

    @Test fun uartDmaAndRvPortsKeepDesktopOrder() {
        val uart = registry.layout(component(SocComponents.UART)).ports
        assertEquals(listOf("clock", "reset", "irq", "readEnable", "clearKeyboard", "available", "dataIn", "dataOut", "write", "clearTty"), uart.map { it.name })
        assertEquals(listOf(GridPoint(100, 250), GridPoint(100, 230), GridPoint(400, 250), GridPoint(110, 200), GridPoint(120, 200),
            GridPoint(230, 200), GridPoint(240, 200), GridPoint(260, 200), GridPoint(280, 200), GridPoint(290, 200)), uart.map { it.position })
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 7, 7, 1, 1), uart.map { it.width })
        assertEquals(listOf(PortDirection.INPUT, PortDirection.INPUT, PortDirection.OUTPUT, PortDirection.OUTPUT,
            PortDirection.OUTPUT, PortDirection.INPUT, PortDirection.INPUT, PortDirection.OUTPUT,
            PortDirection.OUTPUT, PortDirection.OUTPUT), uart.map { it.direction })
        val dma = registry.layout(component(SocComponents.DMA)).ports
        assertEquals(listOf("reset", "clock", "irq"), dma.map { it.name })
        assertEquals(listOf(GridPoint(100, 300), GridPoint(100, 310), GridPoint(420, 300)), dma.map { it.position })
        assertEquals(listOf(PortDirection.INPUT, PortDirection.INPUT, PortDirection.OUTPUT), dma.map { it.direction })
        val rv = registry.layout(component(SocComponents.RV32, "irqWidth" to "32")).ports
        assertEquals(34, rv.size)
        assertEquals(listOf("reset", "clock") + List(32) { "irq$it" }, rv.map { it.name })
        assertEquals(listOf(GridPoint(100, 810), GridPoint(100, 830)) + List(32) { GridPoint(100, 210 + it * 10) }, rv.map { it.position })
        assertTrue(rv.all { it.direction == PortDirection.INPUT && it.width == 1 })
    }

    @Test fun pioPortCountOrderAndDirectionFollowAllFourModes() {
        for (width in listOf(1, 4, 32, 64)) for (direction in listOf("inputonly", "outputonly", "bidir", "inout")) {
            val c = component(SocComponents.PIO, "width" to "$width", "direction" to direction, "genirq" to "true")
            val ports = registry.layout(c).ports
            val hasIrq = direction != "outputonly"
            val inputCount = if (direction == "inputonly" || direction == "inout") width else 0
            val outputCount = if (direction != "inputonly") width else 0
            assertEquals(1 + (if (hasIrq) 1 else 0) + inputCount + outputCount, ports.size, "$direction/$width")
            assertEquals("reset", ports[0].name)
            assertEquals(GridPoint(100, 310), ports[0].position)
            if (hasIrq) assertEquals(ComponentTerminal("irq", GridPoint(120, 200), PortDirection.OUTPUT), ports[1])
            val data = ports.drop(if (hasIrq) 2 else 1)
            assertEquals(List(inputCount) { "input$it" } + List(outputCount) { "output$it" }, data.map { it.name })
            assertEquals(List(inputCount) { GridPoint(470 - it * 10, 320) } +
                List(outputCount) { GridPoint(470 - it * 10, 200) }, data.map { it.position })
            assertEquals(List(inputCount) { PortDirection.INPUT } + List(outputCount) {
                if (direction == "bidir") PortDirection.INOUT else PortDirection.OUTPUT
            }, data.map { it.direction })
            assertTrue(data.all { it.width == 1 })
            assertIs<SocPioDevice>(SocComponents.createDevice(c))
        }
    }

    @Test fun desktopDefaultsConstructTheExpectedDevices() {
        val bus = SocComponents.attributes(component(SocComponents.BUS))
        assertEquals(5, bus[SocComponents.TraceSize]); assertTrue(bus[SocComponents.TraceVisible])
        val memory = assertIs<SocMemoryDevice>(SocComponents.createDevice(component(SocComponents.MEMORY)))
        assertEquals(0u, memory.startAddress); assertEquals(1024uL, memory.sizeInBytes)
        val pio = assertIs<SocPioDevice>(SocComponents.createDevice(component(SocComponents.PIO)))
        assertEquals(1, pio.width); assertEquals(SocPioDirection.INPUT, pio.direction)
        assertFalse(pio.synchronousCapture); assertFalse(pio.generatesIrq); assertEquals(0u, pio.resetValue)
        val vga = assertIs<SocVgaDevice>(SocComponents.createDevice(component(SocComponents.VGA)))
        assertEquals(160, vga.width); assertEquals(120, vga.height); assertEquals(7u, vga.softwareModes)
        val dma = assertIs<SocDmaDevice>(SocComponents.createDevice(component(SocComponents.DMA)))
        assertEquals(16, dma.burstSize); assertEquals("", dma.sourceBus); assertEquals("", dma.destinationBus)
        val uart = assertIs<SocJtagUartDevice>(SocComponents.createDevice(component(SocComponents.UART)))
        assertEquals(64, uart.writeCapacity); assertEquals(64, uart.readCapacity)
        assertEquals(8, uart.writeThreshold); assertEquals(8, uart.readThreshold)
        val rv = SocComponents.attributes(component(SocComponents.RV32))
        assertEquals(0u, rv[SocComponents.ResetVector]); assertEquals(0, rv[SocComponents.IrqWidth])
        assertEquals(0x0C000000u, rv[SocComponents.PlicAddress]); assertTrue(rv[SocComponents.StateVisible])
        val nios = SocComponents.attributes(component(SocComponents.NIOS2))
        assertEquals(0x14u, nios[SocComponents.ExceptionVector]); assertEquals(0x30u, nios[SocComponents.BreakVector])
        assertNull(SocComponents.createDevice(component(SocComponents.NIOS2)))
    }

    @Test fun configurableBoundsUseAllVgaModesAndFullTraceRange() {
        for ((mode, width, height) in listOf(Triple("160x120", 170, 160), Triple("320x240", 330, 280),
            Triple("640x480", 650, 520), Triple("800x600", 810, 640), Triple("1024x768", 1034, 808))) {
            val c = component(SocComponents.VGA, "DisplayMode" to mode)
            assertEquals(ComponentBounds(100, 200, 100 + width, 200 + height), registry.layout(c).bounds)
            assertTrue(registry.layout(c).ports.isEmpty())
        }
        val bus = component(SocComponents.BUS, "TraceSize" to "64", "TraceVisible" to "false")
        assertEquals(ComponentBounds(100, 200, 740, 2150), registry.layout(bus).bounds)
    }

    @Test fun addressesAcceptDesktopNumericFormatsInsteadOfAssumingBareHex() {
        for ((raw, expected) in listOf("1000" to 1000u, "0x1000" to 4096u, "0XFFFFffff" to UInt.MAX_VALUE,
            "0b1010" to 10u, "010" to 8u, "-1" to UInt.MAX_VALUE, "-0x80000000" to 0x80000000u,
            "-0b10" to 0xFFFFFFFEu, "-010" to 0xFFFFFFF8u, "4294967295" to UInt.MAX_VALUE)) {
            val c = component(SocComponents.MEMORY, "StartAddress" to raw)
            assertEquals(expected, SocComponents.attributes(c)[SocComponents.StartAddress], raw)
        }
        for (raw in listOf("deadbeef", "0x100000000", "4294967296", "-2147483649", "-+1", "-0x+1", "08", ""))
            assertFailsWith<IllegalArgumentException>(raw) { component(SocComponents.MEMORY, "StartAddress" to raw) }
    }

    @Test fun dmaBurstParserRoundsUpAndClampsToDesktopOptions() {
        for ((raw, expected) in listOf("-1" to 1, "0" to 1, "1" to 1, "3" to 4, "17" to 32,
            "128" to 128, "129" to 256, "256" to 256, "1024" to 256)) {
            val c = component(SocComponents.DMA, "BurstSize" to raw, "DmaSrcBus" to "source", "DmaDstBus" to "destination")
            val device = assertIs<SocDmaDevice>(SocComponents.createDevice(c))
            assertEquals(expected, device.burstSize)
            assertEquals("source", device.sourceBus); assertEquals("destination", device.destinationBus)
        }
        assertFailsWith<IllegalArgumentException> { component(SocComponents.DMA, "BurstSize" to "1.5") }
    }

    @Test fun uartThresholdsAndFifoSizesRetainDesktopRanges() {
        val c = component(SocComponents.UART, "WriteFifoSize" to "32768", "ReadFifoSize" to "8",
            "WriteThreshold" to "-1", "ReadThreshold" to "2147483647")
        val device = assertIs<SocJtagUartDevice>(SocComponents.createDevice(c))
        assertEquals(32768, device.writeCapacity); assertEquals(8, device.readCapacity)
        assertEquals(-1, device.writeThreshold); assertEquals(Int.MAX_VALUE, device.readThreshold)
        for (raw in listOf("0", "7", "24", "65536"))
            assertFailsWith<IllegalArgumentException>(raw) { component(SocComponents.UART, "WriteFifoSize" to raw) }
    }

    @Test fun onlyMemoryDmaAndPlicAlignTheirBaseAddresses() {
        for (kind in listOf(SocComponents.MEMORY, SocComponents.DMA)) {
            val device = SocComponents.createDevice(component(kind, "StartAddress" to "0x103"))!!
            assertEquals(0x100u, device.startAddress, kind.name)
        }
        val plic = SocComponents.createDevice(component(SocComponents.RV32, "plicBaseAddress" to "0xC000103"))!!
        assertEquals(0xC000100u, plic.startAddress)
        for (kind in listOf(SocComponents.PIO, SocComponents.UART, SocComponents.VGA)) {
            val device = SocComponents.createDevice(component(kind, "StartAddress" to "0x103"))!!
            assertEquals(0x103u, device.startAddress, kind.name)
        }
    }

    @Test fun schemaPreservesUnknownMetadataAndRejectsInvalidIrqWidths() {
        val c = component(SocComponents.NIOS2, "label" to "processor", "futureOption" to "preserve me", "irqWidth" to "32")
        assertEquals("preserve me", SocComponents.attributes(c).raw["futureOption"])
        assertEquals(46, registry.layout(c).ports.size)
        for (kind in listOf(SocComponents.NIOS2, SocComponents.RV32)) {
            assertFailsWith<IllegalArgumentException> { component(kind, "irqWidth" to "33") }
            assertFailsWith<IllegalArgumentException> { component(kind, "irqWidth" to "-1") }
        }
    }
}
