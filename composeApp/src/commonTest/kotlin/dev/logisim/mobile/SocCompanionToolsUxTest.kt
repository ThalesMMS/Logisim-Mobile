package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CompanionOperationCapability
import dev.logisim.mobile.core.ExtensionCapability
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.HdlGenericDeclaration
import dev.logisim.mobile.core.HdlIpDeclarations
import dev.logisim.mobile.core.SocAccessSize
import dev.logisim.mobile.core.SocAddressSpace
import dev.logisim.mobile.core.SocBusError
import dev.logisim.mobile.core.SocBusReply
import dev.logisim.mobile.core.SocBusRequest
import dev.logisim.mobile.core.SocBusTrace
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-06: the pure parts of the SoC listing, the bus reply, the consent copy, the HDL generics and import names. */
class SocCompanionToolsUxTest {
    @Test
    fun listingRowsDecodeEachProcessorsWords() {
        // addi t0,t0,7; li t0,7 (Evolution's pseudo-instruction for addi from zero); beq a0,zero is beqz.
        assertEquals("addi      t0,t0,7", socDisassembly(0x00728293u, nios2 = false))
        assertEquals("li        t0,7", socDisassembly(0x00700293u, nios2 = false))
        assertTrue(socDisassembly(0x00050663u, nios2 = false)!!.startsWith("beqz      a0,"))
        // Nios II add r0,r0,r0 is nop, as the trace writes it.
        assertEquals("nop", socDisassembly(0x0001883Au, nios2 = true))
        assertNull(socDisassembly(0xFFFFFFFFu, nios2 = false), "A data word has no instruction text")
    }

    @Test
    fun registersCarryTheirAbiNames() {
        assertEquals("x0  zero", socRegisterName(0, nios2 = false))
        assertEquals("x1  ra", socRegisterName(1, nios2 = false))
        assertEquals("x10 a0", socRegisterName(10, nios2 = false))
        assertEquals("r1  at", socRegisterName(1, nios2 = true))
        assertEquals("r27 sp", socRegisterName(27, nios2 = true))
        assertEquals("r5", socRegisterName(5, nios2 = true), "A register without an ABI name is not named twice")
    }

    @Test
    fun theManualReplyIsTheFirstLaterEntryOfTheSameRequest() {
        val read = SocBusRequest(0x100u, size = SocAccessSize.WORD, initiator = "c1")
        fun entry(sequence: Long, request: SocBusRequest, data: UInt = 0u, error: SocBusError? = null) =
            SocBusTrace(sequence, request, SocBusReply(data, error))
        val pending = SocManualRequest("c1", read.copy(initiator = ""), after = 4)
        val trace = listOf(entry(4, read, 1u), entry(5, read.copy(initiator = "cpu"), 2u), entry(6, read, 0x12345678u))
        assertEquals(6L, pending.reply(trace)?.sequence)
        assertEquals("12345678", pending.reply(trace)!!.resultText().resolve(AppLanguage.ENGLISH))
        assertEquals("R32", pending.reply(trace)!!.operationText())
        // A trace cleared since the request (sequence restarts at 0) is searched from its start.
        assertEquals(0L, pending.reply(listOf(entry(0, read, 9u)))?.sequence)
        assertNull(pending.reply(listOf(entry(4, read, 1u))))
        val failed = entry(0, read.copy(address = 0x900u), error = SocBusError.NO_RESPONDER)
        assertEquals(SocBusError.NO_RESPONDER.notice, failed.resultText())
    }

    @Test
    fun socComponentsAreNamedByLabelAndToldApartByPosition() {
        fun soc(id: String, label: String, x: Int, y: Int, kind: String = "SOC_RV32IM") =
            CircuitComponent(id, SocComponents.kinds.first { it.name == kind }, GridPoint(x, y), label)
        val names = socComponentNames(listOf(soc("c1", "", 10, 20), soc("c2", "", 30, 40), soc("c3", "Main", 0, 0),
            soc("c4", "", 0, 0, kind = "SOC_BUS"))) { if (it.kind.name == "SOC_BUS") "SoC Bus" else "RV32IM Processor" }
            .mapValues { it.value.resolve(AppLanguage.ENGLISH) }
        assertEquals(mapOf("c1" to "RV32IM Processor (10, 20)", "c2" to "RV32IM Processor (30, 40)", "c3" to "Main",
            "c4" to "SoC Bus"), names)
    }

