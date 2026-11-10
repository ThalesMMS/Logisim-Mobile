package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircSourceVersionTest {
    private fun rom(source: String) = """
        <project $source>
          <lib name="m" desc="#Memory"/>
          <circuit name="main"><comp name="ROM" lib="m" loc="(180,80)"/></circuit>
        </project>
    """.trimIndent()

    @Test
    fun missingOrBlankSourceRejectsAmbiguousRomBeforeSimulation() {
        for (source in listOf("", "source=\" \"")) {
            val result = assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(rom(source)))
            assertEquals(Messages.CodecSourceVersionAmbiguous, result.notices.single().key)
            assertTrue(result.diagnostics.single().contains("ROM"))
        }
    }

    @Test
    fun acceptsUnambiguousMissingSourceAndBothExplicitProfiles() {
        val sourceLess = """
            <project><lib name="w" desc="#Wiring"/>
            <circuit name="main"><comp name="Pin" lib="w" loc="(20,20)"/></circuit>
            </project>
        """.trimIndent()
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(sourceLess)).project
        assertEquals(ComponentKind.INPUT, project.mainCircuit.components.single().kind)
        for ((source, original) in listOf("2.7.1" to true, "5.1.0-dev" to false)) {
            val explicit = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(rom("source=\"$source\""))).project
            assertEquals(source, explicit.sourceVersion)
            assertEquals(original, explicit.mainCircuit.components.single().attributes[ORIGINAL_ROM_ATTRIBUTE] == "true")
        }
    }

    @Test
    fun explicitParentSourceDoesNotMaskAnAmbiguousExternalCircuit() {
        val root = """
            <project source="5.1.0-dev"><lib name="x" desc="file#child.circ"/>
            <circuit name="main"><comp name="main" lib="x" loc="(100,100)"/></circuit>
            </project>
        """.trimIndent()
        val result = assertIs<CircLibraryImportResult.Failure>(
            CircLibraryCodec.read(root, "parent", CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", rom("")) }),
        )
        assertTrue(result.diagnostics.single().contains("ROM"))
        assertTrue(result.diagnostics.single().contains("versão de origem"))
    }
}
