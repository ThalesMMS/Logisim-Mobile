package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/**
 * Evolution's TRUE_CHAR, FALSE_CHAR, UNKNOWN_CHAR, ERROR_CHAR and DONTCARE_CHAR preferences, which
 * Value.toDisplayString(), Value.toHexString() and Entry.getDescription() use for shown values.
 * Files keep their own fixed symbols ([LogicVector.display] and [TruthCell.symbol]).
 */
data class ValueCharacters(
    val trueChar: Char = '1',
    val falseChar: Char = '0',
    val unknownChar: Char = 'U',
    val errorChar: Char = 'E',
    val dontCareChar: Char = '-',
) {
    init {
        require(trueChar in TRUE_CHOICES && falseChar in FALSE_CHOICES && unknownChar in UNKNOWN_CHOICES &&
            errorChar in ERROR_CHOICES && dontCareChar in DONT_CARE_CHOICES)
    }

    fun of(value: LogicValue): Char = when (value) {
        LogicValue.LOW -> falseChar
        LogicValue.HIGH -> trueChar
        LogicValue.UNKNOWN -> unknownChar
        LogicValue.ERROR -> errorChar
    }

    fun of(cell: TruthCell): Char = when (cell) {
        TruthCell.ZERO -> falseChar
        TruthCell.ONE -> trueChar
        TruthCell.DONT_CARE -> dontCareChar
    }

    /** Entry.parse with the chosen characters; the default symbols stay readable after a change. */
    fun parseCell(text: String): TruthCell? = when (text) {
        falseChar.toString(), "0" -> TruthCell.ZERO
        trueChar.toString(), "1" -> TruthCell.ONE
        dontCareChar.toString(), "-", "x", "X" -> TruthCell.DONT_CARE
        else -> null
    }

    /** The truth table help, naming the characters the output cells cycle through ([TruthCell.next]). */
    fun truthTableHelp(): LocalizedText = message(Messages.AnalysisTableHelp,
        "falseChar" to falseChar.toString(), "trueChar" to trueChar.toString(), "dontCare" to dontCareChar.toString())

    companion object {
        val Default = ValueCharacters()

        /** The options of Evolution's SimOptions SymbolChooser for each value. */
        const val TRUE_CHOICES = "1T"
        const val FALSE_CHOICES = "0F"
        const val UNKNOWN_CHOICES = "U?Z"
        const val ERROR_CHOICES = "E!X"
        const val DONT_CARE_CHOICES = "-X"
    }
}

fun LogicValue.display(characters: ValueCharacters): String = characters.of(this).toString()

fun TruthCell.display(characters: ValueCharacters): String = characters.of(this).toString()

/** Value.toDisplayString() for radix 2, without its four-bit grouping, which Mobile never showed. */
fun LogicVector.display(characters: ValueCharacters): String = buildString(width) {
    for (index in width - 1 downTo 0) append(characters.of(bit(index)))
}

/** [LogicVector.hexadecimalDisplay] with Value.toHexString()'s characters for undefined digits. */
fun LogicVector.hexadecimalDisplay(characters: ValueCharacters): String = buildString {
    append("0x")
    for (start in ((width - 1) / 4) * 4 downTo 0 step 4) {
        val digitMask = 15uL shl start
        append(when {
            errorMask and digitMask != 0uL -> characters.errorChar
            unknownMask and digitMask != 0uL -> characters.unknownChar
            else -> "0123456789ABCDEF"[((value shr start) and 15uL).toInt()]
        })
    }
}
