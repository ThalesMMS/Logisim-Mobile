package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedText
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

/**
 * Drives the Mobile side of the menu comparison in scripts/oracle/menus: each case applies one menu action
 * through EditorState to the shared fixture and writes the document that Mobile would save. The script reopens
 * these files with Evolution and compares them with Evolution's own result for the same action.
 */
class MenuActionsParityExportTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "scripts/oracle/menus/fixtures").isDirectory }
    private val fixtures = File(root, "scripts/oracle/menus/fixtures")
    private val out = File(System.getProperty("menuParityOut") ?: File(root, "composeApp/build/menu-parity/mobile").path)
    private val state = linkedMapOf<String, String>()

    private fun fixture(name: String) = File(fixtures, name).readText()
    private fun open(name: String = "project.circ") = EditorState(fixture(name))
    private fun EditorState.circuitId(name: String) = assertNotNull(project.circuit(name)).id
    private fun EditorState.component(x: Int, y: Int, kind: ComponentKind? = null) =
        document.components.single { it.position == GridPoint(x, y) && (kind == null || it.kind == kind) }

    private fun write(id: String, xml: String) {
        out.mkdirs()
        File(out, "$id.circ").writeText(xml)
    }

    private fun EditorState.editAppearance(circuit: String, edit: (SymbolAppearance) -> SymbolAppearance) {
        val appearance = SymbolAppearance.read(assertNotNull(project.circuit(circuit)))
        applyProject(AppearanceEdits.apply(project, circuit, edit(appearance)))
    }

    private fun SymbolAppearance.shapeId(tag: String) =
        objects.filterIsInstance<SymbolObject.Shape>().single { it.tag == tag }.id

    /** The shape panel's Backward and Forward buttons (AppearanceTools.ShapeProperties onLayer). */
    private fun SymbolAppearance.step(id: String, forward: Boolean): SymbolAppearance {
        val list = objects.toMutableList()
        val index = list.indexOfFirst { it is SymbolObject.Shape && it.id == id }
        val destination = (index + if (forward) 1 else -1).coerceIn(0, list.lastIndex)
        val shape = list.removeAt(index)
        list.add(destination, shape)
        return copy(objects = list)
    }

    /** The polygon vertex editor: Add vertex appends a copy of the last point, then the fields set the points. */
    private fun SymbolAppearance.polygonPoints(transform: (List<GridPoint>) -> List<GridPoint>): SymbolAppearance {
        val polygon = objects.filterIsInstance<SymbolObject.Shape>().single { it.tag == "polygon" }
        val points = transform(assertNotNull(AppearanceEdits.controlPoints(polygon)))
        return editShape(polygon.id, AppearanceEdits.controlPointAttributes("polygon", points))
    }

    private fun history(state: EditorState) = "${state.undoHistory.size}/${state.redoHistory.size}"

    private fun threeProjectActions(editor: EditorState) {
        assertTrue(editor.createCircuit("added"))
        assertTrue(editor.setMainCircuit(editor.circuitId("half")))
        assertTrue(editor.moveCircuit(editor.circuitId("spare"), -1))
    }

    @Test
    fun exportsTheMobileResultOfEachMenuAction() {
        write("file-open", open().exportXml())
        write("file-save", open().saveXml())
        // Save As writes the same bytes as Save to the destination chosen in the system picker.
        write("file-saveAs", open().saveXml())
        EditorState(null).apply { newDocument() }.let { write("file-newi", it.saveXml()) }

        open().apply { assertTrue(createCircuit("added")) }.let { write("project-addCircuit", it.exportXml()) }
        open().apply { assertTrue(moveCircuit(circuitId("spare"), -1)) }.let { write("project-moveUp", it.exportXml()) }
        open().apply { assertTrue(moveCircuit(circuitId("main"), 1)) }.let { write("project-moveDown", it.exportXml()) }
        open().apply { assertTrue(removeCircuit(circuitId("spare"))) }.let { write("project-remove", it.exportXml()) }
        open().apply { assertTrue(setMainCircuit(circuitId("half"))) }.let { write("project-setAsMain", it.exportXml()) }
        open().apply {
            // Appearance > Classic, the Mobile command for "Revert to default appearance".
            applyProject(AppearanceEdits.useClassic(project, "half"))
        }.let { write("project-revertAppearance", it.exportXml()) }

        open().apply { editAppearance("half") { it.step(it.shapeId("polygon"), forward = true) } }
            .let { write("edit-raise", it.exportXml()) }
        open().apply { editAppearance("half") { it.step(it.shapeId("ellipse"), forward = false) } }
            .let { write("edit-lower", it.exportXml()) }
        open().apply { editAppearance("half") { AppearanceSelectionEdits.layer(it, setOf(it.shapeId("rect")), front = true) } }
            .let { write("edit-raiseTop", it.exportXml()) }
        open().apply { editAppearance("half") { AppearanceSelectionEdits.layer(it, setOf(it.shapeId("text")), front = false) } }
            .let { write("edit-lowerBottom", it.exportXml()) }
        open().apply {
            editAppearance("half") { appearance -> appearance.polygonPoints { points -> points + GridPoint(55, 70) } }
        }.let { write("edit-addCtrl", it.exportXml()) }
        open().apply {
            editAppearance("half") { appearance -> appearance.polygonPoints { points -> points - GridPoint(85, 85) } }
        }.let { write("edit-remCtrl", it.exportXml()) }

        open().apply {
            selectCircuit("main")
            select(component(250, 300, ComponentKind.AND).id)
            select(component(400, 300, ComponentKind.NOT).id, additive = true)
            selectWire(document.wires.single { it.from == GridPoint(250, 300) && it.to == GridPoint(370, 300) }, additive = true)
            cutSelected()
        }.let { write("edit-cut", it.exportXml()) }
        open().apply {
            selectCircuit("main")
            select(component(400, 300, ComponentKind.NOT).id)
            duplicateSelected()
            // The destination tap: Evolution's offset with the pointer off the canvas is (10,10).
            movePastePreview(GridPoint(410, 310))
            confirmPaste()
            state["edit-dup.offset"] = "10,10"
        }.let { write("edit-dup", it.exportXml()) }

        open().apply {
            threeProjectActions(this)
            state["edit-undoHistory.entries"] = undoHistory.size.toString()
            travelHistory(EditorHistoryDirection.UNDO, undoHistory[1].id)
            state["edit-undoHistory.after"] = history(this)
        }.let { write("edit-undoHistory", it.exportXml()) }
        open().apply {
            threeProjectActions(this)
            travelHistory(EditorHistoryDirection.UNDO, undoHistory[2].id)
            state["edit-redoHistory.entries"] = redoHistory.size.toString()
            travelHistory(EditorHistoryDirection.REDO, redoHistory[1].id)
            state["edit-redoHistory.after"] = history(this)
        }.let { write("edit-redoHistory", it.exportXml()) }
        open().apply {
            assertTrue(createCircuit("added"))
            undo()
            assertTrue(setMainCircuit(circuitId("half")))
            clearHistory()
            state["edit-clearHistory.after"] = history(this)
        }.let { write("edit-clearHistory", it.exportXml()) }

        open().apply { assertTrue(setBuiltinLibrary("#Memory", false, LocalizedText.literal("Memory"))) }
            .let { write("project-unload", it.exportXml()) }
        open().apply {
            assertTrue(setBuiltinLibrary("#Memory", false, LocalizedText.literal("Memory")))
            assertTrue(setBuiltinLibrary("#Memory", true, LocalizedText.literal("Memory")))
        }.let { write("project-loadBuiltin", it.exportXml()) }
        open().apply {
            val source = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(
                DocumentAsset("library.circ", "application/octet-stream", File(fixtures, "library.circ").readBytes()), componentRegistry,
            )).project
            assertTrue(attachLibrary(source, "library"))
        }.let { write("project-loadLogisim", it.exportXml()) }
        open().apply {
            val imported = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(
                DocumentAsset("merge.circ", "application/octet-stream", File(fixtures, "merge.circ").readBytes()), componentRegistry,
            )).project
            applyProject(ProjectCircuitMerge.copySelected(project, imported, setOf("top")))
        }.let { write("file-merge", it.exportXml()) }
        open().apply {
            val options = SimulationProjectOptions.read(project).copy(propagationLimit = 500, gateUndefined = GateUndefinedPolicy.ERROR)
            applyProject(options.applyTo(project))
        }.let { write("project-options", it.exportXml()) }
        open().apply { applyProject(project.addSharedVhdlDefinition(fixture("evolution-vhdl-template.vhd")).first) }
            .let { write("project-addVhdl", it.exportXml()) }
        open().apply { applyProject(project.addSharedVhdlDefinition(fixture("counter.vhd")).first) }
            .let { write("project-importVhdl", it.exportXml()) }

        writeState("state-edit.json")
    }

    private fun bit(value: LogicValue?) = when (value) {
        LogicValue.HIGH -> "1"
        LogicValue.LOW -> "0"
        LogicValue.ERROR -> "E"
        else -> "x"
    }

    private fun EditorState.obs(): String {
        val root = projectSimulationForTest()
        val stage = project.mainCircuit.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        val inner = root.circuitStates[stage.id]
        return "clk=${bit(root.portValues[GridPoint(100, 450)])} q0=${bit(root.portValues[GridPoint(350, 410)])}" +
            " nq0=${bit(root.portValues[GridPoint(350, 450)])} q1=${bit(inner?.portValues?.get(GridPoint(350, 410)))}"
    }

    private fun EditorState.projectSimulationForTest(): SimulationState {
        check(activeInstancePath.isEmpty())
        return simulation
    }

    @Test
    fun exportsTheMobileResultOfEachSimulateMenuAction() {
        val sim = open("sim.circ").apply { resetSimulation() }
        val trace = mutableListOf(sim.obs())
        repeat(4) { sim.tick(fullCycle = false); trace += sim.obs() }
        state["sim-tickHalf"] = trace.joinToString(" | ")
        trace.clear()
        repeat(3) { sim.tick(fullCycle = true); trace += sim.obs() }
        state["sim-tickFull"] = trace.joinToString(" | ")
        sim.resetSimulation()
        state["sim-reset"] = sim.obs()
        repeat(3) { sim.tick(fullCycle = true) }
        val main = sim.document
        sim.viewSubcircuitInstance(sim.document.components.single { it.kind == ComponentKind.SUBCIRCUIT }.id)
        state["sim-downState"] = "${sim.document.name} clk=${bit(sim.simulation.portValues[GridPoint(100, 450)])}" +
            " q=${bit(sim.simulation.portValues[GridPoint(460, 410)])}"
        sim.navigate(CircuitNavigationTarget(main.id, main.name, emptyList()))
        state["sim-upState"] = "${sim.document.name} ${sim.obs()}"

        // Simulate > Ticks Enabled through the run controller, with the delay released four times.
        runBlocking {
            val auto = open("sim.circ").apply { resetSimulation() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val permits = Channel<Unit>(Channel.UNLIMITED)
            val waits = mutableListOf<Long>()
            val controller = SimulationRunController(
                scope = scope,
                capture = auto::captureRunSnapshot,
                advance = { snapshot, step -> advanceSimulation(snapshot, step, auto.componentRegistry).state },
                isCurrent = auto::isCurrentRunSnapshot,
                publish = auto::publishRun,
                workerDispatcher = Dispatchers.Unconfined,
                waitForNext = { waits += it; permits.receive() },
            )
            try {
                controller.configureFrequency(64.0)
                controller.setAutomaticTicks(true)
                assertTrue(controller.start())
                repeat(4) { permits.send(Unit); yield() }
                controller.pause()
                state["sim-ticksEnabled"] = "ticks=${auto.simulation.ticks} ${auto.obs()}"
                state["sim-ticksEnabled.period_ms"] = waits.first().toString()
            } finally { controller.close(); scope.cancel(); permits.close() }
        }

        open("sim.circ").apply {
            applyProject(SimulationProjectOptions.read(project).copy(tickFrequencyHz = 8.0).applyTo(project))
        }.let { write("sim-tickFreq", it.exportXml()) }
        // The Simulate menu's frequency choices (App.kt); the options panel accepts any positive value.
        state["sim-tickFreq.choices"] = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0).toString()

        val step = open("step.circ").apply { resetSimulation() }
        val pinA = step.component(100, 100, ComponentKind.INPUT)
        var snapshot = step.captureRunSnapshot().let { it.copy(inputs = it.inputs + (pinA.id to LogicValue.HIGH)) }
        trace.clear()
        repeat(5) {
            val next = advanceSimulation(snapshot, SimulationStep.EVENT, step.componentRegistry).state
            trace += listOf(100, 160, 220, 280).zip(listOf("a", "n1", "n2", "n3")).joinToString(" ") { (x, name) ->
                "$name=${bit(next.portValues[GridPoint(x, 100)])}"
            }
            snapshot = snapshot.copy(previous = next)
        }
        state["sim-step"] = trace.joinToString(" | ")

        // Simulate > Logging: the trace recorder with the signals Evolution's Logging window selects by default.
        val logged = open("sim.circ").apply { resetSimulation() }
        val available = TraceSignals.fromProject(logged.project, logged.componentRegistry).filter { it.reference.instancePath.isEmpty() }
        val chosen = listOf(
            "Clock(100,450)" to logged.component(100, 450, ComponentKind.CLOCK),
            "D Flip-Flop(300,400)" to logged.component(300, 400, ComponentKind.D_FLIP_FLOP),
            "q0" to logged.component(460, 410, ComponentKind.OUTPUT),
        )
        val recorder = TraceRecorder(chosen.map { (_, component) -> available.single { it.reference.componentId == component.id } })
        recorder.capture(logged.simulation, 0)
        repeat(4) { index -> logged.tick(fullCycle = false); recorder.capture(logged.simulation, index + 1L) }
        chosen.forEachIndexed { index, (name, _) ->
            val values = recorder.frames.map { frame -> frame.values[index]?.let(TraceRadix.BINARY::format) ?: "x" }
            state["sim-log.$name"] = values.fold(emptyList<String>()) { acc, v -> if (acc.lastOrNull() == v) acc else acc + v }.joinToString(",")
        }
        // Mobile's trace panel starts with no signal selected; the user picks them from TraceSignals.fromProject.
        state["sim-log.defaultSignals"] = ""
        writeState("state-sim.json")
    }

    @Test
    fun exportsTheMobileResultOfTheAnalysisAppearanceAndRecentMenus() {
        val editor = open()
        val table = CircuitAnalyzer.truthTable(editor.project, "half", registry = editor.componentRegistry)
        fun cell(value: TruthCell) = when (value) { TruthCell.ZERO -> "0"; TruthCell.ONE -> "1"; else -> "-" }
        state["analyze.vars"] = "${table.inputs}->${table.outputs}"
        state["analyze.table"] = table.rows.indices.joinToString(" ") { row ->
            table.inputs.indices.joinToString("") { if (table.input(row, it)) "1" else "0" } + "->" +
                table.rows[row].joinToString("") { cell(it) }
        }
        table.outputs.forEachIndexed { index, name ->
            state["analyze.minimal.$name"] = BooleanMinimizer.minimize(table, index).expression.format()
        }

        val stats = CircuitComponentStatistics.measure(editor.project, "main", editor.componentRegistry)
        fun ComponentOccurrenceCounts.text() = "$local/$unique/$recursive"
        state["stats.rows"] = stats.rows.map { row ->
            // Evolution names the project's own circuits after the file, here "project".
            "${row.name}@${row.library?.removePrefix("#") ?: "project"}=${row.counts.text()}"
        }.sorted().joinToString(" ")
        state["stats.totals"] = "${stats.totalWithoutSubcircuits.text()} ${stats.totalWithSubcircuits.text()}"

        val session = TestVectorRunSession(editor.project, "half", TestVectorFormat.read(fixture("half.txt")))
        val results = session.run()
        state["test.rows"] = results.joinToString(" ") { "${it.rowIndex + 1}:${if (it.passed) "pass" else "fail"}" }
        state["test.totals"] = "${results.count { it.passed }}/${results.count { !it.passed }}"

        open("appearance.circ").apply {
            var edited = project
            for (circuit in project.circuits) {
                edited = AppearanceEdits.apply(edited, circuit.name, SymbolAppearance.read(assertNotNull(edited.circuit(circuit.name))))
            }
            applyProject(edited)
        }.let { write("project-appearance", it.exportXml()) }

        class Store : WorkspaceCatalogStorage {
            val files = mutableMapOf<String, String>()
            override fun <T> withExclusiveAccess(action: () -> T) = action()
            override fun read(name: String) = files[name]
            override fun listNames() = files.keys.toList()
            override fun writeAtomically(name: String, contents: String) { files[name] = contents }
        }
        val links = StoredWorkspaceDocumentLinks(Store())
        var recent = emptyList<WorkspaceRecentFile>()
        for (name in (1..12).map { "r%02d".format(it) } + "r03") {
            recent = links.recordRecent(WorkspaceRecentFile.Original(
                DocumentReference("content://$name", "$name.circ", true, null, DocumentOpenMode.EDIT_IN_PLACE)))
        }
        state["file-openRecent"] = recent.joinToString(" ") { it.name.removeSuffix(".circ") }

        // File > Open Project Bundle: the same .lsebdl, resolved with its libraries, then simulated.
        val bundleBytes = File(root, "fixtures/portable-projects/portable53.lsebdl").readBytes()
        val bundle = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(bundleBytes)).bundle
        val resolved = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle)).resolved.project
        val settled = CircuitSimulator().settle(resolved)
        val outputs = resolved.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }.map { pin ->
            "${pin.label}=" + assertNotNull(settled.vectorValues[pin.id]).let(TraceRadix.BINARY::format)
        }.sorted()
        // Mobile keeps each bundled library's circuits in the project under a library_<digest>_ prefix.
        val names = resolved.circuits.map { it.name.replace(Regex("^library_[0-9a-f]+_"), "") }.toSortedSet()
        state["file-extractRunProj"] = "main=${resolved.mainCircuitName} circuits=$names ${outputs.joinToString(" ")}"
        writeState("state-analysis.json")
    }

    private fun writeState(name: String) {
        out.mkdirs()
        File(out, name).writeText(state.toSortedMap().entries.joinToString(",\n", "{\n", "\n}\n") { (key, value) ->
            "  \"$key\": \"$value\""
        })
    }
}

