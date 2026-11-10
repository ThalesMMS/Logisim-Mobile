package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FpgaHdlAdapterTest {
    private fun export() = HdlExportResult("c_main", listOf(HdlSourceFile("child.v", "child source"), HdlSourceFile("main.v", "main source")),
        listOf(HdlExportPort("clock", PortDirection.INPUT, 1, "main", "source-clock"),
            HdlExportPort("result", PortDirection.OUTPUT, 8, "main", "output-pin"),
            HdlExportPort("gpio", PortDirection.INOUT, 2, "main", "gpio-pin")), emptyList())

    @Test fun preservesModuleOrderAndPortDirectionWithoutInventingClockFrequency() {
        val design = export().fpgaDesign(HdlLanguage.VERILOG)
        assertEquals("c_main", design.topLevelName)
        assertEquals(FpgaHdlLanguage.VERILOG, design.language)
        assertEquals(listOf(FpgaSourceFile("child.v", "child source"), FpgaSourceFile("main.v", "main source")), design.files)
        assertEquals(listOf(FpgaPinDirection.INPUT, FpgaPinDirection.OUTPUT, FpgaPinDirection.INOUT), design.ports.map { it.direction })
        assertEquals(listOf(1, 8, 2), design.ports.map { it.width })
        assertNull(design.ports.first().clockFrequencyHz)
    }

    @Test fun acceptsOnlyAnExplicitPositiveScalarInputClockRate() {
        val design = export().fpgaDesign(HdlLanguage.VHDL, mapOf("clock" to 50_000_000))
        assertEquals(FpgaHdlLanguage.VHDL, design.language)
        assertEquals(50_000_000L, design.ports.first().clockFrequencyHz)
        for (invalid in listOf(mapOf("clock" to 0L), mapOf("missing" to 10L), mapOf("result" to 50_000_000L))) {
            assertFailsWith<IllegalArgumentException> { export().fpgaDesign(HdlLanguage.VERILOG, invalid) }
        }
    }

    @Test fun refusesAnHdlExportWithAnyErrorDiagnostic() {
        val failed = export().copy(diagnostics = listOf(HdlExportDiagnostic("HDL_UNSUPPORTED", HdlDiagnosticSeverity.ERROR,
            "Unsupported component", "main", "component")))
        assertFailsWith<IllegalArgumentException> { failed.fpgaDesign(HdlLanguage.VERILOG) }
    }
}
