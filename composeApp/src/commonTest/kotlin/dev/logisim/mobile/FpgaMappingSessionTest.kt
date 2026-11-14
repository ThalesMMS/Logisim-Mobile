package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaDesignArtifact
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaMapping
import dev.logisim.mobile.core.FpgaMappingCodec
import dev.logisim.mobile.core.FpgaMappingResult
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.mappingCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpgaMappingSessionTest {
    private fun board(name: String) = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read("""
        <$name>
          <BoardInformation>
            <ClockInformation FPGApin="W5" Frequency="100000000"/>
            <FPGAInformation Family="Artix-7" Part="xc7a35t" Package="cpg236" Speedgrade="-1" Vendor="VIVADO"/>
          </BoardInformation>
          <IOComponents>
            <Button Rect_x_y_w_h="10,20,10,10" InputPinSet="V17" FPGAPinIOStandard="LVCMOS33"/>
            <Led Rect_x_y_w_h="40,20,10,10" OutputPinSet="U16" FPGAPinIOStandard="LVCMOS33"/>
            <Led Rect_x_y_w_h="60,20,10,10" OutputPinSet="E19" FPGAPinIOStandard="LVCMOS33"/>
          </IOComponents>
        </$name>
    """.trimIndent())).board

    private val boardA = board("FIXTURE_A")
    private val boardB = board("FIXTURE_B")
    private val flow = FpgaFlow.VIVADO

    /** A on the button, Y on the first LED, written the way Evolution stores a map. */
    private val savedMap = """
        <boardmap boardname="FIXTURE_A">
            <mc key="/A" map="10,20"/>
            <mc key="/Y" pmap="40_20_0"/>
        </boardmap>
    """.trimIndent()

    private fun project(map: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main">
            $map
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
            <comp lib="0" name="Pin" loc="(150,100)">
              <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
            </comp>
            <wire from="(20,100)" to="(150,100)"/>
          </circuit>
        </project>
    """.trimIndent())).project

    /** The review of [circuit], as the FPGA tool builds it for the circuit being edited. */
    private class Fixture(val project: CircuitProject, circuit: String = project.mainCircuitName) {
        val review = FpgaCircuitRules.reviewForFpga(project.copy(mainCircuitName = circuit), HdlLanguage.VHDL)
        val design: FpgaDesignArtifact = checkNotNull(review.design)
        val codec: FpgaMappingCodec = checkNotNull(review.mappingCodec(checkNotNull(project.circuit(circuit))))
    }

    /** Two circuits with the same ports, each storing its own map for FIXTURE_A: Y on the first LED in X, the second in Z. */
    private val twoCircuits: CircuitProject get() {
        fun circuit(name: String, ledX: Int) = """
            <circuit name="$name">
              <boardmap boardname="FIXTURE_A">
                <mc key="/A" map="10,20"/>
                <mc key="/Y" pmap="${ledX}_20_0"/>
              </boardmap>
              <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
              <comp lib="0" name="Pin" loc="(150,100)">
                <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
              </comp>
              <wire from="(20,100)" to="(150,100)"/>
            </circuit>
        """
        return assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project source="5.1.0dev" version="1.0">
              <lib name="0" desc="#Wiring"/><main name="X"/>
              ${circuit("X", 40)}
              ${circuit("Z", 60)}
            </project>
        """.trimIndent())).project
    }

    private fun Map<FpgaSignalBit, FpgaPinBinding>.locations() =
        mapValues { (_, binding) -> (binding as? FpgaPinBinding.Physical)?.location }

    /** What FpgaPanel does when bindings are first created: read the stored map off the main thread and apply it. */
    private fun FpgaMappingSession.loadPending(codec: FpgaMappingCodec) {
        val request = assertNotNull(pendingLoad)
        completeLoad(request, if (codec.hasSaved(request.board)) codec.loadSaved(request.board, flow) else null)
    }

    private val y = FpgaSignalBit("p_y")
    private val a = FpgaSignalBit("p_a")

    @Test
    fun theSavedMapIsLoadedForTheFirstBindingsOfADesignAndBoard() {
        val fixture = Fixture(project(savedMap))
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA)
        assertEquals(emptyMap(), session.currentBindings)
        session.loadPending(fixture.codec)
        assertEquals("V17", assertIs<FpgaPinBinding.Physical>(session.currentBindings[a]).location)
        assertEquals("U16", assertIs<FpgaPinBinding.Physical>(session.currentBindings[y]).location)
        assertEquals(FpgaMapLoadStatus.Loaded, session.loadStatus)
        assertNull(session.pendingLoad)
        // The same pair does not load again, so a re-keyed panel keeps its edits.
        session.bindings.value = session.currentBindings - y
        session.sync(fixture.design, boardA)
        assertNull(session.pendingLoad)
        assertEquals(setOf(a), session.currentBindings.keys)
    }

    @Test
    fun aMissingMapKeepsTheDefaultsSilentlyAndABrokenMapIsReported() {
        val missing = Fixture(project(""))
        val session = FpgaMappingSession()
        session.sync(missing.design, boardA)
        session.loadPending(missing.codec)
        assertEquals(emptyMap(), session.currentBindings)
        assertNull(session.loadStatus)

        val broken = Fixture(project(savedMap.replace("40_20_0", "99_99_0")))
        val other = FpgaMappingSession()
        other.sync(broken.design, boardA)
        assertTrue(broken.codec.hasSaved(boardA))
        other.loadPending(broken.codec)
        assertEquals(emptyMap(), other.currentBindings)
        assertIs<FpgaMapLoadStatus.Failed>(other.loadStatus)
    }

    @Test
    fun bindingsEditedBeforeTheMapIsReadAreNotOverwritten() {
        val fixture = Fixture(project(savedMap))
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA)
        val request = assertNotNull(session.pendingLoad)
        session.bindings.value = mapOf(y to FpgaPinBinding.Physical("E19"))
        session.completeLoad(request, fixture.codec.loadSaved(boardA, flow))
        assertEquals(mapOf(y to FpgaPinBinding.Physical("E19")), session.currentBindings)
    }

    @Test
    fun switchingBoardsAndBackKeepsTheUnsavedBindingsOfEachBoard() {
        val fixture = Fixture(project(savedMap))
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA)
        session.loadPending(fixture.codec)
        val edited = session.currentBindings + (y to FpgaPinBinding.Physical("E19"))
        session.bindings.value = edited
        session.clockInputs.value = session.currentClocks + ("p_a" to FpgaClockInput(true, "1000"))

        session.sync(fixture.design, boardB)
        assertEquals(emptyMap(), session.currentBindings)
        session.loadPending(fixture.codec) // No map for B: the defaults stay.
        session.bindings.value = mapOf(y to FpgaPinBinding.Open)

        session.sync(fixture.design, boardA)
        assertEquals(edited, session.currentBindings)
        assertEquals(FpgaClockInput(true, "1000"), session.currentClocks["p_a"])
        assertNull(session.pendingLoad, "A board with unsaved bindings is not reloaded")
        session.sync(fixture.design, boardB)
        assertEquals(mapOf(y to FpgaPinBinding.Open), session.currentBindings)
    }

    @Test
    fun aDesignChangeKeepsBindingsOfUnchangedPortsAndAnotherDocumentStartsOver() {
        val design = FpgaDesignArtifact("top", dev.logisim.mobile.core.FpgaHdlLanguage.VHDL, emptyList(), listOf(
            FpgaDesignPort("p_a", FpgaPinDirection.INPUT), FpgaDesignPort("p_y", FpgaPinDirection.OUTPUT),
            FpgaDesignPort("p_bus", FpgaPinDirection.OUTPUT, 2)))
        val session = FpgaMappingSession()
        session.sync(design, boardA, identity = 1)
        session.completeLoad(assertNotNull(session.pendingLoad), null)
        session.bindings.value = mapOf(a to FpgaPinBinding.Physical("V17"), y to FpgaPinBinding.Physical("U16"),
            FpgaSignalBit("p_bus", 1) to FpgaPinBinding.Physical("E19"))
        // p_y changes direction and p_bus width: only p_a keeps its pin.
        val changed = design.copy(ports = listOf(FpgaDesignPort("p_a", FpgaPinDirection.INPUT),
            FpgaDesignPort("p_y", FpgaPinDirection.INOUT), FpgaDesignPort("p_bus", FpgaPinDirection.OUTPUT, 3)))
        session.sync(changed, boardA, identity = 1)
        assertEquals(mapOf(a to FpgaPinBinding.Physical("V17")), session.currentBindings)
        assertNull(session.pendingLoad)

        session.sync(changed, boardA, identity = 2)
        assertEquals(emptyMap(), session.currentBindings)
        assertNotNull(session.pendingLoad, "Another document reads its own stored map")
    }

    @Test
    fun eachCircuitLoadsItsOwnStoredMapAndKeepsItsOwnEdits() {
        val project = twoCircuits
        val x = Fixture(project, "X")
        val z = Fixture(project, "Z")
        val session = FpgaMappingSession()
        session.sync(x.design, boardA, identity = 1, circuit = "X")
        session.loadPending(x.codec)
        assertEquals(mapOf(a to "V17", y to "U16"), session.currentBindings.locations())
        // An unsaved edit in X: A is unmapped.
        session.editBindings(session.currentBindings - a)
        val editedX = session.currentBindings

        // The same board in another circuit of the same document reads that circuit's map, not X's bindings.
        session.sync(z.design, boardA, identity = 1, circuit = "Z")
        assertNotNull(session.pendingLoad, "Another circuit reads its own stored map")
        assertNull(session.loadStatus, "X's load report does not describe Z")
        assertEquals(emptyMap(), session.currentBindings)
        session.loadPending(z.codec)
        assertEquals(mapOf(a to "V17", y to "E19"), session.currentBindings.locations())
        assertEquals(FpgaMapLoadStatus.Loaded, session.loadStatus)
        assertNull(z.codec.replacementGuard(boardA, flow, FpgaMapping(session.currentBindings, emptyMap())),
            "Saving Z's own map back needs no confirmation")

        // Back in X, its edit is intact: not trimmed to Z's ports and not replaced by Z's map.
        session.sync(x.design, boardA, identity = 1, circuit = "X")
        assertNull(session.pendingLoad)
        assertEquals(editedX, session.currentBindings)
        session.sync(z.design, boardA, identity = 1, circuit = "Z")
        assertEquals(mapOf(a to "V17", y to "E19"), session.currentBindings.locations())
    }

    @Test
    fun theLoadReportEndsWithAnEditOrASave() {
        val fixture = Fixture(project(savedMap))
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA, circuit = "main")
        session.loadPending(fixture.codec)
        assertEquals(FpgaMapLoadStatus.Loaded, session.loadStatus)
        session.editBindings(session.currentBindings - y)
        assertNull(session.loadStatus, "Edited bindings are no longer the saved map")

        val clocks = FpgaMappingSession()
        clocks.sync(fixture.design, boardA, circuit = "main")
        clocks.loadPending(fixture.codec)
        clocks.editClocks(clocks.currentClocks + ("p_a" to FpgaClockInput(true, "1000")))
        assertNull(clocks.loadStatus, "A clock edit ends the report too")

        val broken = Fixture(project(savedMap.replace("40_20_0", "99_99_0")))
        val other = FpgaMappingSession()
        other.sync(broken.design, boardA, circuit = "main")
        other.loadPending(broken.codec)
        other.editBindings(mapOf(y to FpgaPinBinding.Physical("E19")))
        assertIs<FpgaMapLoadStatus.Failed>(other.loadStatus, "The stored map stays unreadable until it is saved over")
        other.mapSaved("other", "FIXTURE_A")
        other.mapSaved("main", "FIXTURE_B")
        assertIs<FpgaMapLoadStatus.Failed>(other.loadStatus, "A save of another circuit or board changes nothing here")
        other.mapSaved("main", "FIXTURE_A")
        assertNull(other.loadStatus)
        // The cleared report stays cleared when the board comes back.
        other.sync(broken.design, boardB, circuit = "main")
        other.sync(broken.design, boardA, circuit = "main")
        assertNull(other.loadStatus)
    }

    @Test
    fun savingTheUnchangedLoadedMapGivesByteIdenticalXml() {
        val fixture = Fixture(project(savedMap))
        val loaded = assertIs<FpgaMappingResult.Loaded>(fixture.codec.loadSaved(boardA, flow)).mapping
        assertNull(fixture.codec.replacementGuard(boardA, flow, loaded), "The same bits need no confirmation")
        // The first save writes Mobile's form of Evolution's map; it loads back as the same bindings.
        val first = Fixture(fixture.project.withCircuit(
            assertIs<FpgaMappingResult.Saved>(fixture.codec.save(boardA, flow, loaded)).document))
        val reloaded = assertIs<FpgaMappingResult.Loaded>(first.codec.loadSaved(boardA, flow)).mapping
        assertEquals(loaded, reloaded)
        val second = assertIs<FpgaMappingResult.Saved>(first.codec.save(boardA, flow, reloaded))
        assertEquals(first.project.mainCircuit.extraXml, second.document.extraXml)
        assertEquals(CircCodec.writeProject(first.project), CircCodec.writeProject(first.project.withCircuit(second.document)))
        // The session keeps the bindings of the re-keyed panel: the same design and board do not reload.
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA)
        session.loadPending(fixture.codec)
        session.sync(first.design, boardA)
        assertNull(session.pendingLoad)
        assertEquals(loaded.bindings, session.currentBindings)
    }

    @Test
    fun savingAPartialMapOverAFullOneNeedsConfirmation() {
        val fixture = Fixture(project(savedMap))
        val full = assertIs<FpgaMappingResult.Loaded>(fixture.codec.loadSaved(boardA, flow)).mapping
        val partial = full.copy(bindings = full.bindings - y)
        val guard = assertNotNull(fixture.codec.replacementGuard(boardA, flow, partial))
        assertEquals(FpgaSaveGuard("FIXTURE_A", 2, 1, partial), guard)
        assertNull(fixture.codec.replacementGuard(boardB, flow, partial), "No stored map for B")
        assertNull(Fixture(project("")).codec.replacementGuard(boardA, flow, FpgaMapping(emptyMap(), emptyMap())))
        val broken = Fixture(project(savedMap.replace("40_20_0", "99_99_0")))
        assertNull(assertNotNull(broken.codec.replacementGuard(boardA, flow, full)).savedBits, "An unreadable map asks too")
    }

    @Test
    fun boardEditorDraftLivesInTheSessionAndTracksChanges() {
        val session = FpgaMappingSession()
        val editor = session.boardEditor
        editor.start(boardA)
        assertTrue(editor.open)
        assertFalse(editor.dirty)
        editor.edit(editor.draft.copy(usbTmc = true))
        assertTrue(editor.dirty)
        // The sheet closing leaves the session as it is; the next composition shows the same draft.
        assertTrue(session.boardEditor.open)
        assertTrue(session.boardEditor.draft.usbTmc)
        editor.cancel()
        assertTrue(editor.confirmingDiscard, "A dirty draft asks before it is discarded")
        assertTrue(editor.open)
        editor.close()
        assertFalse(editor.open)
        assertFalse(editor.dirty)
        editor.start(boardA)
        editor.cancel()
        assertFalse(editor.open, "A clean editor closes at once")
    }

    @Test
    fun removingAResourceCanBeUndoneUntilTheNextEdit() {
        val editor = FpgaMappingSession().boardEditor
        editor.start(boardA)
        val original = editor.draft
        editor.selected = 1
        editor.removeResource()
        assertEquals(2, editor.draft.resources.size)
        assertEquals(FpgaRemovedResource(1, original), editor.removed)
        editor.undoRemove()
        assertEquals(original, editor.draft)
        assertEquals(1, editor.selected)
        assertNull(editor.removed)
        assertFalse(editor.dirty)

        editor.removeResource()
        editor.edit(editor.draft.copy(usbTmc = true))
        assertNull(editor.removed, "The next edit ends the undo offer")
    }

    /** The keys the FPGA tool uses for [state]'s circuits, as fpgaEditorTool builds them. */
    private fun keys(state: EditorState) = state.project.circuits.associate { it.name to fpgaCircuitKey(it.id, state.circuitLifetime(it.id)) }

    @Test
    fun renamingACircuitKeepsItsUnsavedBindings() {
        val fixture = Fixture(project(""))
        val state = EditorState(null)
        assertTrue(state.createCircuit("Y"))
        val before = keys(state)
        val y = state.project.circuits.single { it.name == "Y" }
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA, circuit = before.getValue("Y"), liveCircuits = before.values.toSet())
        session.loadPending(fixture.codec)
        session.editBindings(mapOf(a to FpgaPinBinding.Physical("V17")))
        session.sync(fixture.design, boardA, circuit = before.getValue(state.project.mainCircuit.name), liveCircuits = before.values.toSet())
        // Renamed while the sheet is closed: same id, same lifetime.
        assertTrue(state.renameCircuit(y.id, "Y2"))
        val after = keys(state)
        assertEquals(before.getValue("Y"), after.getValue("Y2"), "A rename keeps the circuit's key")
        session.sync(fixture.design, boardA, circuit = after.getValue("Y2"), liveCircuits = after.values.toSet())
        assertEquals(mapOf(a to FpgaPinBinding.Physical("V17")), session.currentBindings, "The renamed circuit keeps its bindings")
        assertNull(session.pendingLoad)
    }

    @Test
    fun aDeletedCircuitsBindingsNeverReachANewCircuitThatReusesItsIdAndName() {
        val fixture = Fixture(project(""))
        val state = EditorState(null)
        assertTrue(state.createCircuit("Y"))
        val y = state.project.circuits.single { it.name == "Y" }
        val before = keys(state)
        val session = FpgaMappingSession()
        session.sync(fixture.design, boardA, circuit = before.getValue("Y"), liveCircuits = before.values.toSet())
        session.loadPending(fixture.codec)
        session.editBindings(mapOf(a to FpgaPinBinding.Physical("V17")))
        session.sync(fixture.design, boardA, circuit = before.getValue(state.project.mainCircuit.name), liveCircuits = before.values.toSet())
        // While the sheet is closed, Y is deleted and a new circuit is created with the same name; it reuses Y's id.
        assertTrue(state.removeCircuit(y.id))
        assertTrue(state.createCircuit("Y"))
        val recreated = state.project.circuits.single { it.name == "Y" }
        assertEquals(y.id, recreated.id, "EditorState reuses the lowest free id")
        val after = keys(state)
        assertNotEquals(before.getValue("Y"), after.getValue("Y"), "The new circuit has another key")
        session.sync(fixture.design, boardA, circuit = after.getValue("Y"), liveCircuits = after.values.toSet())
        assertEquals(emptyMap(), session.currentBindings, "The new Y starts from its own defaults")
        assertNull(session.loadStatus)
        assertNotNull(session.pendingLoad, "The new Y reads its own stored map")
        // The deleted circuit's slot is gone for good.
        session.sync(fixture.design, boardA, circuit = after.getValue(state.project.mainCircuit.name), liveCircuits = after.values.toSet())
        session.sync(fixture.design, boardA, circuit = before.getValue("Y"), liveCircuits = after.values.toSet() + before.getValue("Y"))
        assertEquals(emptyMap(), session.currentBindings)
    }

    @Test
    fun aRectangleDraggedOnThePictureCanBeUndone() {
        val editor = FpgaMappingSession().boardEditor
        editor.start(boardA)
        val original = editor.draft
        editor.changeRectangle(1, dev.logisim.mobile.core.FpgaRectangle(100, 50, 10, 10))
        assertEquals("100,50,10,10", editor.draft.resources[1].fields[FpgaResourceField.RECTANGLE])
        assertEquals(1, editor.selected)
        editor.changeRectangle(1, dev.logisim.mobile.core.FpgaRectangle(100, 50, 10, 10))
        editor.undoRectangle()
        assertEquals(original, editor.draft)
        assertFalse(editor.dirty)
        // New resources start in a free spot, not at 0,0 on top of the others.
        editor.addResource()
        val added = checkNotNull(editor.draft.resources.last().rectangle)
        assertTrue(editor.draft.resources.dropLast(1).mapNotNull { it.rectangle }.none { FpgaBoardGeometry.overlaps(it, added) })
        editor.addResource(dev.logisim.mobile.core.FpgaRectangle(200, 100, 30, 20), "DIPSwitch")
        assertEquals("DIPSwitch", editor.draft.resources.last().type)
        assertEquals(editor.draft.resources.lastIndex, editor.selected)
    }
}
