package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W3-01 (C11): the editor validates label fonts with AnnotationFont, so every font a real project uses must survive it. */
class LabelFontFixturesTest {
    private fun root(name: String) = listOf(File(name), File("../$name")).first { it.isDirectory }

    @Test
    fun everyLabelFontInTheFixturesAndExamplesRoundTrips() {
        val pattern = Regex("""name="labelfont" val="([^"]*)"""")
        val fonts = listOf("fixtures", "examples").map(::root).flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "circ" }
                .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
        }.toSet()
        // The fixtures and examples hold Evolution's two defaults and the plain 12 of io-held-output.circ.
        assertTrue(setOf("SansSerif plain 10", "SansSerif bold 16", "SansSerif plain 12").all { it in fonts }, "$fonts")
        for (font in fonts) assertEquals(font, AnnotationFont.parse(font)?.serialized, font)
    }
}
