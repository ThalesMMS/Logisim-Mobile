package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class ValueCharactersTest {
    private val mixed = LogicVector.fromMasks(4, 0b0101uL, 0b0010uL, 0b1000uL)

    @Test
    fun displayUsesEvolutionDefaultCharactersWhileFilesKeepTheirSymbols() {
        val characters = ValueCharacters.Default
        // Value.toBinaryString(): E for the error bit, U for the unknown bit.
        assertEquals("E1U1", mixed.display(characters))
        assertEquals("0xE", mixed.hexadecimalDisplay(characters))
        assertEquals("0x?", LogicVector.unknown(4).hexadecimalDisplay())
        assertEquals("0xU", LogicVector.unknown(4).hexadecimalDisplay(characters))
        assertEquals("-", TruthCell.DONT_CARE.display(characters))
        // Files and traces keep the fixed symbols.
        assertEquals("E1?1", mixed.display)
        assertEquals("x", TruthCell.DONT_CARE.symbol)
    }

    @Test
    fun chosenCharactersReplaceEveryValueKind() {
        val characters = ValueCharacters('T', 'F', 'Z', 'X', 'X')
        assertEquals("XTZT", mixed.display(characters))
        assertEquals("F", LogicValue.LOW.display(characters))
        assertEquals("X", TruthCell.DONT_CARE.display(characters))
        assertEquals(TruthCell.ONE, characters.parseCell("T"))
        assertEquals(TruthCell.ZERO, characters.parseCell("F"))
        assertEquals(TruthCell.DONT_CARE, characters.parseCell("X"))
        // The default symbols keep working after a change, like Evolution's typed keys.
        assertEquals(TruthCell.ONE, characters.parseCell("1"))
        assertEquals(TruthCell.DONT_CARE, characters.parseCell("-"))
        assertNull(characters.parseCell("Y"))
    }

    @Test
    fun onlyEvolutionSymbolChooserOptionsAreAccepted() {
        assertFailsWith<IllegalArgumentException> { ValueCharacters(trueChar = 'Y') }
        assertFailsWith<IllegalArgumentException> { ValueCharacters(dontCareChar = '?') }
    }

    @Test
    fun truthTableHelpNamesTheConfiguredCharactersThatParsingAccepts() {
        val default = ValueCharacters.Default.truthTableHelp()
        assertEquals("Tap a heading to edit a column. Tap an output cell to cycle through 0, 1, and -. " +
            "Inputs are ordered most significant bit first.", default.resolve(AppLanguage.ENGLISH))
        val chosen = ValueCharacters(trueChar = 'T', falseChar = 'F', dontCareChar = 'X')
        assertEquals("Toque no cabeçalho para editar uma coluna. Toque numa célula de saída para alternar F, T e X. " +
            "O bit mais significativo vem primeiro.", chosen.truthTableHelp().resolve(AppLanguage.PORTUGUESE))
        for (language in AppLanguage.entries) {
            val text = chosen.truthTableHelp().resolve(language)
            assertFalse('{' in text || " x" in text, "$language: $text")
            for (symbol in "FTX") assertTrue(symbol in text, "$language: $text")
        }
        // Every character the help names is accepted when typed or pasted (Entry.parse).
        for (characters in listOf(ValueCharacters.Default, chosen)) {
            assertEquals(TruthCell.ZERO, characters.parseCell(characters.falseChar.toString()))
            assertEquals(TruthCell.ONE, characters.parseCell(characters.trueChar.toString()))
            assertEquals(TruthCell.DONT_CARE, characters.parseCell(characters.dontCareChar.toString()))
        }
    }
}
