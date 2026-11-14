package dev.logisim.mobile

import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.AppearanceEdits
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CompanionOperationCapability
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.HdlIpLibrary
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarItem
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model logic behind the W2-08 fixes: export scale steps and size, toolbar edits, readable names and summaries. */
class ProjectToolsUxModelTest {
    private fun LocalizedText.en() = resolve(AppLanguage.ENGLISH)
    private fun LocalizedText.pt() = resolve(AppLanguage.PORTUGUESE)

    // C104 ------------------------------------------------------------------------------------------------------------

    @Test
    fun exportScaleStepsAreEvolutionsSixthsOfAnOctave() {
        assertEquals(1.0, exportScaleForStep(0))
        assertEquals(2.0, exportScaleForStep(6))
        assertEquals(0.125, exportScaleForStep(-18))
        assertEquals(8.0, exportScaleForStep(18))
        assertEquals(8.0, exportScaleForStep(40), "Steps stop at 800%")
        assertEquals(6, exportStepForScale(2.0))
        assertEquals(-12, exportStepForScale(0.25))
        assertEquals(18, exportStepForScale(200.0), "A percent-sized number sits at the end")
        assertEquals("2", formatExportScale(2.0))
        assertEquals("0.25", formatExportScale(0.25))
        assertEquals("1.1225", formatExportScale(exportScaleForStep(1)))
        assertEquals(150, exportPercent(1.5).toInt())
        assertEquals(1.5, exportNumber(" 1,5 "))
        assertNull(exportNumber(""))
        assertNull(exportNumber("NaN"))
    }

    @Test
    fun theExportSizeIsTheFramesSize() {
        val bounds = VisualBounds(-3.0, 7.0, 117.4, 52.2)
        val scene = VisualScene(listOf(VisualItem("a", bounds, emptyList())))
        for ((scale, margin) in listOf(1.0 to 10.0, 0.25 to 10.0, 3.0 to 0.0, 1.1225 to 2.5)) {
            val frame = VisualExport.frame(scene, VisualExportOptions(scale = scale, margin = margin), raster = true)
            val size = VisualExport.pixelSize(bounds, scale, margin)
            assertEquals(frame.pixelWidth to frame.pixelHeight, size.width.toInt() to size.height.toInt())
            assertTrue(size.fitsRaster())
        }
        assertFalse(VisualExport.pixelSize(bounds, 200.0, 10.0).fitsRaster(), "200x of a 140 unit drawing is over 8 Mpx")
    }

    // X4 --------------------------------------------------------------------------------------------------------------

    @Test
    fun insertingAfterAnEntryWritesTheSameToolbarAsAppendingAndMovingUp() {
        val project = EditorState("""
            <project source="5.1.0dev" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="1" desc="#Gates"/>
              <toolbar><tool lib="1" name="AND Gate"/><tool lib="1" name="OR Gate"/><sep/><tool lib="0" name="Pin"/>
                <tool lib="1" name="NOT Gate"/><tool lib="0" name="Clock"/></toolbar>
              <circuit name="main"/>
            </project>""".trimIndent()).project
        val entries = ProjectToolbar.read(project)
        val tool = ProjectToolbarItem.Tool("#Memory", "D Flip-Flop")
        for (index in 0..entries.size) {
            val inserted = ProjectToolbarEdits.insert(entries, index, tool)
            var appended = entries + tool
            repeat(entries.size - index) { step -> appended = ProjectToolbarEdits.move(appended, appended.lastIndex - step, appended.lastIndex - step - 1) }
            assertEquals(inserted, appended)
            assertEquals(CircCodec.writeProject(ProjectToolbar.apply(project, appended)), CircCodec.writeProject(ProjectToolbar.apply(project, inserted)))
            assertEquals(tool, inserted[index])
        }
        assertEquals(entries.last(), ProjectToolbarEdits.move(entries, entries.lastIndex, 0).first())
        assertEquals(entries.first(), ProjectToolbarEdits.move(entries, 0, entries.lastIndex).last())
        assertEquals(entries.drop(1), ProjectToolbarEdits.remove(entries, 0))
    }

