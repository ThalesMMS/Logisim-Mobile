package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MemoryToolLocalizationTest {
    @Test
    fun toolTitleResolvesInTheCurrentLanguage() {
        val title = memoryEditorTool().titleText!!
        assertEquals("Memory editor", title.resolve(AppLanguage.ENGLISH))
        assertEquals("Editor de memória", title.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun memoryTitlesUseTheLabelOrTheLocalizedKind() {
        val dual = CircuitComponent("dual", MemoryComponents.DualRam, GridPoint(0, 0),
            attributes = mapOf("addrWidth" to "2", "dataWidth" to "8"))
        val title = memoryEditorTitle(dual)
        assertEquals("Dual-port RAM", title.resolve(AppLanguage.ENGLISH))
        assertEquals("RAM de duas portas", title.resolve(AppLanguage.PORTUGUESE))
        assertNotEquals(dual.kind.name, title.resolve(AppLanguage.ENGLISH))
        assertEquals("Boot", memoryEditorTitle(dual.copy(label = "Boot")).resolve(AppLanguage.PORTUGUESE))
        assertEquals("ROM", memoryEditorTitle(dual.copy(kind = ComponentKind.ROM)).resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun toolKeepsStagedEditsPerMemoryAndDropsThemForAnotherDocument() {
        val drafts = MemoryEditorDrafts()
        val image = MemoryImage(2, 8)
        val draft = MemoryEditorDraft(image, MemoryImage(2, 8, mapOf(1L to 5uL)), null, false)
        val rom = MemoryEditorDraftKey(7, "main", "", "rom")
        drafts.put(rom, draft)
        assertEquals(draft, drafts[rom])
        assertEquals(null, drafts[rom.copy(componentId = "ram")])
        assertEquals(null, drafts[rom.copy(instancePath = "main/sub")])
        drafts.retainDocument(7)
        assertEquals(draft, drafts[rom])
        drafts.retainDocument(8)
        assertEquals(null, drafts[rom])
        drafts.put(rom, draft)
        drafts.put(rom, null)
        assertEquals(null, drafts[rom])
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
