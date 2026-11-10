package dev.logisim.mobile

import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PreferencesLocalizationTest {
    @Test
    fun unsupportedVersionsKeepTheOriginalAndTranslateTheirNotice() {
        val loaded = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n999\n")
        assertFalse(loaded.canPersist)
        val notice = loaded.notices.single()
        assertEquals(Messages.PreferencesVersionUnsupported, notice.key)
        assertEquals("Unsupported preferences version: 999. The original was preserved.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Versão de preferências não suportada: 999. O original foi preservado.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun invalidLocaleFallbackDoesNotRewriteTheSerializedLocaleIdentity() {
        val serialized = EditorPreferencesCodec.write(EditorPreferences()).replace("locale=system", "locale=unknown")
        val loaded = EditorPreferencesCodec.read(serialized)
        assertEquals(EditorLocale.SYSTEM, loaded.preferences.locale)
        val notice = loaded.notices.single()
        assertEquals("Invalid preference: locale. The default value was used.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Preferência inválida: locale. O valor padrão foi usado.", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf(notice.resolve(AppLanguage.PORTUGUESE)), loaded.diagnostics)
    }

    @Test
    fun storageDetailsRemainLiteralWhileTheWrapperChangesLanguage() {
        val detail = "Provider {reason} → שלום"
        val storage = object : PreferencesStorage {
            override fun load(): String? = throw IllegalStateException(detail)
            override fun save(serialized: String) = Unit
        }
        val loaded = EditorPreferencesRepository(storage).load()
        assertEquals("Could not read preferences: $detail", loaded.notices.single().resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível ler as preferências: $detail", loaded.notices.single().resolve(AppLanguage.PORTUGUESE))
    }
}
