package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlImportTest {
    private fun bit(id: Int) = HdlBit.Net(id)
    private fun port(name: String, direction: PortDirection, vararg ids: Int) =
        HdlNetlistPort(name, direction, ids.map(::bit))
    private fun convert(netlist: HdlNetlist, existing: CircuitProject? = null): CircuitProject {
        val result = HdlNativeImporter.import(netlist,
            listOf(HdlInputFile("source.v", "module example; endmodule\n// <source> & retained")),
            existingProject = existing)
        return assertIs<HdlImportResult.Success>(result, result.toString()).project
    }

    @Test
    fun readsActualYosysShapeAndPreservesWidthsAndHierarchy() {
        val json = """{"creator":"Yosys","modules":{"top":{"attributes":{"top":"1"},
          "ports":{"a":{"direction":"input","bits":[2,3]},"y":{"direction":"output","bits":[4,5]}},
          "cells":{"instance":{"type":"child","parameters":{},"connections":{"a":[2,3],"y":[4,5]}}}},
          "child":{"ports":{"a":{"direction":"input","bits":[8,9]},"y":{"direction":"output","bits":[8,9]}},"cells":{}}}}"""
        val netlist = assertIs<HdlNetlistResult.Success>(YosysNetlistCodec.read(json)).netlist
        assertEquals("top", netlist.top)
        val project = convert(netlist)
        assertEquals(2, project.circuits.size)
        val simulator = CircuitSimulator()
        val input = project.mainCircuit.components.single { it.kind == ComponentKind.INPUT }
        val output = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        val state = simulator.settle(project, vectorInputs = mapOf(input.id to LogicVector.of(2, 2uL)))
        assertFalse(state.oscillating, state.diagnostics.toString())
        assertEquals(2uL, state.vectorValues.getValue(output.id).toULongOrNull())
        val xml = CircCodec.writeProject(project)
        assertTrue(xml.contains("&lt;source&gt; &amp; retained"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        val reopenedInput = reopened.mainCircuit.components.single { it.kind == ComponentKind.INPUT }
        val reopenedOutput = reopened.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        assertEquals(3uL, simulator.settle(reopened,
            vectorInputs = mapOf(reopenedInput.id to LogicVector.of(2, 3uL)))
            .vectorValues.getValue(reopenedOutput.id).toULongOrNull())
    }


    @Test
    fun selectedSourcesUseInertOptionsAndPreserveEscapedTextAcrossRoundTrip() {
        val text = "// café λ\r\n\t</a> & \"quotes\" 'apostrophes'\nmodule top; endmodule\n"
        val source = HdlInputFile("source-1.v", text)
        val netlist = HdlNetlist("top", listOf(HdlNetlistModule("top", emptyList(), emptyList())), "test")
        val imported = assertIs<HdlImportResult.Success>(HdlNativeImporter.import(netlist, listOf(source))).project
        val serialized = CircCodec.writeProject(imported)
        assertFalse(serialized.contains("<hdl-source"))
        assertTrue(serialized.contains("&#13;&#10;&#9;"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(serialized)).project
        val options = reopened.extraXml.map { CircXmlParser(it).parse() }.single { it.name == "options" }
        val archived = options.children.single()
        assertEquals("a", archived.name)
        assertEquals(mapOf("name" to "logisim-mobile-hdl-source", "file" to "source-1.v"), archived.attributes)
        assertEquals(text, archived.text)
        val repeated = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(reopened))).project
        val repeatedSource = repeated.extraXml.map { CircXmlParser(it).parse() }.single { it.name == "options" }.children.single()
        assertEquals(archived.attributes, repeatedSource.attributes)
        assertEquals(text, repeatedSource.text)
    }

    @Test
    fun customSymbolsDeclareInputAndOutputDirectionsForDesktopPortBinding() {
        val child = HdlNetlistModule("child", listOf(port("a", PortDirection.INPUT, 2),
            port("y", PortDirection.OUTPUT, 2)), emptyList())
        val top = HdlNetlistModule("top", listOf(port("a", PortDirection.INPUT, 2),
            port("y", PortDirection.OUTPUT, 3)), listOf(HdlNetlistCell("nested", "child",
                mapOf("a" to listOf(bit(2)), "y" to listOf(bit(3))))))
        val project = convert(HdlNetlist("top", listOf(top, child), "test"))
        val root = CircXmlParser(CircCodec.writeProject(project)).parse()
        for (circuit in root.children.filter { it.name == "circuit" }) {
            val appearance = circuit.children.single { it.name == "appear" }
            val ports = appearance.children.filter { it.name == "circ-port" }
            assertEquals(listOf("in", "out"), ports.map { it.attributes["dir"] })
            assertEquals(listOf("40,80", "1100,80"), ports.map { it.attributes["pin"] })
        }
    }

    @Test
    fun sourceNamesAcceptRelativePathsButRejectTraversalAndNonAsciiNames() {
        assertEquals("folder/source.v", HdlInputFile("folder/source.v", "module top; endmodule").name)
        for (name in listOf("../source.v", "folder/../source.v", "folder//source.v", "folder\\source.v", "fonte-ç.v", "/source.v")) {
            assertFailsWith<IllegalArgumentException>(name) { HdlInputFile(name, "module top; endmodule") }
        }
    }

    @Test
    fun scalarAndTruthTableUsesNativeEditableGates() {
        val module = HdlNetlistModule("top", listOf(port("a", PortDirection.INPUT, 2),
            port("b", PortDirection.INPUT, 3), port("y", PortDirection.OUTPUT, 4)),
            listOf(HdlNetlistCell("and0", "\$_AND_", mapOf("A" to listOf(bit(2)), "B" to listOf(bit(3)), "Y" to listOf(bit(4))))))
        val project = convert(HdlNetlist("top", listOf(module), "test"))
        assertEquals(1, project.mainCircuit.components.count { it.kind == ComponentKind.AND })
        val pins = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
        val output = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        for (a in 0..1) for (b in 0..1) {
            val state = CircuitSimulator().settle(project, inputs = mapOf(
                pins.getValue("a").id to if (a == 1) LogicValue.HIGH else LogicValue.LOW,
                pins.getValue("b").id to if (b == 1) LogicValue.HIGH else LogicValue.LOW))
            assertEquals(if (a == 1 && b == 1) LogicValue.HIGH else LogicValue.LOW, state.componentValues[output.id])
        }
    }

    @Test
    fun asynchronousFlipFlopDoesNotShortPresetAndClearTerminalStubs() {
        val module = HdlNetlistModule("top", listOf(port("d", PortDirection.INPUT, 2),
            port("clk", PortDirection.INPUT, 3), port("rst", PortDirection.INPUT, 4), port("q", PortDirection.OUTPUT, 5)),
            listOf(HdlNetlistCell("ff", "\$_DFF_PP0_", mapOf("D" to listOf(bit(2)), "C" to listOf(bit(3)),
                "R" to listOf(bit(4)), "Q" to listOf(bit(5))))))
        val project = convert(HdlNetlist("top", listOf(module), "test"))
        val pins = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
        val output = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        val simulator = CircuitSimulator()
        var state = simulator.settle(project, inputs = mapOf(pins.getValue("d").id to LogicValue.HIGH,
            pins.getValue("clk").id to LogicValue.LOW, pins.getValue("rst").id to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, state.componentValues[output.id])
        state = simulator.settle(project, state, inputs = mapOf(pins.getValue("rst").id to LogicValue.LOW))
        state = simulator.settle(project, state, inputs = mapOf(pins.getValue("clk").id to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, state.componentValues[output.id])
    }

    @Test
    fun failedImportIsDetachedAndReportsUnsupportedCellIdentity() {
        val existing = CircuitProject(listOf(CircuitDocument("retained")))
        val module = HdlNetlistModule("top", emptyList(), listOf(HdlNetlistCell("bad", "\$unmapped", emptyMap())))
        val result = assertIs<HdlImportResult.Failure>(HdlNativeImporter.import(
            HdlNetlist("top", listOf(module), "test"), emptyList(), existingProject = existing))
        assertEquals("unsupported-cell", result.diagnostics.single().code)
        assertEquals("bad", result.diagnostics.single().cell)
        assertEquals(listOf("retained"), existing.circuits.map { it.name })
    }

    @Test
    fun repeatedImportsGetDistinctNamesAndRetainOriginalProject() {
        val module = HdlNetlistModule("top", listOf(port("a", PortDirection.INPUT, 2), port("y", PortDirection.OUTPUT, 2)), emptyList())
        val netlist = HdlNetlist("top", listOf(module), "test")
        val original = convert(netlist)
        val next = convert(netlist, original)
        assertEquals(listOf("hdl_top", "hdl_top_2"), next.circuits.map { it.name })
        assertEquals(original.mainCircuit, next.circuits.first())
        val customId = CircuitProject(listOf(CircuitDocument("renamed", id = "hdl_top")))
        assertEquals("hdl_top_2", convert(netlist, customId).mainCircuit.name)
    }

    @Test
    fun explicitUndefinedAndAliasedBidirectionalPortsAreNeverSilentlyReplaced() {
        val x = HdlNetlistModule("top", listOf(HdlNetlistPort("y", PortDirection.OUTPUT, listOf(HdlBit.Constant('x')))), emptyList())
        assertEquals("undefined-constant", assertIs<HdlImportResult.Failure>(HdlNativeImporter.import(
            HdlNetlist("top", listOf(x), "test"), emptyList())).diagnostics.single().code)
        val fixed = assertIs<HdlImportResult.Success>(HdlNativeImporter.import(HdlNetlist("top", listOf(x), "test"),
            emptyList(), HdlUndefinedPolicy.ONE)).project
        val output = fixed.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        assertEquals(LogicValue.HIGH, CircuitSimulator().settle(fixed).componentValues[output.id])
        val inout = x.copy(ports = listOf(port("io", PortDirection.INOUT, 1), port("other", PortDirection.INOUT, 1)))
        assertEquals("bidirectional-port", assertIs<HdlImportResult.Failure>(HdlNativeImporter.import(
            HdlNetlist("top", listOf(inout), "test"), emptyList())).diagnostics.single().code)
    }

    @Test
    fun malformedJsonAndRecursiveHierarchyFailBeforeConversion() {
        assertIs<HdlNetlistResult.Failure>(YosysNetlistCodec.read("""{"modules":{},"modules":{}}"""))
        assertIs<HdlNetlistResult.Failure>(YosysNetlistCodec.read("""{"modules":{"top":{"ports":{},
            "cells":{"self":{"type":"top","connections":{}}}}}}"""))
        assertIs<HdlNetlistResult.Failure>(YosysNetlistCodec.read("""{"modules":{"top":{"ports":{"bad":
            {"direction":"input","bits":[1.5]}}}}}"""))
    }

    @Test fun independentHighImpedanceOutputsStaySeparateWhenTheImportedCircuitIsEdited() {
        val floating = HdlBit.Constant('z')
        val module = HdlNetlistModule("top", listOf(
            HdlNetlistPort("a", PortDirection.OUTPUT, listOf(floating)),
            HdlNetlistPort("b", PortDirection.OUTPUT, listOf(floating)),
        ), emptyList())
        val imported = convert(HdlNetlist("top", listOf(module), "test"))
        val pins = imported.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }.associateBy { it.label }
        val first = pins.getValue("a")
        val driver = CircuitComponent("new-driver", ComponentKind.INPUT, first.position.copy(x = first.position.x - 100))
        val edited = imported.mainCircuit.copy(components = imported.mainCircuit.components + driver,
            wires = imported.mainCircuit.wires + WireSegment(driver.position, first.position))
        val state = CircuitSimulator().settle(imported.withCircuit(edited), inputs = mapOf(driver.id to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, state.componentValues[first.id])
        assertEquals(LogicValue.UNKNOWN, state.componentValues[pins.getValue("b").id])
    }
}
