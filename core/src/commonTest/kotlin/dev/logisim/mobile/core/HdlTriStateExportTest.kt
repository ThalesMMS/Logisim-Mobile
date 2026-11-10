package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlTriStateExportTest {
    @Test
    fun controlledDriversCanShareOneResolvedBus() {
        for (language in HdlLanguage.entries) {
            val export = HdlExporter.export(HdlTriStateFixtures.controlled(), language)
            assertTrue(export.successful, export.diagnostics.toString())
            assertEquals(PortDirection.OUTPUT, export.ports.single { it.name == "p_bus" }.direction)
        }
    }

    @Test
    fun portIoKeepsDirectionAndBitOrderThroughFpgaMapping() {
        val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read("""
            <TEST_BOARD><BoardInformation>
              <FPGAInformation Vendor="OPENFPGA" Family="epc5" Part="85k" Package="CABGA381" Speedgrade="5"/>
            </BoardInformation><IOComponents>
              <Pin Label="Header" BiDirPinSet="C3,C4,C5,C6,C7,C8,C9,C10"/>
            </IOComponents></TEST_BOARD>
        """.trimIndent())).board
        for (language in HdlLanguage.entries) {
            val export = HdlExporter.export(HdlTriStateFixtures.portIo("IOSingleEnable"), language)
            assertTrue(export.successful, export.diagnostics.toString())
            assertEquals(HdlExportPort("io_header", PortDirection.INOUT, 2, "ports", "port"), export.ports.last())
            val design = export.fpgaDesign(language)
            assertEquals(FpgaDesignPort("io_header", FpgaPinDirection.INOUT, 2,
                vhdlVector = language == HdlLanguage.VHDL), design.ports.last())
            val bindings = design.ports.flatMap { port -> (0 until port.width).map { FpgaSignalBit(port.name, it) } }
                .zip(board.pins.map { FpgaPinBinding.Physical(it.location) }).toMap()
            val plan = assertIs<FpgaPlanResult.Success>(
                FpgaProjectGenerator.generate(design, board, bindings, FpgaFlow.OPEN_ECP5)
            ).plan
            val constraints = plan.files.single { it.path.endsWith(".lpf") }.content
            assertTrue(constraints.contains("LOCATE COMP \"fpga_p3_b0\" SITE \"C8\";"), constraints)
            assertTrue(constraints.contains("LOCATE COMP \"fpga_p3_b1\" SITE \"C9\";"), constraints)
        }
    }

    @Test
    fun twoChildInstancesExposeDistinctPhysicalPorts() {
        for (language in HdlLanguage.entries) {
            val export = HdlExporter.export(HdlTriStateFixtures.hierarchy(), language)
            assertTrue(export.successful, export.diagnostics.toString())
            assertEquals(
                listOf("io_v_left_io_header", "io_v_right_io_header"),
                export.ports.filter { it.direction == PortDirection.INOUT }.map { it.name },
            )
            assertTrue(export.ports.filter { it.direction == PortDirection.INOUT }
                .all { it.sourceCircuitId == "ports" && it.sourceComponentId == "port" && it.width == 2 })
        }
    }
}

internal object HdlTriStateFixtures {
    private fun pin(id: String, kind: ComponentKind, at: GridPoint, width: Int) =
        CircuitComponent(id, kind, at, id, mapOf("width" to width.toString()))

    fun controlled(): CircuitProject {
        val buffer = CircuitComponent("buf", ComponentKind.CONTROLLED_BUFFER, GridPoint(100, 100),
            attributes = mapOf("width" to "2"))
        val inverter = CircuitComponent("inv", ComponentKind.CONTROLLED_INVERTER, GridPoint(100, 160),
            attributes = mapOf("width" to "2"))
        val pins = listOf(buffer, inverter).flatMap { gate -> gate.layout().inputs.map {
            pin("${gate.id}_${it.name}", ComponentKind.INPUT, it.position, it.width)
        } }
        return CircuitProject(listOf(CircuitDocument("buffers",
            listOf(buffer, inverter, pin("bus", ComponentKind.OUTPUT, buffer.position, 2)) + pins,
            listOf(WireSegment(buffer.position, inverter.position)))))
    }

    fun portIo(mode: String): CircuitProject {
        val port = CircuitComponent("port", ExternalIoComponents.PortIo, GridPoint(100, 100), "Header",
            mapOf("number" to "2", "direction" to mode))
        val pins = port.layout().ports.map {
            pin(it.name, if (it.direction == PortDirection.INPUT) ComponentKind.INPUT else ComponentKind.OUTPUT, it.position, it.width)
        }
        return CircuitProject(listOf(CircuitDocument("ports", listOf(port) + pins)))
    }

    fun hierarchy(): CircuitProject {
        val child = portIo("IOSingleEnable").mainCircuit
        val ports = child.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            .mapIndexed { index, pin ->
                val direction = if (pin.kind == ComponentKind.INPUT) PortDirection.INPUT else PortDirection.OUTPUT
                ComponentPort(pin.id, GridPoint(if (direction == PortDirection.INPUT) -40 else 40, index * 20),
                    direction, pin.width)
            }
        val instances = listOf("left", "right").mapIndexed { index, id ->
            CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(300, 200 + index * 140),
                circuitName = child.name, explicitPorts = ports)
        }
        val base = CircuitProject(listOf(CircuitDocument("parent", instances), child), "parent")
        val main = base.mainCircuit
        val pins = main.components.flatMap { instance ->
            base.subcircuitPortBindings(instance).map { (point, childPin) ->
                pin("${instance.id}_${childPin.id}", childPin.kind, point, childPin.width)
            }
        }
        return CircuitProject(listOf(CircuitDocument("parent", instances + pins), child), "parent")
    }
}
