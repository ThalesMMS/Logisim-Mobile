package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SymbolAppearanceTest {
    private fun originalCustomProject() = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
        <project source="2.7.1" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main"><comp name="child" loc="(300,140)"/></circuit>
          <circuit name="child">
            <appear>
              <rect height="40" stroke="none" width="40" x="50" y="50"/>
              <circ-port height="8" pin="100,100" width="8" x="46" y="66"/>
              <circ-port height="10" pin="300,100" width="10" x="85" y="65"/>
              <circ-anchor facing="east" height="6" width="6" x="87" y="67"/>
            </appear>
            <comp lib="0" name="Pin" loc="(100,100)"/>
            <comp lib="0" name="Pin" loc="(300,100)"><a name="output" val="true"/></comp>
          </circuit>
        </project>
    """.trimIndent())).project

    @Test fun namedDefaultsKeepDesktopTerminalsAndWiresThroughRoundTrip() {
        for ((attributes, inputX, spacing) in listOf(
            Triple("""<a name="appearance" val="logisim_evolution"/>""", 160, 20),
            Triple("""<a name="appearance" val="evolution"/>""", 200, 10),
            Triple("""<a name="circuitnamedbox" val="true"/><a name="circuitnamedboxfixedsize" val="true"/>""", 80, 20),
        )) {
            val xml = """
                <project source="5.1.0dev" version="1.0">
                  <lib name="0" desc="#Wiring"/><main name="main"/>
                  <circuit name="main">
                    <comp name="Child" loc="(300,200)"/>
                    <comp lib="0" name="Pin" loc="(20,200)"><a name="label" val="A"/></comp>
                    <comp lib="0" name="Pin" loc="(350,200)">
                      <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
                    </comp>
                    <wire from="(20,200)" to="($inputX,200)"/><wire from="(300,200)" to="(350,200)"/>
                  </circuit>
                  <circuit name="Child">
                    $attributes
                    <comp lib="0" name="Pin" loc="(50,100)"><a name="label" val="Beta"/><a name="facing" val="north"/></comp>
                    <comp lib="0" name="Pin" loc="(50,50)"><a name="label" val="Alpha"/></comp>
                    <comp lib="0" name="Pin" loc="(150,50)">
                      <a name="label" val="Result"/><a name="type" val="output"/><a name="facing" val="west"/>
                    </comp>
                    <wire from="(50,50)" to="(150,50)"/>
                  </circuit>
                </project>
            """.trimIndent()
            val runner = ExampleRunner()
            val input = ExampleProbe("Y", GridPoint(350, 200), 1)
            fun verify(source: String): CircuitProject {
                var session = runner.initialize(CircuitExample("named", "Named", "", source, listOf(input), emptyList()))
                val instance = session.project.mainCircuit.components.first()
                assertEquals(listOf(GridPoint(inputX, 200), GridPoint(inputX, 200 + spacing)), instance.inputPorts())
                assertEquals(listOf(GridPoint(300, 200)), instance.outputPorts())
                val child = session.project.circuit("Child")!!
                val appearance = requireNotNull(SymbolAppearance.rendered(child))
                val body = appearance.objects.filterIsInstance<SymbolObject.Shape>().single { it.id == "body" }
                assertEquals(if (spacing == 20) 280 - inputX else 300 - inputX, SymbolAppearance.shapeBounds(body).width)
                val south = instance.copy(attributes = instance.attributes + ("facing" to "south"))
                assertEquals(listOf(GridPoint(300, inputX - 100), GridPoint(300 - spacing, inputX - 100)), south.inputPorts())
                for (value in listOf("0", "1")) {
                    session = runner.advance(session, ExampleAction("A=$value", mapOf("A" to value)))
                    assertEquals(value, runner.observe(session)["Y"])
                }
                return session.project
            }
            val exported = CircCodec.writeProject(verify(xml))
            assertTrue("<appear>" !in exported)
            verify(exported)
        }
    }

    @Test fun originalMarkerBoundsPreserveCentersAndDimensionsAfterPortEdits() {
        val project = originalCustomProject()
        val child = project.circuit("child")!!
        val appearance = SymbolAppearance.read(child)
        assertEquals(SymbolAnchor(GridPoint(90, 70), markerSize = SymbolMarkerSize(6.0, 6.0)), appearance.anchor)
        assertEquals(listOf(GridPoint(50, 70), GridPoint(90, 70)), appearance.ports.map { it.position })
        assertEquals(listOf(SymbolMarkerSize(8.0, 8.0), SymbolMarkerSize(10.0, 10.0)), appearance.ports.map { it.markerSize })
        assertEquals(listOf(GridPoint(260, 140)), project.mainCircuit.components.single().inputPorts())
        assertEquals(listOf(GridPoint(300, 140)), project.mainCircuit.components.single().outputPorts())
        val unchanged = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project))).project
        assertEquals(child.extraXml, unchanged.circuit("child")!!.extraXml)

        val moved = appearance.movePort(child.components.first().id, GridPoint(50, 80))
        val edited = AppearanceEdits.apply(project, "child", moved)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(edited))).project
        val reopenedAppearance = SymbolAppearance.read(reopened.circuit("child")!!)
        assertEquals(moved.anchor, reopenedAppearance.anchor)
        assertEquals(moved.ports, reopenedAppearance.ports)
        assertEquals(listOf(GridPoint(260, 150)), reopened.mainCircuit.components.single().inputPorts())
        assertEquals(listOf(GridPoint(300, 140)), reopened.mainCircuit.components.single().outputPorts())
    }

    @Test fun movingOriginalPinPreservesItsBoundedAppearanceMarker() {
        val project = originalCustomProject()
        val child = project.circuit("child")!!
        val original = SymbolAppearance.read(child)
        val updated = child.copy(components = child.components.mapIndexed { index, pin ->
            if (index == 0) pin.copy(position = GridPoint(100, 110)) else pin
        })
        val edited = AppearanceEdits.reconcileCircuit(project, child, updated)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(edited))).project
        val reopenedAppearance = SymbolAppearance.read(reopened.circuit("child")!!)
        assertEquals(original.anchor, reopenedAppearance.anchor)
        assertEquals(original.ports, reopenedAppearance.ports)
        assertTrue(CircCodec.writeProject(edited).contains("pin=\"100,110\""))
        assertEquals(project.mainCircuit.components.single().explicitPorts, reopened.mainCircuit.components.single().explicitPorts)
    }

    @Test fun originalFractionalMarkerBoundsRoundCentersLikeJava() {
        val circuit = child().copy(extraXml = listOf("""<appear>
            <circ-port x="46.25" y="-54.75" width="8.5" height="8.5" pin="20,30"/>
            <circ-port x="85" y="65" width="10" height="10" pin="90,30"/>
            <circ-anchor x="87.5" y="67.5" width="6" height="6"/>
        </appear>"""))
        val appearance = SymbolAppearance.read(circuit)
        assertEquals(GridPoint(51, -50), appearance.ports.first().position)
        assertEquals(GridPoint(91, 71), appearance.anchor.position)
        val serialized = AppearanceEdits.xml(appearance, circuit)
        assertEquals(appearance, SymbolAppearance.read(circuit.copy(extraXml = listOf(serialized))))
    }

    @Test fun addingPinsToOriginalAppearanceUsesOriginalMarkerDimensions() {
        val project = originalCustomProject()
        val child = project.circuit("child")!!
        val updated = child.copy(components = child.components + listOf(
            CircuitComponent("extra-input", ComponentKind.INPUT, GridPoint(100, 120)),
            CircuitComponent("extra-output", ComponentKind.OUTPUT, GridPoint(300, 120)),
        ))
        val edited = AppearanceEdits.reconcileCircuit(project, child, updated)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(edited))).project
        val ports = SymbolAppearance.read(reopened.circuit("child")!!).ports.associateBy { it.pinId }
        assertEquals(SymbolMarkerSize(8.0, 8.0), ports.getValue("extra-input").markerSize)
        assertEquals(SymbolMarkerSize(10.0, 10.0), ports.getValue("extra-output").markerSize)
    }

    @Test fun addingPinsToUnsizedAppearanceKeepsMarkersUnsized() {
        val child = child()
        val project = AppearanceEdits.apply(CircuitProject(listOf(child)), "child", SymbolAppearance.classic(child))
        val previous = project.circuit("child")!!
        val updated = previous.copy(components = previous.components + CircuitComponent("extra", ComponentKind.INPUT, GridPoint(100, 120)))
        val edited = AppearanceEdits.reconcileCircuit(project, previous, updated)
        val appearance = SymbolAppearance.read(edited.circuit("child")!!)
        assertEquals(null, appearance.anchor.markerSize)
        assertTrue(appearance.ports.all { it.markerSize == null })
    }

    private fun child() = CircuitDocument("child", components = listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(20, 30), attributes = mapOf("width" to "8", "facing" to "east")),
        CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(90, 30), attributes = mapOf("width" to "8", "facing" to "west")),
    ))

    @Test fun classicGeometryMatchesDesktopPortsAndBounds() {
        val child = child()
        val model = SymbolAppearance.classic(child)
        assertEquals(SymbolAnchor(GridPoint(30, 15)), model.anchor)
        assertEquals(listOf(SymbolPort("y", GridPoint(30, 15)), SymbolPort("a", GridPoint(0, 15))), model.ports)
        assertEquals(ComponentBounds(-30, -15, 0, 15), model.relativeBounds())
        val instance = AppearanceEdits.bind(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"), child, model)
        assertEquals(listOf(GridPoint(170, 100)), instance.inputPorts())
        assertEquals(listOf(GridPoint(200, 100)), instance.outputPorts())
    }

    @Test fun northFacingAnchorUsesRelativeRotationExactlyOnce() {
        val child = child()
        val model = SymbolAppearance(SymbolAnchor(GridPoint(50, 50), "north"), listOf(
            SymbolPort("a", GridPoint(50, 80)), SymbolPort("y", GridPoint(50, 50))),
            listOf(SymbolObject.Shape("body", "rect", mapOf("x" to "40", "y" to "50", "width" to "30", "height" to "30"))))
        val instance = AppearanceEdits.bind(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"), child, model)
        assertEquals("north", instance.facing)
        assertEquals(listOf(GridPoint(200, 130)), instance.inputPorts())
        assertEquals(listOf(GridPoint(200, 100)), instance.outputPorts())
        val east = instance.copy(attributes = instance.attributes + ("facing" to "east"))
        assertEquals(listOf(GridPoint(170, 100)), east.inputPorts())
    }

    @Test fun appearanceSerializationPreservesUnknownObjectsAndPortReferences() {
        val original = child().copy(extraXml = listOf("""<appear>
            <!-- keep outer -->
            <rect x="20" y="20" width="50" height="30" fill="none" stroke="#123456" marker="keep"/>
            <text x="30" y="35" font-family="SansSerif" font-size="12">A &amp; B</text>
            <future-shape x="30"><!-- nested --><data val="keep"/></future-shape>
            <circ-port x="20" y="30" dir="in" pin="20,30"/>
            <circ-port x="70" y="30" dir="out" pin="90,30"/>
            <circ-anchor x="70" y="30" facing="east"/>
        </appear>"""))
        val model = SymbolAppearance.read(original)
        val saved = AppearanceEdits.xml(model, original)
        assertTrue(saved.contains("marker=\"keep\""))
        assertTrue(saved.contains("A &amp; B"))
        assertTrue(saved.contains("<future-shape x=\"30\"><!-- nested --><data val=\"keep\"/></future-shape>"))
        assertEquals(1, Regex("keep outer").findAll(saved).count())
        assertEquals(1, Regex("nested").findAll(saved).count())
        assertEquals(model.relativePorts(original), SymbolAppearance.read(original.copy(extraXml = listOf(saved))).relativePorts(original))
    }

    @Test fun resizedSymbolImageKeepsBitmapAndParentWiresAfterRoundTrip() {
        val project = originalCustomProject()
        val main = project.mainCircuit.copy(wires = listOf(
            WireSegment(GridPoint(200, 140), GridPoint(260, 140)),
            WireSegment(GridPoint(300, 140), GridPoint(360, 140)),
        ))
        val child = project.circuit("child")!!
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
        val href = "data:image/png;base64,$png"
        val imageXml = """<image id="bitmap" x="55" y="60" width="10" height="12" scale="cover"
            xlink:href="$href" attribution="Circuit icon" license="unspecified"/>"""
        val imported = child.copy(extraXml = child.extraXml.map { it.replace("</appear>", "$imageXml</appear>") })
        val appearance = SymbolAppearance.read(imported)
        val image = appearance.objects.filterIsInstance<SymbolObject.Shape>().single { it.id == "bitmap" }
        val moved = AppearanceEdits.translate(image, 2, -1)
        val resized = appearance.editShape(image.id, moved.attributes + mapOf("width" to "18", "height" to "24"))
        val edited = AppearanceEdits.apply(project.withCircuit(main), "child", resized)
        val exported = CircCodec.writeProject(edited)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(exported)).project
        val restored = SymbolAppearance.read(reopened.circuit("child")!!)
        val restoredImage = restored.objects.filterIsInstance<SymbolObject.Shape>().single { it.id == "bitmap" }
        assertEquals(href, restoredImage.attributes["href"])
        assertEquals("Circuit icon", restoredImage.attributes["attribution"])
        assertEquals("unspecified", restoredImage.attributes["license"])
        assertEquals(ComponentBounds(57, 59, 75, 83), SymbolAppearance.shapeBounds(restoredImage))
        assertEquals(appearance.ports, restored.ports)
        assertEquals(main.wires, reopened.mainCircuit.wires)
        assertEquals(main.components.single().explicitPorts, reopened.mainCircuit.components.single().explicitPorts)

        val scene = SymbolVisuals.symbol(restored)
        val primitive = assertIs<VisualPrimitive.Image>(scene.items.single { it.id == "bitmap" }.primitives.single())
        assertContentEquals(EmbeddedImage.fromDataUri(href).bytes, primitive.encodedBytes)
        assertEquals("cover", primitive.scaleMode)
        val instance = SymbolVisuals.symbolInstance(reopened.mainCircuit.components.single(), restored)
        val svg = VisualExport.svg(VisualScene(listOf(instance))).decodeToString()
        assertTrue(svg.contains("width=\"18\" height=\"24\" preserveAspectRatio=\"xMidYMid slice\" href=\"$href\""))
        assertFailsWith<IllegalArgumentException> { restored.editShape("bitmap", mapOf("width" to "0")) }
    }

    @Test fun movingAppearancePortPreservesTheParentNetAndIsUndoable() {
        val child = child()
        val initial = SymbolAppearance.classic(child)
        val instance = AppearanceEdits.bind(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"), child, initial)
        val main = CircuitDocument("main", components = listOf(instance,
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(120, 100), attributes = mapOf("width" to "8"))),
            wires = listOf(WireSegment(GridPoint(120, 100), GridPoint(170, 100))))
        val project = CircuitProject(listOf(main, child), "main")
        val moved = initial.movePort("a", GridPoint(0, 25))
        val changed = AppearanceEdits.apply(project, "child", moved)
        assertEquals(listOf(GridPoint(170, 110)), changed.mainCircuit.components.first().inputPorts())
        assertTrue(changed.mainCircuit.wires.any { it.contains(GridPoint(170, 110)) })
        val editor = CircuitEditor(main)
        editor.replace(changed.mainCircuit)
        assertTrue(editor.undo())
        assertEquals(main, editor.document)
        assertEquals(initial.relativePorts(child), SymbolAppearance.classic(project.circuit("child")!!).relativePorts(child))
    }

    @Test fun invalidPortBindingsCannotReplaceTheProject() {
        val child = child()
        val initial = SymbolAppearance.classic(child)
        assertFailsWith<IllegalArgumentException> { initial.copy(ports = initial.ports.dropLast(1)).validate(child) }
        assertFailsWith<IllegalArgumentException> { initial.copy(ports = initial.ports.map { it.copy(position = GridPoint(0, 0)) }).validate(child) }
        val bad = child.copy(extraXml = listOf("<appear><circ-port x=\"0\" y=\"0\" pin=\"999,999\"/><circ-anchor x=\"0\" y=\"0\"/></appear>"))
        assertFailsWith<IllegalArgumentException> { SymbolAppearance.read(bad) }
    }

    @Test fun allShapesContributeToBoundsAndLayerOrder() {
        val model = SymbolAppearance(SymbolAnchor(GridPoint(0, 0)), emptyList(), listOf(
            SymbolObject.Shape("box", "rect", mapOf("x" to "0", "y" to "0", "width" to "30", "height" to "30")),
            SymbolObject.Shape("circle", "ellipse", mapOf("cx" to "70", "cy" to "40", "rx" to "10", "ry" to "20")),
        ))
        assertEquals(ComponentBounds(0, 0, 80, 60), model.relativeBounds())
        assertEquals(listOf("box", "circle"), model.objects.filterIsInstance<SymbolObject.Shape>().map { it.id })
    }

    @Test fun symbolVisualBoundsIncludeStrokeWithoutChangingModelGeometry() {
        val model = SymbolAppearance(SymbolAnchor(GridPoint(0, 0)), emptyList(), listOf(
            SymbolObject.Shape("box", "rect", mapOf("x" to "0", "y" to "0", "width" to "100", "height" to "100", "stroke" to "#000000", "stroke-width" to "20")),
        ))
        val instance = CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child")
        val visual = SymbolVisuals.symbolInstance(instance, model)
        assertEquals(VisualBounds(190.0, 90.0, 310.0, 210.0), visual.bounds)
        assertEquals(ComponentBounds(0, 0, 100, 100), model.relativeBounds())
        val outer = visual.primitives.single() as VisualPrimitive.Group
        val inner = outer.children.single() as VisualPrimitive.Group
        assertEquals(SymbolVisuals.symbol(model).items.flatMap { it.primitives }, inner.children)
    }

    @Test fun symbolStrokeBoundsFollowTheAnchorRotationExactlyOnce() {
        val model = SymbolAppearance(SymbolAnchor(GridPoint(50, 20), "north"), emptyList(), listOf(
            SymbolObject.Shape("box", "rect", mapOf("x" to "0", "y" to "0", "width" to "100", "height" to "40", "stroke" to "#000000", "stroke-width" to "20")),
        ))
        val instance = CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child", attributes = mapOf("facing" to "east"))
        val visual = SymbolVisuals.symbolInstance(instance, model)
        assertEquals(VisualBounds(170.0, 40.0, 230.0, 160.0), visual.bounds)
        assertEquals(ComponentBounds(-20, -50, 20, 50), model.relativeBounds())
        assertEquals(90.0, (visual.primitives.single() as VisualPrimitive.Group).transform.rotationDegrees)
    }

    @Test fun returningToClassicPreservesStoredCustomGeometryAndRebindsParents() {
        val child = child()
        val custom = SymbolAppearance.classic(child).movePort("a", GridPoint(-10, 15))
        val instance = AppearanceEdits.bind(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"), child, custom)
        val project = CircuitProject(listOf(CircuitDocument("main", listOf(instance)), child), "main")
        val saved = AppearanceEdits.apply(project, "child", custom)
        val restored = AppearanceEdits.useClassic(saved, "child")
        assertEquals("classic", restored.circuit("child")!!.attributes["appearance"])
        assertEquals(saved.circuit("child")!!.extraXml, restored.circuit("child")!!.extraXml)
        assertTrue(!SymbolAppearance.isCustom(restored.circuit("child")!!))
        assertEquals(listOf(GridPoint(170, 100)), restored.mainCircuit.components.single().inputPorts())
    }

    @Test fun shapeDraggingTranslatesEverySupportedCoordinateWithoutChangingStyle() {
        val path = SymbolObject.Shape("p", "path", mapOf("d" to "M 0 1 Q 10 11 20 1 L 30 40 Z", "stroke" to "#123456"))
        val moved = AppearanceEdits.translate(path, 5, -2)
        assertEquals("M 5.0 -1.0 Q 15.0 9.0 25.0 -1.0 L 35.0 38.0 Z", moved.attributes["d"])
        assertEquals("#123456", moved.attributes["stroke"])
        assertEquals(ComponentBounds(5, -1, 35, 38), SymbolAppearance.shapeBounds(moved))
    }

    @Test fun movingARealPinRewritesItsAppearanceReferenceWithoutMovingTheSymbolPort() {
        val child = child()
        val instance = CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child")
        val initial = AppearanceEdits.apply(CircuitProject(listOf(CircuitDocument("main", listOf(instance)), child), "main"), "child", SymbolAppearance.classic(child))
        val before = initial.circuit("child")!!
        val updated = before.copy(components = before.components.map { if (it.id == "a") it.copy(position = GridPoint(40, 60)) else it })
        val changed = AppearanceEdits.reconcileCircuit(initial, before, updated)
        assertTrue(changed.circuit("child")!!.extraXml.single().contains("pin=\"40,60\""))
        assertEquals(initial.mainCircuit.components.single().explicitPorts, changed.mainCircuit.components.single().explicitPorts)
        assertEquals(setOf("a", "y"), SymbolAppearance.read(changed.circuit("child")!!).ports.map { it.pinId }.toSet())
    }

    @Test fun editingPinsKeepsInactiveCustomAppearanceAndItsClassicMode() {
        val child = child()
        val instance = CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child")
        val custom = AppearanceEdits.apply(CircuitProject(listOf(CircuitDocument("main", listOf(instance)), child), "main"), "child", SymbolAppearance.classic(child).movePort("a", GridPoint(-10, 15)))
        val classic = AppearanceEdits.useClassic(custom, "child")
        val before = classic.circuit("child")!!
        val updated = before.copy(components = before.components.map { if (it.id == "a") it.copy(position = GridPoint(40, 60)) else it })
        val changed = AppearanceEdits.reconcileCircuit(classic, before, updated)
        val restored = changed.circuit("child")!!
        assertEquals("classic", restored.attributes["appearance"])
        assertTrue(restored.extraXml.single().contains("pin=\"40,60\""))
        assertEquals(GridPoint(-10, 15), SymbolAppearance.read(restored).ports.single { it.pinId == "a" }.position)
        assertEquals(listOf(GridPoint(170, 100)), changed.mainCircuit.components.single().inputPorts())
    }

    @Test fun shapePaintDefaultsAndAlphaSurviveDesktopSvgSerialization() {
        val rectangle = SymbolObject.Shape("box", "rect", mapOf("x" to "0", "y" to "0", "width" to "30", "height" to "30"))
        val model = SymbolAppearance(SymbolAnchor(GridPoint(0, 0)), emptyList(), listOf(rectangle))
        val primitive = SymbolVisuals.symbol(model).items.single().primitives.single() as VisualPrimitive.Rectangle
        assertEquals(0xff000000u, primitive.paint.fill)
        assertEquals(null, primitive.paint.stroke)
        val alpha = model.editShape("box", mapOf("fill" to "#ff000080", "fill-opacity" to "0.5"))
        assertEquals(0x40ff0000u, (SymbolVisuals.symbol(alpha).items.single().primitives.single() as VisualPrimitive.Rectangle).paint.fill)
        val saved = AppearanceEdits.xml(alpha, CircuitDocument("empty"))
        assertTrue(saved.contains("fill=\"#ff0000\""))
        val reloaded = SymbolAppearance.read(CircuitDocument("empty", extraXml = listOf(saved)))
        assertEquals(0x40ff0000u, (SymbolVisuals.symbol(reloaded).items.single().primitives.single() as VisualPrimitive.Rectangle).paint.fill)
    }

    @Test fun editingUnsupportedPathCommandsFailsWithoutChangingTheShape() {
        val shape = SymbolObject.Shape("path", "path", mapOf("d" to "M 0 0 L 10 10"))
        val model = SymbolAppearance(SymbolAnchor(GridPoint(0, 0)), emptyList(), listOf(shape))
        assertFailsWith<IllegalArgumentException> { model.editShape("path", mapOf("d" to "M 0 0 A 10 10 0 0 1 20 20")) }
        assertEquals("M 0 0 L 10 10", (model.objects.single() as SymbolObject.Shape).attributes["d"])
    }

    @Test fun pointEditsRoundTripAQuadraticCurveAndClosedPolygonWithoutRebindingPorts() {
        val project = originalCustomProject()
        val child = project.circuit("child")!!
        val starting = SymbolAppearance.read(child)
        val points = listOf(GridPoint(0, 30), GridPoint(20, 0), GridPoint(40, 30))
        val curve = SymbolObject.Shape("curve", "path", AppearanceEdits.controlPointAttributes("path", points))
        val polygon = SymbolObject.Shape("poly", "polygon", AppearanceEdits.controlPointAttributes("polygon", points))
        val movedCurve = AppearanceEdits.translate(curve, 5, -2)
        assertEquals("M5,28 Q25,-2 45,28", movedCurve.attributes["d"])
        val nextPolygon = listOf(GridPoint(-20, 30), GridPoint(20, 0), GridPoint(40, 30), GridPoint(50, 40))
        val edited = starting.addShape(movedCurve).addShape(polygon)
            .editShape("poly", AppearanceEdits.controlPointAttributes("polygon", nextPolygon))
        val saved = CircCodec.writeProject(AppearanceEdits.apply(project, "child", edited))
        assertTrue(saved.contains("""d="M5,28 Q25,-2 45,28""""))
        assertTrue(saved.contains("""points="-20,30 20,0 40,30 50,40""""))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved)).project
        val appearance = SymbolAppearance.read(reopened.circuit("child")!!)
        assertEquals(starting.ports, appearance.ports)
        assertEquals(project.mainCircuit.components.single().explicitPorts, reopened.mainCircuit.components.single().explicitPorts)
        val shapes = appearance.objects.filterIsInstance<SymbolObject.Shape>().associateBy { it.id }
        assertEquals(listOf(GridPoint(5, 28), GridPoint(25, -2), GridPoint(45, 28)), AppearanceEdits.controlPoints(shapes.getValue("curve")))
        assertEquals(nextPolygon, AppearanceEdits.controlPoints(shapes.getValue("poly")))
        val scene = SymbolVisuals.symbol(appearance).items.associateBy { it.id }
        assertEquals(VisualPathCommand.QuadTo(VisualPoint(25.0, -2.0), VisualPoint(45.0, 28.0)),
            assertIs<VisualPrimitive.Path>(scene.getValue("curve").primitives.single()).commands.last())
        assertEquals(VisualPathCommand.Close, assertIs<VisualPrimitive.Path>(scene.getValue("poly").primitives.single()).commands.last())
    }

    @Test fun desktopTextWithoutAlignmentIsCenteredInsideItsBody() {
        val circuit = CircuitDocument("child", extraXml = listOf("""<appear><rect x="50" y="50" width="60" height="80"/><text x="80" y="85" font-size="12">Module</text><circ-anchor facing="east" x="110" y="90"/></appear>"""))
        val appearance = SymbolAppearance.read(circuit)
        assertEquals(ComponentBounds(-60, -40, 0, 40), appearance.relativeBounds())
        val text = SymbolVisuals.symbol(appearance).items.single { it.id == "shape-1" }.primitives.single() as VisualPrimitive.Text
        assertEquals(VisualTextAlign.CENTER, text.align)
        assertEquals(VisualTextVerticalAlign.CENTER, text.verticalAlign)
    }
}
