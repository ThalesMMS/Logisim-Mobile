package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.EvolutionNames
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #321: files the editor saves keep their circuit names and labels when Evolution d58a61e0 opens them.
 * fixtures/evolution-names holds the saved files and names.tsv (Mobile's rename and entry decisions);
 * scripts/oracle/evolution-names/run.sh opens the files in Evolution twice, re-saves them, checks names.tsv against
 * Evolution's own functions and records the outcome in evolution-load.tsv. Set LOGISIM_REGENERATE_FIXTURES=1 to
 * rewrite the Mobile files.
 */
class EvolutionNamesEvolutionTest {
    private val directory = listOf(File("fixtures/evolution-names"), File("../fixtures/evolution-names")).first(File::isDirectory)

    /** The unicode scenario of scripts/e2e-android.py, without a source version as in the e2e fixture. */
    private val unicodeFixture = """
        <?xml version='1.0' encoding='utf-8'?>
        <project version="1.0"><lib name="0" desc="#Wiring"/><main name="Visão → ‫اختبار‬"/><circuit name="Visão → ‫اختبار‬"><comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="E2E — ligação longa → entrada / ‫مرحبا‬"/></comp></circuit></project>
    """.trimIndent()

    /** The built-in example as Mobile da740d84 saved it (#321 receipt). */
    private val oldDemo = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project source="3.8.0" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <main name="Minha primeira porta AND"/>
          <circuit name="Minha primeira porta AND" id="Minha primeira porta AND">
            <comp lib="0" name="Pin" loc="(100,100)" logisim-mobile-id="a"><a name="label" val="A"/></comp>
            <comp lib="0" name="Pin" loc="(100,200)" logisim-mobile-id="b"><a name="label" val="B"/></comp>
            <comp lib="1" name="AND Gate" loc="(300,150)" logisim-mobile-id="and"><a name="label" val="AND"/></comp>
            <comp lib="0" name="Pin" loc="(420,150)" logisim-mobile-id="out"><a name="label" val="Saída"/><a name="type" val="output"/><a name="facing" val="west"/></comp>
          </circuit>
        </project>
    """.trimIndent()

    private fun opened(xml: String): String {
        val state = EditorState(null)
        assertTrue(state.importXml(xml))
        return state.saveXml()
    }

    private fun scenarios(): Map<String, String> = buildMap {
        put("demo", EditorState(null).saveXml())
        put("unicode", opened(unicodeFixture))
        put("old-demo", opened(oldDemo))
        for (example in CircuitExamples.all) put("example-${example.id}", opened(example.xml))
    }

    private fun names(): String = buildString {
        val samples = listOf("Visão → \u202bاختبار\u202c", "E2E — ligação longa → entrada / \u202bمرحبا\u202c",
            "Minha primeira porta AND", "Saída", "  padded  ", "9a!b", "~x\uD83D\uDE00y", "a_", "a__b", "A AND B", "3 + 2",
            "Child (2)", "Minha_primeira_porta_AND", "Y", "AND", "signal", "_x", "x1", "DFF", "1abc", "Bus-input")
        for (sample in samples) {
            val suffix = EvolutionNames.stableSuffix(sample)
            append(listOf(escape(sample), suffix, escape(EvolutionNames.validLabel(sample)),
                (EvolutionNames.entryProblem(sample) == null).toString(), EvolutionNames.renamedOnLoad(sample).toString()).joinToString("\t"))
            append('\n')
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    @Test
    fun savedFilesKeepTheirNamesInEvolution() {
        val scenarios = scenarios()
        if (System.getenv("LOGISIM_REGENERATE_FIXTURES") == "1") {
            directory.listFiles { file -> file.name.startsWith("mobile-") }?.forEach(File::delete)
            scenarios.forEach { (name, xml) -> File(directory, "mobile-$name.circ").writeText(xml) }
            File(directory, "names.tsv").writeText(names())
        }
        assertEquals(names(), File(directory, "names.tsv").readText())
        val oracle = File(directory, "evolution-load.tsv").readLines().map { it.split('\t') }
        val files = oracle.filter { it[0] == "file" }.map { it[1] }
        assertEquals(scenarios.keys.map { "mobile-$it.circ" }.sorted(), files.sorted())
        for ((name, xml) in scenarios) {
            assertEquals(File(directory, "mobile-$name.circ").readText(), xml, name)
        }
        val results = oracle.filter { it[0] == "result" }
        assertTrue(results.isNotEmpty())
        for (result in results) assertEquals("true", result.last(), result.joinToString("\t"))
    }
}
