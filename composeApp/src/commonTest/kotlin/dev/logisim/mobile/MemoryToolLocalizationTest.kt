package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryToolLocalizationTest {
    @Test
    fun toolTitleResolvesInTheCurrentLanguage() {
        val title = memoryEditorTool().titleText!!
        assertEquals("Memory editor", title.resolve(AppLanguage.ENGLISH))
        assertEquals("Editor de memória", title.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun dimensionFailureKeepsATranslatableIdentity() {
        val ram = CircuitComponent("ram", ComponentKind.RAM, GridPoint(0, 0),
            attributes = mapOf("addrWidth" to "2", "dataWidth" to "8"))
        val document = CircuitDocument(components = listOf(ram))
        val context = EditorToolContext(CircuitProject(listOf(document)), document, SimulationState(), listOf(ram),
            {}, {}, {}, {}, {})
        val failure = assertFailsWith<LocalizedArgumentException> {
            applyMemoryEditorImage(context, ram.id, MemoryImage(3, 16))
        }
        assertEquals("The image does not match the memory address and data widths.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A imagem não corresponde às larguras de endereço e dados da memória.", failure.text.resolve(AppLanguage.PORTUGUESE))
    }
}
