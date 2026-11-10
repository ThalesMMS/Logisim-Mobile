package dev.logisim.mobile.core

import kotlin.test.*

class SocBusTest {
    @Test fun busReportsDesktopErrorPriorityAndOverlappingSlaves() {
        val empty = SocAddressSpace("bus")
        assertEquals(SocBusError.NO_SLAVES, empty.read(0u).error)
        val memory = SocMemoryDevice("ram", sizeInBytes = 256u)
        val bus = SocAddressSpace("bus", listOf(memory))
        assertEquals(SocBusError.NO_RESPONDER, bus.read(256u).error)
        assertEquals(SocBusError.NON_ATOMIC_READ_WRITE, bus.transact(SocBusRequest(0u, read = true, write = true)).error)
        bus.attach(SocMemoryDevice("overlap", sizeInBytes = 4u))
        assertEquals(SocBusError.MULTIPLE_RESPONDERS, bus.read(0u).error)
        assertEquals(SocBusError.NO_BUS_CONNECTED, SocBusRouter(listOf(bus)).transact("missing", SocBusRequest(0u)).error)
    }

    @Test fun memoryUsesLittleEndianAndAtomicReadBeforeWrite() {
        val bus = SocAddressSpace("bus", listOf(SocMemoryDevice("ram")))
        bus.write(0u, 0x12345678u)
        assertEquals(0x56u, bus.read(1u, SocAccessSize.BYTE).data)
        assertEquals(0x1234u, bus.read(2u, SocAccessSize.HALF_WORD).data)
        bus.write(1u, 0xABu, SocAccessSize.BYTE)
        assertEquals(0x1234AB78u, bus.read(0u).data)
        val previous = bus.transact(SocBusRequest(0u, 0xCAFEBABEu, read = true, write = true, atomic = true))
        assertEquals(0x1234AB78u, previous.data)
        assertEquals(0xCAFEBABEu, bus.read(0u).data)
    }

    @Test fun machineCopyOwnsMemoryAndHistory() {
        val bus = SocAddressSpace("bus", listOf(SocMemoryDevice("ram")), traceLimit = 2)
        bus.write(0u, 5u)
        val other = bus.copy()
        other.write(0u, 9u)
        assertEquals(5u, bus.read(0u).data)
        assertEquals(9u, other.read(0u).data)
        assertEquals(2, bus.trace.size)
        bus.transact(SocBusRequest(0u, hidden = true))
        assertEquals(2, bus.trace.size)
    }

    @Test fun pioCapturesEdgesMasksInterruptAndClearsSelectedBits() {
        val pio = SocPioDevice("pio", 0x100u, width = 4, direction = SocPioDirection.BIDIRECTIONAL,
            bitOperations = true, synchronousCapture = true, captureEdge = SocCaptureEdge.RISING,
            clearCaptureBits = true, generatesIrq = true, edgeIrq = true)
        val bus = SocAddressSpace("bus", listOf(pio))
        pio.setInputs(0u); pio.setInputs(3u)
        assertEquals(3u, bus.read(0x10Cu).data)
        bus.write(0x108u, 2u)
        assertTrue(pio.irq)
        bus.write(0x10Cu, 2u)
        assertFalse(pio.irq)
        assertEquals(1u, pio.snapshot.capture)
        bus.write(0x104u, 3u)
        bus.write(0x100u, 9u)
        bus.write(0x110u, 2u)
        assertEquals(11u, pio.outputBits)
        bus.write(0x114u, 1u)
        assertEquals(10u, pio.outputBits)
        assertEquals(3u, pio.drivenBits)
        assertEquals(SocBusError.UNSUPPORTED_ACCESS, bus.read(0x100u, SocAccessSize.BYTE).error)
    }

    @Test fun pioDirectionErrorsMatchDesktop() {
        val input = SocAddressSpace("in", listOf(SocPioDevice("input")))
        assertEquals(SocBusError.READ_ONLY, input.write(0u, 1u).error)
        assertEquals(SocBusError.REGISTER_NOT_FOUND, input.read(4u).error)
        val output = SocAddressSpace("out", listOf(SocPioDevice("output", direction = SocPioDirection.OUTPUT)))
        assertEquals(SocBusError.WRITE_ONLY, output.read(0u).error)
        assertEquals(SocBusError.MISALIGNED_ADDRESS, output.read(1u).error)
    }

    @Test fun uartFifosExposeValidRemainingFreeActivityAndIrq() {
        val uart = SocJtagUartDevice("uart", 0x100u, 8, 8, 2, 2)
        val bus = SocAddressSpace("bus", listOf(uart))
        bus.write(0x104u, 2u)
        assertTrue(uart.irq)
        bus.write(0x100u, 65u)
        assertEquals(65, uart.transmit())
        assertEquals(0x80402u, bus.read(0x104u).data and 0xFFFFFCFFu)
        assertTrue(uart.receive(97)); assertTrue(uart.receive(98))
        assertEquals(0x18061u, bus.read(0x100u).data)
        assertEquals(0x8062u, bus.read(0x100u).data)
        assertEquals(0u, bus.read(0x100u).data)
        bus.write(0x104u, 0x400u)
        assertEquals(0x80000u, bus.read(0x104u).data)
    }

