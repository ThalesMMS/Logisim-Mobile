package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

data class MemoryComponentState(
    val runtime: MemoryRuntimeState,
    val sourceContents: String = "",
) : ComponentState

data class MemoryCellsChanged(val cells: Map<Long, ULong>) : ComponentEvent
data class MemoryImageReplaced(
    val image: MemoryImage,
    val clearLineOverflow: Boolean = false,
    val importedImage: MemoryImageImportResult.Success? = null,
) : ComponentEvent

object MemoryComponents {
    val DualRam = ComponentKind("DUAL_RAM")
    val AddressBits = AttributeKey.int("addrWidth", 8, 2..24)
    val DataBits = AttributeKey.int("dataWidth", 8, 1..64)
    val Enables = AttributeKey.choice("enables", "byte", setOf("byte", "line"))
    val Bus = AttributeKey.choice("databus", "bibus", MemoryBus.entries.map { it.serialized }.toSet())
    val Trigger = AttributeKey.choice("trigger", "rising", MemoryTrigger.entries.map { it.serialized }.toSet())
    val AsyncRead = AttributeKey.boolean("asyncread", false)
    val ReadBehavior = AttributeKey.choice("readbehav", "raw", setOf("raw", "war"))
    val ByteEnables = AttributeKey.choice("byteenables", "NobyteEnables", setOf("NobyteEnables", "byteEnables"))
    val Line = AttributeKey.choice("line", "single", setOf("single", "dual", "quad", "octo"))
    val Misaligned = AttributeKey.boolean("misaligned", false)
    val ClearPin = AttributeKey.boolean("clearpin", false)
    val Type = AttributeKey.choice("type", "volatile", setOf("volatile", "nonvolatile"))
    val Appearance = AttributeKey.choice("appearance", "logisim_evolution", setOf("classic", "evolution", "logisim_evolution"))
    val Contents = AttributeKey.text("contents") {
        it.isBlank() || MemoryImageCodec.decodeCirc(it) is MemoryImageImportResult.Success
    }

    private val schema = AttributeSchema(listOf(AddressBits, DataBits, Enables, Bus, Trigger, AsyncRead,
        ReadBehavior, ByteEnables, Line, Misaligned, ClearPin, Type, Appearance, Contents, CommonAttributes.Label))

    fun definitions(): List<ComponentDefinition> = listOf(
        definition(ComponentKind.ROM, "ROM"), definition(ComponentKind.RAM, "RAM"), definition(DualRam, "DualRAM"),
    )

    fun configuration(component: CircuitComponent): MemoryConfiguration = MemoryConfiguration.fromAttributes(
        if (component.kind == DualRam) "DualRAM" else component.kind.name, component.attributes)

    fun image(state: ComponentState): MemoryImage? = (state as? MemoryComponentState)?.runtime?.image

    fun hasLineOverflow(state: ComponentState?): Boolean =
        (state as? MemoryComponentState)?.runtime?.lineOverflowWords?.isNotEmpty() == true

    private fun definition(kind: ComponentKind, fileName: String) = ComponentDefinition(
        kind = kind, fileName = fileName, library = "#Memory", schema = schema,
        defaultAttributes = mapOf("addrWidth" to "8", "dataWidth" to "8", "appearance" to "logisim_evolution"),
        layout = { component, _ ->
            val layout = MemoryLayouts.layout(component.position, configuration(component))
            ComponentLayout(layout.terminals.map {
                ComponentTerminal(it.name, it.position, it.direction, it.width, it.label)
            }, layout.bounds)
        },
        behavior = MemoryBehavior,
        supportsRotation = false,
        defaultsForVersion = { version ->
            if (!CircFormatProfile.parse(version).originalLogisim) emptyMap()
            else when (kind) {
                ComponentKind.RAM -> mapOf(ORIGINAL_RAM_ATTRIBUTE to "true", "bus" to OriginalRamInterface.COMBINED.serialized)
                ComponentKind.ROM -> mapOf(ORIGINAL_ROM_ATTRIBUTE to "true")
                else -> emptyMap()
            }
        },
        isCombinational = kind == ComponentKind.ROM,
    )

    private object MemoryBehavior : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState {
            val configuration = configuration(component)
            val source = component.attributes["contents"].orEmpty()
            val image = initialImage(configuration, source)
            return MemoryComponentState(MemoryRuntimeState.initial(configuration, image), source)
        }

