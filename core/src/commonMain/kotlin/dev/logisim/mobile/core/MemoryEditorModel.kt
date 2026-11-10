package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class MemoryRadix(val base: Int) { BINARY(2), OCTAL(8), DECIMAL(10), HEXADECIMAL(16) }

data class MemorySelection(val first: Long, val last: Long) {
    init { require(first >= 0 && last >= first) }
    val size: Long get() = last - first + 1
    operator fun contains(address: Long): Boolean = address in first..last
}

data class MemoryCell(
    val address: Long,
    val addressText: String,
    val value: ULong,
    val valueText: String,
    val selected: Boolean,
)

sealed interface MemoryEditResult {
    data class Success(val model: MemoryEditorModel) : MemoryEditResult
    data class Failure(val notice: LocalizedText) : MemoryEditResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

/** Pure paged editor. Applying an image to a ROM document or a RAM session belongs to the caller. */
data class MemoryEditorModel(
    val image: MemoryImage,
    val firstAddress: Long = 0,
    val pageSize: Int = 64,
    val selection: MemorySelection? = null,
    val addressRadix: MemoryRadix = MemoryRadix.HEXADECIMAL,
    val valueRadix: MemoryRadix = MemoryRadix.HEXADECIMAL,
) {
    init {
        requireLocalized(pageSize in 1..256) { message(Messages.MemoryErrorPageSize) }
        requireLocalized(firstAddress in 0 until image.capacity) { message(Messages.MemoryErrorPageRange) }
        requireLocalized(selection == null || selection.last < image.capacity) { message(Messages.MemoryErrorSelectionRange) }
    }

    fun cells(): List<MemoryCell> = (firstAddress until minOf(image.capacity, firstAddress + pageSize)).map { address ->
        MemoryCell(address, format(address.toULong(), image.addressBits, addressRadix), image[address],
            format(image[address], image.dataBits, valueRadix), selection?.contains(address) == true)
    }

    fun navigate(addressText: String): MemoryEditResult = edit {
        val address = parseAddress(addressText)
        copy(firstAddress = address / pageSize * pageSize, selection = MemorySelection(address, address))
    }

    fun page(offset: Int): MemoryEditorModel = copy(
        firstAddress = (firstAddress + offset.toLong() * pageSize).coerceIn(0, (image.capacity - 1) / pageSize * pageSize),
    )

    fun select(firstText: String, lastText: String = firstText): MemoryEditResult = edit {
        val first = parseAddress(firstText)
        val last = parseAddress(lastText)
        requireLocalized(last >= first) { message(Messages.MemoryErrorSelectionOrder) }
        copy(selection = MemorySelection(first, last))
    }

    fun editCell(addressText: String, valueText: String): MemoryEditResult = edit {
        val address = parseAddress(addressText)
        copy(image = image.withCells(mapOf(address to parseValue(valueText))), selection = MemorySelection(address, address))
    }

    fun fillSelection(valueText: String): MemoryEditResult = edit {
        val range = selection ?: throw LocalizedArgumentException(message(Messages.MemoryErrorSelectFirst))
        requireLocalized(range.size <= MAX_EDIT_CELLS) { message(Messages.MemoryErrorEditLimit, "count" to MAX_EDIT_CELLS).copy(quantity = MAX_EDIT_CELLS.toLong()) }
        val value = parseValue(valueText)
        copy(image = image.withCells((range.first..range.last).associateWith { value }))
    }

    fun replaceSelection(valueTexts: List<String>): MemoryEditResult = edit {
        val range = selection ?: throw LocalizedArgumentException(message(Messages.MemoryErrorSelectFirst))
        requireLocalized(valueTexts.size.toLong() == range.size) { message(Messages.MemoryErrorSelectionCount) }
        write(range.first, valueTexts)
    }

    fun paste(addressText: String, text: String): MemoryEditResult = edit {
        val values = text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
        requireLocalized(values.isNotEmpty()) { message(Messages.MemoryErrorPasteEmpty) }
        write(parseAddress(addressText), values)
    }

    fun clearContents(): MemoryEditorModel = copy(image = image.cleared())

    fun replaceImage(replacement: MemoryImage): MemoryEditResult = edit {
        requireLocalized(replacement.addressBits == image.addressBits && replacement.dataBits == image.dataBits) {
            message(Messages.MemoryErrorImageDimensions)
        }
        copy(image = replacement)
    }

    fun selectionText(): String {
        val range = selection ?: return ""
        requireLocalized(range.size <= MAX_EDIT_CELLS) { message(Messages.MemoryErrorCopyLimit, "count" to MAX_EDIT_CELLS).copy(quantity = MAX_EDIT_CELLS.toLong()) }
        return (range.first..range.last).joinToString(" ") { format(image[it], image.dataBits, valueRadix) }
    }

    private fun write(first: Long, valueTexts: List<String>): MemoryEditorModel {
        requireLocalized(valueTexts.size <= MAX_EDIT_CELLS) { message(Messages.MemoryErrorEditLimit, "count" to MAX_EDIT_CELLS).copy(quantity = MAX_EDIT_CELLS.toLong()) }
        requireLocalized(valueTexts.size.toLong() <= image.capacity - first) { message(Messages.MemoryErrorRangeOverflow) }
        val values = valueTexts.map(::parseValue)
        return copy(image = image.withCells(values.mapIndexed { offset, value -> first + offset to value }.toMap()),
            selection = MemorySelection(first, first + values.lastIndex))
    }

    private fun parseAddress(text: String): Long {
        val value = parse(text, addressRadix)
        requireLocalized(value < image.capacity.toULong()) { message(Messages.MemoryErrorAddressRange, "last" to (image.capacity - 1)) }
        return value.toLong()
    }

    private fun parseValue(text: String): ULong = parse(text, valueRadix).also {
        requireLocalized(it and image.mask.inv() == 0uL) { message(Messages.MemoryErrorValueWidth, "bits" to image.dataBits).copy(quantity = image.dataBits.toLong()) }
    }

    private inline fun edit(action: () -> MemoryEditorModel): MemoryEditResult = try {
        MemoryEditResult.Success(action())
    } catch (error: IllegalArgumentException) {
        MemoryEditResult.Failure(error.localizedDescription(message(Messages.MemoryErrorEdit)))
    }

    companion object {
        const val MAX_EDIT_CELLS = 65_536

        private fun parse(text: String, radix: MemoryRadix): ULong {
            val value = text.trim()
            val (digits, base) = when {
                value.startsWith("0x", true) -> value.drop(2) to 16
                value.startsWith("0b", true) -> value.drop(2) to 2
                value.startsWith("0o", true) -> value.drop(2) to 8
                else -> value to radix.base
            }
            return digits.toULongOrNull(base) ?: throw LocalizedArgumentException(message(Messages.MemoryErrorNumber, "value" to text))
        }

        private fun format(value: ULong, bits: Int, radix: MemoryRadix): String {
            val width = when (radix) {
                MemoryRadix.BINARY -> bits
                MemoryRadix.OCTAL -> (bits + 2) / 3
                MemoryRadix.HEXADECIMAL -> (bits + 3) / 4
                MemoryRadix.DECIMAL -> 1
            }
            return value.toString(radix.base).padStart(width, '0')
        }
    }
}
