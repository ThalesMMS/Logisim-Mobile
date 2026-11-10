package dev.logisim.mobile.core

data class SocDriverFiles(val name: String, val header: String, val source: String)

/** Generates C99 drivers for the register map of the selected circuit peripheral. */
object SocDriverExport {
    private data class Register(val name: String, val offset: Int, val read: Boolean, val write: Boolean)

    fun export(component: CircuitComponent): SocDriverFiles? {
        val kind = when (component.kind) {
            SocComponents.PIO -> "PIO"
            SocComponents.VGA -> "VGA"
            else -> return null
        }
        val fallback = "${kind}_${component.position.x}_${component.position.y}"
        val identifier = component.label.uppercase()
            .map { if (it in 'A'..'Z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').take(80).ifBlank { fallback.replace('-', '_') }
        val name = if (identifier.first().isDigit()) "${kind}_$identifier" else identifier
        val device = requireNotNull(SocComponents.createDevice(component))
        val registers = when (device) {
            is SocPioDevice -> pioRegisters(device)
            is SocVgaDevice -> listOf(Register("VgaMode", 0, read = true, write = true))
            else -> error("Unexpected SoC driver peripheral.")
        }
        val header = buildString {
            appendLine("#ifndef LOGISIM_${name}_H")
            appendLine("#define LOGISIM_${name}_H")
            appendLine()
            appendLine("#include <stdint.h>")
            appendLine("#define ${name}_BASE_ADDRESS ${constant(device.startAddress)}")
            when (device) {
                is SocPioDevice -> appendLine("#define ${name}_PIO_WIDTH ${device.width}u")
                is SocVgaDevice -> {
                    appendLine("#define ${name}_FRAMEBUFFER_ADDRESS ${constant(device.bufferAddress)}")
                    appendLine("#define ${name}_ENABLED_MODES ${constant(device.softwareModes)}")
                    SocVgaDevice.dimensions.forEachIndexed { index, (width, height) ->
                        appendLine("#define SOFT_MODE_${width}X${height}_MASK ${constant(1u shl index)}")
                    }
                }
            }
            appendLine()
            for (register in registers) {
                if (register.read) appendLine("uint32_t get$name${register.name}(void);")
                if (register.write) appendLine("void set$name${register.name}(uint32_t value);")
            }
            appendLine()
            appendLine("#endif")
        }
        val source = buildString {
            appendLine("#include \"$name.h\"")
            for (register in registers) {
                val address = "*(volatile uint32_t *)(uintptr_t)(${name}_BASE_ADDRESS + ${register.offset}u)"
                if (register.read) {
                    appendLine()
                    appendLine("uint32_t get$name${register.name}(void) {")
                    appendLine("    return $address;")
                    appendLine("}")
                }
                if (register.write) {
                    appendLine()
                    appendLine("void set$name${register.name}(uint32_t value) {")
                    appendLine("    $address = value;")
                    appendLine("}")
                }
            }
        }
        return SocDriverFiles(name, header, source)
    }

    private fun pioRegisters(device: SocPioDevice) = buildList {
        val hasInput = device.direction != SocPioDirection.OUTPUT
        val hasOutput = device.direction != SocPioDirection.INPUT
        if (hasOutput) add(Register("OutputValue", 0, read = false, write = true))
        if (hasInput) add(Register("InputValue", 0, read = true, write = false))
        if (device.direction == SocPioDirection.BIDIRECTIONAL) add(Register("DirectionReg", 4, read = true, write = true))
        if (hasInput && device.generatesIrq) add(Register("IrqMaskReg", 8, read = true, write = true))
        if (hasInput && device.synchronousCapture) add(Register("EdgeCapturReg", 12, read = true, write = true))
        if (hasOutput && device.bitOperations) {
            add(Register("OutsetReg", 16, read = false, write = true))
            add(Register("OutclearReg", 20, read = false, write = true))
        }
    }

    private fun constant(value: UInt) = "UINT32_C(0x${value.toString(16).uppercase().padStart(8, '0')})"
}
