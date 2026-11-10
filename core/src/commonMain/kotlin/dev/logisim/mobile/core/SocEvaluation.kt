package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

data class SocComponentEvaluation(val evaluation: ComponentEvaluation, val peerStates: Map<String, ComponentState> = emptyMap())

/** Executes a component against the current instance's peer snapshots, never another circuit's devices. */
object SocEvaluation {
    fun initialState(component: CircuitComponent): ComponentState {
        val attributes = SocComponents.attributes(component)
        return when (component.kind) {
            SocComponents.BUS -> SocBusComponentState(SocAddressSpace(attributes[SocComponents.BusIdentifier].ifBlank { component.id }))
            SocComponents.RV32 -> SocRv32ComponentState(Rv32Cpu.reset(attributes[SocComponents.ResetVector]).copy(stopped = true),
                SocComponents.createDevice(component) as SocPlicDevice)
            SocComponents.NIOS2 -> SocNios2ComponentState(Nios2Cpu.reset(attributes[SocComponents.ResetVector]).copy(stopped = true))
            else -> SocDeviceComponentState(requireNotNull(SocComponents.createDevice(component)))
        }
    }

    fun evaluate(context: ComponentEvaluationContext, components: List<CircuitComponent>, peerStates: Map<String, ComponentState>): SocComponentEvaluation {
        val component = context.component
        val attributes = context.attributes
        val currentState = if (context.state === EmptyComponentState) initialState(component) else context.state
        val allStates = components.filter { it.kind in SocComponents.kinds }.associate { it.id to (peerStates[it.id] ?: initialState(it)) } + (component.id to currentState)
        val scope = SocRuntimeScope(components, allStates)
        fun input(name: String) = context.inputs[name]?.bit(0) ?: LogicValue.UNKNOWN
        fun high(name: String) = input(name) == LogicValue.HIGH
        fun irqLines(): UInt = (0 until attributes[SocComponents.IrqWidth]).fold(0u) { mask, index -> if (high("irq$index")) mask or (1u shl index) else mask }
        val diagnostics = mutableListOf<String>()
        val notices = mutableListOf<LocalizedText>()
        var state: ComponentState = currentState
        // Outputs that Evolution's JtagUartState.handleOperations sets again in this propagation.
        var repeated: Set<String>? = null
        if (scope.selectedBus(component) in scope.duplicateBusIds) {
            diagnostics += "Duplicate SoC bus identifier: ${scope.selectedBus(component)}"
            notices += message("soc_duplicate_bus", "id" to scope.selectedBus(component))
        }
        val reset = high("reset")
        val singleStep = context.events.any { it === SocStepEvent }
        fun shouldExecute(lastClock: LogicValue) =
            !reset && (singleStep || lastClock == LogicValue.LOW && input("clock") == LogicValue.HIGH)
        when (val old = state) {
            is SocRv32ComponentState -> {
                var cpu = old.cpu
                var entryPoint = old.entryPoint
                var debugger = if (reset) old.debugger.reset() else old.debugger
                val plic = scope.device(component) as SocPlicDevice
                if (reset) cpu = Rv32Cpu.reset(entryPoint ?: attributes[SocComponents.ResetVector], cpu).copy(stopped = true)
                else {
                    plic.setIrqLines(irqLines())
                    val pending = cpu.csrs[0x344] ?: 0u
                    cpu = cpu.copy(csrs = cpu.csrs + (0x344 to if (plic.irq) pending or 0x800u else pending and 0x800u.inv()))
                }
                for (event in context.events) when (event) {
                    is SocSetRunningEvent -> {
                        cpu = cpu.copy(stopped = !event.running)
                        debugger = debugger.setRunning(event.running)
                    }
                    is SocSetBreakpointEvent -> debugger = debugger.setBreakpoint(event)
                    is SocSetRegisterEvent -> if (event.index in 0..31) cpu = cpu.withRegister(event.index, event.value)
                    is SocLoadExecutableEvent, is SocLoadAssemblyEvent -> when (val loaded =
                        if (event is SocLoadExecutableEvent) SocElfLoader.read(event.bytes, SocArchitecture.RV32IM)
                        else SocExecutableResult.Loaded((event as SocLoadAssemblyEvent).program.image)) {
                        is SocExecutableResult.Rejected -> { diagnostics += loaded.reason; notices += loaded.notice }
                        is SocExecutableResult.Loaded -> {
                            scope.markDirty()
                            val reply = scope.router.bus(scope.selectedBus(component))?.load(loaded.image)
                            if (reply?.successful == true) {
                                entryPoint = loaded.image.entryPoint
                                debugger = SocCpuDebugState(program = SocProgramListing(loaded.image))
                                cpu = Rv32Cpu.reset(loaded.image.entryPoint, cpu).copy(stopped = true)
                            } else {
                                val error = reply?.error ?: SocBusError.NO_BUS_CONNECTED
                                if (event is SocLoadAssemblyEvent) {
                                    diagnostics += "Assembly memory load: $error"
                                    notices += message("soc_asm_memory_load", "error" to error.notice)
                                } else {
                                    diagnostics += "ELF memory load: $error"
                                    notices += message("soc_elf_memory_load", "error" to error.notice)
                                }
                            }
                        }
                    }
                    SocClearTraceEvent -> cpu = cpu.copy(trace = emptyList())
                }
                if (shouldExecute(old.lastClock)) {
                    val result = Rv32Cpu.step(if (singleStep) cpu.copy(stopped = false) else cpu, scope.memory(component), plic.irq) { pc ->
                        debugger = debugger.beforeFetch(pc, singleStep)
                        debugger.breakpointHit == null
                    }
                    cpu = if (singleStep) result.state.copy(stopped = true) else result.state
                    result.error?.let { diagnostics += it; notices += result.notice ?: LocalizedText.literal(it) }
                }
                state = SocRv32ComponentState(cpu, plic, if (reset) old.lastClock else input("clock"), entryPoint, debugger)
            }
            is SocNios2ComponentState -> {
                var cpu = old.cpu
                var entryPoint = old.entryPoint
                var debugger = if (reset) old.debugger.reset() else old.debugger
                if (reset) cpu = Nios2Cpu.reset(entryPoint ?: attributes[SocComponents.ResetVector], cpu).copy(stopped = true)
                for (event in context.events) when (event) {
                    is SocSetRunningEvent -> {
                        cpu = cpu.copy(stopped = !event.running)
                        debugger = debugger.setRunning(event.running)
                    }
                    is SocSetBreakpointEvent -> debugger = debugger.setBreakpoint(event)
                    is SocSetRegisterEvent -> if (event.index in 0..31) cpu = cpu.withRegister(event.index, event.value)
                    is SocLoadExecutableEvent, is SocLoadAssemblyEvent -> when (val loaded =
                        if (event is SocLoadExecutableEvent) SocElfLoader.read(event.bytes, SocArchitecture.NIOS_II)
                        else SocExecutableResult.Loaded((event as SocLoadAssemblyEvent).program.image)) {
                        is SocExecutableResult.Rejected -> { diagnostics += loaded.reason; notices += loaded.notice }
                        is SocExecutableResult.Loaded -> {
                            scope.markDirty()
                            val reply = scope.router.bus(scope.selectedBus(component))?.load(loaded.image)
                            if (reply?.successful == true) {
                                entryPoint = loaded.image.entryPoint
                                debugger = SocCpuDebugState(program = SocProgramListing(loaded.image))
                                cpu = Nios2Cpu.reset(loaded.image.entryPoint, cpu).copy(stopped = true)
                            } else {
                                val error = reply?.error ?: SocBusError.NO_BUS_CONNECTED
                                if (event is SocLoadAssemblyEvent) {
                                    diagnostics += "Assembly memory load: $error"
                                    notices += message("soc_asm_memory_load", "error" to error.notice)
                                } else {
                                    diagnostics += "ELF memory load: $error"
                                    notices += message("soc_elf_memory_load", "error" to error.notice)
                                }
                            }
                        }
                    }
                    SocClearTraceEvent -> cpu = cpu.copy(trace = emptyList())
                }
                if (shouldExecute(old.lastClock)) {
                    val done = when (input("done")) { LogicValue.LOW -> false; LogicValue.HIGH -> true; else -> null }
                    val result = Nios2Cpu.step(if (singleStep) cpu.copy(stopped = false) else cpu, scope.memory(component), old.cpu.ipending,
                        attributes[SocComponents.ExceptionVector], attributes[SocComponents.BreakVector],
                        Nios2CustomResponse(done, context.inputs["result"]?.toULongOrNull()?.toUInt())) { pc ->
                        debugger = debugger.beforeFetch(pc, singleStep)
                        debugger.breakpointHit == null
                    }
                    cpu = if (singleStep) result.state.copy(stopped = true) else result.state
                    result.error?.let { diagnostics += it; notices += result.notice ?: LocalizedText.literal(it) }
                }
                state = SocNios2ComponentState(cpu.copy(ipending = irqLines()), if (reset) old.lastClock else input("clock"), if (reset) old.customOutputs else cpu.pendingCustom ?: old.customOutputs?.copy(start = false), entryPoint, debugger)
            }
            is SocDeviceComponentState -> {
                val device = requireNotNull(scope.device(component))
                val rising = old.lastClock == LogicValue.LOW && input("clock") == LogicValue.HIGH
                var current = old.copy(device = device, lastClock = input("clock"), lastReset = input("reset"))
                if (reset && (device !is SocJtagUartDevice || old.lastReset == LogicValue.LOW)) device.reset()
                when (device) {
                    is SocPioDevice -> if (!reset) {
                        val inputs = (0 until device.width).fold(0u) { value, bit ->
                            val name = if (device.direction == SocPioDirection.BIDIRECTIONAL) "output$bit" else "input$bit"
                            if (high(name)) value or (1u shl bit) else value
                        }
                        device.setInputs(inputs)
                    }
                    is SocDmaDevice -> if (!reset && rising && device.isBusy) { scope.markDirty(); device.clock(scope.router, scope.selectedBus(component)) }
                    is SocJtagUartDevice -> {
                        val all = setOf("irq", "readEnable", "clearKeyboard", "dataOut", "write", "clearTty")
                        // JtagUartState.setReset ends a reset only on TRUE to FALSE, or after an UNKNOWN reset.
                        // Evolution propagates only on an input change, so an UNKNOWN reset ends at the next change.
                        val endReset = old.lastReset == LogicValue.UNKNOWN && old.lastInputs != context.inputs ||
                            old.lastReset == LogicValue.HIGH && input("reset") == LogicValue.LOW
                        val resetEdge = reset && old.lastReset == LogicValue.LOW || endReset
                        repeated = if (resetEdge) all else emptySet()
                        if (reset) current = current.copy(lastClock = old.lastClock)
                        if (reset && old.lastReset == LogicValue.LOW) current = current.copy(readEnable = false, uartData = 0u, uartWrite = false, clearUart = true, uartIrq = false)
                        if (endReset) current = current.copy(readEnable = false, uartData = 0u, uartWrite = false, clearUart = false, uartIrq = false)
                        if (!reset && rising) {
                            val irq = device.irq
                            val data = device.transmit()
                            val read = high("available") && !old.readEnable
                            if (read) device.receive((context.inputs["dataIn"]?.toULongOrNull()?.toInt() ?: 0) and 127)
                            current = current.copy(readEnable = read, uartData = data?.toUInt()?.and(127u) ?: old.uartData, uartWrite = data != null, uartIrq = irq)
                            repeated = repeated.orEmpty() + setOf("irq", "write", "readEnable") + if (data != null) setOf("dataOut") else emptySet()
                        }
                        current = current.copy(lastInputs = context.inputs)
                    }
                    is SocVgaDevice -> if (context.events.any { it === SocRefreshVgaEvent }) scope.router.bus(scope.selectedBus(component))?.let { scope.markDirty(); device.refreshFrom(it) }
                }
                state = current
            }
            is SocBusComponentState -> if (reset) scope.router.bus(scope.selectedBus(component))?.let { if (it.trace.isNotEmpty()) { scope.markDirty(); it.clearTrace() } }
        }
        for (event in context.events) {
            if (event is SocTransactionEvent) {
                val reply = scope.transact(component, event.request.copy(initiator = component.id))
                reply.error?.let { diagnostics += "Bus transaction: $it"; notices += message("soc_bus_transaction_error", "error" to it.notice) }
            }
            if (event === SocClearTraceEvent) scope.router.bus(scope.selectedBus(component))?.let { if (it.trace.isNotEmpty()) { scope.markDirty(); it.clearTrace() } }
        }
        val updates = scope.updatedStates().toMutableMap()
        if (state is SocBusComponentState) state = updates[component.id] ?: state
        // Refresh a device reference after CPU/MMIO transactions in the same evaluation.
        if (state is SocDeviceComponentState) state = state.copy(device = requireNotNull(scope.device(component)))
        updates.remove(component.id)
        val delay = when (component.kind) { SocComponents.PIO -> 10L; SocComponents.UART, SocComponents.NIOS2 -> 5L; else -> 1L }
        val outputDelays = if (currentState is SocNios2ComponentState && state is SocNios2ComponentState &&
            currentState.customOutputs?.start == true && state.customOutputs?.start != true) mapOf("start" to 0L) else emptyMap()
        return SocComponentEvaluation(ComponentEvaluation(outputs(state), state, delay = delay,
            diagnostics = diagnostics, outputDelays = outputDelays, notices = notices, repeatedOutputs = repeated), updates)
    }

