package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HdlImportLocalizationTest {
    @Test fun parserErrorsKeepTheirNoticeUntilPresentation() {
        val failure = assertIs<HdlNetlistResult.Failure>(YosysNetlistCodec.read("""{"modules":{},"modules":{}}"""))
        val diagnostic = failure.diagnostics.single()
        assertEquals("hdl_import_json_key_duplicate", diagnostic.notice.key)
        assertEquals("Duplicate JSON key: modules.", diagnostic.message)
        assertEquals("Duplicate JSON key: modules.", diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Chave JSON duplicada: modules.", diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test fun sourceValidationUsesTheSameTypedNoticeAsTheUi() {
        val failure = assertFailsWith<LocalizedArgumentException> { HdlInputFile("../source.v", "source") }
        assertEquals("Use a relative HDL file path with letters, digits, dots, underscores and hyphens.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Use um caminho relativo para o arquivo HDL, com letras, dígitos, pontos, sublinhados e hífens.",
            failure.text.resolve(AppLanguage.PORTUGUESE))
        val options = HdlImportOptions(listOf(HdlInputFile("source.v", "module top; endmodule")), top = "top; command")
        val invalidTop = assertFailsWith<LocalizedArgumentException> { options.request() }
        assertEquals("The top module must be an identifier.", invalidTop.text.resolve(AppLanguage.ENGLISH))
        assertEquals("O módulo principal deve ser um identificador.", invalidTop.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test fun importedNamesRemainLiteralParametersInBothLanguages() {
        val missing = "User{module}"
        val failure = assertIs<HdlNetlistResult.Failure>(YosysNetlistCodec.read(
            """{"modules":{"top":{"ports":{},"cells":{}}}}""", missing))
        assertEquals("Top module does not exist: User{module}.", failure.diagnostics.single().notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O módulo principal não existe: User{module}.", failure.diagnostics.single().notice.resolve(AppLanguage.PORTUGUESE))

        val unknown = HdlNetlistCell("cell{type}", "vendor{type}", emptyMap())
        val result = assertIs<HdlImportResult.Failure>(HdlNativeImporter.import(
            HdlNetlist("top", listOf(HdlNetlistModule("top", emptyList(), listOf(unknown))), "test"), emptyList()))
        val diagnostic = result.diagnostics.single()
        assertEquals("cell{type}", diagnostic.cell)
        assertEquals("Unsupported synthesis cell vendor{type}; no project was imported.", diagnostic.message)
        assertEquals("Célula de síntese não suportada: vendor{type}. Nenhum projeto foi importado.",
            diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test fun artifactCatchPreservesLocalizedValidation() {
        val job = CompanionJob("a".repeat(32), "yosys-import", CompanionJobState.SUCCEEDED,
            CompanionJsonString("external data"), null, emptyList())
        val options = HdlImportOptions(listOf(HdlInputFile("source.v", "module top; endmodule")))
        val project = CircuitProject(listOf(CircuitDocument("retained")))
        val failure = assertIs<HdlImportResult.Failure>(job.importHdl(options, project))
        assertEquals("hdl_import_synthesis_object", failure.diagnostics.single().notice.key)
        assertEquals("Synthesis result must be an object.", failure.diagnostics.single().message)
        assertEquals("O resultado da síntese deve ser um objeto.",
            failure.diagnostics.single().notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf("retained"), project.circuits.map { it.name })
    }

    @Test fun providerMessagesRemainLiteralData() {
        val raw = "Provider: módulo {module}, do not translate"
        val job = CompanionJob("b".repeat(32), "yosys-import", CompanionJobState.FAILED, CompanionJsonNull,
            CompanionJobError("provider-code", raw, CompanionJsonNull), emptyList())
        val options = HdlImportOptions(listOf(HdlInputFile("source.v", "module top; endmodule")))
        val failure = assertIs<HdlImportResult.Failure>(job.importHdl(options, CircuitProject(listOf(CircuitDocument("main")))))
        assertEquals(raw, failure.diagnostics.single().notice.resolve(AppLanguage.ENGLISH))
        assertEquals(raw, failure.diagnostics.single().notice.resolve(AppLanguage.PORTUGUESE))
    }
}