    /** C91: the bus trace and its comparison name initiators, responders and buses without internal ids. */
    @Test
    fun busPartiesAndBusesAreNamedWithoutIds() {
        val names = mapOf("c4" to LocalizedText.literal("RV32IM Processor"), "c2" to LocalizedText.literal("SoC Memory"))
        assertEquals("SoC Memory", socBusPartyName("c2", names)!!.resolve(AppLanguage.ENGLISH))
        assertEquals("RISC-V interrupt controller (PLIC) of RV32IM Processor", socBusPartyName("c4/plic", names)!!.resolve(AppLanguage.ENGLISH))
        assertNull(socBusPartyName("", names))
        assertEquals("elsewhere", socBusPartyName("elsewhere", names)!!.resolve(AppLanguage.ENGLISH), "An id nothing explains is kept")

        fun bus(instances: List<String>, breadcrumb: String, local: String? = null) =
            SocTraceBus(SocTracePath(instances, "bus", local), breadcrumb, SocAddressSpace("shared"))
        val buses = listOf(bus(listOf("a"), "Main / Board / shared"), bus(listOf("b"), "Main / Board / shared"),
            bus(emptyList(), "Main / bus"), bus(emptyList(), "Main / bus", local = "doc"))
        val titles = socTraceBusTitles(buses).values.map { it.resolve(AppLanguage.ENGLISH) }
        assertEquals(listOf("Main / Board / shared #1", "Main / Board / shared #2", "Main / bus", "Main / bus (local view)"), titles)
    }

