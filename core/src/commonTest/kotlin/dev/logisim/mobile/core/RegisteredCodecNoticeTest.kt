package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegisteredCodecNoticeTest {
    @Test
    fun unknownRegisteredComponentsKeepTheCanonicalDiagnosticAndLiteralName() {
        val name = "Missing {kind} → שלום"
        val xml = "<project><lib name=\"w\" desc=\"#Wiring\"/><circuit name=\"main\"><comp name=\"$name\" lib=\"w\" loc=\"(0,0)\"/></circuit></project>"
        val failure = assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml, ComponentRegistry(emptyList())))
        val notice = failure.notices.single()
        assertEquals(Messages.CodecComponentUnsupported, notice.key)
        assertEquals("Unsupported component: $name (library #Wiring).", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Componente não suportado: $name (biblioteca #Wiring).", notice.resolve(AppLanguage.PORTUGUESE))
    }
}
