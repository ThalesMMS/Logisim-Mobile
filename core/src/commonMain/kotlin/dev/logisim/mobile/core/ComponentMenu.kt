package dev.logisim.mobile.core

/** Items of Evolution's Menu Tool popups (tools.MenuTool and the components' MenuExtender), d58a61e0. */
enum class ComponentMenuItem {
    ROTATE_LEFT, ROTATE_RIGHT, DELETE, SHOW_ATTRIBUTES,
    VIEW_SUBCIRCUIT,
    MEMORY_EDIT, MEMORY_CLEAR, MEMORY_LOAD, MEMORY_SAVE,
    PLA_EDIT,
    PLA_ROM_EDIT, PLA_ROM_CLEAR,
    SOC_ASSEMBLER, SOC_READ_ELF, SOC_SHOW_PROGRAM,
    SOC_MEMORY_MAP, SOC_INSERT_TRANSACTION, SOC_EXPORT_C,
    IMAGE_RESET_SIZE, IMAGE_OPTIMIZE, IMAGE_MAKE_TRANSPARENT,
    SPLITTER_DISTRIBUTE_ASCENDING, SPLITTER_DISTRIBUTE_DESCENDING,
    DELETE_SELECTION, CUT_SELECTION, COPY_SELECTION,
}

data class ComponentMenuEntry(val item: ComponentMenuItem, val enabled: Boolean = true, val separatorBefore: Boolean = false)

object ComponentMenu {
    /**
     * MenuTool.MenuComponent plus the component's MenuExtender: Rotate Left/Right when the component has a
     * facing attribute, Delete (only in an editable circuit), Show Attributes, then the component's own items.
     * [state] is the component's simulation state: SocUpMenuProvider offers "Show loaded program" only once the
     * CPU has a program.
     */
    fun component(component: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry, canChange: Boolean = true,
                  state: ComponentState? = null): List<ComponentMenuEntry> = buildList {
        if (hasFacing(component, registry)) {
            add(ComponentMenuEntry(ComponentMenuItem.ROTATE_LEFT))
            add(ComponentMenuEntry(ComponentMenuItem.ROTATE_RIGHT))
        }
        add(ComponentMenuEntry(ComponentMenuItem.DELETE, enabled = canChange))
        add(ComponentMenuEntry(ComponentMenuItem.SHOW_ATTRIBUTES))
        when (component.kind) {
            // SubcircuitFactory.CircuitFeature: "View <circuit>", no separator.
            ComponentKind.SUBCIRCUIT -> add(ComponentMenuEntry(ComponentMenuItem.VIEW_SUBCIRCUIT))
            // MemMenu: enabled whenever there is a circuit state.
            ComponentKind.RAM, ComponentKind.ROM, MemoryComponents.DualRam -> {
                add(ComponentMenuEntry(ComponentMenuItem.MEMORY_EDIT, separatorBefore = true))
                add(ComponentMenuEntry(ComponentMenuItem.MEMORY_CLEAR))
                add(ComponentMenuEntry(ComponentMenuItem.MEMORY_LOAD))
                add(ComponentMenuEntry(ComponentMenuItem.MEMORY_SAVE))
            }
            GatesLibrary.Pla -> add(ComponentMenuEntry(ComponentMenuItem.PLA_EDIT, separatorBefore = true))
            // PlaRom.PlaMenu: enabled whenever there is a circuit state.
            ExtraIoKinds.PlaRom -> {
                add(ComponentMenuEntry(ComponentMenuItem.PLA_ROM_EDIT, separatorBefore = true))
                add(ComponentMenuEntry(ComponentMenuItem.PLA_ROM_CLEAR))
            }
            // SocUpMenuProvider on the canvas (no hierarchy state holder): assembler, ELF, then the loaded program.
            SocComponents.RV32, SocComponents.NIOS2 -> {
                add(ComponentMenuEntry(ComponentMenuItem.SOC_ASSEMBLER, separatorBefore = true))
                add(ComponentMenuEntry(ComponentMenuItem.SOC_READ_ELF))
                if (programLoaded(state)) add(ComponentMenuEntry(ComponentMenuItem.SOC_SHOW_PROGRAM))
            }
            // SocBusMenuProvider on the canvas: the trace window item needs a hierarchy state holder.
            SocComponents.BUS -> {
                add(ComponentMenuEntry(ComponentMenuItem.SOC_MEMORY_MAP, separatorBefore = true))
                add(ComponentMenuEntry(ComponentMenuItem.SOC_INSERT_TRANSACTION))
            }
            // PioMenu and VgaMenu.
            SocComponents.PIO, SocComponents.VGA -> add(ComponentMenuEntry(ComponentMenuItem.SOC_EXPORT_C, separatorBefore = true))
            // Image.getInstanceFeature: three items, no separator.
            AnnotationLibrary.Image -> {
                add(ComponentMenuEntry(ComponentMenuItem.IMAGE_RESET_SIZE))
                add(ComponentMenuEntry(ComponentMenuItem.IMAGE_OPTIMIZE))
                add(ComponentMenuEntry(ComponentMenuItem.IMAGE_MAKE_TRANSPARENT))
            }
            // SplitterDistributeItem: enabled only when the distribution would change.
            ComponentKind.SPLITTER -> {
                add(ComponentMenuEntry(ComponentMenuItem.SPLITTER_DISTRIBUTE_ASCENDING,
                    enabled = distributionChanges(component, ascending = true), separatorBefore = true))
                add(ComponentMenuEntry(ComponentMenuItem.SPLITTER_DISTRIBUTE_DESCENDING,
                    enabled = distributionChanges(component, ascending = false)))
            }
            else -> Unit
        }
    }

