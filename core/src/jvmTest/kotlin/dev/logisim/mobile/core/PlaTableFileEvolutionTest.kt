package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** fixtures/pla is written and read by Evolution d58a61e0; see scripts/oracle/gates/pla-table-file.sh. */
class PlaTableFileEvolutionTest {
    private val directory = listOf(File("fixtures/pla"), File("../fixtures/pla")).first(File::isDirectory)

    @Test
    fun mobileWritesTheFileEvolutionSaves() {
        val table = "1x0x 101 # first match wins\n1xxx 010\n0011 111 #  spaced comment  \nx1x0 001\n"
        assertEquals(File(directory, "evolution-saved.txt").readText(), PlaTableFile.write(PlaProgram.parse(table)))
    }

    @Test
    fun evolutionAndMobileReadEachFileTheSameWay() {
        val oracle = File(directory, "evolution-read.tsv").readLines().map { it.split('\t') }
        val files = oracle.filter { it[0] == "file" }
        assertEquals(listOf("evolution-saved.txt", "mobile-saved.txt", "flexible.txt"), files.map { it[1] })
        for ((_, name, inputs, outputs) in files) {
            val program = PlaTableFile.read(File(directory, name).readBytes(), inputs.toInt(), outputs.toInt())
            assertEquals(oracle.filter { it[0] == "row" && it[1] == name }.map { it[2] + "\n" }.joinToString(""),
                program.serialize(), name)
            for (row in oracle.filter { it[0] == "value" && it[1] == name }) {
                val input = row[2].toULong()
                assertEquals(row[3].toULong(), program.evaluate(LogicVector.of(inputs.toInt(), input)).toULongOrNull(),
                    "$name input $input")
            }
        }
    }

    @Test
    fun mobileSavedFixtureRoundTrips() {
        val saved = File(directory, "mobile-saved.txt").readText()
        assertEquals(saved, PlaTableFile.write(PlaTableFile.read(saved, 5, 2)))
    }
}
