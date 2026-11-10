package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JarCircuitInputsTest {
    @Test
    fun readsOnlyLabeledWiringInputsOfMainCircuit() {
        val xml = """
            <project source="5.1.0" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <lib name="1" desc="jar#external/gray.jar#com.cburch.gray.Components"/>
              <main name="top"/>
              <circuit name="other">
                <comp lib="0" name="Pin"><a name="label" val="unrelated"/></comp>
              </circuit>
              <circuit name="top">
                <comp lib="0" name="Pin"><a name="label" val="CLK"/></comp>
                <comp lib="0" name="Pin"><a name="label" val="Data"/><a name="width" val="4"/></comp>
                <comp lib="0" name="Pin"><a name="label" val="Y"/><a name="type" val="output"/></comp>
                <comp lib="0" name="Pin"><a name="label" val="Z"/><a name="output" val="true"/></comp>
                <comp lib="1" name="Pin"><a name="label" val="third-party"/></comp>
              </circuit>
            </project>
        """.trimIndent()

        assertEquals(listOf(JarCircuitInput("CLK", 1), JarCircuitInput("Data", 4)), readJarCircuitInputs(xml))
    }

    @Test
    fun rejectsAmbiguousInputAndOutputLabels() {
        val xml = """
            <project>
              <lib name="0" desc="#Wiring"/>
              <circuit name="top">
                <comp lib="0" name="Pin"><a name="label" val="CLK"/></comp>
                <comp lib="0" name="Pin"><a name="label" val="CLK"/><a name="type" val="output"/></comp>
              </circuit>
            </project>
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { readJarCircuitInputs(xml) }
    }
}