    @Test fun dmaTransfersBurstsAndSetsCompletionIrq() {
        val source = SocMemoryDevice("src", 0u, 256u)
        val destination = SocMemoryDevice("dst", 0x1000u, 256u)
        val dma = SocDmaDevice("dma", 0x100u, burstSize = 2)
        val bus = SocAddressSpace("bus", listOf(source, destination, dma))
        val router = SocBusRouter(listOf(bus))
        listOf(11u, 22u, 33u).forEachIndexed { index, value -> bus.write(index.toUInt() * 4u, value) }
        bus.write(0x100u, 0u); bus.write(0x104u, 0x1000u); bus.write(0x108u, 12u); bus.write(0x10Cu, 3u)
        dma.clock(router, "bus")
        assertTrue(dma.isBusy); assertEquals(8u, dma.bytesDone)
        assertEquals(11u, bus.read(0x1000u).data); assertEquals(22u, bus.read(0x1004u).data)
        dma.clock(router, "bus")
        assertTrue(dma.isDone); assertFalse(dma.isBusy); assertTrue(dma.irq)
        assertEquals(33u, bus.read(0x1008u).data)
        assertEquals(12u, dma.bytesDone)
        bus.write(0x110u, 2u)
        assertFalse(dma.irq); assertFalse(dma.isDone)
    }

    @Test fun dmaStopsBurstOnFirstErrorAndRetriesWithoutSkipping() {
        val source = SocMemoryDevice("src", 0u, 4u)
        val target = SocMemoryDevice("dst", 0x1000u, 16u)
        val dma = SocDmaDevice("dma", 0x100u, burstSize = 4)
        val bus = SocAddressSpace("bus", listOf(source, target, dma))
        bus.write(0u, 42u)
        bus.write(0x100u, 0u); bus.write(0x104u, 0x1000u); bus.write(0x108u, 16u); bus.write(0x10Cu, 1u)
        dma.clock(SocBusRouter(listOf(bus)), "bus")
        assertEquals(4u, dma.bytesDone); assertTrue(dma.isBusy); assertFalse(dma.isDone)
        assertEquals(42u, bus.read(0x1000u).data)
        assertEquals(setOf(0x1000u), target.storedWords.keys)
    }

    @Test fun vgaSniffsWritesAndFallsBackToItsConfiguredModeInACopiedBus() {
        val vga = SocVgaDevice("vga", 0x100u, 0x1000u, initialMode = 1, softwareModes = 3u)
        val bus = SocAddressSpace("bus", listOf(SocMemoryDevice("ram", 0x1000u, 0x100000u), vga))
        assertEquals(320 to 240, vga.width to vga.height)
        assertEquals(3u, bus.read(0x100u).data)
        bus.write(0x100u, 1u)
        assertEquals(160 to 120, vga.width to vga.height)
        bus.write(0x1000u, 0x00CC7733u)
        assertEquals(0xFFCC7733u, vga.pixel(0, 0))
        // A mode change reloads the frame from the bus, as Evolution's display does; hidden writes are not sniffed.
        val reloaded = vga.pixel(1, 0)
        bus.transact(SocBusRequest(0x1004u, 0xFFFFFFu, read = false, write = true, hidden = true))
        assertEquals(reloaded, vga.pixel(1, 0))
        assertNotEquals(0xFFFFFFFFu, vga.pixel(1, 0))
        bus.write(0x100u, 1u)
        assertEquals(0xFFCC7733u, vga.pixel(0, 0))

        val snapshot = bus.copy()
        val copiedVga = snapshot.devices.filterIsInstance<SocVgaDevice>().single()
        assertEquals(160 to 120, copiedVga.width to copiedVga.height)
        assertEquals(0xFFCC7733u, copiedVga.pixel(0, 0))
        snapshot.write(0x100u, 4u)
        assertEquals(320 to 240, copiedVga.width to copiedVga.height)
        assertEquals(320 * 240, copiedVga.argbPixels.size)
        assertEquals(0xFFCC7733u, copiedVga.pixel(0, 0))
        assertEquals(160 to 120, vga.width to vga.height)
        assertEquals(0xFFCC7733u, vga.pixel(0, 0))

        snapshot.write(0x100u, 1u)
        snapshot.write(0x100u, 3u)
        assertEquals(320 to 240, copiedVga.width to copiedVga.height)
        assertEquals(3u, snapshot.read(0x100u).data)
    }
    @Test fun plicClaimsLowestEnabledSourceAndReassertsAfterCompletion() {
        val plic = SocPlicDevice("plic", 0x0C000000u, 32)
        val bus = SocAddressSpace("bus", listOf(plic))
        plic.setIrqLines(0x80000005u)
        bus.write(0x0C002000u, 10u)
        bus.write(0x0C002004u, 1u)
        assertTrue(plic.irq)
        assertEquals(10u, bus.read(0x0C001000u).data)
        assertEquals(1u, bus.read(0x0C001004u).data)
        assertEquals(1u, bus.read(0x0C200004u).data)
        assertEquals(3u, bus.read(0x0C200004u).data)
        assertEquals(32u, bus.read(0x0C200004u).data)
        assertFalse(plic.irq)
        plic.setIrqLines(0x80000005u)
        assertFalse(plic.irq)
        bus.write(0x0C200004u, 3u)
        plic.setIrqLines(0x80000005u)
        assertTrue(plic.irq)
        bus.write(0x0C200000u, 1u)
        assertFalse(plic.irq)
        assertEquals(0u, bus.read(0x0C200004u).data)
        assertEquals(SocBusError.READ_ONLY, bus.write(0x0C001000u, 0u).error)
    }

}
