package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeNoticeLocalizationTest {
    @Test
    fun plaWarningsKeepTheirRowIdentityAndTranslateAfterParsing() {
        val program = PlaProgram.parse("1 || 0\n00 || 1")
        val notice = program.notices.single()
        assertEquals(Messages.GatesPlaRowWidth, notice.key)
        assertEquals("PLA row 2: inconsistent width", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Linha 2 da PLA: largura inconsistente", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(program.rows, program.resized(2, 2).resized(1, 1).rows)
        assertEquals(program.notices, program.resized(2, 2).notices)
    }

    @Test
    fun romEditWarningsRemainTypedAtTheComponentEvaluationBoundary() {
        val registry = ComponentRegistry(MemoryComponents.definitions())
        val rom = registry.create(ComponentKind.ROM, "rom", GridPoint(0, 0))
        val attributes = registry.attributes(rom)
        val behavior = registry.requireDefinition(rom.kind).behavior!!
        val result = behavior.evaluate(ComponentEvaluationContext(rom, attributes, emptyMap(), emptySet(),
            state = behavior.initialState(rom, attributes), events = listOf(MemoryCellsChanged(mapOf(0L to 1uL)))))
        val notice = result.notices.single()
        assertEquals(Messages.MemoryRuntimeRomDocumentEdit, notice.key)
        assertEquals("Edit ROM contents in the document rather than the simulation state.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Edite o conteúdo da ROM no documento, não no estado da simulação.", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf(notice.resolve(AppLanguage.PORTUGUESE)), result.diagnostics)
    }

    @Test
    fun dualRamCollisionKeepsWriteOrderingAndReportsATranslatableNotice() {
        val configuration = MemoryConfiguration(dualPort = true)
        val address = LogicVector.of(8, 1uL)
        fun input(value: ULong) = MemoryPortInput(address, listOf(LogicVector.of(8, value)),
            writeEnable = LogicValue.HIGH, clock = LogicValue.HIGH)
        val result = MemoryCore.evaluate(configuration, MemoryRuntimeState.initial(configuration),
            listOf(input(1uL), input(2uL)))
        assertEquals(2uL, result.state.image[1])
        val notice = result.notices.single()
        assertEquals(Messages.MemoryRuntimeDualWriteCollision, notice.key)
        assertEquals("Dual-port RAM write collision: port B is applied after port A.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Colisão de escrita na RAM de duas portas: a porta B é aplicada depois da porta A.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun diagnosticsFromExternalBehaviorsAreLiteralData() {
        val detail = "Custom {kind} → שלום"
        val result = ComponentEvaluation(emptyMap(), diagnostics = listOf(detail))
        assertEquals(detail, result.notices.single().resolve(AppLanguage.ENGLISH))
        assertEquals(detail, result.notices.single().resolve(AppLanguage.PORTUGUESE))
    }
}
