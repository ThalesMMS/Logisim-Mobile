package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssemblyListingTest {
    @Test
    fun listingFollowsTheClockedRegisterAndClearsWhenAddressIsMissing() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(REGISTER_CIRCUIT)).project
        val register = project.mainCircuit.components.single { it.kind == ComponentKind.REGISTER }
        val listing = assertNotNull(AssemblyListing.read("    0: NOP\n    1: INC\n    2: JMP".encodeToByteArray()))
        val simulator = CircuitSimulator()
        var state = simulator.settle(project)
        val visited = mutableListOf<ULong?>()
        val highlights = mutableListOf<String?>()
        repeat(6) {
            val address = state.assemblyRegisterAddress(register.id)
            visited += address
            highlights += listing.lineAt(address)?.let(listing.lines::get)
            state = simulator.settle(project, state, tick = true)
        }
        assertEquals(listOf<ULong?>(0uL, 1uL, 1uL, 2uL, 2uL, 3uL), visited)
        assertEquals(listOf("    0: NOP", "    1: INC", "    1: INC", "    2: JMP", "    2: JMP", null), highlights)
        assertNull(listing.lineAt(SimulationState(vectorValues = mapOf(register.id to LogicVector.unknown(8)))
            .assemblyRegisterAddress(register.id)))
    }

    @Test
    fun listingMatchesEvolutionsSpaceIndentedAddressesWithoutLeadingZeros() {
        val text = "\uFEFFfirmware.elf: file format elf32-avr\r\n0000000a <loop>:\r\n\t  a: TAB\r\n   0a: ZERO\r\na: BARE\r\n   A: UPPER\r\n   a: AGAIN\r   b : SPACE\n  10: TEN\n"
        val listing = assertNotNull(AssemblyListing.read(text.encodeToByteArray()))
        assertEquals("firmware.elf: file format elf32-avr", listing.lines[0])
        assertEquals("   A: UPPER", listing.lines[assertNotNull(listing.lineAt(0xAuL))])
        assertNull(listing.lineAt(0xBuL))
        assertEquals("  10: TEN", listing.lines[assertNotNull(listing.lineAt(0x10uL))])
        assertNull(listing.lineAt(1uL))
    }

    @Test
    fun followsLabeledRegistersSortedByLabel() {
        fun register(id: String, label: String) = CircuitComponent(id, ComponentKind.REGISTER, GridPoint(0, 0), label = label)
        val document = CircuitDocument(name = "main", components = listOf(
            register("z", "ZP"), register("unlabeled", ""), register("pc", "PC"), register("again", "PC"), register("a", "ACC")))
        assertEquals(listOf("a", "pc", "z"), document.assemblyRegisters().map { it.id })
    }

    @Test
    fun rejectsMalformedEmptyAndOversizedListings() {
        assertNull(AssemblyListing.read(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(AssemblyListing.read("just a comment".encodeToByteArray()))
        assertNull(AssemblyListing.read("  10000000000000000: NOP\n  00: NOP\n\t1: NOP".encodeToByteArray()))
        assertNull(AssemblyListing.read("0: \u0000".encodeToByteArray()))
        assertNull(AssemblyListing.read(ByteArray(AssemblyListing.MAX_BYTES + 1)))
    }

    private companion object {
        const val REGISTER_CIRCUIT = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <lib name="1" desc="#Arithmetic"/>
              <lib name="2" desc="#Memory"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(110,110)" name="Constant"><a name="width" val="8"/><a name="value" val="0x1"/></comp>
                <comp lib="0" loc="(240,140)" name="Clock"/>
                <comp lib="1" loc="(170,100)" name="Adder"/>
                <comp lib="2" loc="(290,100)" name="Register"><a name="label" val="PC"/></comp>
                <wire from="(110,110)" to="(130,110)"/>
                <wire from="(170,100)" to="(260,100)"/>
                <wire from="(240,140)" to="(270,140)"/>
                <wire from="(270,120)" to="(270,140)"/>
                <wire from="(290,100)" to="(320,100)"/>
                <wire from="(320,60)" to="(320,100)"/>
                <wire from="(130,60)" to="(320,60)"/>
                <wire from="(130,60)" to="(130,90)"/>
              </circuit>
            </project>
        """
    }
}
