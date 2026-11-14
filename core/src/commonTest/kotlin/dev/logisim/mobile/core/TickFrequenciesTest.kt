package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickFrequenciesTest {
    @Test
    fun theListIsEvolutionsSupportedTickFrequencies() {
        // MenuSimulate.SUPPORTED_TICK_FREQUENCIES at d58a61e0, listed fastest first there.
        val evolution = listOf(2048000.0, 1024000.0, 512000.0, 256000.0, 128000.0, 64000.0, 32000.0, 16000.0, 8000.0, 4000.0,
            2000.0, 1000.0, 512.0, 256.0, 128.0, 64.0, 32.0, 16.0, 8.0, 4.0, 2.0, 1.0, 0.5, 0.25)
        assertEquals(evolution.sorted(), TickFrequencies.supported)
        // UnifiedHeaderBar.showFrequencyMenu presets.
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1000.0, 2000.0, 4000.0),
            TickFrequencies.headerPresets)
        assertTrue(TickFrequencies.headerPresets.all { it in TickFrequencies.supported })
    }

    @Test
    fun onlyRatesTheRunLoopSustainsAreOffered() {
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1000.0), TickFrequencies.choices)
        assertTrue(TickFrequencies.isSustained(1000.0))
        assertFalse(TickFrequencies.isSustained(2000.0))
    }

    @Test
    fun labelsFollowGetTickFrequencyStrings() {
        assertEquals("2048 kHz", formatTickFrequency(2048000.0))
        assertEquals("0.25 Hz", formatTickFrequency(0.25))
        assertEquals("0.5 Hz", formatTickFrequency(0.5))
        assertEquals("1 kHz", formatTickFrequency(1000.0))
        assertEquals("512 Hz", formatTickFrequency(512.0))
        assertEquals("4 Hz", formatTickFrequency(4.0))
        assertEquals("1.5 kHz", formatTickFrequency(1500.0))
        assertEquals("3 Hz", formatTickFrequency(3.00001))
        assertEquals("2.5 Hz", formatTickFrequency(2.5))
    }

    @Test
    fun aStoredValueOutsideTheListStaysAsACustomEntry() {
        val withThree = TickFrequencies.choicesWith(3.0)
        assertEquals(TickFrequencies.choices.size + 1, withThree.size)
        assertEquals(TickFrequencyChoice(3.0, custom = true), withThree.first { it.custom })
        assertEquals(listOf(2.0, 3.0, 4.0), withThree.map { it.hz }.filter { it in 2.0..4.0 })
        // An imported 4 kHz is shown and kept, not offered as a choice of its own.
        assertEquals(4000.0, TickFrequencies.choicesWith(4000.0).last().hz)
        assertEquals(TickFrequencies.choices.size, TickFrequencies.choicesWith(4.0).size)
    }

    @Test
    fun frequenciesCompareWithATolerance() {
        assertEquals(0.25, TickFrequencies.choiceFor("0.250".toDouble()))
        assertEquals(1000.0, TickFrequencies.choiceFor(999.9999999999))
        assertNull(TickFrequencies.choiceFor(0.26))
        assertTrue(TickFrequencies.same(0.1 + 0.2, 0.3))
        assertFalse(TickFrequencies.same(1.0, 1.001))
    }
}
