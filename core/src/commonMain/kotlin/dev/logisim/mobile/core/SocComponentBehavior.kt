package dev.logisim.mobile.core

/** Peer updates are synchronous bus transactions within the engine's current circuit instance. */
object SocComponentBehavior : ComponentBehavior {
    override val usesPeerStates = true
    override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = SocEvaluation.initialState(component)
    override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
        when (state) {
            is SocDeviceComponentState -> if (state.device is SocMemoryDevice) state.copy(device = state.device.copyDevice()) else initialState(component, attributes)
            is SocRv32ComponentState -> state.copy(cpu = Rv32Cpu.reset(state.entryPoint ?: attributes[SocComponents.ResetVector], state.cpu).copy(stopped = true),
                plic = state.plic.copyDevice(), lastClock = LogicValue.UNKNOWN, debugger = state.debugger.reset())
            is SocNios2ComponentState -> state.copy(cpu = Nios2Cpu.reset(state.entryPoint ?: attributes[SocComponents.ResetVector], state.cpu).copy(stopped = true),
                lastClock = LogicValue.UNKNOWN, customOutputs = null, debugger = state.debugger.reset())
            else -> initialState(component, attributes)
        }
    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
        val result = SocEvaluation.evaluate(context, context.peers, context.peerStates)
        return result.evaluation.copy(peerStateUpdates = result.peerStates)
    }
}