    // C101 ------------------------------------------------------------------------------------------------------------

    @Test
    fun appearanceObjectsAreNamedByTypeAndContent() {
        val state = EditorState("""
            <project source="5.1.0dev" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <circuit name="main">
                <comp lib="0" loc="(20,30)" name="Pin"><a name="type" val="input"/></comp>
                <comp lib="0" loc="(90,30)" name="Pin"><a name="type" val="output"/><a name="width" val="4"/><a name="label" val="SUM"/></comp>
              </circuit>
            </project>""".trimIndent())
        val loaded = SymbolAppearance.read(state.document)
        val appearance = loaded.copy(objects = listOf(
            SymbolObject.Shape("shape-1", "rect", mapOf("x" to "0", "y" to "0", "width" to "40", "height" to "30")),
            SymbolObject.Shape("shape-2", "text", mapOf("x" to "10", "y" to "20"), "ALU"),
            SymbolObject.Shape("shape-3", "ellipse", mapOf("cx" to "20", "cy" to "15", "rx" to "20", "ry" to "15")),
            SymbolObject.Shape("shape-4", "rect", mapOf("x" to "0", "y" to "0", "width" to "4", "height" to "3")),
            SymbolObject.Shape("shape-5", "text", mapOf("x" to "10", "y" to "20"), "A very long label for the ALU\nsecond line"),
        ))
        val names = AppearanceObjectNames.of(appearance, state.document).mapValues { it.value.en() }
        assertEquals(listOf("Rectangle 1", "Text “ALU”", "Ellipse 1", "Rectangle 2", "Text “A very long lab…”"), names.values.toList())
        assertTrue(names.values.none { Regex("shape-\\d+").containsMatchIn(it) })
        assertEquals("Retângulo 1", AppearanceObjectNames.of(appearance, state.document).getValue("shape-1").pt())
        val pins = state.document.components.associateBy { it.label }
        val (input, output) = pins.getValue("") to pins.getValue("SUM")
        assertEquals("Input pin (20, 30)", AppearanceObjectNames.port(loaded.ports.single { it.pinId == input.id }, state.document).en())
        assertEquals("SUM", AppearanceObjectNames.port(loaded.ports.single { it.pinId == output.id }, state.document).en())
        assertEquals("Missing pin", AppearanceObjectNames.port(loaded.ports.first().copy(pinId = "gone"), state.document).en())
        // Only the labels change: the file keeps the shape ids.
        val applied = AppearanceEdits.apply(state.project, state.document.name, appearance)
        assertTrue("id=\"shape-1\"" in CircCodec.writeProject(applied))
    }

    // C110 ------------------------------------------------------------------------------------------------------------

    @Test
    fun libraryNamesAndCompanionLabelsAreReadable() {
        val libraries = ComponentCatalog.registry.definitions.map { it.library }.distinct()
        assertTrue(libraries.isNotEmpty())
        for (library in libraries) assertFalse(builtinLibraryName(library).en().startsWith("#"), library)
        assertEquals("Gates", builtinLibraryName("#Gates").en())
        assertEquals("Annotations", builtinLibraryName("#Base").en())
        assertEquals("Seletores", builtinLibraryName("#Plexers").pt())
        assertEquals("4 MiB", byteSizeText(4_194_304).en())
        assertEquals("1.5 KiB", byteSizeText(1_536).en())
        assertEquals("1,5 KiB", byteSizeText(1_536).pt())
        assertEquals("512 bytes", byteSizeText(512).en())
        assertEquals("1 byte", byteSizeText(1).en())
        assertEquals("879 KiB", byteSizeText(900_000).en())
        fun operation(name: String, description: String = "") = CompanionOperationCapability(name, true, true, emptyList(), emptyList(), false, description)
        assertEquals("Synthesize HDL with Yosys", companionOperationLabel(operation("fpga-synthesize")).en())
        assertEquals("Start a VHDL or BLIF simulation", companionOperationLabel(operation("hdl.start")).en())
        assertEquals("Host-specific tool", companionOperationLabel(operation("future.tool", "Host-specific tool")).en())
        assertEquals("future.tool", companionOperationLabel(operation("future.tool")).en())
    }

