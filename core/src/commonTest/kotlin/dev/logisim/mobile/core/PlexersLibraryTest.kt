package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlexersLibraryTest {
    private val registry = ComponentRegistry(PlexersLibrary.definitions)

    @Test
    fun registersEveryDesktopPlexerWithItsExactFileIdentity() {
        assertEquals(listOf("Multiplexer", "Demultiplexer", "Decoder", "Priority Encoder", "BitSelector"),
            registry.definitions.map { it.fileName })
        for (definition in registry.definitions) {
            assertNotNull(registry.resolve("#Plexers", definition.fileName, emptyMap()))
            assertTrue(definition.isCombinational)
        }
    }

    @Test
    fun muxGeometryUsesVisualInputOrderWhenFacingWest() {
        val component = registry.create(PlexersLibrary.Multiplexer, "mux", GridPoint(100, 100),
            mapOf("width" to "8", "select" to "2", "facing" to "west", "enable" to "true"))
        val layout = registry.layout(component)
        assertEquals(listOf(GridPoint(140, 80), GridPoint(140, 90), GridPoint(140, 100), GridPoint(140, 110),
            GridPoint(120, 120), GridPoint(110, 120), GridPoint(100, 100)), layout.ports.map { it.position })
        assertEquals(listOf(8, 8, 8, 8, 2, 1, 8), layout.ports.map { it.width })
        assertEquals(ComponentBounds(100, 70, 140, 130), layout.bounds)
    }

    @Test
    fun decoderSelectPositionAndOutputsMatchBothSides() {
        val component = registry.create(PlexersLibrary.Decoder, "decoder", GridPoint(100, 100),
            mapOf("facing" to "north", "selloc" to "tr"))
        assertEquals(listOf(GridPoint(70, 90), GridPoint(90, 90), GridPoint(100, 100), GridPoint(100, 110)),
            registry.layout(component).ports.map { it.position })
    }

    @Test
    fun priorityPortsKeepCascadeTerminalsInDesktopOrder() {
        val component = registry.create(PlexersLibrary.PriorityEncoder, "priority", GridPoint(100, 100),
            mapOf("select" to "1", "facing" to "south"))
        val layout = registry.layout(component)
        assertEquals(listOf("in0", "in1", "out", "enable", "enableOut", "groupSelect"), layout.ports.map { it.name })
        assertEquals(listOf(GridPoint(100, 60), GridPoint(110, 60), GridPoint(100, 100),
            GridPoint(120, 80), GridPoint(90, 80), GridPoint(110, 100)), layout.ports.map { it.position })
    }

    @Test
    fun bitSelectorDerivesSelectAndOutputWidthsFromGroups() {
        val component = registry.create(PlexersLibrary.BitSelector, "selector", GridPoint(0, 0),
            mapOf("width" to "64", "group" to "3"))
        assertEquals(listOf(3, 64, 5), registry.layout(component).ports.map { it.width })
    }

    @Test
    fun typedSchemasRejectUnsupportedWidthsSelectorsLocationsAndSizes() {
        val mux = registry.requireDefinition(PlexersLibrary.Multiplexer)
        for (invalid in listOf(mapOf("select" to "0"), mapOf("select" to "9"), mapOf("width" to "0"),
            mapOf("width" to "65"), mapOf("size" to "50"), mapOf("selloc" to "middle"), mapOf("enable" to "yes"))) {
            assertFailsWith<IllegalArgumentException> { mux.schema.parse(invalid) }
        }
    }

    @Test
    fun namedTerminalsDriveTheRegisteredBehavior() {
        val definition = registry.requireDefinition(PlexersLibrary.Multiplexer)
        val component = registry.create(definition.kind, "mux", GridPoint(0, 0), mapOf("width" to "8"))
        val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component,
            registry.attributes(component), mapOf("in0" to LogicVector.of(8, 0x12u),
                "in1" to LogicVector.of(8, 0xA5u), "select" to LogicVector.high(1)), setOf("select")))
        assertEquals(0xA5uL, result.outputs.getValue("out").toULongOrNull())
        assertEquals(3L, result.delay)
    }

    @Test
    fun bitSelectorBehaviorDoesNotRequireEnableOrDisabledAttributes() {
        val definition = registry.requireDefinition(PlexersLibrary.BitSelector)
        val component = registry.create(definition.kind, "selector", GridPoint(0, 0))
        val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component,
            registry.attributes(component), mapOf("data" to LogicVector.of(8, 0x80u),
                "select" to LogicVector.of(3, 7u)), setOf("data", "select")))
        assertEquals("1", result.outputs.getValue("out").display)
    }

    @Test
    fun versionDefaultsPreserveHistoricalEnablePinsWithoutChangingCurrentSchema() {
        val mux = registry.requireDefinition(PlexersLibrary.Multiplexer)
        assertEquals(mapOf("enable" to "true"), mux.defaultsForVersion("3.6.1"))
        assertEquals(mapOf("enable" to "false"), mux.defaultsForVersion("3.6.2"))
        assertEquals(mapOf("enable" to "false"), mux.defaultsForVersion("3.6.2dev"))
        val decoder = registry.requireDefinition(PlexersLibrary.Decoder)
        assertEquals("false", decoder.defaultsForVersion("2.6.3")["enable"])
        assertEquals("true", decoder.defaultsForVersion("2.6.4")["enable"])
        assertEquals("false", mux.schema.defaults["enable"])
        assertEquals("true", decoder.schema.defaults["enable"])
    }
}
