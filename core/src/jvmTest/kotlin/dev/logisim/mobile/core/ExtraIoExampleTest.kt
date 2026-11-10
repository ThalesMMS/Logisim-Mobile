package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtraIoExampleTest {
    @Test
    fun bundledBfhExampleShowsThreeTwoOneAndTheSegmentsForOne() {
        val file = sequenceOf(File("../examples/extra-converters.circ"), File("examples/extra-converters.circ")).first { it.isFile }
        val registry = ComponentCatalog.registry.withDefinitions(ExtraIoComponents.definitions())
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(file.readText(), registry)).project
        val state = CircuitSimulator(registry = registry).settle(project)
        val values = project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }
            .associate { it.label to state.vectorValues.getValue(it.id).toULongOrNull() }
        assertEquals(1uL, values["ones"])
        assertEquals(2uL, values["tens"])
        assertEquals(3uL, values["hundreds"])
        assertEquals(mapOf("segment_a" to 0uL, "segment_b" to 1uL, "segment_c" to 1uL,
            "segment_d" to 0uL, "segment_e" to 0uL, "segment_f" to 0uL, "segment_g" to 0uL),
            values.filterKeys { it.startsWith("segment_") })
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString())
        assertFalse(state.oscillating)
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project, registry), registry))
    }
}
