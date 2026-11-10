package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Poke tool on input pins follows Evolution's PinPoker (scripts/oracle/base-tools poke-desktop.tsv, #345). */
class PinPokeEditorTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin">
      <a name="label" val="p1"/>
    </comp>
    <comp lib="0" loc="(100,200)" name="Pin">
      <a name="behavior" val="tristate"/>
      <a name="label" val="t1"/>
    </comp>
    <comp lib="0" loc="(100,300)" name="Pin">
      <a name="label" val="b4"/>
      <a name="radix" val="2"/>
      <a name="width" val="4"/>
    </comp>
    <comp lib="0" loc="(100,400)" name="Pin">
      <a name="label" val="h8"/>
      <a name="radix" val="16"/>
      <a name="width" val="8"/>
    </comp>
    <comp lib="0" loc="(100,500)" name="Pin">
      <a name="behavior" val="tristate"/>
      <a name="label" val="t3"/>
      <a name="radix" val="2"/>
      <a name="width" val="3"/>
    </comp>
  </circuit>
</project>"""

    @Test
    fun pokingPinsInTheEditorFollowsEvolution() {
        val state = EditorState(xml)
        fun value(label: String): String {
            val id = state.document.components.first { it.label == label }.id
            return state.simulation.vectorValues.getValue(id).display.replace('?', 'U')
        }
        fun pokes(label: String, vararg bits: Int): List<String> {
            val id = state.document.components.first { it.label == label }.id
            return listOf(value(label)) + bits.map { state.pokePin(id, it); value(label) }
        }
        assertEquals(listOf("0", "1", "0", "1", "0"), pokes("p1", 0, 0, 0, 0))
        assertEquals(listOf("U", "0", "1", "U", "0"), pokes("t1", 0, 0, 0, 0))
        assertEquals(listOf("0000", "0001", "0000", "0100", "0000", "1000"), pokes("b4", 0, 0, 2, 2, 3))
        assertEquals(listOf("00000000", "00000001", "00000010", "00010010", "00010011"), pokes("h8", 0, 0, 4, 0))
        assertEquals(listOf("UUU", "UU0", "UU1", "UUU", "U0U"), pokes("t3", 0, 0, 0, 1))
    }
}