    @Test
    fun everyCapabilityAndKnownCompanionOperationHasAPlainName() {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
            for (capability in ExtensionCapability.entries) {
                val name = capability.displayName().resolve(language)
                val description = capability.description().resolve(language)
                assertTrue(name.isNotBlank() && name != capability.serialized, "$capability in $language: $name")
                assertTrue(description.isNotBlank() && description != capability.serialized && description != name, "$capability: $description")
            }
            for (operation in companionOperations) {
                val name = companionOperationName(operation).resolve(language)
                assertTrue(name.isNotBlank() && name != operation, "$operation in $language: $name")
            }
        }
        assertEquals("future-op", companionOperationName("future-op").resolve(AppLanguage.ENGLISH), "An unknown operation keeps its id")
        assertNotEquals(companionOperationName("tcl-run").resolve(), companionOperationName("tcl-timed-run").resolve())
        // The status lists label an operation this app does not know by the host's own description.
        fun operation(name: String, description: String) = CompanionOperationCapability(name, allowed = true, available = true,
            missingTools = emptyList(), toolAlternatives = emptyList(), requiresConfirmation = false, description = description)
        assertEquals("Future work", companionOperationLabel(operation("future-op", "Future work")).resolve(AppLanguage.ENGLISH))
        assertEquals("future-op", companionOperationLabel(operation("future-op", " ")).resolve(AppLanguage.ENGLISH))
        assertEquals(companionOperationName("tcl-run").resolve(AppLanguage.ENGLISH),
            companionOperationLabel(operation("tcl-run", "host text")).resolve(AppLanguage.ENGLISH))
    }

    /** The operations the companion server registers (the companion Python modules) and the clients ask for. */
    private val companionOperations = listOf("verify-inputs", "fpga-synthesize", "fpga-program", "fpga-ise-build",
        "fpga-ise-cpld-build", "fpga-quartus-build", "fpga-vivado-build", "yosys-import", "hdl.validate", "hdl.start",
        "hdl.step", "hdl.reset", "hdl.cancel", "tcl-run", "tcl-step", "tcl-timed-run", "tcl-timed-step", "tcl-tk-run",
        "tcl-tk-live", "jar-run", "jar-live", "telnet.start", "telnet.poll", "telnet.resize", "telnet.send", "telnet.stop")

    /** C89: the fields write the same NAME=value text, so Apply writes exactly the attributes the text field wrote. */
    @Test
    fun genericFieldsProduceTheSameAttributesAsTheText() {
        assertEquals(hdlGenericAttributes("WIDTH=16"), hdlGenericAttributes(hdlGenericTextWith("", "WIDTH", "16")))
        assertEquals(mapOf("generic.WIDTH" to "16"), hdlGenericAttributes(hdlGenericTextWith("", "WIDTH", "16")))
        // An existing line keeps its spelling and position; an empty field removes it; others are kept as they are.
        val text = "width=8\nOLD=3\nDELAY=10 ns"
        assertEquals("width=12\nOLD=3\nDELAY=10 ns", hdlGenericTextWith(text, "WIDTH", " 12 "))
        assertEquals("OLD=3\nDELAY=10 ns", hdlGenericTextWith(text, "Width", ""))
        assertEquals(hdlGenericAttributes("width=12\nOLD=3\nDELAY=10 ns"), hdlGenericAttributes(hdlGenericTextWith(text, "WIDTH", "12")))
        assertEquals("8", hdlGenericValue(text, "WIDTH"))
        assertNull(hdlGenericValue(text, "DEPTH"))
        val declarations = listOf(HdlGenericDeclaration("WIDTH", "positive", "8"), HdlGenericDeclaration("DELAY", "time", "5 ns"))
        assertEquals(listOf("OLD"), hdlGenericExtras(text, declarations))
        assertEquals("8", declarations[0].placeholder())
        assertEquals("1", HdlGenericDeclaration("N", "positive", "").placeholder())
        assertEquals("hdl_tools_sim_generic_invalid_positive", hdlGenericNumberError("positive", "0"))
        assertEquals("hdl_tools_sim_generic_invalid_natural", hdlGenericNumberError("natural", "-1"))
        assertEquals("hdl_tools_sim_generic_invalid_integer", hdlGenericNumberError("integer", "x"))
        assertNull(hdlGenericNumberError("integer", "-4"))
        assertNull(hdlGenericNumberError("positive", ""))
    }

    /**
     * C89 time generics: Evolution's VhdlGenericAttribute stores an integer femtosecond count. The field shows that
     * count in the largest unit that divides it and writes a count back, so the .circ value stays one Evolution parses.
     */
    @Test
    fun timeGenericsRoundTripAsFemtoseconds() {
        assertEquals(HdlTimeValue("10", "ns"), hdlTimeShown("10000000"))
        assertEquals(HdlTimeValue("5", "fs"), hdlTimeShown("5"))
        assertEquals(HdlTimeValue("-2", "ps"), hdlTimeShown("-2000"))
        assertEquals(HdlTimeValue("2", "us"), hdlTimeShown("2000000000"))
        // The unit being typed in is kept while it divides the count, so 1000 ns does not jump to 1 us.
        assertEquals(HdlTimeValue("1000", "ns"), hdlTimeShown("1000000000", preferred = "ns"))
        assertEquals(HdlTimeValue("3", "fs"), hdlTimeShown("3", preferred = "ns"))
        assertEquals(HdlTimeValue("0", "ns"), hdlTimeShown("0", preferred = "ns"))
        assertEquals(HdlTimeValue("", "ns"), hdlTimeShown("", preferred = "ns"))
        // Text with a unit (typed in the text editor) is shown as written; ms or an expression stays text.
        assertEquals(HdlTimeValue("10", "ns"), hdlTimeShown("10 ns"))
        assertNull(hdlTimeShown("1 ms"))
        assertNull(hdlTimeShown("2*N ns"))

        assertEquals("10000000", hdlTimeStored("10", "ns"))
        assertEquals("5", hdlTimeStored("5", "fs"))
        assertEquals("-1000", hdlTimeStored("-1", "ps"))
        assertEquals("2000000000", hdlTimeStored("2", "us"))
        assertNull(hdlTimeStored("3", "us"), "Beyond Evolution's Integer range")
        assertNull(hdlTimeStored("x", "ns"))
        assertNull(hdlTimeStored("", "ns"))
        for (value in listOf("10000000", "5", "-2000", "0", "2000000000")) {
            val shown = hdlTimeShown(value)!!
            assertEquals(value, hdlTimeStored(shown.number, shown.unit), value)
        }
        // The stored count means the same delay to the simulator: an override without a unit is in fs.
        val source = "entity T is generic (DELAY : time := 5 ns); port (a : in std_logic); end T;"
        assertEquals("10000000", HdlIpDeclarations.vhdl(source, mapOf("DELAY" to hdlTimeStored("10", "ns")!!)).parameters["DELAY"])
        assertEquals("10000000", HdlIpDeclarations.vhdl(source, mapOf("DELAY" to "10 ns")).parameters["DELAY"])
        assertEquals(mapOf("generic.DELAY" to "10000000"), hdlGenericAttributes(hdlGenericTextWith("", "DELAY", hdlTimeStored("10", "ns")!!)))
    }

    /** C95: Add moves the name on, keeping the extension the root-file filter reads. */
    @Test
    fun theNextImportNameKeepsThePathAndExtension() {
        assertEquals("design2.v", nextHdlImportName("design.v", listOf("design.v")))
        assertEquals("design3.v", nextHdlImportName("design2.v", listOf("design.v", "design2.v")))
        assertEquals("rtl/core2.sv", nextHdlImportName("rtl/core.sv", listOf("rtl/core.sv")))
        assertEquals("top2.blif", nextHdlImportName("top.blif", emptyList()))
        assertEquals("lib.d/cells2", nextHdlImportName("lib.d/cells", listOf("lib.d/cells")))
    }
}
