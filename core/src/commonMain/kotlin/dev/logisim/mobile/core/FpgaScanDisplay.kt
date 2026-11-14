package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** A logical display channel as the user picks it: digit and segment, or row, column and color plane. */
data class FpgaScanPosition(val row: Int, val column: Int, val plane: Int = 0)

/** A board display's logical channels are independent of its multiplexed package pins. */
class FpgaScanDisplay internal constructor(
    val resource: FpgaBoardResource,
    internal val mode: Mode,
    val rows: Int,
    val columns: Int,
    private val decodedWidth: Int,
) {
    internal enum class Mode { LED_ROWS, LED_COLUMNS, RGB_ROWS, RGB_COLUMNS, SEG_DECODED, SEG_LOW, SEG_HIGH }

    /** Rows are digits and columns are the segments a to g and dp. */
    val isSevenSegment: Boolean get() = mode in setOf(Mode.SEG_DECODED, Mode.SEG_LOW, Mode.SEG_HIGH)
    private val byRow get() = mode == Mode.LED_ROWS || mode == Mode.RGB_ROWS
    private val colors get() = if (mode == Mode.RGB_ROWS || mode == Mode.RGB_COLUMNS) 3 else 1

    val isRgb: Boolean get() = colors == 3
    /** Color planes a logical channel can address: red, green and blue for RGB arrays. */
    val planes: Int get() = colors
    val channelCount: Int get() = rows * columns * colors

    /** The linear channel the scan driver reads for a digit or row, a segment or column, and a color plane. */
    fun channelOf(row: Int, column: Int, plane: Int = 0): Int = plane * rows * columns + row * columns + column

    /** The inverse of [channelOf]. */
    fun coordinatesOf(channel: Int): FpgaScanPosition {
        val layer = rows * columns
        return FpgaScanPosition((channel % layer) / columns, channel % columns, channel / layer)
    }

    /** "Digit 3" for a seven-segment display, "Row 3" for an array; numbered from 1. */
    fun rowLabel(row: Int): LocalizedText =
        if (isSevenSegment) message("fpga_scan_digit_value", "digit" to row + 1) else message("fpga_scan_row_value", "row" to row + 1)

    /** "Segment c" for a seven-segment display, "Column 3" for an array. */
    fun columnLabel(column: Int): LocalizedText =
        if (isSevenSegment) message("fpga_scan_segment_value", "segment" to SEGMENTS.getOrElse(column) { column.toString() })
        else message("fpga_scan_column_value", "column" to column + 1)

    fun planeLabel(plane: Int): LocalizedText = message(PLANES.getOrElse(plane) { PLANES.first() })

    /** "Digit 3 · Segment c (channel 18)"; a pixel binding drives all planes, so it names no color. */
    fun channelLabel(channel: Int, rgbPixel: Boolean = false): LocalizedText {
        val position = coordinatesOf(channel)
        val parts = listOfNotNull(rowLabel(position.row), columnLabel(position.column),
            planeLabel(position.plane).takeIf { isRgb && !rgbPixel })
        return message("fpga_scan_summary", "position" to LocalizedText.join(parts, " · "), "channel" to channel)
    }

    /** A plain Evolution output pin drives all three planes at its mapped RGB pixel. */
    fun channelLimit(rgbPixel: Boolean): Int = when {
        !rgbPixel -> channelCount
        isRgb -> rows * columns
        else -> 0
    }

    internal fun dataChannels(binding: FpgaPinBinding.Display): List<Int> =
        if (binding.rgbPixel) List(colors) { plane -> binding.channel + plane * rows * columns }
        else listOf(binding.channel)

    internal val positions get() = if (byRow || isSevenSegment) rows else columns
    private val addressWidth get() = bitsFor(positions)
    private val outputsPerColor get() = if (byRow) columns else rows
    private val selectWidth get() = when (mode) {
        Mode.SEG_DECODED -> maxOf(addressWidth, decodedWidth)
        Mode.SEG_LOW, Mode.SEG_HIGH -> rows
        else -> addressWidth
    }
    internal val pinCount get() = selectWidth + if (isSevenSegment) columns else colors * outputsPerColor

    internal fun signalForPin(pin: Int): ScanOutput = when {
        isSevenSegment && pin < columns ->
            ScanOutput.Data(stride = columns, offset = pin)
        isSevenSegment && mode == Mode.SEG_LOW -> ScanOutput.Select(pin - columns, true)
        isSevenSegment && mode == Mode.SEG_HIGH -> ScanOutput.Select(pin - columns, false)
        isSevenSegment -> ScanOutput.Address(pin - columns)
        pin < addressWidth -> ScanOutput.Address(pin)
        else -> {
            val index = pin - addressWidth
            val offset = (index / outputsPerColor) * rows * columns
            val lane = index % outputsPerColor
            if (byRow) ScanOutput.Data(stride = columns, offset = offset + lane)
            else ScanOutput.Data(stride = 1, offset = offset + lane * columns)
        }
    }

    private fun bitsFor(count: Int): Int = (count - 1).takeIf { it > 0 }?.countLeadingZeroBits()?.let { 32 - it } ?: 0
}

