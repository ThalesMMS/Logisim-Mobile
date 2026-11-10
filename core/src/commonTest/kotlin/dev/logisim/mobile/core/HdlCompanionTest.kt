package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HdlCompanionTest {
    @Test fun importRequestIncludesExplicitSourceAndLibraryAndRejectsHostPaths() {
        val options = HdlImportOptions(listOf(HdlInputFile("design.v", "`include \"gates.vh\"\nmodule top;endmodule"),
            HdlInputFile("gates.vh", "`define WIDTH 2")), sourceNames = listOf("design.v"), top = "top")
        val request = options.request()
        assertEquals("yosys-import", request.operation)
        assertEquals(listOf("design.v", "gates.vh"), request.inputs.map { it.name })
        assertEquals(listOf(CompanionJsonString("design.v")), (request.parameters.getValue("sourceNames") as CompanionJsonArray).values)
        assertFailsWith<IllegalArgumentException> { HdlInputFile("../host.v", "module top;endmodule") }
        assertFailsWith<IllegalArgumentException> { options.copy(defines = listOf("X=1;exec evil")).request() }
        assertFailsWith<IllegalArgumentException> { options.copy(sourceNames = listOf("missing.v")).request() }
        assertFailsWith<IllegalArgumentException> { options.copy(sources = listOf(HdlInputFile("A.v", "x"), HdlInputFile("a.v", "y"))).request() }
        assertFailsWith<IllegalArgumentException> { HdlInputFile("source.v", "á".repeat(70_000)) }
    }

    @Test fun incompleteSynthesisNeverChangesExistingProject() {
        val project = CircuitProject(listOf(CircuitDocument("retained")))
        val failed = CompanionJob("a".repeat(32), "yosys-import", CompanionJobState.FAILED, CompanionJsonNull,
            CompanionJobError("synthesis-failed", "bad source", CompanionJsonNull), emptyList())
        val result = assertIs<HdlImportResult.Failure>(failed.importHdl(HdlImportOptions(listOf(HdlInputFile("a.v", "bad"))), project))
        assertEquals("synthesis-failed", result.diagnostics.single().code)
        assertEquals(listOf("retained"), project.circuits.map { it.name })
    }
}
