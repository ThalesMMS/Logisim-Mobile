package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticNestedLocalizationTest {
    @Test
    fun attributeDiagnosticsKeepTheTypedSchemaReasonAcrossLanguages() {
        val kind = ComponentKind("TEST_INVALID_LOCALIZED")
        val registry = ComponentRegistry(listOf(ComponentDefinition(kind, "Test", "#Test",
            schema = AttributeSchema(listOf(AttributeKey.int("width", 8, 1..64))))))
        val component = CircuitComponent("bad", kind, GridPoint(0, 0), attributes = mapOf("width" to "0"))
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitDocument("main", listOf(component)),
            registry = registry, includeUnconnectedInputs = false)
        val diagnostic = report.diagnostics.single { it.code == DiagnosticCode.INVALID_ATTRIBUTES }
        assertEquals("Component bad has invalid attributes: Invalid value for width: 0.",
            diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O componente bad contém atributos inválidos: Valor inválido para width: 0.",
            diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun importDiagnosticsKeepAnInnerCodecNoticeAndRawStringCompatibility() {
        val diagnostic = CircuitDiagnosticAnalyzer.importFailure(message(Messages.CodecMainMissing))
        assertEquals("The file was not imported: The main circuit does not exist.",
            diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O arquivo não foi importado: Circuito principal inexistente.",
            diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
        val raw = "Provider {detail} → שלום"
        assertEquals("The file was not imported: $raw",
            CircuitDiagnosticAnalyzer.importFailure(raw).notice.resolve(AppLanguage.ENGLISH))
    }
}
