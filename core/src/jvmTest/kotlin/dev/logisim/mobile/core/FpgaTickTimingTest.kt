package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FpgaTickTimingTest {
    @Test fun resolvesTheSelectedCircuitsSavedRates() {
        val source = """
            <project source="5.1.0-dev" version="1.0">
              <main name="main"/>
              <options><a name="tickFrequencyHz" val="8"/></options>
              <circuit name="main">
                <a name="downloadFrequency" val="10000000"/>
                <a name="simulationFrequency" val="5000000"/>
              </circuit>
              <circuit name="child">
                <a name="downloadFrequency" val="-1"/>
                <a name="simulationFrequency" unit="Hz">5000000</a>
              </circuit>
              <circuit name="unset"/>
            </project>
        """.trimIndent()
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project
        val child = assertNotNull(project.circuit("child"))
        val unset = assertNotNull(project.circuit("unset"))
        assertEquals(12_500_000.0, FpgaTickTiming.requestedFrequencyHz(project.mainCircuit, 12_500_000))
        assertEquals(10_000_000.0, FpgaTickTiming.requestedFrequencyHz(project.mainCircuit))
        assertEquals(5_000_000.0, FpgaTickTiming.requestedFrequencyHz(child, 0))
        assertEquals(1.0, FpgaTickTiming.requestedFrequencyHz(unset))
        assertEquals(2.0, FpgaTickTiming.requestedFrequencyHz(child.copy(attributes = mapOf("simulationFrequency" to "2"))))
    }

    @Test fun preservesThePhysicalRateAndLimitsTheDivider() {
        val explicit = FpgaTickTiming.forOscillator(100_000_000, 12_500_000.0)
        assertEquals(100_000_000L, explicit.oscillatorFrequencyHz)
        assertEquals(12_500_000.0, explicit.cappedTickFrequencyHz)
        assertEquals(8, explicit.reloadCycles)
        assertEquals(8, FpgaTickTiming.forOscillator(100_000_000, 12_000_000.0).reloadCycles)
        assertEquals(10, FpgaTickTiming.forOscillator(100_000_000, 10_000_000.0).reloadCycles)
        assertEquals(20, FpgaTickTiming.forOscillator(100_000_000, 5_000_000.0).reloadCycles)
        val capped = FpgaTickTiming.forOscillator(100_000_003, 50_000_000.0)
        assertEquals(25_000_000.0, capped.cappedTickFrequencyHz)
        assertEquals(4, capped.reloadCycles)
        assertEquals(Int.MAX_VALUE, FpgaTickTiming.forOscillator(4_000_000_000, 1.0).reloadCycles)
    }

    @Test fun rejectsInvalidRatesWithoutFallingThrough() {
        val circuit = CircuitDocument(attributes = mapOf("downloadFrequency" to "NaN", "simulationFrequency" to "5"))
        assertFailsWith<IllegalArgumentException> { FpgaTickTiming.requestedFrequencyHz(circuit) }
        assertEquals(12.0, FpgaTickTiming.requestedFrequencyHz(circuit, 12))
        assertFailsWith<IllegalArgumentException> { FpgaTickTiming.requestedFrequencyHz(circuit, -1) }
        assertFailsWith<IllegalArgumentException> { FpgaTickTiming.forOscillator(3, 1.0) }
        assertFailsWith<IllegalArgumentException> { FpgaTickTiming.forOscillator(100_000_000, 0.0) }
        assertFailsWith<IllegalArgumentException> { FpgaTickTiming.forOscillator(100_000_000, Double.POSITIVE_INFINITY) }
    }
}
