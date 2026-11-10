package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemorySimulationTest {
    private val registry = ComponentCatalog.registry.withDefinitions(MemoryComponents.definitions())
    private val simulator = CircuitSimulator(registry = registry)

    private fun fixture(kind: ComponentKind = ComponentKind.RAM, attributes: Map<String, String> = emptyMap()): CircuitDocument {
        val memory = registry.create(kind, "memory", GridPoint(100, 100), mapOf("addrWidth" to "4") + attributes)
        val drives = registry.layout(memory).inputs.map { terminal ->
            CircuitComponent(terminal.name, ComponentKind.INPUT, terminal.position,
                attributes = mapOf("width" to terminal.width.toString()))
        }
        return CircuitDocument(components = drives + memory)
    }

    private fun drive(vararg values: Pair<String, ULong>, dataBits: Int = 8, addressBits: Int = 4): Map<String, LogicVector> = values.associate { (name, value) ->
        name to LogicVector.of(when {
            name.startsWith("address") -> addressBits
            name.startsWith("data") -> dataBits
            else -> 1
        }, value)
    }

    private fun image(state: SimulationState) = MemoryComponents.image(state.componentStates.getValue("memory"))!!
    private fun output(state: SimulationState, port: Int = 0) =
        state.componentOutputs.getValue("memory").getValue(MemoryLayouts.outputName(port, 0))

    @Test
    fun traceReadsTheSelectedRamCellWhenTheOutputAddressesAnotherCell() {
        val document = fixture(attributes = mapOf("databus" to "bidir", "asyncread" to "true"))
        val project = CircuitProject(listOf(document), document.name)
        val memory = TraceSignals.fromProject(project, registry).single { it.reference.componentId == "memory" }
        val cell = requireNotNull(TraceSignals.ramCell(memory, 2))
        val anotherCell = requireNotNull(TraceSignals.ramCell(memory, 3))
        assertEquals(2L, cell.reference.ramAddress)
        assertNotEquals(memory.reference, cell.reference)
        assertNotEquals(cell.reference, anotherCell.reference)
        assertNull(TraceSignals.ramCell(memory, -1))
        assertNull(TraceSignals.ramCell(memory, 16))

        val recorder = TraceRecorder(listOf(cell, anotherCell))
        var state = simulator.settle(project,
            events = mapOf("memory" to listOf(MemoryCellsChanged(mapOf(2L to 0xa5uL, 3L to 0x3cuL)))),
            vectorInputs = drive("address0" to 2uL, "writeEnable0" to 0uL, "outputEnable0" to 1uL))
        assertEquals(0xa5uL, output(state).toULongOrNull())
        recorder.capture(state, 1)
        state = simulator.settle(project, state, vectorInputs = drive("address0" to 3uL))
        assertEquals(0x3cuL, output(state).toULongOrNull())
        recorder.capture(state, 2)
        state = simulator.settle(project, state,
            events = mapOf("memory" to listOf(MemoryCellsChanged(mapOf(2L to 0x7euL)))))
        assertEquals(0x3cuL, output(state).toULongOrNull())
        recorder.capture(state, 3)

        assertEquals(listOf(listOf(0xa5uL, 0x3cuL), listOf(0xa5uL, 0x3cuL), listOf(0x7euL, 0x3cuL)),
            recorder.frames.map { frame -> frame.values.map { it?.toULongOrNull() } })
        assertTrue(recorder.exportCsv().contains("memory//0x2:"))
        assertTrue(recorder.exportCsv().contains("memory//0x3:"))
    }

    @Test
    fun traceReadsTheSameRamAddressIndependentlyInTwoCircuitInstances() {
        val memory = registry.create(ComponentKind.RAM, "memory", GridPoint(100, 100))
        val child = CircuitDocument("child", listOf(memory))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "child"),
            CircuitComponent("second", ComponentKind.SUBCIRCUIT, GridPoint(100, 0), circuitName = "child"),
        ))
        val project = CircuitProject(listOf(main, child), main.name)
        val cells = TraceSignals.fromProject(project, registry).filter { it.reference.componentId == "memory" }
            .map { requireNotNull(TraceSignals.ramCell(it, 2)) }
        val state = simulator.settle(project, events = mapOf(
            "first/memory" to listOf(MemoryCellsChanged(mapOf(2L to 0xa5uL))),
            "second/memory" to listOf(MemoryCellsChanged(mapOf(2L to 0x3cuL))),
        ))
        val recorder = TraceRecorder(cells)
        recorder.capture(state)
        assertEquals(listOf("first/memory//0x2", "second/memory//0x2"), cells.map { it.reference.key })
        assertEquals(listOf(0xa5uL, 0x3cuL), recorder.frames.single().values.map { it?.toULongOrNull() })
    }

    @Test
    fun romImportsThroughTheRegistryAndReloadsDocumentContent() {
        val image = MemoryImage(4, 8, mapOf(2L to 0xabuL))
        val document = fixture(ComponentKind.ROM, mapOf("contents" to MemoryImageCodec.encodeCirc(image)))
        val encoded = CircCodec.writeProject(CircuitProject(listOf(document), document.name), registry)
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(encoded, registry)).project
        val memoryId = imported.mainCircuit.components.single { it.kind == ComponentKind.ROM }.id
        val addressId = imported.mainCircuit.components.single { it.kind == ComponentKind.INPUT }.id
        val first = simulator.settle(imported, vectorInputs = mapOf(addressId to LogicVector.of(4, 2uL)))
        assertEquals(0xabuL, first.componentOutputs.getValue(memoryId).getValue("dataOut0_0").toULongOrNull())
        assertEquals(11L, first.propagationTime)
        val edited = imported.mainCircuit.copy(components = imported.mainCircuit.components.map {
            if (it.id == memoryId) it.copy(attributes = it.attributes + ("contents" to MemoryImageCodec.encodeCirc(image.withCells(mapOf(2L to 7uL))))) else it
        })
        val changed = simulator.settle(edited, first)
        assertEquals(7uL, changed.componentOutputs.getValue(memoryId).getValue("dataOut0_0").toULongOrNull())
        assertTrue(changed.diagnostics.isEmpty())
    }

    @Test
    fun synchronousRamWritesOnlyOnTheClockEdgeAndHonorsByteEnables() {
        val document = fixture(attributes = mapOf("dataWidth" to "20", "byteenables" to "byteEnables"))
        var state = simulator.settle(document, vectorInputs = drive("address0" to 2uL, "dataIn0_0" to 0xabcdeuL,
            "writeEnable0" to 1uL, "outputEnable0" to 1uL, "clock0" to 0uL,
            "byteEnable0_0" to 1uL, "byteEnable0_1" to 1uL, "byteEnable0_2" to 1uL, dataBits = 20))
        assertEquals(0uL, image(state)[2])
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 1uL))
        assertEquals(0xabcdeuL, image(state)[2])
        assertEquals(0xabcdeuL, output(state).toULongOrNull())
        state = simulator.settle(document, state, vectorInputs = drive("dataIn0_0" to 0x12345uL, "byteEnable0_1" to 0uL, dataBits = 20))
        assertEquals(0xabcdeuL, image(state)[2])
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 0uL))
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 1uL))
        assertEquals(0x1bc45uL, image(state)[2])
        assertEquals(0x1bc45uL, output(state).toULongOrNull())
        assertFalse(state.oscillating)
    }

    @Test
    fun sharedDataBusReleasesItsDriverBeforeWritingAndCanReadAfterwards() {
        val document = fixture(attributes = mapOf("databus" to "bidir", "asyncread" to "true"))
        var state = simulator.settle(document, vectorInputs = drive("address0" to 3uL, "dataOut0_0" to 0x5auL,
            "writeEnable0" to 1uL, "outputEnable0" to 0uL, "clock0" to 0uL))
        assertTrue(output(state).isUnknown)
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 1uL))
        assertEquals(0x5auL, image(state)[3])
        state = simulator.settle(document, state, vectorInputs = drive("writeEnable0" to 0uL, "outputEnable0" to 1uL) +
            ("dataOut0_0" to LogicVector.unknown(8)))
        assertEquals(0x5auL, output(state).toULongOrNull())
        val position = registry.layout(document.components.last()).outputs.single().position
        assertEquals(0x5auL, state.portVectors.getValue(position).toULongOrNull())
        assertFalse(state.oscillating)
    }

    @Test
    fun dualRamCollisionKeepsBothReadResultsAndReportsTheCollision() {
        val document = fixture(MemoryComponents.DualRam)
        var state = simulator.settle(document, vectorInputs = drive("address0" to 1uL, "address1" to 1uL,
            "dataIn0_0" to 0x11uL, "dataIn1_0" to 0x22uL,
            "writeEnable0" to 1uL, "writeEnable1" to 1uL, "outputEnable0" to 1uL, "outputEnable1" to 1uL,
            "clock0" to 0uL, "clock1" to 0uL))
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 1uL, "clock1" to 1uL))
        assertEquals(0x22uL, image(state)[1])
        assertEquals(0x11uL, output(state, 0).toULongOrNull())
        assertEquals(0x22uL, output(state, 1).toULongOrNull())
        assertTrue(state.diagnostics.any { it.contains("Colisão") })
    }

    @Test
    fun imageEditsStayInSessionAndResetRetainsOnlyNonvolatileMemory() {
        for (nonvolatile in listOf(false, true)) {
            val document = fixture(attributes = mapOf("type" to if (nonvolatile) "nonvolatile" else "volatile", "asyncread" to "true"))
            val initial = simulator.settle(document)
            val image = MemoryImage(4, 8, mapOf(3L to 0x77uL))
            val edited = simulator.settle(document, initial, events = mapOf("memory" to listOf(MemoryImageReplaced(image))),
                vectorInputs = drive("address0" to 3uL, "outputEnable0" to 1uL))
            assertEquals(0x77uL, image(edited)[3])
            assertEquals(0x77uL, output(edited).toULongOrNull())
            assertFalse(document.components.last().attributes.containsKey("contents"))
            val reset = simulator.settle(document, edited, reset = true)
            assertEquals(if (nonvolatile) 0x77uL else 0uL, image(reset)[3])
        }
    }

    @Test
    fun ramImageEditsPreserveOverflowUntilExplicitClear() {
        val document = fixture(attributes = mapOf("enables" to "line", "line" to "dual", "misaligned" to "true"))
        fun secondOutput(state: SimulationState) = state.componentOutputs.getValue("memory")
            .getValue(MemoryLayouts.outputName(0, 1)).toULongOrNull()

        var state = simulator.settle(document, vectorInputs = drive("address0" to 15uL, "clock0" to 0uL,
            "writeEnable0" to 1uL, "dataIn0_1" to 0x3cuL, "lineEnable0_0" to 0uL, "lineEnable0_1" to 1uL))
        state = simulator.settle(document, state, vectorInputs = drive("clock0" to 1uL))
        assertEquals(0x3cuL, secondOutput(state))
        assertEquals(MemoryImage(4, 8), image(state))
        assertTrue(MemoryComponents.hasLineOverflow(state.componentStates["memory"]))

        state = simulator.settle(document, state,
            events = mapOf("memory" to listOf(MemoryImageReplaced(MemoryImage(4, 8, mapOf(15L to 0xa5uL))))),
            vectorInputs = drive("writeEnable0" to 0uL))
        assertEquals(0xa5uL, output(state).toULongOrNull())
        assertEquals(0x3cuL, secondOutput(state))

        val loaded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText("v3.0 hex words plain\n00\n", 4, 8))
        state = simulator.settle(document, state, events = mapOf("memory" to listOf(MemoryImageReplaced(loaded.image, importedImage = loaded))))
        assertEquals(0uL, output(state).toULongOrNull())
        assertEquals(0x3cuL, secondOutput(state))

        state = simulator.settle(document, state,
            events = mapOf("memory" to listOf(MemoryImageReplaced(MemoryImage(4, 8), clearLineOverflow = true))))
        assertEquals(0uL, output(state).toULongOrNull())
        assertEquals(0uL, secondOutput(state))
        assertFalse(MemoryComponents.hasLineOverflow(state.componentStates["memory"]))
    }

    @Test
    fun changingLineModeKeepsTheOverflowWordAndGrowingTheBankMakesItVisible() {
        val attributes = mapOf("enables" to "line", "line" to "dual", "misaligned" to "true")
        val dual = fixture(attributes = attributes)
        val byte = fixture(attributes = attributes + ("enables" to "byte"))
        val quad = fixture(attributes = attributes + ("line" to "quad"))
        val wider = fixture(attributes = attributes + ("addrWidth" to "5"))
        val lineOne = MemoryLayouts.outputName(0, 1)

        var state = simulator.settle(dual, vectorInputs = drive("address0" to 15uL, "clock0" to 0uL,
            "writeEnable0" to 1uL, "dataIn0_1" to 0x3cuL, "lineEnable0_0" to 0uL, "lineEnable0_1" to 1uL))
        state = simulator.settle(dual, state, vectorInputs = drive("clock0" to 1uL))
        assertEquals(0x3cuL, state.componentOutputs.getValue("memory").getValue(lineOne).toULongOrNull())
        assertEquals(MemoryImage(4, 8), image(state))

        state = simulator.settle(byte, state, vectorInputs = drive("writeEnable0" to 0uL, "clock0" to 0uL))
        state = simulator.settle(quad, state, vectorInputs = drive("address0" to 15uL, "writeEnable0" to 0uL))
        assertEquals(0x3cuL, state.componentOutputs.getValue("memory").getValue(lineOne).toULongOrNull())
        assertEquals(MemoryImage(4, 8), image(state))

        state = simulator.settle(wider, state, vectorInputs = drive("address0" to 16uL, "writeEnable0" to 0uL,
            addressBits = 5))
        assertEquals(MemoryImage(5, 8, mapOf(16L to 0x3cuL)), image(state))
        assertEquals(0x3cuL, output(state).toULongOrNull())
    }

    @Test
    fun aResizedAllocatedPageRejectsOverflowUntilClearedWhileNonempty() {
        val attributes = mapOf("enables" to "line", "line" to "dual", "misaligned" to "true")
        val original = fixture(attributes = attributes)
        val narrower = fixture(attributes = attributes + ("addrWidth" to "2"))
        fun events(event: ComponentEvent) = mapOf("memory" to listOf(event))
        fun writeOverflow(previous: SimulationState): SimulationState {
            val low = simulator.settle(narrower, previous, vectorInputs = drive("address0" to 3uL, "clock0" to 0uL,
                "writeEnable0" to 1uL, "dataIn0_1" to 0x3cuL, "lineEnable0_0" to 0uL, "lineEnable0_1" to 1uL,
                addressBits = 2))
            return simulator.settle(narrower, low, vectorInputs = drive("clock0" to 1uL))
        }
        fun overflow(state: SimulationState) = state.componentOutputs.getValue("memory")
            .getValue(MemoryLayouts.outputName(0, 1)).toULongOrNull()

        val initial = simulator.settle(original, events = events(MemoryCellsChanged(mapOf(1L to 0xa5uL))))
        var state = writeOverflow(initial)
        assertEquals(0uL, overflow(state))
        assertEquals(MemoryImage(2, 8, mapOf(1L to 0xa5uL)), image(state))

        state = simulator.settle(narrower, state, vectorInputs = drive("writeEnable0" to 0uL),
            events = events(MemoryCellsChanged(mapOf(1L to 0uL))))
        val clear = events(MemoryImageReplaced(MemoryImage(2, 8), clearLineOverflow = true))
        state = simulator.settle(narrower, state, events = clear)
        state = writeOverflow(state)
        assertEquals(0uL, overflow(state))

        state = simulator.settle(narrower, state, vectorInputs = drive("writeEnable0" to 0uL),
            events = events(MemoryCellsChanged(mapOf(1L to 0xa5uL))))
        state = simulator.settle(narrower, state, events = clear)
        state = writeOverflow(state)
        assertEquals(0x3cuL, overflow(state))
        assertEquals(MemoryImage(2, 8), image(state))
    }

    @Test
    fun loadingZeroHexWordsReleasesAnAllocatedShortPageButRawZerosKeepIt() {
        val attributes = mapOf("enables" to "line", "line" to "dual", "misaligned" to "true")
        val original = fixture(attributes = attributes)
        val narrower = fixture(attributes = attributes + ("addrWidth" to "2"))
        val initial = simulator.settle(original, events = mapOf("memory" to listOf(MemoryCellsChanged(mapOf(1L to 0xa5uL)))))

        val formats = listOf("v3.0 hex words plain\n00 00 00 00\n" to 0x3cuL, "v2.0 raw\n0\n" to 0uL)
        for ((text, expected) in formats) {
            val loaded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText(text, 2, 8))
            val state = simulator.settle(narrower, initial,
                events = mapOf("memory" to listOf(MemoryImageReplaced(loaded.image, importedImage = loaded))),
                vectorInputs = drive("address0" to 3uL, "clock0" to 0uL, "writeEnable0" to 1uL,
                    "dataIn0_1" to 0x3cuL, "lineEnable0_0" to 0uL, "lineEnable0_1" to 1uL, addressBits = 2))
            val written = simulator.settle(narrower, state, vectorInputs = drive("clock0" to 1uL))
            assertEquals(MemoryImage(2, 8), image(written))
            assertEquals(expected, written.componentOutputs.getValue("memory")
                .getValue(MemoryLayouts.outputName(0, 1)).toULongOrNull(), loaded.format.name)
        }
    }

    @Test
    fun wideningEvolutionRamCopiesTheSignedBackingWordAcrossStorageWidths() {
        val cases = listOf(
            Triple(8, 16, 0xa5uL to 0xffa5uL),
            Triple(16, 32, 0x8000uL to 0xffff8000uL),
            Triple(32, 64, 0x80000000uL to 0xffffffff80000000uL),
            Triple(7, 16, 0x45uL to 0x45uL),
        )
        for ((oldWidth, newWidth, words) in cases) {
            val original = fixture(attributes = mapOf("enables" to "line", "dataWidth" to oldWidth.toString()))
            val wider = fixture(attributes = mapOf("enables" to "line", "dataWidth" to newWidth.toString()))
            val initial = simulator.settle(original, events = mapOf("memory" to listOf(MemoryCellsChanged(mapOf(1L to words.first)))))
            val state = simulator.settle(wider, initial, vectorInputs = drive("address0" to 1uL, "writeEnable0" to 0uL))
            assertEquals(words.second, image(state)[1], "$oldWidth → $newWidth")
            assertEquals(words.second, output(state).toULongOrNull(), "$oldWidth → $newWidth")
        }
    }

    @Test
    fun wideningAfterNarrowingRestoresOnlyTheRawBitsEvolutionKeeps() {
        val wide = fixture(attributes = mapOf("enables" to "line"))
        val narrow = fixture(attributes = mapOf("enables" to "line", "dataWidth" to "4"))
        fun events(event: ComponentEvent) = mapOf("memory" to listOf(event))
        val initialized = simulator.settle(wide, events = events(MemoryCellsChanged(mapOf(1L to 0xa5uL, 2L to 0xb5uL, 3L to 0xc0uL))))
        val narrowed = simulator.settle(narrow, initialized)
        assertEquals(MemoryImage(4, 4, mapOf(1L to 5uL, 2L to 5uL)), image(narrowed))

        val edited = simulator.settle(narrow, narrowed, events = events(MemoryCellsChanged(mapOf(1L to 5uL, 2L to 6uL))))
        val restored = simulator.settle(wide, edited)
        assertEquals(MemoryImage(4, 8, mapOf(1L to 0xa5uL, 2L to 6uL, 3L to 0xc0uL)), image(restored))

        val imported = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText("v3.0 hex words plain\n0 5 5 0\n", 4, 4))
        val loaded = simulator.settle(narrow, narrowed, events = events(MemoryImageReplaced(imported.image, importedImage = imported)))
        assertEquals(MemoryImage(4, 8, mapOf(1L to 5uL, 2L to 5uL)), image(simulator.settle(wide, loaded)))

        val cleared = simulator.settle(narrow, narrowed,
            events = events(MemoryImageReplaced(MemoryImage(4, 4), clearLineOverflow = true)))
        assertEquals(MemoryImage(4, 8), image(simulator.settle(wide, cleared)))

        val onlyHighBits = simulator.settle(wide, events = events(MemoryCellsChanged(mapOf(3L to 0xc0uL))))
        val maskedZero = simulator.settle(narrow, onlyHighBits)
        val clearedZero = simulator.settle(narrow, maskedZero,
            events = events(MemoryImageReplaced(MemoryImage(4, 4), clearLineOverflow = true)))
        assertEquals(MemoryImage(4, 8, mapOf(3L to 0xc0uL)), image(simulator.settle(wide, clearedZero)))
    }

    @Test
    fun rejectedSessionEditLeavesTheWholeImageAndAnActionableDiagnostic() {
        val document = fixture(attributes = mapOf("asyncread" to "true"))
        val state = simulator.settle(document, events = mapOf("memory" to listOf(MemoryCellsChanged(mapOf(1L to 9uL, 99L to 2uL)))),
            vectorInputs = drive("outputEnable0" to 1uL))
        assertTrue(image(state).cells.isEmpty())
        assertTrue(state.diagnostics.any { it.contains("endereço", ignoreCase = true) })
    }
}
