package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FpgaGenerationOptionsTest {
    private fun gatedProject(): Pair<CircuitProject, CircuitComponent> {
        val flip = CircuitComponent("flip", ComponentKind.D_FLIP_FLOP, GridPoint(300, 200))
        val ports = flip.layout().ports.associateBy { it.name }
        val gate = CircuitComponent("gate", ComponentKind.AND, ports.getValue("clock").position)
        val document = CircuitDocument("main", listOf(flip, gate,
            CircuitComponent("clock", ComponentKind.CLOCK, gate.layout().inputs.first().position),
            CircuitComponent("data", ComponentKind.INPUT, ports.getValue("d").position),
            CircuitComponent("result", ComponentKind.OUTPUT, ports.getValue("q").position)))
        return CircuitProject(listOf(document)) to gate
    }

    @Test
    fun evolutionDefaultsUseUpperCaseKeywordsAndReportEveryWarning() {
        val defaults = FpgaGenerationOptions()
        assertTrue(defaults.vhdlKeywordsUpperCase)
        assertFalse(defaults.suppressGatedClockWarnings)
        assertFalse(defaults.suppressOpenPinWarnings)
    }

    @Test
    fun suppressesOnlyTheSelectedWarningGroups() {
        val (project, _) = gatedProject()
        val keys = { options: FpgaGenerationOptions ->
            FpgaCircuitRules.review(project, HdlLanguage.VERILOG, options = options).diagnostics.map { it.notice.key }
        }
        val all = keys(FpgaGenerationOptions())
        assertTrue("fpga_core_clock_gated" in all && "fpga_core_terminal_open" in all, all.toString())

        val noGated = keys(FpgaGenerationOptions(suppressGatedClockWarnings = true))
        assertFalse("fpga_core_clock_gated" in noGated)
        assertTrue("fpga_core_terminal_open" in noGated)

        val noOpen = keys(FpgaGenerationOptions(suppressOpenPinWarnings = true))
        assertTrue("fpga_core_clock_gated" in noOpen)
        assertFalse("fpga_core_terminal_open" in noOpen)
    }

    @Test
    fun suppressionKeepsErrors() {
        val at = GridPoint(120, 100)
        val end = GridPoint(180, 100)
        val document = CircuitDocument("main", listOf(
            CircuitComponent("sourceA", ComponentKind.INPUT, at),
            CircuitComponent("sourceB", ComponentKind.INPUT, end),
            CircuitComponent("result", ComponentKind.OUTPUT, end)), listOf(WireSegment(at, end)))
        val options = FpgaGenerationOptions(suppressGatedClockWarnings = true, suppressOpenPinWarnings = true)
        val review = FpgaCircuitRules.reviewForFpga(CircuitProject(listOf(document)), HdlLanguage.VERILOG, options = options)
        assertEquals(FpgaDiagnosticSeverity.ERROR, review.diagnostics.single { it.notice.key == "fpga_core_circuit_short" }.severity)
    }

    @Test
    fun vhdlKeywordCaseFollowsThePreferenceInModulesAndTheFpgaTopLevel() {
        val (project, _) = gatedProject()
        val upper = HdlExporter.export(project, HdlLanguage.VHDL)
        val lower = HdlExporter.export(project, HdlLanguage.VHDL, options = FpgaGenerationOptions(vhdlKeywordsUpperCase = false))
        val upperText = upper.files.joinToString("\n") { it.content }
        val lowerText = lower.files.joinToString("\n") { it.content }
        assertTrue("ENTITY c_main IS" in upperText && "ARCHITECTURE rtl OF c_main IS" in upperText, upperText)
        assertTrue("std_logic_vector" in upperText && "ieee.std_logic_1164.ALL" in upperText, upperText)
        assertTrue("entity c_main is" in lowerText && "architecture rtl of c_main is" in lowerText, lowerText)
        assertEquals(lowerText.lowercase(), upperText.lowercase(), "Only keyword case differs.")
        assertEquals(upper.ports, lower.ports)

        val design = assertNotNull(FpgaCircuitRules.review(project, HdlLanguage.VHDL).design)
        assertTrue(design.vhdlKeywordsUpperCase)
        assertFalse(assertNotNull(FpgaCircuitRules.review(project, HdlLanguage.VHDL,
            options = FpgaGenerationOptions(vhdlKeywordsUpperCase = false)).design).vhdlKeywordsUpperCase)
    }

    @Test
    fun keywordCaseLeavesCommentsLiteralsAttributesAndIdentifiersAlone() {
        val source = "-- entity is a comment\nsignal s_in : std_logic := '0'; -- end\n" +
            "x <= \"in out\" when clk'event and rising_edge(clk) else y'range;\nportable <= b\"01\";\n/* begin */ END ENTITY;\n"
        assertEquals("-- entity is a comment\nSIGNAL s_in : std_logic := '0'; -- end\n" +
            "x <= \"in out\" WHEN clk'event AND rising_edge(clk) ELSE y'RANGE;\nportable <= b\"01\";\n/* begin */ END ENTITY;\n",
            VhdlKeywordCase.apply(source, upperCase = true))
        assertEquals("-- entity is a comment\nsignal s_in : std_logic := '0'; -- end\n" +
            "x <= \"in out\" when clk'event and rising_edge(clk) else y'range;\nportable <= b\"01\";\n/* begin */ end entity;\n",
            VhdlKeywordCase.apply(source, upperCase = false))
    }

    @Test
    fun vhdlEntitySourcesKeepTheirText() {
        val result = HdlExporter.export(inlineVhdlProject(), HdlLanguage.VHDL)
        assertTrue(result.successful, result.diagnostics.joinToString())
        assertEquals(inlineVhdlSource, result.files.single { it.path.startsWith("hdl_ip_") }.content)
        assertTrue("v_ip_0 : ENTITY work.C_IP_Demo GENERIC MAP (Flip => false)" in result.files.last().content)
    }
}
