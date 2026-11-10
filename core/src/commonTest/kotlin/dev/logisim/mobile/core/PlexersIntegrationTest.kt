package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PlexersIntegrationTest {
    private val registry = PlexersLibrary.install(ComponentCatalog.registry)

    private data class Sample(
        val kind: ComponentKind,
        val attributes: Map<String, String>,
        val inputs: Map<String, ULong>,
        val outputs: Map<String, ULong>,
    )

    @Test
    fun allFiveComponentsImportRunExportAndRunAgain() {
        val samples = listOf(
            Sample(PlexersLibrary.Multiplexer, mapOf("width" to "8", "select" to "2", "enable" to "true"),
                mapOf("in0" to 0x12uL, "in1" to 0xA5uL, "in2" to 0x66uL, "in3" to 0xFFuL, "select" to 1uL, "enable" to 1uL),
                mapOf("out" to 0xA5uL)),
            Sample(PlexersLibrary.Demultiplexer, mapOf("width" to "8", "select" to "2"),
                mapOf("data" to 0xA5uL, "select" to 2uL),
                mapOf("out0" to 0uL, "out1" to 0uL, "out2" to 0xA5uL, "out3" to 0uL)),
            Sample(PlexersLibrary.Decoder, mapOf("select" to "2"),
                mapOf("select" to 1uL, "enable" to 1uL),
                mapOf("out0" to 0uL, "out1" to 1uL, "out2" to 0uL, "out3" to 0uL)),
            Sample(PlexersLibrary.PriorityEncoder, mapOf("select" to "2"),
                mapOf("in0" to 0uL, "in1" to 1uL, "in2" to 1uL, "in3" to 0uL, "enable" to 1uL),
                mapOf("out" to 2uL, "enableOut" to 0uL, "groupSelect" to 1uL)),
            Sample(PlexersLibrary.BitSelector, mapOf("width" to "8", "group" to "3"),
                mapOf("data" to 0xD5uL, "select" to 2uL), mapOf("out" to 3uL)),
        )
        for (sample in samples) {
            val device = registry.create(sample.kind, "device", GridPoint(300, 300), sample.attributes)
            val layout = registry.layout(device)
            val pins = layout.ports.map { port ->
                CircuitComponent("pin-${port.name}", if (port.direction == PortDirection.INPUT) ComponentKind.INPUT else ComponentKind.OUTPUT,
                    port.position, label = port.name, attributes = mapOf("width" to port.width.toString()))
            }
            var project = CircuitProject(listOf(CircuitDocument("main", listOf(device) + pins)))
            repeat(2) { round ->
                val doc = project.mainCircuit
                val drives = doc.components.filter { it.kind == ComponentKind.INPUT }.associate { pin ->
                    pin.id to LogicVector.of(pin.width, sample.inputs.getValue(pin.label))
                }
                val state = CircuitSimulator(registry = registry).settle(project, vectorInputs = drives)
                assertFalse(state.oscillating, "${sample.kind.name}, round $round: ${state.diagnostics}")
                for (pin in doc.components.filter { it.kind == ComponentKind.OUTPUT }) {
                    assertEquals(sample.outputs.getValue(pin.label), state.vectorValues.getValue(pin.id).toULongOrNull(),
                        "${sample.kind.name} ${pin.label}, round $round")
                }
                val xml = CircCodec.writeProject(project, registry)
                project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
                assertEquals(sample.attributes, project.mainCircuit.components.single { it.kind == sample.kind }.attributes
                    .filterKeys { it in sample.attributes }, sample.kind.name)
            }
        }
    }

    @Test
    fun legacyFilesUseHistoricalEnableDefaultsWithoutAddingAttributesToXml() {
        for ((fileName, version, enabled) in listOf(
            Triple("Multiplexer", "3.6.1", true), Triple("Multiplexer", "3.6.2", false),
            Triple("Demultiplexer", "3.6.1", true), Triple("Demultiplexer", "3.6.2", false),
            Triple("Decoder", "2.6.3", false), Triple("Decoder", "2.6.4", true),
        )) {
            val xml = """<project source="$version" version="1.0"><lib name="2" desc="#Plexers"/><main name="main"/><circuit name="main"><comp lib="2" loc="(100,100)" name="$fileName"/></circuit></project>"""
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
            val component = project.mainCircuit.components.single()
            assertEquals(enabled, registry.layout(component).inputs.any { it.name == "enable" }, "$fileName $version")
            assertFalse(Regex("<a[^>]+name=\"enable\"").containsMatchIn(CircCodec.writeProject(project, registry)))
        }
    }
}
