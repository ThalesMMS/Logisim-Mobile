package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticLocalizationTest {
    @Test
    fun reportCountsHandleSingularAndPluralIndependently() {
        val oneError = diagnosticReportCountNotice(1, 2)
        assertEquals("1 error · 2 warnings", oneError.resolve(AppLanguage.ENGLISH))
        assertEquals("1 erro · 2 avisos", oneError.resolve(AppLanguage.PORTUGUESE))
        val oneWarning = diagnosticReportCountNotice(0, 1)
        assertEquals("0 errors · 1 warning", oneWarning.resolve(AppLanguage.ENGLISH))
        assertEquals("0 erros · 1 aviso", oneWarning.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun toolTitleUsesThePresentationLocaleAfterTheFactoryWasCreated() {
        val tool = diagnosticsEditorTool(ComponentRegistry(emptyList()))
        assertEquals("Diagnostics and statistics", tool.titleText.resolve(AppLanguage.ENGLISH))
        assertEquals("Diagnósticos e estatísticas", tool.titleText.resolve(AppLanguage.PORTUGUESE))
    }
}
