package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import kotlin.test.*

class SocLocalizationTest {
    @Test fun elfAndCpuNoticesRemainTranslatableAfterExecution() {
        val rejected = assertIs<SocExecutableResult.Rejected>(SocElfLoader.read(byteArrayOf(), SocArchitecture.RV32IM))
        assertEquals("ELF header is truncated.", rejected.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O cabeçalho ELF está truncado.", rejected.notice.resolve(AppLanguage.PORTUGUESE))
        val bus = SocAddressSpace("bus", listOf(SocMemoryDevice("ram")))
        bus.write(0u, 0xFFFFFFFFu)
        val result = Rv32Cpu.step(Rv32CpuState(), bus)
        assertEquals("Invalid RV32 instruction.", result.notice?.resolve(AppLanguage.ENGLISH))
        assertEquals("Instrução RV32 inválida.", result.notice?.resolve(AppLanguage.PORTUGUESE))
        assertEquals(result.notice, result.trace?.notice)
    }
    @Test fun busFailureCarriesItsTechnicalErrorAndLocalizedExplanation() {
        val result = Nios2Cpu.step(Nios2CpuState(), SocAddressSpace("empty"))
        assertEquals("Instruction fetch: No devices are connected to the bus.", result.notice?.resolve(AppLanguage.ENGLISH))
        assertEquals("Busca de instrução: Não há dispositivos conectados ao barramento.", result.notice?.resolve(AppLanguage.PORTUGUESE))
        assertTrue(result.state.stopped)
    }
    @Test fun allSocComponentLabelsResolveInBothLanguages() {
        val expected = mapOf(
            SocComponents.BUS to ("SoC Bus" to "Barramento SoC"),
            SocComponents.MEMORY to ("SoC Memory" to "Memória SoC"),
            SocComponents.PIO to ("SoC PIO" to "PIO SoC"),
            SocComponents.VGA to ("SoC VGA" to "VGA SoC"),
            SocComponents.DMA to ("SoC DMA" to "DMA SoC"),
            SocComponents.UART to ("JTAG UART" to "JTAG UART"),
            SocComponents.RV32 to ("RV32IM Processor" to "Processador RV32IM"),
            SocComponents.NIOS2 to ("Nios II Processor" to "Processador Nios II"),
        )
        assertEquals(SocComponents.kinds, expected.keys)
        for ((kind, labels) in expected) {
            val label = message("component_${kind.name.lowercase()}")
            assertEquals(labels.first, label.resolve(AppLanguage.ENGLISH))
            assertEquals(labels.second, label.resolve(AppLanguage.PORTUGUESE))
        }
    }

    @Test fun debuggerFormatsKeepTechnicalDataAndTranslateTheirLabels() {
        val binding = message("soc_bus_binding", "id" to "bus-{name}")
        assertEquals("Bus: bus-{name}", binding.resolve(AppLanguage.ENGLISH))
        assertEquals("Barramento: bus-{name}", binding.resolve(AppLanguage.PORTUGUESE))
        val registers = message("soc_register_value", "register" to "x31", "value" to "89ABCDEF")
        assertEquals("x31 89ABCDEF", registers.resolve(AppLanguage.ENGLISH))
        assertEquals("x31 89ABCDEF", registers.resolve(AppLanguage.PORTUGUESE))
        val uart = message("soc_uart_state", "rx" to 3, "rxCapacity" to 64, "tx" to 2, "txCapacity" to 64, "irq" to true)
        assertEquals("RX 3/64 · TX 2/64 · IRQ true", uart.resolve(AppLanguage.PORTUGUESE))
        val dma = message("soc_dma_state", "bytes" to 16, "busy" to false, "done" to true, "irq" to true)
        assertEquals("DMA 16 bytes · ocupado false · concluído true · IRQ true", dma.resolve(AppLanguage.PORTUGUESE))
        val trace = message("soc_bus_entry", "sequence" to 4, "initiator" to "cpu", "operation" to "R", "address" to "00001000", "result" to SocBusError.NO_RESPONDER.notice)
        assertEquals("4 cpu R 00001000 → Nenhum dispositivo responde neste endereço.", trace.resolve(AppLanguage.PORTUGUESE))
    }

    @Test fun everyBusErrorHasAnExplanationWithoutChangingItsProtocolCode() {
        val explanations = listOf(
            "Nenhum dispositivo responde neste endereço.",
            "Não há dispositivos conectados ao barramento.",
            "Mais de um dispositivo responde à transação.",
            "Leitura e gravação simultâneas exigem uma transação atômica.",
            "Nenhum barramento está conectado.",
            "O endereço está desalinhado ou não seleciona um registrador.",
            "Esta largura de acesso não tem suporte.",
            "Este registrador é somente leitura.",
            "Este registrador é somente gravação.",
            "Este registrador não está disponível nesta configuração.",
        )
        for ((index, error) in SocBusError.entries.withIndex()) {
            assertEquals(index + 1, error.desktopId)
            assertEquals(explanations[index], error.notice.resolve(AppLanguage.PORTUGUESE))
            assertFalse(error.notice.resolve(AppLanguage.ENGLISH).startsWith("soc_"))
        }
    }

    @Test fun wrappingMemoryConfigurationIsRejectedBeforeApplyingAttributes() {
        val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
        val memory = registry.create(SocComponents.MEMORY, "ram", GridPoint(0, 0))
        val failure = assertFailsWith<LocalizedArgumentException> {
            registry.reconfigure(memory, mapOf("StartAddress" to "0xfffffffc", "MemSize" to "10"))
        }
        assertEquals("Memory at 0xfffffffc with 1024 bytes exceeds the 32-bit address space.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A memória em 0xfffffffc com 1024 bytes ultrapassa o espaço de endereçamento de 32 bits.", failure.text.resolve(AppLanguage.PORTUGUESE))
        val valid = registry.reconfigure(memory, mapOf("StartAddress" to "0xfffffc00", "MemSize" to "10"))
        assertEquals(0xFFFFFC00u, SocComponents.createDevice(valid)?.startAddress)
        assertEquals(1024uL, SocComponents.createDevice(valid)?.sizeInBytes)
        assertFailsWith<LocalizedArgumentException> {
            SocComponents.createDevice(memory.copy(attributes = mapOf("StartAddress" to "0xfffffffc", "MemSize" to "10")))
        }
    }

}
