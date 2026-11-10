package dev.logisim.mobile.core

import kotlin.test.*

class SocExecutableTest {
    private fun executable(machine: Int = 243): ByteArray = ByteArray(88).also { bytes ->
        fun put16(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at+1] = (value ushr 8).toByte() }
        fun put32(at: Int, value: Int) { repeat(4) { bytes[at+it] = (value ushr (it*8)).toByte() } }
        bytes[0] = 0x7F; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70; bytes[4] = 1; bytes[5] = 1; bytes[6] = 1
        put16(16, 2); put16(18, machine); put32(20, 1); put32(24, 0x100); put32(28, 52); put16(40, 52); put16(42, 32); put16(44, 1)
        put32(52, 1); put32(56, 84); put32(64, 0x100); put32(68, 4); put32(72, 8)
        put32(84, 0x00500093)
    }
    @Test fun elfLoadsPhysicalAddressesZeroFillsBssAndRuns() {
        val image = assertIs<SocExecutableResult.Loaded>(SocElfLoader.read(executable(), SocArchitecture.RV32IM)).image
        val bus = SocAddressSpace("bus", listOf(SocMemoryDevice("ram", sizeInBytes = 1024u)))
        assertTrue(bus.load(image).successful)
        assertEquals(0x00500093u, bus.read(0x100u).data); assertEquals(0u, bus.read(0x104u).data)
        val cpu = Rv32Cpu.step(Rv32Cpu.reset(image.entryPoint), bus).state
        assertEquals(5u, cpu.register(1)); assertEquals(0x104u, cpu.pc)
    }
    @Test fun malformedWrongArchitectureAndEndianAreRejected() {
        assertIs<SocExecutableResult.Rejected>(SocElfLoader.read(byteArrayOf(), SocArchitecture.RV32IM))
        assertIs<SocExecutableResult.Rejected>(SocElfLoader.read(executable(113), SocArchitecture.RV32IM))
        assertIs<SocExecutableResult.Rejected>(SocElfLoader.read(executable().also { it[5] = 2 }, SocArchitecture.RV32IM))
        assertIs<SocExecutableResult.Rejected>(SocElfLoader.read(executable().copyOf(87), SocArchitecture.RV32IM))
    }
    @Test fun failedLoadDoesNotPartiallyModifyMemory() {
        val memory = SocMemoryDevice("ram", sizeInBytes = 1024u)
        val bus = SocAddressSpace("bus", listOf(memory))
        bus.write(0u, 99u)
        val image = SocExecutableImage(SocArchitecture.RV32IM, 0u, listOf(
            SocExecutableSegment(0u, byteArrayOf(1), 1u), SocExecutableSegment(1024u, byteArrayOf(2), 1u)))
        assertFalse(bus.load(image).successful)
        assertEquals(99u, bus.read(0u).data)
    }
}
