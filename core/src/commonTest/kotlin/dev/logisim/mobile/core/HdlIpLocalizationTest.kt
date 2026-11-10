package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HdlIpLocalizationTest {
    private val source = "entity chip is port (a : in std_logic; y : out std_logic); end;"

    @Test fun declarationNoticesTranslateAtPresentationAndPreserveLiteralNames() {
        val design = HdlIpDeclarations.vhdl(source, mapOf("missing{count}" to "4"))
        val diagnostic = design.diagnostics.single { it.code == "unknown-generic" }
        assertEquals("Undeclared generic: missing{count}.", diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Parâmetro não declarado: missing{count}.", diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(diagnostic.notice.resolve(AppLanguage.ENGLISH), diagnostic.message)
        assertEquals("VHDL entity", HdlIpLibrary.VHDL.localizedLabel().resolve(AppLanguage.ENGLISH))
        assertEquals("Entidade VHDL", HdlIpLibrary.VHDL.localizedLabel().resolve(AppLanguage.PORTUGUESE))
        assertEquals("BLIF circuit", HdlIpLibrary.BLIF.localizedLabel().resolve(AppLanguage.ENGLISH))
        assertEquals("Circuito BLIF", HdlIpLibrary.BLIF.localizedLabel().resolve(AppLanguage.PORTUGUESE))
    }

    @Test fun motorKeepsTypedNoticesWhileLegacyDiagnosticsStayEnglish() {
        val component = CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(0, 0), attributes = mapOf("content" to source))
        val context = ComponentEvaluationContext(component, HdlIpLibrary.Schema.parse(component.attributes),
            inputs = mapOf("a" to LogicVector.high(1)), connectedInputs = setOf("a"))
        val initial = HdlIpBehavior.evaluate(context)
        assertEquals("The HDL-IP companion is disconnected. Start co-simulation explicitly.", initial.notices.single().resolve(AppLanguage.ENGLISH))
        assertEquals("O companion HDL-IP está desconectado. Inicie a co-simulação explicitamente.", initial.notices.single().resolve(AppLanguage.PORTUGUESE))
        val reason = message(Messages.HdlIpJobFailureReason, "reason" to "nvc: signal {width}")
        val failed = HdlIpBehavior.evaluate(context.copy(events = listOf(CompanionHdlUnavailable(1, reason))))
        val state = assertIs<HdlIpState>(failed.state)
        assertEquals("Co-simulation failed: nvc: signal {width}", state.failure)
        assertEquals("A co-simulação falhou: nvc: signal {width}", state.failureNotice?.resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf("Co-simulation failed: nvc: signal {width}"), failed.diagnostics)
        assertEquals("E", failed.outputs.getValue("y").display)
    }

    @Test fun companionFailuresAndGuardsPreserveKeysCodesAndProviderText() {
        val job = CompanionJob("a".repeat(32), "hdl.start", CompanionJobState.FAILED, CompanionJsonNull,
            CompanionJobError("analysis_failed", "NVC {port}", CompanionJsonNull), emptyList())
        val failure = assertFailsWith<HdlCosimulationFailure> { job.hdlSample(emptyMap()) }
        assertEquals("analysis_failed", failure.code)
        assertEquals("Co-simulation failed: NVC {port}", failure.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("A co-simulação falhou: NVC {port}", failure.notice.resolve(AppLanguage.PORTUGUESE))
        val guard = assertFailsWith<LocalizedArgumentException> { hdlStepRequest("invalid", emptyMap(), 1) }
        assertEquals("Invalid co-simulation session.", guard.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Sessão de co-simulação inválida.", guard.text.resolve(AppLanguage.PORTUGUESE))
    }
}