internal sealed interface ScanOutput {
    data class Address(val bit: Int) : ScanOutput
    data class Select(val position: Int, val activeLow: Boolean) : ScanOutput
    data class Data(val stride: Int, val offset: Int) : ScanOutput
}

/** Interpret Evolution's XML metadata without changing how raw physical pins are imported. */
fun FpgaBoardResource.scannedDisplay(): FpgaScanDisplay? = scanLayout()?.takeIf { display ->
    pins.size == display.pinCount && pins.withIndex().all { (index, pin) -> pin.index == index && pin.direction == FpgaPinDirection.OUTPUT }
}

/**
 * Output pins an LED array or a scanning seven-segment display needs for its [configuration] ("rows,columns,Mode"), as
 * Evolution's settings dialog counts them, or null when the configuration is not one Mobile can drive.
 */
fun fpgaDisplayPinCount(type: String, configuration: String): Int? {
    val ledArray = type.equals("LedArray", ignoreCase = true)
    val fields = configuration.split(',').map(String::trim)
    if (ledArray && fields.size == 3 && fields[2] in setOf("LedDefault", "RgbDefault")) {
        val rows = fields[0].toIntOrNull()?.takeIf { it in 1..128 } ?: return null
        val columns = fields[1].toIntOrNull()?.takeIf { it in 1..128 } ?: return null
        return rows * columns * if (fields[2] == "RgbDefault") 3 else 1
    }
    val key = if (ledArray) "LedArrayInfo" else "ScanningSevenSegInfo"
    return FpgaBoardResource("", type, "", null, emptyList(), mapOf(key to configuration)).scanLayout()?.pinCount
}

private val SEGMENTS = listOf("a", "b", "c", "d", "e", "f", "g", "dp")
private val PLANES = listOf("fpga_scan_red", "fpga_scan_green", "fpga_scan_blue")

private fun FpgaBoardResource.scanLayout(): FpgaScanDisplay? {
    val configuration = when {
        type.equals("LedArray", ignoreCase = true) -> attributes["LedArrayInfo"]
        type.equals("SevenSegmentScanning", ignoreCase = true) -> attributes["ScanningSevenSegInfo"]
        else -> null
    } ?: return null
    val fields = configuration.split(',').map(String::trim)
    if (fields.size != 3) return null
    val rows = fields[0].toIntOrNull()?.takeIf { it in 1..64 } ?: return null
    val columns = fields[1].toIntOrNull() ?: return null
    val mode = when (fields[2]) {
        "LedRowScanning" -> FpgaScanDisplay.Mode.LED_ROWS
        "LedColumnScanning" -> FpgaScanDisplay.Mode.LED_COLUMNS
        "RgbRowScanning" -> FpgaScanDisplay.Mode.RGB_ROWS
        "RgbColScanning" -> FpgaScanDisplay.Mode.RGB_COLUMNS
        "SevenSegDecoded" -> FpgaScanDisplay.Mode.SEG_DECODED
        "SevenSegScanningActiveLow" -> FpgaScanDisplay.Mode.SEG_LOW
        "SevenSegScanningActiveHi" -> FpgaScanDisplay.Mode.SEG_HIGH
        else -> return null
    }
    val isSevenSegment = type.equals("SevenSegmentScanning", ignoreCase = true)
    if (isSevenSegment != (mode in setOf(FpgaScanDisplay.Mode.SEG_DECODED, FpgaScanDisplay.Mode.SEG_LOW, FpgaScanDisplay.Mode.SEG_HIGH))) return null
    if (!isSevenSegment && columns !in 1..64) return null
    if (mode == FpgaScanDisplay.Mode.SEG_DECODED && columns !in 0..16) return null
    val display = FpgaScanDisplay(this, mode, rows, if (isSevenSegment) 8 else columns, columns)
    return display.takeIf { it.channelCount <= 8192 }
}

