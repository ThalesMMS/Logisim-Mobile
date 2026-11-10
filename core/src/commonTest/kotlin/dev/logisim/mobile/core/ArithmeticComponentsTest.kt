package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArithmeticComponentsTest {
    private val registry = ComponentRegistry(ArithmeticComponents.definitions())

    @Test fun everyActiveEntryResolvesAndHasAnEvaluableDefinition() {
        assertEquals(13, registry.definitions.size)
        for (operation in ArithmeticOperation.entries) {
            val definition = registry.resolve("#Arithmetic", operation.fileName, emptyMap())!!
            assertTrue(definition.isCombinational)
            val component = registry.create(definition.kind, operation.name, GridPoint(200, 100))
            val attributes = registry.attributes(component)
            val layout = registry.layout(component)
            val inputs = layout.inputs.associate { it.name to LogicVector.low(it.width) }
            val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component, attributes, inputs, inputs.keys))
            assertEquals(layout.outputs.map { it.name }.toSet(), result.outputs.keys)
            for (port in layout.outputs) assertEquals(port.width, result.outputs.getValue(port.name).width)
        }
    }

    @Test fun multiplierHasWideCarryPortsAndFixedGeometry() {
        val component = registry.create(ComponentKind("MULTIPLIER"), "multiply", GridPoint(200, 100),
            mapOf("width" to "64", "mode" to "signedUnsigned", "facing" to "north"))
        assertEquals(listOf(GridPoint(160, 90), GridPoint(160, 110), GridPoint(180, 80)),
            registry.layout(component).inputs.map { it.position })
        assertEquals(listOf(64, 64, 64), registry.layout(component).inputs.map { it.width })
        assertEquals(listOf(GridPoint(200, 100), GridPoint(180, 120)),
            registry.layout(component).outputs.map { it.position })
    }

    @Test fun multiplierKeepsItsSourceArithmeticWhenSavedAndReopened() {
        val original = HdlFixtureCases.originalMultiplier().project
        val modern = original.withCircuit(original.mainCircuit.copy(components = original.mainCircuit.components.map {
            if (it.kind == ComponentKind("MULTIPLIER")) registry.create(it.kind, it.id, it.position, mapOf("width" to "32"))
            else it
        })).copy(sourceVersion = "5.1.0-dev")

        val simulator = CircuitSimulator()
        fun checkProduct(project: CircuitProject, high: ULong) {
            val result = simulator.settle(project, vectorInputs = mapOf(
                "i0" to LogicVector.of(32, 0x80000000uL),
                "i1" to LogicVector.of(32, 2uL),
                "i2" to LogicVector.low(32),
            ))
            assertEquals(0uL, result.vectorValues["o0"]?.toULongOrNull())
            assertEquals(high, result.vectorValues["o1"]?.toULongOrNull())
        }

        checkProduct(original, 0xffffffffuL)
        checkProduct(modern, 1uL)
        for (destination in listOf("2.7.1", "5.1.0-dev")) {
            for ((project, high) in listOf(original to 0xffffffffuL, modern to 1uL)) {
                val saved = CircCodec.writeProject(project.copy(sourceVersion = destination))
                checkProduct(assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved)).project, high)
            }
        }
    }

    @Test fun defaultsAndTypedSchemasPreserveUnknownMetadata() {
        val exponent = registry.create(ComponentKind("EXPONENTIATOR"), "power", GridPoint(0, 0))
        assertEquals("32", exponent.attributes["width"])
        val comparator = registry.create(ComponentKind("COMPARATOR"), "compare", GridPoint(0, 0))
        assertEquals("twosComplement", comparator.attributes["mode"])
        val count = registry.create(ComponentKind("BIT_ADDER"), "count", GridPoint(0, 0),
            mapOf("inputs" to "64", "width" to "64", "future-field" to "preserved"))
        assertEquals(13, registry.layout(count).outputs.single().width)
        assertEquals("preserved", registry.attributes(count).raw["future-field"])
        assertFailsWith<IllegalArgumentException> {
            registry.create(ComponentKind("MULTIPLIER"), "bad", GridPoint(0, 0), mapOf("width" to "65"))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.create(ComponentKind("DIVIDER"), "bad", GridPoint(0, 0), mapOf("mode" to "signedUnsigned"))
        }
    }
}