    /** SocUpStateInterface.programLoaded: the CPU holds a program read from an ELF file or the assembler. */
    fun programLoaded(state: ComponentState?): Boolean = when (state) {
        is SocRv32ComponentState -> state.debugger.program != null
        is SocNios2ComponentState -> state.debugger.program != null
        else -> false
    }

    /** MenuTool.MenuSelection, shown when the pressed component belongs to a selection of more than one. */
    fun selection(canChange: Boolean = true): List<ComponentMenuEntry> = listOf(
        ComponentMenuEntry(ComponentMenuItem.DELETE_SELECTION, enabled = canChange),
        ComponentMenuEntry(ComponentMenuItem.CUT_SELECTION, enabled = canChange),
        ComponentMenuEntry(ComponentMenuItem.COPY_SELECTION),
    )

    fun hasFacing(component: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry): Boolean =
        component.kind == ComponentKind.SUBCIRCUIT ||
            registry.definition(component.kind)?.schema?.keys?.any { it.name == "facing" } == true

    /** Direction.getLeft()/getRight() of Evolution: a quarter turn counter-clockwise or clockwise. */
    fun rotated(facing: Direction, left: Boolean): Direction = when (facing) {
        Direction.EAST -> if (left) Direction.NORTH else Direction.SOUTH
        Direction.NORTH -> if (left) Direction.WEST else Direction.EAST
        Direction.WEST -> if (left) Direction.SOUTH else Direction.NORTH
        Direction.SOUTH -> if (left) Direction.EAST else Direction.WEST
    }

    /** SplitterAttributes.computeDistribution as branch indices (bitN values), for [bits] bits over [fanout] ends. */
    fun splitterDistribution(fanout: Int, bits: Int, ascending: Boolean): List<Int> {
        if (fanout >= bits) return List(bits) { if (ascending) it else fanout - 1 - it }
        val result = IntArray(bits)
        val perEnd = bits / fanout
        var withExtra = bits % fanout
        var end = -1
        var left = 0
        val order = if (ascending) 0 until bits else (bits - 1) downTo 0
        for (index in order) {
            if (left == 0) {
                end++
                left = perEnd
                if (withExtra > 0) { left++; withExtra-- }
            }
            result[index] = end
            left--
        }
        return result.toList()
    }

    fun distributionChanges(splitter: CircuitComponent, ascending: Boolean): Boolean =
        splitter.splitterBitEnds() != splitterDistribution(fanout(splitter), splitter.width, ascending)

    /** The bitN attributes Splitter.redistribute sets; empty when the distribution is already in place. */
    fun redistribution(splitter: CircuitComponent, ascending: Boolean): Map<String, String> {
        val current = splitter.splitterBitEnds()
        return splitterDistribution(fanout(splitter), splitter.width, ascending).withIndex()
            .filter { (bit, end) -> current[bit] != end }
            .associate { (bit, end) -> "bit$bit" to end.toString() }
    }

    /** The popup in the oracle's form: Evolution's English item texts, "|" for a separator, " (disabled)" suffix. */
    fun describe(entries: List<ComponentMenuEntry>, childName: String? = null): String = entries.flatMap { entry ->
        val text = when (entry.item) {
            ComponentMenuItem.ROTATE_LEFT -> "Rotate Left"
            ComponentMenuItem.ROTATE_RIGHT -> "Rotate Right"
            ComponentMenuItem.DELETE -> "Delete"
            ComponentMenuItem.SHOW_ATTRIBUTES -> "Show Attributes"
            ComponentMenuItem.VIEW_SUBCIRCUIT -> "View ${childName.orEmpty()}"
            ComponentMenuItem.MEMORY_EDIT -> "Edit Contents…"
            ComponentMenuItem.MEMORY_CLEAR -> "Clear Contents"
            ComponentMenuItem.MEMORY_LOAD -> "Load Image…"
            ComponentMenuItem.MEMORY_SAVE -> "Save Image…"
            ComponentMenuItem.PLA_EDIT -> "Edit PLA Program…"
            ComponentMenuItem.PLA_ROM_EDIT -> "Edit Contents…"
            ComponentMenuItem.PLA_ROM_CLEAR -> "Clear Contents"
            ComponentMenuItem.SOC_ASSEMBLER -> "Open assembler"
            ComponentMenuItem.SOC_READ_ELF -> "Read ELF file"
            ComponentMenuItem.SOC_SHOW_PROGRAM -> "Show loaded program"
            ComponentMenuItem.SOC_MEMORY_MAP -> "Show memory map"
            ComponentMenuItem.SOC_INSERT_TRANSACTION -> "Insert a bus transaction"
            ComponentMenuItem.SOC_EXPORT_C -> "Export C-templates"
            ComponentMenuItem.IMAGE_RESET_SIZE -> "Reset to Original Size"
            ComponentMenuItem.IMAGE_OPTIMIZE -> "Optimize Image Size"
            ComponentMenuItem.IMAGE_MAKE_TRANSPARENT -> "Make Outer Background Transparent"
            ComponentMenuItem.SPLITTER_DISTRIBUTE_ASCENDING -> "Distribute Ascending"
            ComponentMenuItem.SPLITTER_DISTRIBUTE_DESCENDING -> "Distribute Descending"
            ComponentMenuItem.DELETE_SELECTION -> "Delete Selection"
            ComponentMenuItem.CUT_SELECTION -> "Cut Selection"
            ComponentMenuItem.COPY_SELECTION -> "Copy Selection"
        } + if (entry.enabled) "" else " (disabled)"
        if (entry.separatorBefore) listOf("|", text) else listOf(text)
    }.joinToString(", ")

    private fun fanout(splitter: CircuitComponent) = splitter.attributeInt("fanout", 2).coerceIn(1, 64)
}
