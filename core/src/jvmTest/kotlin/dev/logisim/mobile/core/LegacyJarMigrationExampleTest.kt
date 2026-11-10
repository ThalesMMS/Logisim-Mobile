package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegacyJarMigrationExampleTest {
    @Test
    fun approvedPortRunsAndReopensARealExternal271Circuit() {
        val xml = resource("inverter.circ")
        val envelope = ExtensionReferenceScanner.inspect(xml)
        val reference = envelope.jarLibraries.single()
        val provider = BusInverterMigrationProvider.provider
        val manifest = provider.manifest
        val base = MobileExtensionCatalog(ComponentCatalog.registry)

        assertEquals(xml, envelope.exportOriginal())
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml, base.registry))
        val approval = ExtensionApproval(manifest.fingerprint, manifest.capabilities, manifest.licenseId)
        val installed = assertIs<ExtensionRegistration.Installed>(base.install(provider, approval)).catalog
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml, installed.registry))

        val unrelatedXml = xml.replace("org.example.InverterLibrary", "unrelated.InverterLibrary")
        val unrelatedReference = ExtensionReferenceScanner.inspect(unrelatedXml).jarLibraries.single()
        assertFailsWith<IllegalArgumentException> { installed.bindLegacyLibrary(unrelatedReference, manifest.id) }

        val registry = installed.bindLegacyLibrary(reference, manifest.id)
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        val exported = CircCodec.writeProject(imported, registry)
        assertEquals(reference.descriptor, ExtensionReferenceScanner.inspect(exported).jarLibraries.single().descriptor)
        assertTrue(exported.contains("<tool name=\"Bus Inverter\"/>"))
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(exported, installed.registry))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(exported, registry)).project

        val original = resource("inverter.original.tsv").lineSequence().toList()
        val expected = original.filter { it.startsWith("V\t") }.associate { row ->
            val (_, input, _, output) = row.split('\t')
            input.toULong(2) to output.toULong(2)
        }
        assertEquals((0uL..15uL).toSet(), expected.keys)
        val originalPorts = original.single { it.startsWith("G\tmain\tBus Inverter\t") }.substringAfterLast('\t')
        val simulator = CircuitSimulator(registry = registry)
        for (project in listOf(imported, reopened)) {
            val circuit = project.mainCircuit
            val input = circuit.components.single { it.label == "A" }
            val output = circuit.components.single { it.label == "Y" }
            val inverter = circuit.components.single { it != input && it != output }
            val ports = registry.layout(inverter).ports.joinToString(";") { port ->
                val direction = when (port.direction) {
                    PortDirection.INPUT -> 1
                    PortDirection.OUTPUT -> 2
                    PortDirection.INOUT -> 3
                }
                "${port.position.x},${port.position.y}:${port.width}:$direction"
            }
            assertEquals(originalPorts, ports)
            for ((value, expectedOutput) in expected) {
                val state = simulator.settle(project, vectorInputs = mapOf(input.id to LogicVector.of(4, value)))
                assertEquals(expectedOutput, state.vectorValues.getValue(output.id).toULongOrNull(), "A=$value")
            }
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/legacy-jar-migration/$name")).readText()
}