    // C12 -------------------------------------------------------------------------------------------------------------

    @Test
    fun largeValuesAreExternalWithSummaries() {
        val registry = ComponentCatalog.registry
        fun field(component: CircuitComponent, name: String) = PropertyEditorModel.fields(registry, listOf(component)).single { it.name == name }
        val rom = CircuitComponent("rom", ComponentKind.ROM, GridPoint(0, 0),
            attributes = mapOf("addrWidth" to "8", "dataWidth" to "8", "contents" to "addr/data: 8 8\n1 2 3 4\n"))
        val pla = CircuitComponent("pla", GatesLibrary.Pla, GridPoint(0, 0), attributes = mapOf("in_width" to "2", "out_width" to "1", "table" to "11 1\n00 1\n01 0\n"))
        val vhdl = CircuitComponent("vhdl", HdlIpLibrary.VHDL, GridPoint(0, 0), attributes = mapOf("content" to
            "library ieee;\nuse ieee.std_logic_1164.all;\nentity e is port(a : in std_logic; y : out std_logic); end e;\n\narchitecture r of e is begin y <= a; end r;\n"))
        val image = CircuitComponent("img", AnnotationLibrary.Image, GridPoint(0, 0),
            attributes = mapOf("image" to "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4z8DwHwgbABB5A359Y87XAAAAAElFTkSuQmCC"))
        val cases = listOf(Triple(rom, "contents", "256 words × 8 bits"), Triple(pla, "table", "3 lines"),
            Triple(image, "image", "PNG image, 72 bytes"))
        for ((component, name, summary) in cases) {
            val shown = field(component, name)
            assertEquals(PropertyInputKind.EXTERNAL, shown.kind, name)
            assertEquals(summary, shown.localizedValue().en(), name)
            assertFalse(shown.localizedValue().en().contains(component.attributes.getValue(name).take(12)), name)
        }
        val source = field(vhdl, "content")
        assertEquals(PropertyInputKind.EXTERNAL, source.kind)
        assertEquals("4 lines", source.localizedValue().en())
        assertEquals("hdl-simulation", externalPropertyEditor(source, listOf(vhdl))?.toolId)
        assertEquals("memory-editor", externalPropertyEditor(field(rom, "contents"), listOf(rom))?.toolId)
        assertEquals("annotations", externalPropertyEditor(field(image, "image"), listOf(image))?.toolId)
        // The PLA table has no dedicated editor here: its row opens the text editor with Export and Import.
        assertNull(externalPropertyEditor(field(pla, "table"), listOf(pla)))
        // The raw value is still validated and stored as before.
        assertTrue(PropertyEditorModel.validate(registry, listOf(rom), "contents", "addr/data: 8 8\n5\n") is PropertyEditResult.Valid)
    }

    // X5 --------------------------------------------------------------------------------------------------------------

    @Test
    fun sliderStopsAtEachOutputValueAndTheJoystickHasDiagonals() {
        assertEquals(2, extraSliderSteps(2))
        assertEquals(0, extraSliderSteps(1))
        assertEquals(62, extraSliderSteps(6))
        assertEquals(0, extraSliderSteps(8))
        // Each stop is a position whose output value is that stop: positions 0, 85, 170, 255 for 2 bits.
        assertEquals(listOf(0, 85, 170, 255), listOf(10f, 90f, 160f, 250f).map { extraSliderPosition(it, 2) })
        for (width in 1..8) {
            val top = (1 shl width) - 1
            for (level in 0..top) {
                val position = extraSliderPosition(level * 255f / top, width)
                assertEquals(level.toULong(), dev.logisim.mobile.core.ExtraSliderState(position).value(width).value, "width $width level $level")
            }
        }
        assertEquals(9, JOYSTICK_DIRECTIONS.map { it.x to it.y }.toSet().size)
        assertTrue((-14 to -14) in JOYSTICK_DIRECTIONS.map { it.x to it.y })
    }
}
