package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.CreationDefaults
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearancePreferencesTest {
    @Test
    fun defaultsMatchEvolutionAppPreferences() {
        val defaults = EditorPreferences()
        assertEquals(EditorThemeMode.SYSTEM, defaults.themeMode) // Main enables FollowSystemTheme on new installs.
        assertEquals(HdlEditorTheme.DEFAULT, defaults.lightEditorTheme)
        assertEquals(HdlEditorTheme.DARK, defaults.darkEditorTheme)
        assertTrue(defaults.attributeHalo)
        assertTrue(defaults.componentTips)
        assertEquals(EditorPinAppearance.DOT_SMALL, defaults.pinAppearance)
        assertEquals(false, defaults.kmapLinedStyle)
        assertTrue(defaults.appearanceShowGrid)
        assertEquals(EditorDefaultAppearance.EVOLUTION, defaults.defaultAppearance)
        assertTrue(defaults.newInputOutputShapes)
        assertTrue(defaults.namedCircuitBoxesFixedSize)
        assertEquals(false, defaults.removeUnusedLibraries)
        assertEquals(listOf("default", "dark", "monokai", "eclipse", "idea", "vs", "druid"), HdlEditorTheme.entries.map { it.key })
        assertEquals(listOf("Default", "Dark", "Monokai", "Eclipse", "IDEA", "Visual Studio", "Druid"), HdlEditorTheme.entries.map { it.label })
        assertEquals(listOf(4, 6, 8, 10), EditorPinAppearance.entries.map { it.diameter })
    }

    @Test
    fun everyNewPreferencePersistsAndInvalidValuesFallBack() {
        val changed = EditorPreferences(
            themeMode = EditorThemeMode.DARK, lightEditorTheme = HdlEditorTheme.IDEA, darkEditorTheme = HdlEditorTheme.MONOKAI,
            darkColors = mapOf(EditorColor.CANVAS to 0x101010, EditorColor.SIGNAL_HIGH to 0x00AA00),
            colors = mapOf(EditorColor.CANVAS to 0xFAFAFA),
            attributeHalo = false, componentTips = false, pinAppearance = EditorPinAppearance.DOT_BIG, kmapLinedStyle = true,
            appearanceShowGrid = false, defaultAppearance = EditorDefaultAppearance.CLASSIC, newInputOutputShapes = false,
            namedCircuitBoxesFixedSize = false, removeUnusedLibraries = true,
        )
        val encoded = EditorPreferencesCodec.write(changed)
        assertTrue("pinAppearance=dot-big" in encoded)
        assertTrue("defaultAppearance=classic" in encoded)
        assertTrue("darkEditorTheme=monokai" in encoded)
        val read = EditorPreferencesCodec.read(encoded)
        assertEquals(changed, read.preferences)
        assertTrue(read.notices.isEmpty())
        assertEquals(emptyMap(), read.preferences.extraValues)

        val broken = EditorPreferencesCodec.read(encoded.replace("pinAppearance=dot-big", "pinAppearance=square")
            .replace("themeMode=DARK", "themeMode=NEON").replace("attributeHalo=false", "attributeHalo=maybe"))
        assertEquals(changed.copy(pinAppearance = EditorPinAppearance.DOT_SMALL, themeMode = EditorThemeMode.SYSTEM, attributeHalo = true),
            broken.preferences)
        assertEquals(3, broken.notices.size)
        // Files written before these options read as Evolution's defaults.
        assertEquals(EditorPreferences(), EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nshowGrid=true\n").preferences)
    }

    @Test
    fun themeModeSelectsColorsAndEditorThemePerMode() {
        val preferences = EditorPreferences(colors = mapOf(EditorColor.CANVAS to 0xFFFFF0), darkColors = mapOf(EditorColor.SIGNAL_HIGH to 0x11EE11))
        assertEquals(true, EditorPreferences().isDark(systemDark = true))
        assertEquals(false, EditorPreferences().isDark(systemDark = false))
        assertEquals(false, EditorPreferences(themeMode = EditorThemeMode.LIGHT).isDark(systemDark = true))
        assertEquals(true, EditorPreferences(themeMode = EditorThemeMode.DARK).isDark(systemDark = false))
        assertEquals(mapOf(EditorColor.CANVAS to 0xFFFFF0), preferences.effectiveColors(dark = false))
        val dark = preferences.effectiveColors(dark = true)
        assertEquals(0x2B2B2B, dark[EditorColor.CANVAS]) // DARK_CANVAS_BG_COLOR
        assertEquals(0xFFFFFF, dark[EditorColor.COMPONENT]) // DARK_COMPONENT_COLOR
        assertEquals(0x11EE11, dark[EditorColor.SIGNAL_HIGH]) // the user's dark-theme color wins
        assertEquals(0xBBBBBB, dark[EditorColor.SIGNAL_FLOATING]) // DARK_NIL_COLOR
        val edited = preferences.withCustomColors(dark = true, mapOf(EditorColor.GRID_DOT to 0x777777))
        assertEquals(preferences.colors, edited.colors)
        assertEquals(mapOf(EditorColor.GRID_DOT to 0x777777), edited.darkColors)
        val themed = EditorPreferences().withEditorTheme(dark = true, HdlEditorTheme.DRUID)
        assertEquals(HdlEditorTheme.DRUID, themed.editorTheme(dark = true))
        assertEquals(HdlEditorTheme.DEFAULT, themed.editorTheme(dark = false))
    }

    @Test
    fun creationDefaultsFollowLayoutPreferences() {
        assertEquals(CreationDefaults(), EditorPreferences().creationDefaults)
        assertEquals(CreationDefaults("classic", "evolution", newInputOutputShapes = false, namedCircuitBoxesFixedSize = false,
            removeUnusedLibraries = true), EditorPreferences(defaultAppearance = EditorDefaultAppearance.FPGA, newInputOutputShapes = false,
            namedCircuitBoxesFixedSize = false, removeUnusedLibraries = true).creationDefaults)
        assertEquals("classic", EditorPreferences(defaultAppearance = EditorDefaultAppearance.CLASSIC).creationDefaults.circuitAppearance)
    }

    @Test
    fun editorPlacementAndNewCircuitsUseCreationDefaults() {
        val state = EditorState(null)
        fun placeAt(kind: ComponentKind, point: GridPoint) = run {
            val before = state.document.components.map { it.id }.toSet()
            state.place(kind)
            state.tap(point, EditorMode.PLACE)
            state.document.components.single { it.id !in before }
        }
        state.creationDefaults = EditorPreferences(defaultAppearance = EditorDefaultAppearance.CLASSIC, newInputOutputShapes = false,
            namedCircuitBoxesFixedSize = false).creationDefaults
        val classicFlipFlop = placeAt(ComponentKind.D_FLIP_FLOP, GridPoint(600, 600))
        assertEquals("classic", classicFlipFlop.attributes["appearance"])
        assertEquals("classic", placeAt(ComponentKind.INPUT, GridPoint(500, 800)).attributes["appearance"])
        assertTrue(state.createCircuit("child"))
        val child = state.project.circuits.first { it.name == "child" }
        assertEquals("classic", child.attributes["appearance"])
        assertEquals("false", child.attributes["circuitnamedboxfixedsize"])

        state.creationDefaults = EditorPreferences().creationDefaults
        assertEquals("logisim_evolution", placeAt(ComponentKind.D_FLIP_FLOP, GridPoint(300, 100)).attributes["appearance"])
        assertEquals("NewPins", placeAt(ComponentKind.INPUT, GridPoint(100, 300)).attributes["appearance"])
    }

    @Test
    fun pinDotsScaleLikeComponentDrawContext() {
        assertEquals(ClassicTerminalRadius, classicTerminalRadius(4))
        listOf(2.0, 3.0, 4.0, 5.0).zip(EditorPinAppearance.entries).forEach { (radius, pin) -> assertTrue(abs(radius - classicTerminalRadius(pin.diameter)) < 1e-9) }
        assertEquals(ClassicTerminalRadius * 2, CircuitDrawingStyle(pinDotDiameter = 8).terminalRadius)
        val pin = ComponentCatalog.registry.create(ComponentKind.AND, "g", GridPoint(100, 100))
        val markers = circuitDrawingTerminalMarkers(pin, dev.logisim.mobile.core.SimulationState(), ComponentCatalog.registry, false, radius = 4.0)
        assertTrue(markers.all { abs(it.bounds.right - it.bounds.left - 8.0) < 1e-9 })
    }

    @Test
    fun darkThemeLabelsWithoutColorUseEvolutionsDarkDefaultLabelColor() {
        // StdAttr.getDefaultLabelColor(): 0x6CB6FF in the dark theme; an explicit labelcolor still wins.
        val pin = ComponentCatalog.registry.create(ComponentKind.INPUT, "a", GridPoint(100, 100)).copy(label = "A")
        val bounds = Rect(90f, 90f, 110f, 110f)
        fun color(style: CircuitDrawingStyle, component: dev.logisim.mobile.core.CircuitComponent = pin) =
            assertNotNull(classicComponentLabel(component, bounds, style.defaultLabelColor)).color
        assertEquals(0xFF0000FFu, color(CircuitDrawingStyle()))
        assertEquals(0xFF6CB6FFu, color(CircuitDrawingStyle(darkTheme = true)))
        assertEquals(0xFF0000FFu, color(CircuitDrawingStyle(darkTheme = true, printerView = true)))
        val red = pin.copy(attributes = pin.attributes + ("labelcolor" to "#ff0000"))
        assertEquals(0xFFFF0000u, color(CircuitDrawingStyle(darkTheme = true), red))
    }

    @Test
    fun haloIsTheEllipseThroughTheExpandedBoundsCorners() {
        val oval = attributeHaloOval(Rect(0f, 0f, 30f, 20f))
        assertEquals(Offset(15f, 10f), oval.center)
        assertTrue(abs(oval.width - 40f * sqrt(2f)) < 1e-3)
        assertTrue(abs(oval.height - 30f * sqrt(2f)) < 1e-3)
    }

    @Test
    fun componentTipsDescribeThePortUnderThePointer() {
        val gate = ComponentCatalog.registry.create(ComponentKind.D_FLIP_FLOP, "ff", GridPoint(100, 100))
        val document = CircuitDocument("main", listOf(gate))
        val port = gate.layout(ComponentCatalog.registry).ports.first()
        val tip = assertNotNull(document.componentTipAt(Offset(port.position.x + 3f, port.position.y.toFloat()), ComponentCatalog.registry))
        assertEquals(port, tip.port)
        val text = assertNotNull(tip.text()).resolve(AppLanguage.ENGLISH)
        assertTrue(text.endsWith(": " + port.label.ifBlank { port.name }), text)
        assertNull(document.componentTipAt(Offset(-500f, -500f), ComponentCatalog.registry))
        // Away from a port Evolution shows no tip; the long-press menu still names the component.
        val bounds = gate.layout(ComponentCatalog.registry).bounds
        val inside = document.componentTipAt(Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f), ComponentCatalog.registry)
        if (inside?.port == null) {
            assertNull(inside?.text())
            assertNotNull(inside?.text(includeComponentOnly = true))
        }
    }

    @Test
    fun hdlThemesColorTokensFromTheReferenceThemes() {
        val source = "-- note\nentity top is port (a : in std_logic); end top; x <= \"10\"; y <= 42;"
        val highlighted = highlightHdl(source, HdlEditorTheme.DEFAULT.palette)
        fun colorAt(text: String) = highlighted.spanStyles.last { it.start <= source.indexOf(text) && source.indexOf(text) < it.end }.item
        assertEquals(androidx.compose.ui.graphics.Color(0xFF008000), colorAt("note").color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0000FF), colorAt("entity").color)
        assertEquals(FontWeight.Bold, colorAt("entity").fontWeight)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF008080), colorAt("std_logic").color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFDC009C), colorAt("\"10\"").color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF6400C8), colorAt("42").color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF293134), HdlEditorTheme.DARK.palette.background)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF001C00), HdlEditorTheme.DRUID.palette.background)
    }

    @Test
    fun appearanceGridDotsFollowTheTenUnitGrid() {
        val points = appearanceGridPoints(Offset(5f, 5f), zoom = 2f, width = 60f, height = 40f)
        assertEquals(Offset(5f, 5f), points.first())
        assertTrue(points.all { abs((it.x - 5f) % 20f) < 1e-3 && abs((it.y - 5f) % 20f) < 1e-3 })
        assertEquals(3 * 2, points.size)
        assertTrue(appearanceGridPoints(Offset.Zero, zoom = 0.2f, width = 100f, height = 100f).isEmpty())
    }
}
