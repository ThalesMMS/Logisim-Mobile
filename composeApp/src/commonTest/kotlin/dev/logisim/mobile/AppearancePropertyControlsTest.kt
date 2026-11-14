package dev.logisim.mobile

import dev.logisim.mobile.AppearanceShapeControls.HorizontalAlign
import dev.logisim.mobile.AppearanceShapeControls.VerticalAlign
import dev.logisim.mobile.core.AppearanceEdits
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppearancePropertyControlsTest {
    private val rect = mapOf("x" to "0", "y" to "0", "width" to "40", "height" to "30", "fill" to "none", "stroke" to "#000000")
    private val evolutionText = mapOf("dominant-baseline" to "central", "font-family" to "SansSerif", "font-size" to "12",
        "text-anchor" to "middle", "x" to "81", "y" to "83")

    @Test
    fun strokeWidthIsOneOfEvolutionsEightWidths() {
        assertEquals((1..8).map(Int::toString), AppearanceShapeControls.strokeWidths(null))
        assertEquals("3", AppearanceShapeControls.withStrokeWidth(rect, "3")["stroke-width"])
        // A missing width reads as 1 and choosing 1 writes nothing.
        assertSame(rect, AppearanceShapeControls.withStrokeWidth(rect, "1"))
        // A width an imported file carries is offered as is, not coerced.
        assertEquals((1..8).map(Int::toString) + "1.5", AppearanceShapeControls.strokeWidths("1.5"))
        assertEquals((1..8).map(Int::toString) + "12", AppearanceShapeControls.strokeWidths("12"))
        val loaded = rect + ("stroke-width" to "1.5")
        assertSame(loaded, AppearanceShapeControls.withStrokeWidth(loaded, "1.5"))
    }

    @Test
    fun alignmentWritesSvgCreatorValuesOnlyWhenChanged() {
        assertEquals("end", AppearanceShapeControls.withHorizontal(evolutionText, HorizontalAlign.RIGHT)["text-anchor"])
        assertEquals("start", AppearanceShapeControls.withHorizontal(evolutionText, HorizontalAlign.LEFT)["text-anchor"])
        assertEquals("alphabetic", AppearanceShapeControls.withVertical(evolutionText, VerticalAlign.BASELINE)["dominant-baseline"])
        assertEquals("top", AppearanceShapeControls.withVertical(evolutionText, VerticalAlign.TOP)["dominant-baseline"])
        assertSame(evolutionText, AppearanceShapeControls.withHorizontal(evolutionText, HorizontalAlign.CENTER))
        assertSame(evolutionText, AppearanceShapeControls.withVertical(evolutionText, VerticalAlign.MIDDLE))
        // A shape without the attributes is drawn centered, as SvgReader reads it, and stays without them.
        val plain = mapOf("x" to "10", "y" to "20")
        assertEquals(HorizontalAlign.CENTER, AppearanceShapeControls.horizontal(plain))
        assertEquals(VerticalAlign.MIDDLE, AppearanceShapeControls.vertical(plain))
        assertSame(plain, AppearanceShapeControls.withHorizontal(plain, HorizontalAlign.CENTER))
        assertFalse("dominant-baseline" in AppearanceShapeControls.withVertical(plain, VerticalAlign.MIDDLE))
    }

    @Test
    fun fontsKeepAnUnknownFamilyAndWriteWholeSizes() {
        val desktop = evolutionText + ("font-family" to "DejaVu Sans") + ("font-size" to "12.5")
        assertEquals("DejaVu Sans", AppearanceShapeControls.fontFamily(desktop))
        assertSame(desktop, AppearanceShapeControls.withFontFamily(desktop, "DejaVu Sans"))
        assertTrue("DejaVu Sans" in FontChoice.families(AppearanceShapeControls.fontFamily(desktop)))
        assertEquals("Serif", AppearanceShapeControls.withFontFamily(desktop, "Serif")["font-family"])
        assertEquals(13, AppearanceShapeControls.fontSize(desktop))
        assertEquals("14", AppearanceShapeControls.withFontSize(desktop, 14)["font-size"])
        assertSame(evolutionText, AppearanceShapeControls.withFontSize(evolutionText, 12))
        assertEquals("bold", AppearanceShapeControls.withStyle(evolutionText, evolutionText, "font-weight", true)["font-weight"])
        // Turning a style off removes an attribute the shape did not have, and writes "normal" over one it had.
        val bold = AppearanceShapeControls.withStyle(evolutionText, evolutionText, "font-weight", true)
        assertEquals(evolutionText, AppearanceShapeControls.withStyle(bold, evolutionText, "font-weight", false))
        val italic = evolutionText + ("font-style" to "italic")
        assertEquals("normal", AppearanceShapeControls.withStyle(italic, italic, "font-style", false)["font-style"])
    }

    @Test
    fun paintAlphaIsTheOpacityAndIsWrittenOnlyWhenMoved() {
        val shown = AppearanceShapeControls.paint(rect, "stroke", "rect")!!
        assertEquals(ColorPickerModel(0x000000), shown)
        assertNull(AppearanceShapeControls.paint(rect, "fill", "rect"))
        // A new color with the slider untouched leaves the opacity attribute alone.
        val red = AppearanceShapeControls.withPaint(rect, "stroke", shown, ColorPickerModel(0xf00000))
        assertEquals(rect + ("stroke" to "#f00000"), red)
        assertSame(rect, AppearanceShapeControls.withPaint(rect, "stroke", shown, ColorPickerModel(0x000000, 0xFF)))
        assertEquals("0.5", AppearanceShapeControls.withPaint(rect, "stroke", shown, ColorPickerModel(0x000000, 128))["stroke-opacity"])
        // A loaded opacity is kept when only the color changes.
        val translucent = rect + ("stroke-opacity" to "0.3")
        val loaded = AppearanceShapeControls.paint(translucent, "stroke", "rect")!!
        assertEquals("0.3", AppearanceShapeControls.withPaint(translucent, "stroke", loaded, loaded.copy(rgb = 0x00ff00))["stroke-opacity"])
        assertEquals(listOf("1", "0", "0.5", "0.25", "0.75", "0.1"), listOf(255, 0, 128, 64, 191, 26).map(AppearanceShapeControls::opacity))
        assertEquals(ColorPickerModel(0xff0000), AppearanceShapeControls.paint(mapOf("fill" to "red"), "fill", "rect"))
    }

    @Test
    fun turningAPaintBackOnRestoresItsLastColor() {
        val off = AppearanceShapeControls.withPaintEnabled(rect + ("stroke" to "#ff8800"), "stroke", "rect", false, null)
        assertEquals("none", off["stroke"])
        assertEquals("#ff8800", AppearanceShapeControls.withPaintEnabled(off, "stroke", "rect", true, "#ff8800")["stroke"])
        // Without a remembered color the fill takes the outline's color, then the shape's default ink.
        assertEquals("#000000", AppearanceShapeControls.withPaintEnabled(rect, "fill", "rect", true, null)["fill"])
        assertEquals("#123456", AppearanceShapeControls.withPaintEnabled(rect + ("stroke" to "#123456"), "fill", "rect", true, null)["fill"])
    }

    @Test
    fun untouchedControlsKeepAnEvolutionAppearanceByteIdentical() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <a name="appearance" val="custom"/>
    <appear>
      <rect fill="none" height="80" stroke="#000000" stroke-width="2" width="60" x="50" y="50"/>
      <text dominant-baseline="central" font-family="SansSerif" font-size="12" text-anchor="middle" x="81" y="83">Contador</text>
      <circ-anchor facing="east" x="110" y="90"/>
      <circ-port dir="in" pin="90,100" x="50" y="60"/>
    </appear>
    <comp lib="0" loc="(90,100)" name="Pin"/>
  </circuit>
</project>
"""
        val project = (CircCodec.readProject(xml) as CircProjectImportResult.Success).project
        val circuit = project.circuits.single()
        val appearance = SymbolAppearance.read(circuit)
        // Every control applied with the value it shows changes nothing.
        val edited = appearance.objects.filterIsInstance<SymbolObject.Shape>().fold(appearance) { draft, shape ->
            var attributes = shape.attributes
            attributes = AppearanceShapeControls.withStrokeWidth(attributes, AppearanceShapeControls.strokeWidth(attributes))
            attributes = AppearanceShapeControls.withHorizontal(attributes, AppearanceShapeControls.horizontal(attributes))
            attributes = AppearanceShapeControls.withVertical(attributes, AppearanceShapeControls.vertical(attributes))
            if (shape.tag == "text") {
                attributes = AppearanceShapeControls.withFontFamily(attributes, AppearanceShapeControls.fontFamily(attributes))
                attributes = AppearanceShapeControls.withFontSize(attributes, AppearanceShapeControls.fontSize(attributes))
            }
            for (key in listOf("fill", "stroke")) AppearanceShapeControls.paint(attributes, key, shape.tag)?.let {
                attributes = AppearanceShapeControls.withPaint(attributes, key, it, it)
            }
            assertEquals(shape.attributes, attributes)
            draft.editShape(shape.id, attributes, shape.text)
        }
        assertEquals(appearance, edited)
        // Applying re-serializes the <appear> element (AppearanceEdits); untouched controls add nothing to it. Without
        // Apply the file is not touched at all (PickersHostTest.evolutionAppearanceRoundTripsAfterOpeningTheEditor).
        assertEquals(CircCodec.writeProject(AppearanceEdits.apply(project, circuit.name, appearance)),
            CircCodec.writeProject(AppearanceEdits.apply(project, circuit.name, edited)))
    }

    @Test
    fun theDraftHolderKeepsAnUnappliedDraftForItsCircuitOnly() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <a name="appearance" val="custom"/>
    <appear>
      <rect fill="none" height="80" stroke="#000000" stroke-width="2" width="60" x="50" y="50"/>
      <circ-anchor facing="east" x="110" y="90"/>
      <circ-port dir="in" pin="90,100" x="50" y="60"/>
    </appear>
    <comp lib="0" loc="(90,100)" name="Pin"/>
  </circuit>
</project>
"""
        val circuit = (CircCodec.readProject(xml) as CircProjectImportResult.Success).project.circuits.single()
        val other = circuit.copy(name = "other", id = "other")
        val loaded = Result.success(SymbolAppearance.read(circuit))
        val otherLoaded = Result.success(SymbolAppearance.read(other))
        val shape = loaded.getOrThrow().objects.filterIsInstance<SymbolObject.Shape>().single()
        val edited = loaded.getOrThrow().removeShape(shape.id)
        val holder = AppearanceDraftHolder()
        holder.sync(1, circuit, loaded)
        assertFalse(holder.dirty)
        holder.draft = edited
        holder.selectedShapes = setOf(shape.id)
        holder.clipboard.value = listOf(shape)
        assertTrue(holder.dirty)
        // Reopening the same circuit of the same document keeps the unapplied draft and its selection.
        holder.sync(1, circuit, loaded)
        assertEquals(edited, holder.draft)
        assertEquals(setOf(shape.id), holder.selectedShapes)
        // Another circuit starts from its own loaded appearance; the clipboard is kept for pasting there.
        holder.sync(1, other, otherLoaded)
        assertFalse(holder.dirty)
        assertEquals(otherLoaded.getOrThrow(), holder.draft)
        assertEquals(emptySet(), holder.selectedShapes)
        assertEquals(listOf(shape), holder.clipboard.value)
        // So does the same circuit in another open document.
        holder.draft = edited
        holder.sync(2, other, otherLoaded)
        assertFalse(holder.dirty)
        // Discard drops a dirty draft for the loaded appearance; a failed load keeps its error and no draft.
        holder.draft = edited
        holder.discard(2, other, otherLoaded)
        assertEquals(otherLoaded.getOrThrow(), holder.draft)
        holder.sync(3, other, Result.failure(IllegalArgumentException("unreadable")))
        assertNull(holder.draft)
        assertEquals("unreadable", holder.error)
    }
}
