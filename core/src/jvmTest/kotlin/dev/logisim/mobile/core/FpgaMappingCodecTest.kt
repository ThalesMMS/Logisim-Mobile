package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaMappingCodecTest {
    private val board = assertIs<FpgaBoardImportResult.Success>(
        FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/BASYS3.xml")).readText())
    ).board
    private val flow = FpgaFlow.VIVADO
    private val mappingXml = """
        <boardmap boardname="BASYS3">
            <mc key="/A" map="647,323"/>
            <mc key="/Y" pmap="649_295_0"/>
            <mc key="/Mode" vconst="1"/>
            <mc key="/Unused" open="open"/>
            <mc key="/Clk" map="321,237"/>
        </boardmap>
    """.trimIndent()

    private fun fixture(map: String) = """
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main">
            $map
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
            <comp lib="0" name="Pin" loc="(20,120)"><a name="label" val="Mode"/></comp>
            <comp lib="0" name="Pin" loc="(20,140)"><a name="label" val="Clk"/></comp>
            <comp lib="0" name="Pin" loc="(150,100)">
              <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
            </comp>
            <comp lib="0" name="Pin" loc="(150,160)">
              <a name="label" val="Unused"/><a name="type" val="output"/><a name="facing" val="west"/>
            </comp>
            <wire from="(20,100)" to="(100,100)"/><wire from="(100,100)" to="(150,100)"/>
            <wire from="(100,100)" to="(100,160)"/><wire from="(100,160)" to="(150,160)"/>
          </circuit>
        </project>
    """.trimIndent()

    private fun import(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun mappingCodec(project: CircuitProject): FpgaMappingCodec {
        val exported = HdlExporter.export(project, HdlLanguage.VERILOG)
        return FpgaMappingCodec(exported.fpgaDesign(HdlLanguage.VERILOG), project.mainCircuit, exported.ports)
    }

    @Test fun basysMappingReopensInProjectAndGeneratesConstraints() {
        val blank = import(fixture(""))
        val loaded = assertIs<FpgaMappingResult.Loaded>(mappingCodec(blank).import(fixture(mappingXml), board, flow)).mapping
        fun binding(name: String) = loaded.bindings[FpgaSignalBit("p_$name")]
        assertEquals("V17", assertIs<FpgaPinBinding.Physical>(binding("a")).location)
        assertEquals("U16", assertIs<FpgaPinBinding.Physical>(binding("y")).location)
        assertEquals(FpgaPinBinding.Constant(true), binding("mode"))
        assertEquals(FpgaPinBinding.Open, binding("unused"))
        assertEquals("W5", assertIs<FpgaPinBinding.Physical>(binding("clk")).location)
        assertEquals(mapOf("p_clk" to 100_000_000L), loaded.clocks)

        val saved = assertIs<FpgaMappingResult.Saved>(mappingCodec(blank).save(board, flow, loaded))
        val reopened = import(CircCodec.writeProject(blank.withCircuit(saved.document)))
        assertEquals(loaded, assertIs<FpgaMappingResult.Loaded>(mappingCodec(reopened).loadSaved(board, flow)).mapping)
        val design = HdlExporter.export(reopened, HdlLanguage.VERILOG).fpgaDesign(HdlLanguage.VERILOG, loaded.clocks)
        val result = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design, board, loaded.bindings, flow))
        val xdc = result.plan.files.single { it.path == "pins.xdc" }.content
        assertTrue("PACKAGE_PIN V17" in xdc && "PACKAGE_PIN U16" in xdc && "PACKAGE_PIN W5" in xdc)
        assertTrue("create_clock -period 10" in xdc)
    }

    @Test fun rejectsIncompatibleBoardsCircuitsAndPhysicalPinsBeforeReturningBindings() {
        val codec = mappingCodec(import(fixture("")))
        for (xml in listOf(
            fixture(mappingXml.replace("BASYS3", "other-board")),
            fixture(mappingXml).replace("name=\"main\"", "name=\"other\""),
            fixture(mappingXml.replace("/Y", "/Missing")),
            fixture(mappingXml.replace("649_295_0", "1_1_0")),
            fixture(mappingXml.replace("649_295_0", "647_323_0")),
            fixture(mappingXml.replace("321,237", "321,237\" mobileclockhz=\"50000000")),
        )) assertIs<FpgaMappingResult.Failure>(codec.import(xml, board, flow))

        val legacy = """
            <LogisimGoesFPGABoardMapInformation>
              <GlobalMapInformation BoardName="BASYS3" ToplevelCircuitName="main"/>
              <MAPPEDCOMPONENT1 Key="Pin: /A#Pin0" LocationX="647" LocationY="323" Width="30" Height="54"/>
              <MAPPEDCOMPONENT2 Key="/Y" LocationX="649" LocationY="295" Width="14" Height="14"/>
            </LogisimGoesFPGABoardMapInformation>
        """.trimIndent()
        val mapped = assertIs<FpgaMappingResult.Loaded>(codec.import(legacy, board, flow)).mapping
        assertEquals(setOf("V17", "U16"), mapped.bindings.values.map { assertIs<FpgaPinBinding.Physical>(it).location }.toSet())
        assertIs<FpgaMappingResult.Failure>(codec.import(legacy.replace("ToplevelCircuitName=\"main\"", "ToplevelCircuitName=\"other\""), board, flow))
    }

    @Test fun scanChannelsRoundTripWithoutTreatingTheLogicalIndexAsAPackagePin() {
        val gecko = assertIs<FpgaBoardImportResult.Success>(
            FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/GECKO5_EDUCATION.xml")).readText()),
        ).board
        val project = import(fixture(""))
        val matrix = gecko.resources.single { it.type == "LedArray" }
        val sevenSegment = gecko.resources.single { it.type == "SevenSegmentScanning" }
        val mapping = FpgaMapping(
            mapOf(
                FpgaSignalBit("p_y") to FpgaPinBinding.Display(matrix.id, 359),
                FpgaSignalBit("p_unused") to FpgaPinBinding.Display(sevenSegment.id, 31),
            ),
            emptyMap(),
        )
        val saved = assertIs<FpgaMappingResult.Saved>(
            mappingCodec(project).save(gecko, FpgaFlow.OPEN_ECP5, mapping),
        )
        val serialized = CircCodec.writeProject(project.withCircuit(saved.document))
        val reopened = import(serialized)
        assertTrue("""mobilescan="398_82_359" pmap="u"""" in serialized)
        assertTrue("""mobilescan="119_68_31" pmap="119_68_31"""" in serialized)
        val loaded = assertIs<FpgaMappingResult.Loaded>(
            mappingCodec(reopened).loadSaved(gecko, FpgaFlow.OPEN_ECP5),
        )
        assertEquals(mapping, loaded.mapping)
    }

    @Test fun evolutionOutputPinMappingDrivesAllThreePlanesOfOneRgbPixel() {
        val gecko = assertIs<FpgaBoardImportResult.Success>(
            FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/GECKO5_EDUCATION.xml")).readText()),
        ).board
        val blank = import(fixture(""))
        val xml = """
            <boardmap boardname="GECKO5_EDUCATION">
              <mc key="/A" vconst="1"/>
              <mc key="/Mode" vconst="0"/>
              <mc key="/Clk" vconst="0"/>
              <mc key="/Y" pmap="398_82_119"/>
              <mc key="/Unused" open="open"/>
            </boardmap>
        """.trimIndent()
        val codec = mappingCodec(blank)
        val flow = FpgaFlow.OPEN_ECP5
        val mapping = assertIs<FpgaMappingResult.Loaded>(codec.import(fixture(xml), gecko, flow)).mapping
        val saved = assertIs<FpgaMappingResult.Saved>(codec.save(gecko, flow, mapping))
        val serialized = CircCodec.writeProject(blank.withCircuit(saved.document))
        assertTrue("pmap=\"398_82_119\"" in serialized)
        val reopened = import(serialized)
        assertEquals(mapping, assertIs<FpgaMappingResult.Loaded>(mappingCodec(reopened).loadSaved(gecko, flow)).mapping)

        val design = HdlExporter.export(blank, HdlLanguage.VERILOG).fpgaDesign(HdlLanguage.VERILOG)
        val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design, gecko, mapping.bindings, flow)).plan
        val source = "design_p${design.ports.indexOfFirst { it.name == "p_y" }}"
        val wrapper = plan.files.single { it.path == "logisim_fpga_top.v" }.content
        for (channel in listOf(119, 239, 359)) assertTrue("assign fpga_scan_0_data[$channel] = $source;" in wrapper)

        val matrix = gecko.resources.single { it.type == "LedArray" }
        val collision = mapping.bindings + (FpgaSignalBit("p_unused") to FpgaPinBinding.Display(matrix.id, 239))
        val rejected = assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(design, gecko, collision, flow))
        assertTrue(rejected.diagnostics.any { it.code == "duplicate-display-channel" })
    }

    @Test fun aMissingMapIsToldApartFromAnUnreadableOneAndAnUnchangedMapIsNotRewritten() {
        val blank = mappingCodec(import(fixture("")))
        assertFalse(blank.hasSaved(board))
        assertIs<FpgaMappingResult.Failure>(blank.loadSaved(board, flow))

        val broken = mappingCodec(import(fixture(mappingXml.replace("649_295_0", "1_1_0"))))
        assertTrue(broken.hasSaved(board))
        assertIs<FpgaMappingResult.Failure>(broken.loadSaved(board, flow))

        val stored = import(fixture(mappingXml))
        val codec = mappingCodec(stored)
        assertTrue(codec.hasSaved(board))
        assertFalse(codec.hasSaved(board.copy(name = "OTHER")))
        // Save writes Mobile's form of the map; saving it again unchanged gives the same bytes.
        val loaded = assertIs<FpgaMappingResult.Loaded>(codec.loadSaved(board, flow)).mapping
        val saved = import(CircCodec.writeProject(stored.withCircuit(assertIs<FpgaMappingResult.Saved>(codec.save(board, flow, loaded)).document)))
        val reloaded = assertIs<FpgaMappingResult.Loaded>(mappingCodec(saved).loadSaved(board, flow)).mapping
        assertEquals(loaded, reloaded)
        val again = assertIs<FpgaMappingResult.Saved>(mappingCodec(saved).save(board, flow, reloaded))
        assertEquals(saved.mainCircuit.extraXml, again.document.extraXml)
        assertEquals(CircCodec.writeProject(saved), CircCodec.writeProject(saved.withCircuit(again.document)))
    }

    @Test fun derivedClockSettingsRoundTripWithoutChangingTheOscillator() {
        val blank = import(fixture(""))
        val original = assertIs<FpgaMappingResult.Loaded>(
            mappingCodec(blank).import(fixture(mappingXml), board, flow),
        ).mapping
        val scaled = original.copy(clockScales = mapOf("p_clk" to FpgaClockScale(64, 128)))
        val saved = assertIs<FpgaMappingResult.Saved>(mappingCodec(blank).save(board, flow, scaled))
        val reopened = import(CircCodec.writeProject(blank.withCircuit(saved.document)))
        val restored = assertIs<FpgaMappingResult.Loaded>(mappingCodec(reopened).loadSaved(board, flow)).mapping
        assertEquals(scaled, restored)
        assertEquals(100_000_000L, restored.clocks["p_clk"])
        assertEquals(50_000_000.0, restored.clockScales.getValue("p_clk").outputFrequencyHz(100_000_000))
    }
}
