package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdlIpExportTest {
    @Test
    fun repeatedEntitySharesSourceAndPreservesInstanceBindings() {
        val result = HdlExporter.export(inlineVhdlProject(), HdlLanguage.VHDL)

        assertTrue(result.successful, result.diagnostics.joinToString())
        assertEquals("c_ip_demo_2", result.topLevelName)
        assertEquals(listOf("hdl_ip_c_ip_demo.vhd", "c_ip_demo_2.vhd"), result.files.map { it.path })
        assertEquals(inlineVhdlSource, result.files.first().content)
        val top = result.files.last().content
        assertTrue("v_ip_0 : ENTITY work.C_IP_Demo GENERIC MAP (Flip => false)" in top)
        assertTrue("v_ip_1 : ENTITY work.C_IP_Demo GENERIC MAP (Flip => true)" in top)
    }

    @Test
    fun verilogAndConflictingVhdlDefinitionsFailWithoutPartialFiles() {
        val verilog = HdlExporter.export(inlineVhdlProject(), HdlLanguage.VERILOG)
        assertFalse(verilog.successful)
        assertTrue(verilog.files.isEmpty())

        val result = HdlExporter.export(inlineVhdlProject(inlineVhdlSource.replace("C_IP_Demo", "c_ip_demo").replace("not Data", "Data")), HdlLanguage.VHDL)
        assertFalse(result.successful)
        assertTrue(result.files.isEmpty())
        assertTrue(result.diagnostics.any {
            it.code == "HDL_UNSUPPORTED_CONFIGURATION" && it.componentId == "ip-1" && it.reason.arguments["entity"] == "c_ip_demo"
        }, result.diagnostics.toString())
    }
}

internal val inlineVhdlSource = """
    library ieee;
    use ieee.std_logic_1164.all;
    entity C_IP_Demo is
      generic (Flip : boolean := false);
      port (Enable : in std_logic; Data : in std_logic_vector(4 to 5);
        Single : out std_logic; Result : out std_logic_vector(4 to 5));
    end entity;
    architecture rtl of C_IP_Demo is
    begin
      Single <= Enable;
      Result <= not Data when Enable = '1' and Flip else Data;
    end architecture;
""".trimIndent()

internal fun inlineVhdlProject(secondSource: String = inlineVhdlSource): CircuitProject {
    val components = (0..1).flatMap { index ->
        val ip = CircuitComponent("ip-$index", HdlIpLibrary.VHDL, GridPoint(100, 100 + index * 100),
            attributes = mapOf("content" to if (index == 0) inlineVhdlSource else secondSource,
                "generic.flip" to (index != 0).toString()))
        listOf(ip) + ip.layout().ports.map { terminal ->
            val name = "${terminal.name.lowercase()}_$index"
            CircuitComponent(name, if (terminal.direction == PortDirection.INPUT) ComponentKind.INPUT else ComponentKind.OUTPUT,
                terminal.position, label = name, attributes = mapOf("width" to terminal.width.toString()))
        }
    }
    return CircuitProject(listOf(CircuitDocument("IP Demo", components)))
}
