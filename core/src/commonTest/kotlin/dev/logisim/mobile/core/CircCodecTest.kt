package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircCodecTest {
    @Test
    fun exportsSelectedCircuitWithItsDependenciesAndLibraries() {
        val xml = """
            <project source="5.1.0-dev" version="1.0">
              <lib desc="#Wiring" name="2"/>
              <lib desc="#Gates" name="7"/>
              <main name="Main"/>
              <toolbar>
                <tool name="Unused"/>
                <tool name="Grandchild"/>
                <tool lib="7" name="NOT Gate"/>
              </toolbar>
              <mappings>
                <tool map="Ctrl" name="Main"/>
                <tool map="Shift" name="Child"/>
              </mappings>
              <circuit name="Main"><comp loc="(120,100)" name="Child"/></circuit>
              <circuit name="Child">
                <comp loc="(120,100)" name="Grandchild"/>
                <comp loc="(180,100)" name="Grandchild"/>
              </circuit>
              <circuit name="Grandchild">
                <comp lib="2" loc="(80,100)" name="Pin"/>
                <comp lib="7" loc="(120,100)" name="NOT Gate"/>
              </circuit>
              <circuit name="Unused"/>
            </project>
        """.trimIndent()
        val original = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

        val exportedXml = CircCodec.writeCircuit(original, "Child")
        val exported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(exportedXml)).project

        assertEquals("Child", exported.mainCircuitName)
        assertEquals(listOf("Child", "Grandchild"), exported.circuits.map { it.name })
        assertEquals(listOf("Grandchild", "Grandchild"), exported.mainCircuit.components.map { it.circuitName })
        assertEquals(mapOf("2" to "#Wiring", "7" to "#Gates"), exported.libraries)
        assertEquals(listOf("2", "7"), exported.circuit("Grandchild")!!.components.map { it.xmlAttributes["lib"] })
        assertTrue("<tool name=\"Grandchild\"/>" in exportedXml)
        assertTrue("<tool lib=\"7\" name=\"NOT Gate\"/>" in exportedXml)
        assertTrue("<tool map=\"Shift\" name=\"Child\"/>" in exportedXml)
        assertFalse("name=\"Main\"" in exportedXml)
        assertFalse("name=\"Unused\"" in exportedXml)
        assertEquals("Main", original.mainCircuitName)
        assertEquals(listOf("Main", "Child", "Grandchild", "Unused"), original.circuits.map { it.name })

        val mainExport = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(CircCodec.writeCircuit(original, "Main")),
        ).project
        assertEquals(listOf("Main", "Child", "Grandchild"), mainExport.circuits.map { it.name })
    }

    @Test
    fun largeOriginalAndEvolutionProjectsRoundTrip() {
        val count = 3_400
        val wires = (0 until count).map { index ->
            val x = 100 + 100 * (index % 70)
            val y = 100 + 100 * (index / 70)
            WireSegment(GridPoint(x, y), GridPoint(x + 20, y))
        }
        for (source in listOf("2.7.1", "5.1.0dev")) {
            val xml = buildString {
                append("""<project source="$source" version="1.0"><lib desc="#Gates" name="0"/><main name="main"/><circuit name="main">""")
                for ((index, wire) in wires.withIndex()) {
                    val (from, to) = wire
                    append("""<comp lib="0" name="NOT Gate" loc="(${from.x},${from.y})"><a name="label" val="N$index"/></comp>""")
                    append("""<wire from="(${from.x},${from.y})" to="(${to.x},${to.y})"/>""")
                }
                append("</circuit></project>")
            }
            val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml), source).project
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(imported)), source).project
            for (result in listOf(imported, reopened)) {
                assertEquals(source, result.sourceVersion)
                assertEquals(wires, result.mainCircuit.wires)
                assertEquals(wires.map { it.from }, result.mainCircuit.components.map { it.position })
                assertEquals(List(count) { ComponentKind.NOT }, result.mainCircuit.components.map { it.kind })
                assertEquals(List(count) { "N$it" }, result.mainCircuit.components.map { it.label })
            }
        }
    }

    @Test
    fun rejectsDocumentsBeyondXmlNodeAndSizeBounds() {
        val tooManyNodes = "<project>" + "<a/>".repeat(12_000) + "</project>"
        val tooLarge = "<project>" + " ".repeat(2_000_000) + "</project>"
        for ((xml, key) in listOf(tooManyNodes to "error_xml_structure", tooLarge to "error_xml_size")) {
            assertEquals(key, assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml)).notices.single().key)
        }
    }

    @Test
    fun exportedAndCircuitRetainsGeometryLabelsAndTruthTable() {
        val document = andCircuit()
        val xml = CircCodec.write(document)
        val imported = assertIs<CircImportResult.Success>(CircCodec.read(xml)).document
        assertEquals(document.name, imported.name)
        assertEquals(document.components.map { Triple(it.kind, it.position, it.label) }, imported.components.map { Triple(it.kind, it.position, it.label) })
        assertEquals(document.wires, imported.wires)
        val inputs = imported.components.filter { it.kind == ComponentKind.INPUT }
        val output = imported.components.single { it.kind == ComponentKind.OUTPUT }
        val simulator = CircuitSimulator()
        for ((a, b, expected) in listOf(
            Triple(LogicValue.LOW, LogicValue.LOW, LogicValue.LOW),
            Triple(LogicValue.LOW, LogicValue.HIGH, LogicValue.LOW),
            Triple(LogicValue.HIGH, LogicValue.LOW, LogicValue.LOW),
            Triple(LogicValue.HIGH, LogicValue.HIGH, LogicValue.HIGH),
        )) {
            val result = simulator.settle(imported, inputs = mapOf(inputs[0].id to a, inputs[1].id to b))
            assertEquals(expected, result.componentValues[output.id], "A=$a, B=$b")
        }
    }

    @Test
    fun emptyCircuitRoundTrips() {
        val document = CircuitDocument("Vazio")
        assertEquals(document, assertIs<CircImportResult.Success>(CircCodec.read(CircCodec.write(document))).document)
    }

    @Test
    fun allDeclaredComponentKindsRoundTripWithCanonicalGeometry() {
        val components = ComponentKind.entries.filter { it != ComponentKind.SUBCIRCUIT }.mapIndexed { index, kind ->
            CircuitComponent("c$index", kind, GridPoint(index * 100, 100), kind.name)
        }
        val document = CircuitDocument("Componentes", components)
        val imported = assertIs<CircImportResult.Success>(CircCodec.read(CircCodec.write(document))).document
        assertEquals(components.map { it.kind }, imported.components.map { it.kind })
        assertEquals(components.map { it.inputPorts() }, imported.components.map { it.inputPorts() })
        assertEquals(components.map { it.outputPorts() }, imported.components.map { it.outputPorts() })
    }

    @Test
    fun supportsCommentsSingleQuotesAndNumericEntities() {
        val imported = assertIs<CircImportResult.Success>(CircCodec.read("""
            <?xml version="1.0"?>
            <!-- exported by a desktop editor; original Logisim keeps free-form names (#321) -->
            <project source='2.7.1' version='1.0'>
              <lib desc='#Wiring' name='9'/>
              <main name='A &amp; B'/>
              <circuit name='A &amp; B'>
                <comp lib='9' loc='(120,140)' name='Pin'>
                  <a name='label' val='&lt;A&gt; &quot;B&quot; &#39;C&#39; &#10; &#x1F4A1;'/>
                </comp>
              </circuit>
            </project>
        """.trimIndent())).document
        assertEquals("A & B", imported.name)
        assertEquals("<A> \"B\" 'C' \n 💡", imported.components.single().label)
        val again = assertIs<CircImportResult.Success>(CircCodec.read(CircCodec.write(imported))).document
        assertEquals(imported.components.single().label, again.components.single().label)
    }

    @Test
    fun importsRomContentsInCdataAndPreservesThemOnReopen() {
        val rom = component("ROM", "2", """
            <a name="contents">addr/data: 8 8
            <![CDATA[a5 5a]]></a>
        """.trimIndent())
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(project(rom))).project
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(imported))).project
        for (project in listOf(imported, reopened)) {
            val contents = project.mainCircuit.components.single().attributes.getValue("contents")
            val image = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeCirc(contents)).image
            assertEquals(MemoryImage(8, 8, mapOf(0L to 0xa5uL, 1L to 0x5auL)), image)
        }
    }

    @Test
    fun treatsMarkupAndEntityReferencesInCdataAsLiteralText() {
        val pin = component("Pin", "0", """<a name="label"><![CDATA[<tag> &unknown;]]></a>""")
        val imported = assertIs<CircImportResult.Success>(CircCodec.read(project(pin))).document
        assertEquals("<tag> &unknown;", imported.components.single().label)
    }

    @Test
    fun acceptsLegacyOutputPinAndGivesModernTypePrecedence() {
        val legacy = component("Pin", "0", "<a name=\"output\" val=\"true\"/>")
        assertEquals(ComponentKind.OUTPUT, assertIs<CircImportResult.Success>(CircCodec.read(project(legacy))).document.components.single().kind)
        val contradictory = component("Pin", "0", "<a name=\"output\" val=\"true\"/><a name=\"type\" val=\"input\"/>")
        assertEquals(ComponentKind.INPUT, assertIs<CircImportResult.Success>(CircCodec.read(project(contradictory))).document.components.single().kind)
    }

    @Test
    fun unknownComponentFailsWholeImportInsteadOfDroppingIt() {
        val xml = project(component("Pin", "0") + component("Mystery Component", "0"))
        val failure = assertIs<CircImportResult.Failure>(CircCodec.read(xml))
        assertTrue(failure.diagnostics.any { "Mystery Component" in it })
    }

    @Test
    fun supportsBusAttributesAndRejectsInvalidGeometry() {
        val supported = listOf(
            component("Pin", "0", "<a name=\"width\" val=\"8\"/>"),
            component("Pin", "0", "<a name=\"initial\" val=\"1\"/>"),
            component("AND Gate", "1", "<a name=\"facing\" val=\"north\"/>"),
            component("AND Gate", "1", "<a name=\"size\" val=\"30\"/>"),
            component("AND Gate", "1", "<a name=\"inputs\" val=\"3\"/>"),
            component("AND Gate", "1", "<a name=\"negate0\" val=\"true\"/>"),
            component("AND Gate", "1", "<a name=\"out\" val=\"0Z\"/>"),
            component("Clock", "0", "<a name=\"highDuration\" val=\"2\"/>"),
            component("D Flip-Flop", "2", "<a name=\"appearance\" val=\"classic\"/><a name=\"trigger\" val=\"falling\"/>"),
        )
        supported.forEach { content -> assertIs<CircImportResult.Success>(CircCodec.read(project(content)), content) }
        val invalid = listOf(
            component("Pin", "0", "<a name=\"width\" val=\"65\"/>"),
            component("Pin", "0", "<a name=\"facing\" val=\"diagonal\"/>"),
            component("AND Gate", "1", "<a name=\"inputs\" val=\"0\"/>"),
            "<wire from=\"(0,0)\" to=\"(20,20)\"/>",
            "<wire from=\"(0,0)\" to=\"(0,0)\"/>",
        )
        invalid.forEach { content -> assertIs<CircImportResult.Failure>(CircCodec.read(project(content)), content) }
    }

    @Test
    fun legacyReaderRejectsMultipleCircuitsAndUnknownReferences() {
        val multiple = project("").replace("</project>", "<circuit name=\"child\"/></project>")
        assertIs<CircImportResult.Failure>(CircCodec.read(multiple))
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(multiple))
        assertIs<CircImportResult.Failure>(CircCodec.read(project(component("child", ""))))
        val preserved = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(project("<appear><rect x=\"0\"/></appear>"))).project
        assertTrue(preserved.mainCircuit.extraXml.single().contains("<appear>"))
    }

    @Test
    fun preservesOriginalRandomDelayOptionAndReportsWhenItIsEnabled() {
        for (value in listOf(0, 1, 32)) {
            val options = """<options><a name="simrand" val="$value"/></options>"""
            val xml = project("").replace("5.1.0-dev", "2.7.1").replace("<main", "$options<main")
            val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))
            val exported = CircCodec.writeProject(imported.project)
            assertTrue(options in exported)
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(exported))
            for (result in listOf(imported, reopened)) {
                if (value <= 1) {
                    assertTrue(result.migrations.isEmpty())
                } else {
                    val notice = result.migrations.single().description
                    assertTrue("simrand=32" in notice)
                    assertTrue("atrasos aleatórios de propagação estão ativos" in notice)
                }
            }
        }
    }

    @Test
    fun preservesPaletteDefaults() {
        val xml = project(component("AND Gate", "1"))
        val defaults = xml.replace("<lib desc=\"#Gates\" name=\"1\"/>", "<lib desc=\"#Gates\" name=\"1\"><tool name=\"AND Gate\"><a name=\"size\" val=\"30\"/></tool></lib>")
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(defaults)).project
        assertEquals(null, imported.mainCircuit.components.single().attributes["size"])
        assertTrue(CircCodec.writeProject(imported).contains("val=\"30\""))
    }

    @Test
    fun rejectsMalformedOrUntrustedXml() {
        for (xml in listOf(
            "", "<project>", "<project></circuit>", "<project/><project/>",
            "<project/><?xml version='1.0'?>", "<?xml garbage?><project/>",
            "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project/>",
            project(component("Pin", "0", """<a name="label"><![CDATA[unfinished</a>""")),
            project(component("Pin", "0", "<a name=\"label\"><![CDATA[\u0000]]></a>")),
            project(component("Pin", "0", "<a name=\"label\" val=\"&secret;\"/>")),
            project(component("Pin", "0", "<a name=\"label\" val=\"&#0;\"/>")),
            project(component("Pin", "0", "<a name=\"label\" name=\"width\" val=\"1\"/>")),
            project(component("Pin", "0", "<a name=\"label\" val=\"one\"/><a name=\"label\" val=\"two\"/>")),
            "<project>" + "<a>".repeat(40) + "</a>".repeat(40) + "</project>",
        )) assertIs<CircImportResult.Failure>(CircCodec.read(xml), xml.take(100))
    }

    private fun andCircuit() = CircuitDocument("A_and_B", listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(120, 140), "A"),
        CircuitComponent("b", ComponentKind.INPUT, GridPoint(120, 240), "B"),
        CircuitComponent("gate", ComponentKind.AND, GridPoint(300, 190)),
        CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(420, 190), "Y"),
    ), listOf(
        WireSegment(GridPoint(120, 140), GridPoint(200, 140)),
        WireSegment(GridPoint(200, 140), GridPoint(200, 170)),
        WireSegment(GridPoint(200, 170), GridPoint(250, 170)),
        WireSegment(GridPoint(120, 240), GridPoint(220, 240)),
        WireSegment(GridPoint(220, 240), GridPoint(220, 210)),
        WireSegment(GridPoint(220, 210), GridPoint(250, 210)),
        WireSegment(GridPoint(300, 190), GridPoint(420, 190)),
    ))

    private fun component(name: String, library: String, attrs: String = "") =
        "<comp lib=\"$library\" loc=\"(300,190)\" name=\"$name\">$attrs</comp>"

    private fun project(content: String) = """
        <project source="5.1.0-dev" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <lib desc="#Memory" name="2"/>
          <main name="main"/>
          <circuit name="main">$content</circuit>
        </project>
    """.trimIndent()
}
