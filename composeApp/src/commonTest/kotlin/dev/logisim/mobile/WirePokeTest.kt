package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WirePokeTest {
    @Test
    fun pokeAddsTheDesktopFloatInterpretationAtEachSupportedWidth() {
        for ((width, bits, decimal) in listOf(
            Triple(8, 0x01uL, "0.001953125"),
            Triple(16, 0x8000uL, "-0.0"),
            Triple(32, 0x3dcccccduL, "0.1"),
            Triple(64, 0x7ff0000000000000uL, "Infinity"),
        )) {
            assertEquals(listOf(bits.toString(), decimal),
                wirePokeReadings(LogicVector.of(width, bits), WirePokeRadix.UNSIGNED_DECIMAL, null))
        }
        assertEquals(listOf("07", "7"),
            wirePokeReadings(LogicVector.of(5, 7uL), WirePokeRadix.OCTAL, WirePokeRadix.UNSIGNED_DECIMAL))
        assertEquals(listOf("1"),
            wirePokeReadings(LogicVector.high(1), WirePokeRadix.BINARY, WirePokeRadix.SIGNED_DECIMAL))
    }

    @Test
    fun pokeReadsTheCurrentBusValueAndProvidesAnAccessibleDescription() {
        val driven = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val floating = WireSegment(GridPoint(100, 200), GridPoint(200, 200))
        val conflicted = WireSegment(GridPoint(100, 300), GridPoint(200, 300))
        val components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, driven.from, attributes = mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, driven.to, attributes = mapOf("width" to "8")),
            CircuitComponent("floating", ComponentKind.OUTPUT, floating.to, attributes = mapOf("width" to "8")),
            CircuitComponent("low", ComponentKind.CONSTANT, conflicted.from, attributes = mapOf("value" to "0")),
            CircuitComponent("high", ComponentKind.CONSTANT, conflicted.to, attributes = mapOf("value" to "1")),
        )
        val state = EditorState(CircCodec.write(CircuitDocument(components = components, wires = listOf(driven, floating, conflicted))))
        val input = state.document.components.single { it.kind == ComponentKind.INPUT }
        state.setInputValue(input.id, 0xA5uL)

        state.tap(GridPoint(150, 100))
        val probe = assertNotNull(state.wirePoke)
        val known = assertNotNull(probe.signal(state.simulation))
        assertEquals("10100101", formattedSignal(known, "2"))
        assertEquals("a5", formattedSignal(known, "16"))
        assertEquals("1.0", formattedSignal(LogicVector.of(32, 0x3f800000uL), "float"))
        assertEquals("NaN", formattedSignal(LogicVector.of(4, 3uL), "float"))
        assertEquals(listOf("a5", "-91", "-0.203125"),
            wirePokeReadings(known, WirePokeRadix.HEXADECIMAL, WirePokeRadix.SIGNED_DECIMAL))
        assertEquals(listOf("a5", "245", "-0.203125"),
            wirePokeReadings(known, WirePokeRadix.HEXADECIMAL, WirePokeRadix.OCTAL))
        assertEquals(listOf("a5", "-0.203125"), wirePokeReadings(known, WirePokeRadix.HEXADECIMAL, null))
        assertEquals(known, probe.signal(state.simulation))
        assertEquals("Wire value. 8 bits, hexadecimal A5.", probe.description(known).resolve(AppLanguage.ENGLISH))

        state.setInputValue(input.id, 0x5AuL)
        assertEquals("01011010", state.wirePoke?.signal(state.simulation)?.display)

        state.tap(GridPoint(150, 200))
        val floatingProbe = assertNotNull(state.wirePoke)
        val unknown = assertNotNull(floatingProbe.signal(state.simulation))
        assertEquals("????????", unknown.display)
        // The callout shows Evolution's UNKNOWN_CHAR; the stored display keeps its file symbol.
        assertEquals(listOf("UUUU UUUU", "U", "NaN"),
            wirePokeReadings(unknown, WirePokeRadix.BINARY, WirePokeRadix.UNSIGNED_DECIMAL))
        assertEquals("Wire value. 8 undefined bits.", floatingProbe.description(unknown).resolve(AppLanguage.ENGLISH))

        // Canvas hit tolerance can pick a wire even when the tap is slightly off the line.
        state.pokeWire(state.document.wires.single { it.contains(GridPoint(150, 300)) }, GridPoint(150, 302))
        val conflictedProbe = assertNotNull(state.wirePoke)
        val error = assertNotNull(conflictedProbe.signal(state.simulation))
        assertEquals(GridPoint(150, 300), conflictedProbe.point)
        assertEquals("E", error.display)
        assertEquals("Wire value. 1 conflicting or erroneous bit.", conflictedProbe.description(error).resolve(AppLanguage.ENGLISH))

        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertNull(state.wirePoke)
        state.tap(GridPoint(150, 100), EditorMode.INTERACT)
        state.changeMode(EditorMode.SELECT)
        state.tap(GridPoint(150, 100))
        assertNull(state.wirePoke)
        assertEquals(1, state.selectedWires.size)
    }
}
