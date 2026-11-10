package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ComponentIdentityScopeTest {
    @Test
    fun reservedInstanceSeparatorIsRejectedAtBothModelAndXmlBoundaries() {
        assertFailsWith<LocalizedArgumentException> { CircuitComponent("a/b", ComponentKind.INPUT, GridPoint(0, 0)) }
        val xml = "<project><lib name=\"w\" desc=\"#Wiring\"/><circuit name=\"main\"><comp name=\"Pin\" lib=\"w\" loc=\"(0,0)\" logisim-mobile-id=\"a/b\"/></circuit></project>"
        val result = assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml))
        assertEquals("component_identity_reserved_separator", result.notices.single().key)
    }

    @Test
    fun colonDotAndUnicodeIdsDoNotAliasNestedInstancesOrTraceSignals() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(FIXTURE)).project
        val state = CircuitSimulator().settle(project)
        assertEquals(setOf("a:b.β", "a", "a/b.β"), state.circuitStates.keys)
        assertEquals(LogicVector.high(1), state.circuitStates.getValue("a:b.β").vectorValues["out:β.x"])
        assertEquals(LogicVector.low(1), state.circuitStates.getValue("a/b.β").vectorValues["out:β.x"])
        val signals = TraceSignals.fromProject(project)
        assertEquals(signals.size, signals.map { it.reference }.distinct().size)
        assertEquals(signals.size, signals.map { it.reference.key }.distinct().size)
        val high = signals.single { it.reference == TraceSignalReference("a:b.β", "out:β.x") }
        val low = signals.single { it.reference == TraceSignalReference("a/b.β", "out:β.x") }
        val recorder = TraceRecorder(listOf(high, low))
        recorder.capture(state)
        assertEquals(listOf(LogicVector.high(1), LogicVector.low(1)), recorder.frames.single().values)
    }

    @Test
    fun diagnosticTerminalPairsAlreadyUseLengthFraming() {
        assertNotEquals(DiagnosticTerminalRef("a:b.c", "out").key, DiagnosticTerminalRef("a", "b.c:out").key)
        assertEquals("5:a:b.c3:out", DiagnosticTerminalRef("a:b.c", "out").key)
    }

    private companion object {
        val FIXTURE = """<project><lib name="w" desc="#Wiring"/><main name="main"/>
          <circuit name="main"><comp name="High" loc="(100,100)" logisim-mobile-id="a:b.β"/><comp name="Middle" loc="(300,100)" logisim-mobile-id="a"/></circuit>
          <circuit name="High"><comp name="Constant" lib="w" loc="(100,100)" logisim-mobile-id="value:β.x"><a name="value" val="1"/></comp><comp name="Pin" lib="w" loc="(200,100)" logisim-mobile-id="out:β.x"><a name="type" val="output"/></comp><wire from="(100,100)" to="(200,100)"/></circuit>
          <circuit name="Middle"><comp name="Low" loc="(100,100)" logisim-mobile-id="b.β"/></circuit>
          <circuit name="Low"><comp name="Constant" lib="w" loc="(100,100)" logisim-mobile-id="value:β.x"><a name="value" val="0"/></comp><comp name="Pin" lib="w" loc="(200,100)" logisim-mobile-id="out:β.x"><a name="type" val="output"/></comp><wire from="(100,100)" to="(200,100)"/></circuit></project>"""
    }
}
