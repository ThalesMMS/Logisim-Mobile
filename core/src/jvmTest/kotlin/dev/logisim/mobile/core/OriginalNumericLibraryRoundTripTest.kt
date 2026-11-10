package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OriginalNumericLibraryRoundTripTest {
    @Test
    fun importsOriginal271NumericExternalCircuit() {
        val resolved = loadFixture()
        val definition = assertNumericInstance(resolved.project)
        assertEquals(listOf(definition.name), resolved.snapshots.single { it.sourceId == "7400-lib.circ" }.circuitNames)
    }

    @Test
    fun portableBundleUsesTheNumericNameFromTheReferencedLibrary() {
        val resolved = loadFixture()
        val originalDefinition = assertNumericInstance(resolved.project)
        val exported = CircLibraryCodec.bundle(resolved)
        val archive = CircBundleCodec.write(exported)
        val bundle = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(archive)).bundle

        val mainXml = CircXmlParser(bundle.text(bundle.mainFile)).parse()
        val instance = mainXml.children.single { it.name == "circuit" }
            .children.single { it.name == "comp" }
        assertEquals("7400", instance.attributes["name"])
        val library = mainXml.children.single {
            it.name == "lib" && it.attributes["name"] == instance.attributes["lib"]
        }
        val libraryPath = BundlePaths.resolve(bundle.mainFile, library.attributes.getValue("desc").removePrefix("file#"))
        val libraryXml = CircXmlParser(bundle.text(libraryPath)).parse()
        assertEquals("2.7.1", libraryXml.attributes["source"])
        assertEquals("7400", libraryXml.children.single { it.name == "circuit" }.attributes["name"])

        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle)).resolved
        val reopenedDefinition = assertNumericInstance(reopened.project)
        assertEquals(originalDefinition.id, reopenedDefinition.id)
        assertEquals(originalDefinition.components.map { it.id }, reopenedDefinition.components.map { it.id })
        assertEquals(originalDefinition.components.map { it.attributes }, reopenedDefinition.components.map { it.attributes })
        assertEquals(originalDefinition.wires, reopenedDefinition.wires)
    }

    private fun loadFixture(): ResolvedCircuitProject = assertIs<CircLibraryImportResult.Success>(
        CircLibraryCodec.read(fixture("dependent.circ"), "dependent.circ", CircuitLibraryResolver { from, reference ->
            assertEquals("dependent.circ", from)
            assertEquals("7400-lib.circ", reference)
            CircuitLibrarySource(reference, fixture(reference))
        }),
    ).resolved

    private fun fixture(name: String): String = assertNotNull(
        javaClass.getResourceAsStream("/original-2.7.1/numeric-external/$name"),
    ).bufferedReader().use { it.readText() }

    private fun assertNumericInstance(project: CircuitProject): CircuitDocument {
        assertEquals(2, project.circuits.size)
        assertEquals("2.7.1", project.sourceVersion)
        val instance = project.mainCircuit.components.single()
        assertEquals(ComponentKind.SUBCIRCUIT, instance.kind)
        assertEquals(GridPoint(200, 200), instance.position)
        val definition = assertNotNull(project.circuit(assertNotNull(instance.circuitName)))
        assertEquals("7400", definition.attributes["clabel"])
        assertEquals(4, definition.components.count { it.kind == ComponentKind.NAND })
        assertEquals(14, project.subcircuitPortBindings(instance).size)
        assertEquals(
            listOf(
                GridPoint(170, 200), GridPoint(170, 210), GridPoint(170, 220), GridPoint(170, 230),
                GridPoint(170, 240), GridPoint(170, 250), GridPoint(170, 260),
                GridPoint(200, 200), GridPoint(200, 210), GridPoint(200, 220), GridPoint(200, 230),
                GridPoint(200, 240), GridPoint(200, 250), GridPoint(200, 260),
            ),
            instance.layout().ports.map { it.position }.sortedWith(compareBy({ it.x }, { it.y })),
        )
        return definition
    }
}
