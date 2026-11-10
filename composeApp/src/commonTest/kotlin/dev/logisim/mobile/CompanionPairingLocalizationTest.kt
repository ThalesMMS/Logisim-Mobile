package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionOperationCapability
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionPairingLocalizationTest {
    @Test
    fun protocolFailuresKeepHostDetailsLiteralAcrossLanguageChanges() {
        val detail = "Unexpected {code} → שלום " + "long detail ".repeat(30)
        val notice = companionPairingFailure(CompanionProtocolException("HOST_REPLY", detail))
        assertEquals("Host error HOST_REPLY: $detail", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Erro do host HOST_REPLY: $detail", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun transportFailuresKeepTheirCodeAndTranslateOnlyTheExplanation() {
        val notice = companionPairingFailure(CompanionTransportException("TLS_ERROR"))
        assertEquals("Could not connect to the host: TLS_ERROR.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível conectar ao host: TLS_ERROR.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun operationStatusPreservesCapabilityPriorityAndProtocolNames() {
        fun operation(allowed: Boolean, available: Boolean, confirmation: Boolean) =
            CompanionOperationCapability("yosys.{name}", allowed, available, emptyList(), emptyList(), confirmation, "host description")
        val operations = listOf(operation(false, false, true), operation(true, false, true),
            operation(true, true, true), operation(true, true, false))
        assertEquals(listOf("disabled by the host", "tools unavailable", "available, with confirmation before execution", "available"),
            operations.map { companionOperationStatus(it).resolve(AppLanguage.ENGLISH) })
        assertEquals(listOf("desabilitada pelo host", "ferramentas indisponíveis", "disponível, com confirmação antes de executar", "disponível"),
            operations.map { companionOperationStatus(it).resolve(AppLanguage.PORTUGUESE) })
        val notice = message(Messages.CompanionUiOperationStatus, "name" to operations.last().name,
            "status" to companionOperationStatus(operations.last()))
        assertEquals("yosys.{name}: available", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("yosys.{name}: disponível", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun capabilityLimitsAndReceiptsUseSingularAndPluralForms() {
        val english = Localizer(AppLanguage.ENGLISH)
        val portuguese = Localizer(AppLanguage.PORTUGUESE)
        assertEquals("Protocol 1 · input limit 1 byte", english.plural(Messages.CompanionUiCapabilities, 1, "version" to 1))
        assertEquals("Protocolo 1 · limite de entrada 2 bytes", portuguese.plural(Messages.CompanionUiCapabilities, 2, "version" to 1))
        assertEquals("The host retains up to 1 receipt for safe action retries.", english.plural(Messages.CompanionUiReceipts, 1))
        assertEquals("O host conserva até 2 recibos para repetição segura de ações.", portuguese.plural(Messages.CompanionUiReceipts, 2))
    }

    @Test
    fun creatingTheToolKeepsATypedTitleAndDoesNotInvokePairingCallbacks() {
        var connected = 0
        var disconnected = 0
        val tool = companionPairingEditorTool({ _, _ -> connected++ }, { disconnected++ })
        assertEquals(Messages.CompanionUiTitle, tool.titleText.key)
        assertEquals("Companion", tool.titleText.resolve(AppLanguage.ENGLISH))
        assertEquals("Companion", tool.titleText.resolve(AppLanguage.PORTUGUESE))
        assertEquals(0, connected)
        assertEquals(0, disconnected)
    }
}
