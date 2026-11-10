package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrayCodeExtensionTest {
    private val provider = GrayCodeExtension.provider

    private fun installed(): MobileExtensionCatalog = assertIs<ExtensionRegistration.Installed>(
        MobileExtensionCatalog(ComponentCatalog.registry).install(provider,
            ExtensionApproval(provider.manifest.fingerprint, provider.manifest.capabilities, provider.manifest.licenseId))).catalog

    @Test
    fun legacyProjectRequiresExplicitBindingAndKeepsItsDescriptor() {
        val envelope = ExtensionReferenceScanner.inspect(legacyXml)
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(legacyXml))
        val catalog = installed()
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(legacyXml, catalog.registry))
        val registry = catalog.bindLegacyLibrary(envelope.jarLibraries.single(), provider.manifest.id)
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacyXml, registry)).project
        val incrementer = project.mainCircuit.components.single { it.kind.name.startsWith("GrayCodeIncrementer") }
        val layout = registry.layout(incrementer)
        assertEquals(listOf(GridPoint(170, 100), GridPoint(200, 100)), layout.ports.map { it.position })
        assertEquals(listOf(4, 4), layout.ports.map { it.width })
        val exported = CircCodec.writeProject(project, registry)
        assertTrue(exported.contains("jar#gray-1.0.jar#com.cburch.gray.Components"))
        assertTrue(exported.contains("<tool name=\"Gray Code Incrementer\"/>"))
        assertTrue(exported.contains("<a name=\"legacy-metadata\" val=\"keep me\"/>"))
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(exported, registry))
        assertEquals(legacyXml, envelope.exportOriginal())
        assertFailsWith<IllegalArgumentException> {
            catalog.bindLegacyLibrary(envelope.jarLibraries.single().copy(className = "unknown.Components"), provider.manifest.id)
        }
    }

    @Test
    fun legacyCircuitRunsCompleteFourBitSequenceFromLogisim271() {
        val registry = installed().bindLegacyLibrary(ExtensionReferenceScanner.inspect(legacyXml).jarLibraries.single(), provider.manifest.id)
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacyXml, registry)).project
        val input = project.mainCircuit.components.single { it.kind == ComponentKind.INPUT }
        val output = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        val simulator = CircuitSimulator(registry = registry)
        // Full cycle independently recorded from the original 2.7.1 example.
        val cycle = listOf(0, 1, 3, 2, 6, 7, 5, 4, 12, 13, 15, 14, 10, 11, 9, 8, 0)
        for ((previous, expected) in cycle.zipWithNext()) {
            val result = simulator.settle(project, vectorInputs = mapOf(input.id to LogicVector.of(4, previous.toULong())))
            assertEquals(expected.toULong(), result.vectorValues.getValue(output.id).toULongOrNull(), "input=$previous")
        }
    }

    @Test
    fun providerPreservesOriginalWidthsUndefinedInputsAndDelay() {
        val registry = installed().registry
        val definition = registry.requireDefinition(GrayCodeExtension.incrementerKind)
        assertEquals(4, registry.create(definition.kind, "default", GridPoint(0, 0)).width)
        for (bits in listOf(1, 4, 32)) {
            val component = registry.create(definition.kind, "gray", GridPoint(0, 0), mapOf("width" to bits.toString()))
            val attributes = registry.attributes(component)
            for (input in listOf(LogicVector.unknown(bits), LogicVector.error(bits), LogicVector.low(bits).withBit(0, LogicValue.UNKNOWN))) {
                val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component, attributes, mapOf("in" to input), setOf("in")))
                assertEquals(LogicVector.error(bits), result.outputs.getValue("out"))
                assertEquals(bits + 1L, result.delay)
            }
            val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component, attributes,
                mapOf("in" to LogicVector.of(bits, 1uL shl (bits - 1))), setOf("in")))
            assertEquals(LogicVector.low(bits), result.outputs.getValue("out"))
        }
        assertFailsWith<IllegalArgumentException> { registry.create(definition.kind, "bad", GridPoint(0, 0), mapOf("width" to "33")) }
        assertEquals(provider.manifest, MobileExtensionManifestCodec.read(MobileExtensionManifestCodec.write(provider.manifest)))
    }

    companion object {
        val legacyXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="jar#gray-1.0.jar#com.cburch.gray.Components" name="1"><tool name="Gray Code Incrementer"/></lib>
              <main name="main"/>
              <circuit name="main">
                <wire from="(100,100)" to="(170,100)"/>
                <wire from="(200,100)" to="(260,100)"/>
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="a"/><a name="width" val="4"/></comp>
                <comp lib="1" loc="(200,100)" name="Gray Code Incrementer"><a name="legacy-metadata" val="keep me"/></comp>
                <comp lib="0" loc="(260,100)" name="Pin"><a name="facing" val="west"/><a name="label" val="y"/><a name="output" val="true"/><a name="width" val="4"/></comp>
              </circuit>
            </project>
        """.trimIndent()
    }
}
