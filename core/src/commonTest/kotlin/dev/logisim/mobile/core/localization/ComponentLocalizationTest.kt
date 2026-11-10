package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComponentLocalizationTest {
    @Test
    fun everyBuiltInComponentHasBothResourceForms() {
        for (kind in (ComponentKind.entries + ComponentCatalog.definitions.map { it.kind }).distinct()) {
            val key = "component_${kind.name.lowercase()}"
            val template = assertNotNull(appMessageCatalog[key], kind.name)
            assertEquals(template.english.other, kind.localizedLabel().resolve(AppLanguage.ENGLISH))
            assertEquals(template.portuguese.other, kind.localizedLabel().resolve(AppLanguage.PORTUGUESE))
        }
    }

    @Test
    fun familyLabelsAreTranslatedWhileChipNumbersRemainTechnicalNames() {
        assertEquals("Floating-point adder", message("component_fpadder").resolve(AppLanguage.ENGLISH))
        assertEquals("Somador de ponto flutuante", message("component_fpadder").resolve(AppLanguage.PORTUGUESE))
        assertEquals("Priority encoder", message("component_priority_encoder").resolve(AppLanguage.ENGLISH))
        assertEquals("Codificador de prioridade", message("component_priority_encoder").resolve(AppLanguage.PORTUGUESE))
        assertEquals("74123", message("component_ttl_74123").resolve(AppLanguage.ENGLISH))
        assertEquals("74123", message("component_ttl_74123").resolve(AppLanguage.PORTUGUESE))
    }
}