internal class FpgaScanHdl(
    private val prefix: String,
    private val display: FpgaScanDisplay,
    private val clock: String,
    private val cycles: Long,
    private val channels: Map<Int, String>,
) {
    private val position = "${prefix}_position"
    private val count = "${prefix}_ticks"
    private val data = "${prefix}_data"
    private val select = "${prefix}_address"
    private val addressBits = maxOf(1, 32 - (display.positions - 1).countLeadingZeroBits())
    private val countBits = maxOf(1, 64 - (cycles - 1).countLeadingZeroBits())

    fun verilog() = buildString {
        append("reg [${addressBits - 1}:0] $position = 0;\n")
        append("reg [${countBits - 1}:0] $count = 0;\n")
        append("wire [${display.channelCount - 1}:0] $data;\n")
        for (channel in 0 until display.channelCount) {
            append("assign $data[$channel] = ${channels[channel] ?: "1'b0"};\n")
        }
        append("always @(posedge $clock) begin\n")
        append("  if ($count == ${cycles - 1}) begin\n")
        append("    $count <= 0;\n")
        append("    $position <= ($position == 0) ? ${display.positions - 1} : $position - 1;\n")
        append("  end else $count <= $count + 1;\n")
        append("end\n")
        for (pin in display.resource.pins) {
            val expression = when (val output = display.signalForPin(pin.index)) {
                is ScanOutput.Address -> if (output.bit < addressBits) "$position[${output.bit}]" else "1'b0"
                is ScanOutput.Select -> "($position ${if (output.activeLow) "!=" else "=="} ${output.position})"
                is ScanOutput.Data -> "${if (pin.electrical.activeLow) "~" else ""}$data[${output.offset} + $position * ${output.stride}]"
            }
            append("assign ${prefix}_p${pin.index} = $expression;\n")
        }
    }

    fun vhdlDeclarations() = buildString {
        append("  signal $position : natural range 0 to ${display.positions - 1} := 0;\n")
        append("  signal $count : unsigned(${countBits - 1} downto 0) := (others => '0');\n")
        append("  signal $select : unsigned(${addressBits - 1} downto 0);\n")
        append("  signal $data : std_logic_vector(${display.channelCount - 1} downto 0);\n")
    }

    fun vhdl() = buildString {
        append("  $select <= to_unsigned($position, $addressBits);\n")
        for (channel in 0 until display.channelCount) {
            append("  $data($channel) <= ${channels[channel] ?: "'0'"};\n")
        }
        append("  process($clock) begin\n")
        append("    if rising_edge($clock) then\n")
        append("      if $count = ${cycles - 1} then\n")
        append("        $count <= (others => '0');\n")
        append("        if $position = 0 then $position <= ${display.positions - 1};\n")
        append("        else $position <= $position - 1; end if;\n")
        append("      else $count <= $count + 1; end if;\n")
        append("    end if;\n")
        append("  end process;\n")
        for (pin in display.resource.pins) {
            val expression = when (val output = display.signalForPin(pin.index)) {
                is ScanOutput.Address -> if (output.bit < addressBits) "$select(${output.bit})" else "'0'"
                is ScanOutput.Select -> if (output.activeLow) "'0' when $position = ${output.position} else '1'"
                    else "'1' when $position = ${output.position} else '0'"
                is ScanOutput.Data -> "${if (pin.electrical.activeLow) "not " else ""}$data(${output.offset} + $position * ${output.stride})"
            }
            append("  ${prefix}_p${pin.index} <= $expression;\n")
        }
    }
}
