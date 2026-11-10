package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlexersLogicTest {
    private fun value(text: String) = LogicVector.fromBits(text.reversed().map {
        when (it) { '0' -> LogicValue.LOW; '1' -> LogicValue.HIGH; '?' -> LogicValue.UNKNOWN; else -> LogicValue.ERROR }
    })

    @Test
    fun muxSelectsEachBusWithoutLosingUnknownOrErrorBits() {
        val data = listOf(value("0011"), value("1010"), value("1?0E"), value("1111"))
        assertEquals("0011", PlexersLogic.multiplexer(data, value("00")).display)
        assertEquals("1010", PlexersLogic.multiplexer(data, value("01")).display)
        assertEquals("1?0E", PlexersLogic.multiplexer(data, value("10")).display)
        assertEquals("1111", PlexersLogic.multiplexer(data, value("11")).display)
    }

    @Test
    fun muxDistinguishesUndefinedSelectionAndConflictingSelection() {
        val data = listOf(value("0011"), value("1010"))
        assertEquals("????", PlexersLogic.multiplexer(data, value("?")).display)
        assertEquals("EEEE", PlexersLogic.multiplexer(data, value("E")).display)
        assertEquals("????", PlexersLogic.multiplexer(data, value("10")).display)
    }

    @Test
    fun disabledMuxUsesTheConfiguredZeroOrFloatingOutputBeforeSelection() {
        val data = listOf(value("1111"), value("1111"))
        assertEquals("0000", PlexersLogic.multiplexer(data, value("E"), value("0")).display)
        assertEquals("????", PlexersLogic.multiplexer(data, value("E"), value("0"),
            disabled = PlexerDisabledOutput.FLOATING).display)
    }

    @Test
    fun muxFloatingEnableActsEnabledButConnectedErrorEnableDoesNot() {
        val data = listOf(value("0011"), value("1010"))
        assertEquals("1010", PlexersLogic.multiplexer(data, value("1"), value("?")).display)
        assertEquals("EEEE", PlexersLogic.multiplexer(data, value("1"), value("E")).display)
        assertEquals("1010", PlexersLogic.multiplexer(data, value("1"), value("E"), false).display)
    }

    @Test
    fun demuxRoutesDataAndZerosOrFloatsOtherOutputs() {
        assertEquals(listOf("0000", "0000", "1?E0", "0000"),
            PlexersLogic.demultiplexer(value("1?E0"), value("10"), 4).map { it.display })
        assertEquals(listOf("????", "????", "1?E0", "????"),
            PlexersLogic.demultiplexer(value("1?E0"), value("10"), 4, tristate = true).map { it.display })
    }

    @Test
    fun demuxUndefinedSelectAndDisabledOutputsFollowDesktopPolicy() {
        assertEquals(List(4) { "????" }, PlexersLogic.demultiplexer(value("1111"), value("?0"), 4).map { it.display })
        assertEquals(List(4) { "EEEE" }, PlexersLogic.demultiplexer(value("1111"), value("E0"), 4).map { it.display })
        assertEquals(List(4) { "0000" }, PlexersLogic.demultiplexer(value("1111"), value("E0"), 4,
            enable = value("0"), tristate = true).map { it.display })
        assertEquals(List(4) { "????" }, PlexersLogic.demultiplexer(value("1111"), value("E0"), 4,
            enable = value("0"), disabled = PlexerDisabledOutput.FLOATING).map { it.display })
    }

    @Test
    fun decoderSupportsAll256Outputs() {
        val output = PlexersLogic.decoder(LogicVector.of(8, 255u), 256)
        assertEquals(List(255) { "0" } + "1", output.map { it.display })
    }

    @Test
    fun decoderUsesOneBitOutputsAndTristatePolicy() {
        assertEquals(listOf("?", "1", "?", "?"), PlexersLogic.decoder(value("01"), 4,
            tristate = true).map { it.display })
        assertEquals(List(4) { "E" }, PlexersLogic.decoder(value("00"), 4, value("E")).map { it.display })
    }

    @Test
    fun priorityEncoderSelectsHighestHighAndProducesCascadeFlags() {
        val result = PlexersLogic.priorityEncoder(listOf(value("1"), value("1"), value("E"), value("?")), 2)
        assertEquals("01", result.value.display)
        assertEquals("0", result.enableOut.display)
        assertEquals("1", result.groupSelect.display)
    }

    @Test
    fun priorityEncoderEmptyAndDisabledValuesAreDistinct() {
        val data = List(4) { value("?") }
        val enabled = PlexersLogic.priorityEncoder(data, 2)
        assertEquals("??", enabled.value.display)
        assertEquals("1", enabled.enableOut.display)
        assertEquals("0", enabled.groupSelect.display)
        val disabled = PlexersLogic.priorityEncoder(data, 2, value("0"))
        assertEquals("00", disabled.value.display)
        assertEquals("0", disabled.enableOut.display)
        assertEquals("0", disabled.groupSelect.display)
        assertEquals("??", PlexersLogic.priorityEncoder(data, 2, value("0"), PlexerDisabledOutput.FLOATING).value.display)
    }

    @Test
    fun priorityEncoderUndefinedAndErrorEnableAreEnabled() {
        val data = listOf(value("0"), value("1"))
        assertEquals("1", PlexersLogic.priorityEncoder(data, 1, value("?")).value.display)
        assertEquals("1", PlexersLogic.priorityEncoder(data, 1, value("E")).value.display)
    }

    @Test
    fun bitSelectorExtractsGroupsAndZeroFillsPartialOrOutOfRangeGroups() {
        val data = value("11010101")
        assertEquals("101", PlexersLogic.bitSelector(data, value("00"), 3).display)
        assertEquals("010", PlexersLogic.bitSelector(data, value("01"), 3).display)
        assertEquals("011", PlexersLogic.bitSelector(data, value("10"), 3).display)
        assertEquals("000", PlexersLogic.bitSelector(data, value("11"), 3).display)
        assertEquals("000", PlexersLogic.bitSelector(data, LogicVector.high(64), 3).display)
    }

    @Test
    fun bitSelectorPropagatesSelectedDataStatesAndUndefinedSelection() {
        assertEquals("1?E0", PlexersLogic.bitSelector(value("10101?E0"), value("0"), 4).display)
        assertEquals("????", PlexersLogic.bitSelector(value("10101010"), value("E"), 4).display)
        assertEquals("????", PlexersLogic.bitSelector(value("10101010"), value("?"), 4).display)
    }

    @Test
    fun selectorWidthCoversAllGroupsWithoutAllocatingAnExtraBit() {
        assertEquals(3, PlexersLogic.bitSelectorSelectBits(8, 1))
        assertEquals(2, PlexersLogic.bitSelectorSelectBits(8, 3))
        assertEquals(1, PlexersLogic.bitSelectorSelectBits(64, 64))
        assertEquals(6, PlexersLogic.bitSelectorSelectBits(64, 1))
    }

    @Test
    fun malformedDataAndParametersFailAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> { PlexersLogic.multiplexer(listOf(value("0")), value("0")) }
        assertFailsWith<IllegalArgumentException> { PlexersLogic.multiplexer(listOf(value("0"), value("00")), value("0")) }
        assertFailsWith<IllegalArgumentException> { PlexersLogic.priorityEncoder(List(3) { value("0") }, 2) }
        assertFailsWith<IllegalArgumentException> { PlexersLogic.bitSelector(value("0"), value("0"), 65) }
    }
}
