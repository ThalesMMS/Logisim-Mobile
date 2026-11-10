package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircEscapingTest {
    @Test
    fun exportsEscapedAndPlainUnicodeWithoutChangingTheValues() {
        for (label in listOf("", "plain-ASCII-123", "ação ∧ β 💡", "<&>\"'\n\r\t💡ação", "already &lt; encoded")) {
            // Original Logisim keeps free-form names; Evolution would rename them when it opens the file (#321).
            val document = CircuitDocument("project $label", listOf(
                CircuitComponent("input", ComponentKind.INPUT, GridPoint(10, 10), label)),
                fileMetadata = CircuitFileMetadata(sourceVersion = "2.7.1", mainCircuitName = "project $label"))
            val xml = CircCodec.write(document)
            val restored = assertIs<CircImportResult.Success>(CircCodec.read(xml)).document
            assertEquals(document.name, restored.name)
            assertEquals(label, restored.components.single().label)
        }
    }

    @Test
    fun exportsAttributeWhitespaceAndMetacharactersAsEntities() {
        val label = "<&>\"'\n\r\t💡ação"
        val xml = CircCodec.write(CircuitDocument("main", listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(10, 10), label))))
        assertTrue(xml.contains("val=\"&lt;&amp;&gt;&quot;&apos;&#10;&#13;&#9;💡ação\""))
    }

    @Test
    fun rejectsForbiddenControlCharactersBeforeAndAfterAnEscapedCharacter() {
        for (label in listOf("bad\u0000plain", "&bad\u0001after", "\u000b", "\u001f")) {
            assertFailsWith<IllegalArgumentException> {
                CircCodec.write(CircuitDocument("main", listOf(
                    CircuitComponent("input", ComponentKind.INPUT, GridPoint(10, 10), label))))
            }
        }
    }
}
