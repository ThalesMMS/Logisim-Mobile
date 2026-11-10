package dev.logisim.mobile.core

enum class PlexerDisabledOutput { ZERO, FLOATING }

data class PriorityEncoderResult(
    val value: LogicVector,
    val enableOut: LogicVector,
    val groupSelect: LogicVector,
)

/** Combinational behavior of the desktop Plexers library. */
object PlexersLogic {
    fun multiplexer(
        data: List<LogicVector>,
        select: LogicVector,
        enable: LogicVector? = null,
        enableConnected: Boolean = true,
        disabled: PlexerDisabledOutput = PlexerDisabledOutput.ZERO,
    ): LogicVector {
        val width = dataWidth(data)
        disabledValue(width, enable, enableConnected, disabled)?.let { return it }
        if (select.isError) return LogicVector.error(width)
        val index = select.toULongOrNull() ?: return LogicVector.unknown(width)
        return if (index < data.size.toULong()) data[index.toInt()] else LogicVector.unknown(width)
    }

    fun demultiplexer(
        data: LogicVector,
        select: LogicVector,
        outputCount: Int,
        enable: LogicVector? = null,
        enableConnected: Boolean = true,
        tristate: Boolean = false,
        disabled: PlexerDisabledOutput = PlexerDisabledOutput.ZERO,
    ): List<LogicVector> {
        require(outputCount in 2..256)
        val off = disabledValue(data.width, enable, enableConnected, disabled)
        if (off != null) return List(outputCount) { off }
        if (select.isError) return List(outputCount) { LogicVector.error(data.width) }
        val index = select.toULongOrNull()
            ?: return List(outputCount) { LogicVector.unknown(data.width) }
        val other = if (tristate) LogicVector.unknown(data.width) else LogicVector.low(data.width)
        return List(outputCount) { if (it.toULong() == index) data else other }
    }

    fun decoder(
        select: LogicVector,
        outputCount: Int,
        enable: LogicVector? = null,
        enableConnected: Boolean = true,
        tristate: Boolean = false,
        disabled: PlexerDisabledOutput = PlexerDisabledOutput.ZERO,
    ): List<LogicVector> = demultiplexer(
        LogicVector.high(1), select, outputCount, enable, enableConnected, tristate, disabled,
    )

    fun priorityEncoder(
        data: List<LogicVector>,
        selectBits: Int,
        enable: LogicVector = LogicVector.high(1),
        disabled: PlexerDisabledOutput = PlexerDisabledOutput.ZERO,
    ): PriorityEncoderResult {
        require(selectBits in 1..8 && data.size == 1 shl selectBits)
        require(data.all { it.width == 1 } && enable.width == 1)
        val enabled = enable.bit(0) != LogicValue.LOW
        val index = if (enabled) data.indexOfLast { it.bit(0) == LogicValue.HIGH } else -1
        if (index >= 0) return PriorityEncoderResult(
            LogicVector.of(selectBits, index.toULong()), LogicVector.low(1), LogicVector.high(1),
        )
        val value = if (enabled || disabled == PlexerDisabledOutput.FLOATING)
            LogicVector.unknown(selectBits) else LogicVector.low(selectBits)
        return PriorityEncoderResult(value, LogicVector.fromBit(if (enabled) LogicValue.HIGH else LogicValue.LOW),
            LogicVector.low(1))
    }

    fun bitSelector(data: LogicVector, select: LogicVector, groupBits: Int): LogicVector {
        require(groupBits in 1..64)
        val index = select.toULongOrNull() ?: return LogicVector.unknown(groupBits)
        if (index >= 64uL || index * groupBits.toULong() >= data.width.toULong()) return LogicVector.low(groupBits)
        val start = index.toInt() * groupBits
        return LogicVector.fromBits(List(groupBits) { offset ->
            if (start + offset < data.width) data.bit(start + offset) else LogicValue.LOW
        })
    }

    fun bitSelectorSelectBits(dataBits: Int, groupBits: Int): Int {
        require(dataBits in 1..64 && groupBits in 1..64)
        var highestGroup = (dataBits + groupBits - 1) / groupBits - 1
        var bits = 1
        while (highestGroup > 1) {
            highestGroup = highestGroup shr 1
            bits++
        }
        return bits
    }

    private fun dataWidth(data: List<LogicVector>): Int {
        require(data.size in 2..256)
        val width = data.first().width
        require(data.all { it.width == width })
        return width
    }

    private fun disabledValue(
        width: Int,
        enable: LogicVector?,
        connected: Boolean,
        disabled: PlexerDisabledOutput,
    ): LogicVector? {
        if (enable == null) return null
        require(enable.width == 1)
        return when {
            enable.bit(0) == LogicValue.LOW -> if (disabled == PlexerDisabledOutput.ZERO)
                LogicVector.low(width) else LogicVector.unknown(width)
            enable.bit(0) == LogicValue.ERROR && connected -> LogicVector.error(width)
            else -> null
        }
    }
}
