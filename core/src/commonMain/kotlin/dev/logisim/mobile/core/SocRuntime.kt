package dev.logisim.mobile.core

/** Runtime state is copied before a transaction, so saved simulation snapshots remain independent. */
data class SocBusComponentState(val bus: SocAddressSpace) : ComponentState

data class SocDeviceComponentState(
    val device: SocBusDevice,
    val lastClock: LogicValue = LogicValue.UNKNOWN,
    val lastReset: LogicValue = LogicValue.UNKNOWN,
    val readEnable: Boolean = false,
    val uartData: UInt = 0u,
    val uartWrite: Boolean = false,
    val clearUart: Boolean = false,
    val uartIrq: Boolean = false,
    /** Inputs of the last evaluation; Evolution only propagates the JTAG UART when one of them changes. */
    val lastInputs: Map<String, LogicVector>? = null,
) : ComponentState

data class SocRv32ComponentState(val cpu: Rv32CpuState, val plic: SocPlicDevice, val lastClock: LogicValue = LogicValue.UNKNOWN, val entryPoint: UInt? = null, val debugger: SocCpuDebugState = SocCpuDebugState()) : ComponentState

data class SocNios2ComponentState(val cpu: Nios2CpuState, val lastClock: LogicValue = LogicValue.UNKNOWN, val customOutputs: Nios2CustomRequest? = null, val entryPoint: UInt? = null, val debugger: SocCpuDebugState = SocCpuDebugState()) : ComponentState

data class SocTransactionEvent(val request: SocBusRequest) : ComponentEvent

data class SocLoadExecutableEvent(val bytes: ByteArray) : ComponentEvent

data class SocLoadAssemblyEvent(val program: SocAssemblyResult.Assembled) : ComponentEvent

object SocStepEvent : ComponentEvent

data class SocSetRunningEvent(val running: Boolean) : ComponentEvent

data class SocSetRegisterEvent(val index: Int, val value: UInt) : ComponentEvent

object SocClearTraceEvent : ComponentEvent

object SocRefreshVgaEvent : ComponentEvent

data class SocMemoryMapDevice(val component: CircuitComponent, val id: String)

data class SocMemoryMapRow(
    val startAddress: UInt,
    val endAddress: UInt,
    val device: SocMemoryMapDevice? = null,
)

/** Rebuilds only the selected circuit instance's bus bindings from its component snapshots. */
class SocRuntimeScope(val components: List<CircuitComponent>, private val states: Map<String, ComponentState>) {
    private val soc = components.filter { it.kind in SocComponents.kinds }
    val duplicateBusIds: Set<String> = soc.filter { it.kind == SocComponents.BUS }.groupBy { busId(it) }.filterValues { it.size > 1 }.keys
    private val devices = mutableMapOf<CircuitComponent, SocBusDevice>()
    private fun currentDevice(component: CircuitComponent): SocBusDevice? =
        (states[component.id] as? SocDeviceComponentState)?.device
            ?: (states[component.id] as? SocRv32ComponentState)?.plic
            ?: SocComponents.createDevice(component)

    private fun copyDevice(component: CircuitComponent): SocBusDevice? =
        devices[component] ?: currentDevice(component)?.copyDevice()?.also { devices[component] = it }

    private fun attachedComponents(busId: String): List<CircuitComponent> =
        soc.filter { it.kind != SocComponents.BUS && selectedBus(it) == busId }

    fun memoryMap(busComponent: CircuitComponent): List<SocMemoryMapRow> {
        val mapped = attachedComponents(busId(busComponent)).mapNotNull { component ->
            val device = currentDevice(component) ?: return@mapNotNull null
            if (device.sizeInBytes == 0uL) return@mapNotNull null
            val lastAddress = UInt.MAX_VALUE.toULong()
            val end = (device.startAddress.toULong() + device.sizeInBytes - 1uL).coerceAtMost(lastAddress)
            SocMemoryMapRow(device.startAddress, end.toUInt(), SocMemoryMapDevice(component, device.id))
        }.sortedBy { it.startAddress }
        return buildList {
            var nextUnmapped = 0uL
            for (row in mapped) {
                if (nextUnmapped < row.startAddress.toULong()) {
                    add(SocMemoryMapRow(nextUnmapped.toUInt(), row.startAddress - 1u))
                }
                add(row)
                nextUnmapped = maxOf(nextUnmapped, row.endAddress.toULong() + 1uL)
            }
            if (nextUnmapped <= UInt.MAX_VALUE.toULong()) {
                add(SocMemoryMapRow(nextUnmapped.toUInt(), UInt.MAX_VALUE))
            }
        }
    }
    private val spaces by lazy {
        soc.filter { it.kind == SocComponents.BUS }.map { component ->
            val id = busId(component)
            val attached = attachedComponents(id).mapNotNull(::copyDevice)
            (states[component.id] as? SocBusComponentState)?.bus?.withDevices(attached) ?: SocAddressSpace(id, attached)
        }
    }
    val router by lazy { SocBusRouter(spaces) }
    private var dirty = false
    fun markDirty() { dirty = true }
    fun transact(component: CircuitComponent, request: SocBusRequest): SocBusReply {
        dirty = true
        return router.transact(selectedBus(component), request)
    }
    fun busId(component: CircuitComponent): String = SocComponents.attributes(component)[SocComponents.BusIdentifier].ifBlank { component.id }
    fun selectedBus(component: CircuitComponent): String = if (component.kind == SocComponents.BUS) busId(component)
        else SocComponents.attributes(component)[SocComponents.BusSelection]
    fun memory(component: CircuitComponent): SocCpuMemory = object : SocCpuMemory {
        private val bus = selectedBus(component)
        override fun read(address: UInt, size: SocAccessSize): SocBusReply {
            dirty = true
            return router.transact(bus, SocBusRequest(address, size = size, initiator = component.id))
        }
        override fun write(address: UInt, value: UInt, size: SocAccessSize): SocBusReply {
            dirty = true
            return router.transact(bus, SocBusRequest(address, value, size, read = false, write = true, initiator = component.id))
        }
    }
    fun device(component: CircuitComponent): SocBusDevice? = copyDevice(component)
    fun updatedStates(): Map<String, ComponentState> {
        if (!dirty) return emptyMap()
        val result = mutableMapOf<String, ComponentState>()
        for ((component, device) in devices) {
            // A CPU also owns its PLIC slave. Its processor state stays under the CPU's own key.
            if (component.kind == SocComponents.RV32) {
                val old = states[component.id] as? SocRv32ComponentState
                if (old != null) result[component.id] = old.copy(plic = device as SocPlicDevice)
                continue
            }
            val old = states[component.id] as? SocDeviceComponentState
            result[component.id] = old?.copy(device = device) ?: SocDeviceComponentState(device)
        }
        for (component in soc.filter { it.kind == SocComponents.BUS }) {
            router.bus(busId(component))?.let { result[component.id] = SocBusComponentState(it) }
        }
        return result
    }
}