        override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState {
            val current = state as? MemoryComponentState ?: return initialState(component, attributes)
            val configuration = configuration(component)
            if (!configuration.readOnly && !configuration.nonvolatile && current.sourceContents.isNotBlank()) {
                return initialState(component, attributes)
            }
            return current.copy(runtime = MemoryCore.reset(configuration, current.runtime))
        }

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val component = context.component
            val configuration = configuration(component)
            val source = component.attributes["contents"].orEmpty()
            var stored = context.state as? MemoryComponentState
                ?: initialState(component, context.attributes) as MemoryComponentState
            if (stored.sourceContents != source) {
                stored = initialState(component, context.attributes) as MemoryComponentState
            }
            var runtime = stored.runtime
            if (runtime.image.addressBits != configuration.addressBits || runtime.image.dataBits != configuration.dataBits ||
                runtime.outputs.size != configuration.portCount || runtime.outputs.any { it.size != configuration.dataLines }) {
                runtime = runtime.reconfigured(configuration)
            }
            val notices = mutableListOf<LocalizedText>()
            for (event in context.events) {
                if (event !is MemoryCellsChanged && event !is MemoryImageReplaced) continue
                if (configuration.readOnly) {
                    notices += message(Messages.MemoryRuntimeRomDocumentEdit)
                    continue
                }
                try {
                    runtime = when (event) {
                        is MemoryCellsChanged -> runtime.replaceVisibleImage(runtime.image.withCells(event.cells))
                        is MemoryImageReplaced -> {
                            val imported = event.importedImage
                            requireLocalized(event.image.addressBits == configuration.addressBits && event.image.dataBits == configuration.dataBits &&
                                (imported == null || imported.image.addressBits == configuration.addressBits && imported.image.dataBits == configuration.dataBits)) {
                                message(Messages.MemoryRuntimeImageWidthMismatch)
                            }
                            val cleared = if (event.clearLineOverflow) runtime.clearContents() else runtime
                            val loaded = if (imported != null) cleared.importVisibleImage(imported) else cleared
                            loaded.replaceVisibleImage(event.image)
                        }
                        else -> runtime
                    }
                } catch (error: IllegalArgumentException) {
                    notices += error.localizedDescription(message(Messages.MemoryRuntimeContentsInvalid))
                }
            }
            fun value(name: String, width: Int): LogicVector = context.inputs[name] ?: LogicVector.unknown(width)
            fun bit(name: String) = value(name, 1).bit(0)
            val ports = List(configuration.portCount) { port ->
                MemoryPortInput(
                    address = value(MemoryLayouts.addressName(port), configuration.addressBits),
                    data = List(configuration.dataLines) { line -> value(
                        if (configuration.bus == MemoryBus.SHARED) MemoryLayouts.outputName(port, line)
                        else MemoryLayouts.inputName(port, line), configuration.dataBits) },
                    outputEnable = bit(MemoryLayouts.outputEnableName(port)),
                    writeEnable = bit(MemoryLayouts.writeEnableName(port)),
                    clock = bit(MemoryLayouts.clockName(port)),
                    lineEnables = List(configuration.lineEnableCount) { bit(MemoryLayouts.lineEnableName(port, it)) },
                    byteEnables = List(configuration.byteEnableCount) { bit(MemoryLayouts.byteEnableName(port, it)) },
                    chipSelect = bit(MemoryLayouts.ORIGINAL_CHIP_SELECT),
                )
            }
            val transition = MemoryCore.evaluate(configuration, runtime, ports, bit("clear"))
            val outputs = buildMap {
                transition.state.outputs.forEachIndexed { port, words -> words.forEachIndexed { line, word ->
                    put(MemoryLayouts.outputName(port, line), word)
                } }
            }
            return ComponentEvaluation(outputs, MemoryComponentState(transition.state, source), MemoryCore.DELAY,
                diagnostics = (notices + transition.notices).map { it.resolve(AppLanguage.PORTUGUESE) },
                notices = notices + transition.notices,
                repeatedOutputs = evolutionSetOutputs(configuration, runtime, ports, bit("clear")))
        }
    }

    /** Mirrors the paths where Evolution's Ram and DualRam propagate call setPort for a port's data outputs. */
    private fun evolutionSetOutputs(
        configuration: MemoryConfiguration,
        previous: MemoryRuntimeState,
        ports: List<MemoryPortInput>,
        clear: LogicValue,
    ): Set<String>? {
        if (configuration.readOnly || configuration.originalInterface != null) return null
        val clearing = configuration.clearPin && clear == LogicValue.HIGH
        val async = configuration.trigger == MemoryTrigger.HIGH || configuration.trigger == MemoryTrigger.LOW
        return ports.indices.filter { port ->
            val input = ports[port]
            clearing || configuration.enables == MemoryEnables.LINE || when {
                input.outputEnable == LogicValue.LOW -> configuration.bus != MemoryBus.SEPARATE
                input.address.isError || !input.address.isFullyDefined -> true
                async || configuration.asynchronousRead -> true
                else -> MemoryCore.triggered(previous.clocks.getOrElse(port) { LogicValue.UNKNOWN }, input.clock, configuration.trigger)
            }
        }.flatMap { port -> List(configuration.dataLines) { line -> MemoryLayouts.outputName(port, line) } }.toSet()
    }

    private fun initialImage(configuration: MemoryConfiguration, source: String): MemoryImage {
        if (source.isBlank()) return MemoryImage(configuration.addressBits, configuration.dataBits)
        return when (val decoded = MemoryImageCodec.decodeCirc(source)) {
            is MemoryImageImportResult.Success -> decoded.image.resized(configuration.addressBits, configuration.dataBits)
            is MemoryImageImportResult.Failure -> throw LocalizedArgumentException(LocalizedText.join(decoded.diagnostics.map { it.notice }))
        }
    }
}
