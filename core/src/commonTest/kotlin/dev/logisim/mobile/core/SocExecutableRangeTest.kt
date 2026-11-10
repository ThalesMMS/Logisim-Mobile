package dev.logisim.mobile.core

import kotlin.test.*

class SocExecutableRangeTest {
    @Test fun executableDataCannotCrossIntoAnOverlappingPeripheral() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 0x2000u)
        val pio = SocPioDevice("pio", 0x1000u)
        val bus = SocAddressSpace("bus", listOf(memory, pio))
        bus.write(0xFFCu, 0xAABBCCDDu)

        val reply = bus.load(image(SocExecutableSegment(0xFFCu, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8u)))

        assertEquals(SocBusError.MULTIPLE_RESPONDERS, reply.error)
        assertEquals(0xAABBCCDDu, bus.read(0xFFCu).data)
        assertEquals(0u, pio.snapshot.output)
    }

    @Test fun zeroFilledBssCannotOverlapAPeripheral() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 256u)
        val bus = SocAddressSpace("bus", listOf(memory, SocJtagUartDevice("uart", 32u)))
        bus.write(8u, 0xAABBCCDDu)
        val before = memory.storedWords

        val reply = bus.load(image(SocExecutableSegment(8u, byteArrayOf(1, 2, 3, 4), 48u)))

        assertEquals(SocBusError.MULTIPLE_RESPONDERS, reply.error)
        assertEquals(before, memory.storedWords)
    }

    @Test fun anOverlapInsideTheSegmentCannotBeMissedByItsEndpoints() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 256u)
        val bus = SocAddressSpace("bus", listOf(memory, SocMemoryDevice("overlay", 32u, 4u)))
        val before = memory.storedWords

        val reply = bus.load(image(SocExecutableSegment(16u, ByteArray(64) { 42 }, 64u)))

        assertEquals(SocBusError.MULTIPLE_RESPONDERS, reply.error)
        assertEquals(before, memory.storedWords)
    }

    @Test fun aLaterOverlapRejectsTheEntireImageBeforeWritingEarlierSegments() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 256u)
        val bus = SocAddressSpace("bus", listOf(memory, SocVgaDevice("vga", 64u, 128u)))
        bus.write(0u, 99u)
        val before = memory.storedWords

        val reply = bus.load(image(
            SocExecutableSegment(0u, byteArrayOf(1), 1u),
            SocExecutableSegment(60u, byteArrayOf(2), 12u),
        ))

        assertEquals(SocBusError.MULTIPLE_RESPONDERS, reply.error)
        assertEquals(before, memory.storedWords)
        assertEquals(99u, bus.read(0u).data)
    }

    @Test fun adjacentPeripheralRangesDoNotRejectAValidSegment() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 256u)
        val bus = SocAddressSpace("bus", listOf(memory,
            SocJtagUartDevice("before", 8u), SocJtagUartDevice("after", 32u)))

        val reply = bus.load(image(SocExecutableSegment(16u, byteArrayOf(0x78, 0x56, 0x34, 0x12), 16u)))

        assertTrue(reply.successful)
        assertEquals(0x12345678u, bus.read(16u).data)
        assertEquals(0u, bus.read(20u).data)
        assertEquals(0u, bus.read(28u).data)
    }

    private fun image(vararg segments: SocExecutableSegment) =
        SocExecutableImage(SocArchitecture.RV32IM, segments.first().address, segments.toList())
}
