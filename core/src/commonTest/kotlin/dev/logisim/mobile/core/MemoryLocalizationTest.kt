package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MemoryLocalizationTest {
    @Test
    fun editingFailureKeepsItsIdentityAndLiteralInputAcrossLanguages() {
        val input = "valeur → שלום {bits} " + "longue ".repeat(30)
        val failure = assertIs<MemoryEditResult.Failure>(MemoryEditorModel(MemoryImage(4, 8)).editCell("0", input))
        assertEquals(Messages.MemoryErrorNumber, failure.notice.key)
        assertEquals("Invalid number: $input.", failure.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Número inválido: $input.", failure.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun addressBoundsAreFormattedAtThePresentationBoundary() {
        val failure = assertIs<MemoryEditResult.Failure>(MemoryEditorModel(MemoryImage(4, 8)).navigate("10"))
        assertEquals("The address must be between 0 and 15.", failure.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O endereço deve estar entre 0 e 15.", failure.message)
    }

    @Test
    fun parserDiagnosticsTranslateTheirLineAndReasonTogether() {
        val failure = assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText("v2.0 raw\nzz", 4, 8))
        val diagnostic = failure.diagnostics.single()
        assertEquals(Messages.MemoryCodecHexValue, diagnostic.detail.key)
        assertEquals(2, diagnostic.line)
        assertEquals("Line 2: Invalid hexadecimal value: zz.", diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Linha 2: Valor hexadecimal inválido: zz.", diagnostic.toString())
        val escape = assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decode("\\q".encodeToByteArray(), 4, 8, MemoryImageFormat.ASCII_BIG))
        assertEquals("Line 1: Invalid escape sequence: \\q.", escape.diagnostics.single().notice.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun exportFailuresKeepTheLimitAndFormatsKeepTheirBytes() {
        val image = MemoryImage(4, 8, mapOf(0L to 0x2auL))
        val failure = assertFailsWith<LocalizedArgumentException> {
            MemoryImageCodec.encode(image, MemoryImageFormat.RAW, MemoryImageLimits(maxExportBytes = 1))
        }
        assertEquals("The export exceeds 1 byte.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Dados binários, big-endian", MemoryImageFormat.BINARY_BIG.localizedDescription().resolve(AppLanguage.PORTUGUESE))
        assertEquals("v2.0 raw\n2a\n", MemoryImageCodec.encodeText(image, MemoryImageFormat.RAW))
    }

    @Test
    fun dimensionsAndAccessibleStatesUseNestedLocalizedValues() {
        val dimensions = message(Messages.MemoryDimensions, "words" to 4,
            "bits" to LocalizedText(Messages.MemoryWordBits, quantity = 1))
        assertEquals("4 words · 1 bit per word", dimensions.resolve(AppLanguage.ENGLISH))
        assertEquals("4 palavras · 1 bit por palavra", dimensions.resolve(AppLanguage.PORTUGUESE))
        val switch = message(Messages.IoA11ySwitch, "label" to "IRQ →", "number" to 1,
            "state" to message(Messages.IoSwitchOn))
        assertEquals("IRQ →, switch 1, on", switch.resolve(AppLanguage.ENGLISH))
        assertEquals("IRQ →, chave 1, ligada", switch.resolve(AppLanguage.PORTUGUESE))
    }
}