    private val unknownNios2CustomOutputs = listOf("dataA" to 32, "dataB" to 32, "start" to 1, "n" to 8, "a" to 5,
        "readRA" to 1, "b" to 5, "readRB" to 1, "c" to 5, "writeRC" to 1).associate { (name, width) -> name to LogicVector.unknown(width) }

    fun outputs(state: ComponentState): Map<String, LogicVector> {
        fun bit(value: Boolean) = LogicVector.fromBit(if (value) LogicValue.HIGH else LogicValue.LOW)
        return when (state) {
            is SocDeviceComponentState -> when (val device = state.device) {
                is SocPioDevice -> buildMap {
                    if (device.generatesIrq && device.direction != SocPioDirection.OUTPUT) put("irq", bit(device.irq))
                    if (device.direction != SocPioDirection.INPUT) for (index in 0 until device.width) {
                        put("output$index", if (device.drivenBits and (1u shl index) == 0u) LogicVector.unknown(1)
                            else bit(device.outputBits and (1u shl index) != 0u))
                    }
                }
                is SocDmaDevice -> mapOf("irq" to bit(device.irq))
                is SocJtagUartDevice -> mapOf("irq" to bit(state.uartIrq), "readEnable" to bit(state.readEnable),
                    "clearKeyboard" to bit(state.clearUart), "dataOut" to LogicVector.of(7, state.uartData.toULong()),
                    "write" to bit(state.uartWrite), "clearTty" to bit(state.clearUart))
                else -> emptyMap()
            }
            is SocNios2ComponentState -> {
                // Evolution sets these ports only while executing a custom instruction.
                val custom = state.customOutputs ?: return unknownNios2CustomOutputs
                mapOf("dataA" to LogicVector.of(32, custom?.dataA?.toULong() ?: 0uL), "dataB" to LogicVector.of(32, custom?.dataB?.toULong() ?: 0uL),
                    "start" to bit(custom?.start == true), "n" to LogicVector.of(8, custom?.n?.toULong() ?: 0uL),
                    "a" to LogicVector.of(5, custom?.a?.toULong() ?: 0uL), "readRA" to bit(custom?.readRA == true),
                    "b" to LogicVector.of(5, custom?.b?.toULong() ?: 0uL), "readRB" to bit(custom?.readRB == true),
                    "c" to LogicVector.of(5, custom?.c?.toULong() ?: 0uL), "writeRC" to bit(custom?.writeRC == true))
            }
            else -> emptyMap()
        }
    }
}
