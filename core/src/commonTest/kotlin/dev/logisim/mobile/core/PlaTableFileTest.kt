package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaTableFileTest {
    private fun failure(text: String, inputs: Int = 3, outputs: Int = 2) =
        assertFailsWith<LocalizedArgumentException> { PlaTableFile.read(text, inputs, outputs) }.text

    @Test
    fun writesEvolutionHeaderAndStandardRows() {
        val program = PlaProgram(3, 2, listOf(PlaRow("1x0", "10", "  note "), PlaRow("011", "01")))
        assertEquals("# Logisim PLA program table\n1x0 10 # note\n011 01\n", PlaTableFile.write(program))
        assertEquals("# Logisim PLA program table\n", PlaTableFile.write(PlaProgram.parse("")))
    }

    @Test
    fun readsCompactAndFlexibleRowsAndSkipsOtherLines() {
        val program = PlaTableFile.read("# header\n1x0 10 # a\n\n| 0 | 1 | 1 || 0 | 1 |\nnot a row\n011\t01\n", 3, 2)
        assertEquals(listOf(PlaRow("1x0", "10", "a"), PlaRow("011", "01")), program.rows)
    }

    @Test
    fun reportsEvolutionErrors() {
        assertEquals("pla_file_no_data", failure("# nothing\n").key)
        failure("10 1\n").let {
            assertEquals("pla_unexpected_input_width", it.key)
            assertEquals(mapOf("expected" to "3", "found" to "2"), it.arguments)
        }
        failure("101 1\n").let {
            assertEquals("pla_unexpected_output_width", it.key)
            assertEquals(mapOf("expected" to "2", "found" to "1"), it.arguments)
        }
        failure("101 10\n10 10\n").let {
            assertEquals("pla_row_exact_in_bits", it.key)
            assertEquals(mapOf("row" to "10 10", "count" to "3"), it.arguments)
        }
        failure("101 10\n101 1\n").let {
            assertEquals("pla_row_exact_out_bits", it.key)
            assertEquals(mapOf("row" to "101 1", "count" to "2"), it.arguments)
        }
        failure("1 2 0 || 1 0\n").let {
            assertEquals("pla_invalid_input_bit", it.key)
            assertEquals(mapOf("row" to "120", "bit" to "2"), it.arguments)
        }
        failure("1 0 0 || 1 z\n").let {
            assertEquals("pla_invalid_output_bit", it.key)
            assertEquals(mapOf("row" to "1z", "bit" to "z"), it.arguments)
        }
        assertEquals("pla_file_open_error",
            assertFailsWith<LocalizedArgumentException> { PlaTableFile.read(byteArrayOf(0xC3.toByte()), 3, 2) }.text.key)
    }

    @Test
    fun namesTheFileFromTheLabelLikeEvolution() {
        assertEquals("pla.txt", PlaTableFile.fileName(""))
        assertEquals("pla.txt", PlaTableFile.fileName(" _ ".trim()))
        assertEquals("decoder unit.txt", PlaTableFile.fileName(" decoder unit "))
    }
}
