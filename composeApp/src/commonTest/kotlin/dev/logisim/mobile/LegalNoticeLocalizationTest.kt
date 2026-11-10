package dev.logisim.mobile

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalNoticeLocalizationTest {
    @Test
    fun englishControlsKeepTheOriginalLicenseTextAvailable() {
        val strings = Localizer(AppLanguage.ENGLISH)
        assertEquals("Licenses", strings(Messages.LegalButton))
        assertEquals("Licenses, attribution and source code", strings(Messages.LegalButtonDescription))
        assertEquals("Licenses and attribution", strings(Messages.LegalTitle))
        assertEquals("Free software under GPLv3. No warranty. The rights and notices for each component are listed below.",
            strings(Messages.LegalIntroduction))
        assertEquals("Open source code and build instructions", strings(Messages.LegalSourceAction))
        assertEquals("Close", strings(Messages.ActionClose))
        assertEquals("Back", strings(Messages.LegalBackAction))
        assertTrue(LegalNoticeContent.notices.single { it.title == "Logisim Mobile" }.text
            .contains("Version 3, 29 June 2007"))
    }

    @Test
    fun portugueseControlsKeepTheOriginalLicenseTextAvailable() {
        val strings = Localizer(AppLanguage.PORTUGUESE)
        assertEquals("Licenças", strings(Messages.LegalButton))
        assertEquals("Licenças, atribuições e código-fonte", strings(Messages.LegalButtonDescription))
        assertEquals("Licenças e atribuições", strings(Messages.LegalTitle))
        assertEquals("Software livre sob GPLv3. Sem garantia. Os direitos e avisos de cada componente estão abaixo.",
            strings(Messages.LegalIntroduction))
        assertEquals("Abrir código-fonte e instruções de compilação", strings(Messages.LegalSourceAction))
        assertEquals("Fechar", strings(Messages.ActionClose))
        assertEquals("Voltar", strings(Messages.LegalBackAction))
        assertTrue(LegalNoticeContent.notices.single { it.title == "Logisim Mobile" }.text
            .contains("Version 3, 29 June 2007"))
    }
}
