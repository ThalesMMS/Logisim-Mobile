package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircProjectCodecTest {
    @Test
    fun legacyMainCircuitWithSpacesSurvivesExportAndReopen() {
        val imported = success("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <main name="Data path"/>
              <circuit name="first"/>
              <circuit name="Data path"/>
            </project>
        """.trimIndent())
        assertEquals(listOf("first", "Data path"), imported.circuits.map { it.name })
        assertEquals("Data path", imported.mainCircuitName)
        assertEquals("Data path", imported.mainCircuit.id)

        val saved = CircCodec.writeProject(imported)
        val main = CircXmlParser(saved).parse().children.single { it.name == "main" }
        assertEquals("Data path", main.attributes["name"])

        val reopened = success(saved)
        assertEquals(listOf("first", "Data path"), reopened.circuits.map { it.name })
        assertEquals("Data path", reopened.mainCircuitName)
        assertEquals(imported.mainCircuit.id, reopened.mainCircuit.id)
    }

    @Test
    fun retainsMainAttributesAndOpaqueContentAfterChangingMainCircuit() {
        val project = success("""
            <project><main marker="KEEP &gt; ME" name="first">prefix<extra key="retained"/>suffix</main>
              <circuit name="first"/><circuit name="second"/>
            </project>
        """.trimIndent())
        val saved = CircCodec.writeProject(project.copy(mainCircuitName = "second"))
        val main = CircXmlParser(saved).parse().children.single { it.name == "main" }
        assertEquals(mapOf("marker" to "KEEP > ME", "name" to "second"), main.attributes)
        assertEquals("prefixsuffix", main.text)
        assertEquals("<extra key=\"retained\"/>", main.children.single().rawXml)
        assertTrue(saved.contains("prefix<extra key=\"retained\"/>suffix"))
        assertEquals("second", success(saved).mainCircuitName)
    }

    @Test
    fun singleCircuitApiRetainsMainAttributesAndChildren() {
        val original = "<project><main name=\"main\" marker=\"KEEP_ME\"><extra/></main><circuit name=\"main\"/></project>"
        val document = assertIs<CircImportResult.Success>(CircCodec.read(original)).document
        val main = CircXmlParser(CircCodec.write(document)).parse().children.single { it.name == "main" }
        assertEquals("KEEP_ME", main.attributes["marker"])
        assertEquals("extra", main.children.single().name)
    }

    @Test
    fun descriptiveProjectTextRetainsItsWhitespaceAcrossRepeatedSaves() {
        val xml = "<project version=\"1.0\">  description\n <circuit name=\"main\"/>  </project>"
        val imported = success(xml)
        assertEquals("  description\n   ", imported.metadata["#text"])
        val saved = CircCodec.writeProject(imported)
        val reopened = success(saved)
        assertEquals(imported.metadata, reopened.metadata)
        assertEquals(saved, CircCodec.writeProject(reopened))
    }

    @Test
    fun rejectsSignificantProjectTextBetweenChildrenBeforeReplacingTheDocument() {
        val result = CircCodec.readProject("<project>before<main name=\"main\"/>middle<circuit name=\"main\"/>after</project>")
        assertIs<CircProjectImportResult.Failure>(result)
    }

    @Test
    fun textualAttributeValuesRetainLeadingTrailingAndEmbeddedWhitespace() {
        val xml = """<project><lib name="w" desc="#Wiring"/><circuit name="main">
            <a name="custom-note">  leading
            trailing  </a>
            <comp lib="w" name="Pin" loc="(10,10)"><a name="label">  spaced label  </a></comp>
            </circuit></project>"""
        val imported = success(xml)
        assertEquals("  leading\n            trailing  ", imported.mainCircuit.attributes["custom-note"])
        assertEquals("  spaced label  ", imported.mainCircuit.components.single().label)
        val reopened = success(CircCodec.writeProject(imported))
        assertEquals(imported.mainCircuit.attributes, reopened.mainCircuit.attributes)
        // Evolution's XmlReader renames only `val` labels, so it keeps this one; once written as `val`, both rename it (#321).
        assertEquals(EvolutionNames.validLabel("  spaced label  "), reopened.mainCircuit.components.single().label)
    }

    @Test
    fun circuitIdentitySurvivesRenameExportAndReopen() {
        val original = success("""
            <project><main name="top"/><circuit name="top" id="top-id"><comp name="old" loc="(50,50)"/></circuit>
              <circuit name="old" id="stable-child" marker="kept"/>
            </project>
        """.trimIndent())
        val reopened = success(CircCodec.writeProject(original.renameCircuit("old", "new")))
        assertEquals("top-id", reopened.mainCircuit.id)
        assertEquals("stable-child", reopened.circuit("new")?.id)
        assertEquals("new", reopened.mainCircuit.components.single().circuitName)
        assertEquals(mapOf("marker" to "kept"), reopened.circuit("new")?.xmlAttributes)
    }

    @Test
    fun legacyCircuitGetsPersistentIdentityOnFirstExport() {
        val imported = success("<project><circuit name=\"original\"/></project>")
        val renamed = imported.renameCircuit("original", "renamed")
        val reopened = success(CircCodec.writeProject(renamed))
        assertEquals("original", reopened.mainCircuit.id)
        assertEquals("renamed", reopened.mainCircuitName)
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(
            "<project><circuit name=\"a\" id=\"duplicate\"/><circuit name=\"b\" id=\"duplicate\"/></project>"))
    }

    @Test
    fun retainsProjectPresentationLibraryDefaultsAndMemoryContents() {
        val xml = """
            <project source="3.8.0" version="1.0" custom="retained">
              This file is intended to be loaded by Logisim.
              <lib name="g" desc="#Gates"><tool name="AND Gate"><a name="size" val="30"/></tool></lib>
              <lib name="w" desc="#Wiring"/>
              <lib name="m" desc="#Memory"/>
              <options><a name="simrand" val="0"/><a name="simlimit" val="1000"/></options>
              <mappings><tool lib="g" map="Button1" name="AND Gate"/></mappings>
              <toolbar><sep/><tool lib="w" name="Pin"/></toolbar>
              <main name="main"/>
              <circuit name="main">
                <a name="clabelfont" val="Dialog bold 17"/>
                <comp lib="g" name="AND Gate" loc="(100,100)">
                  <a name="labelfont" val="SansSerif bold 12"/><a name="labelcolor" val="#ff0000"/>
                  <a name="labelvisible" val="false"/><a name="labelloc" val="north"/>
                  <a name="radix" val="16"/><a name="mapinfo"><port id="1"/></a>
                </comp>
                <comp lib="m" name="ROM" loc="(200,100)">
                  <a name="addrWidth" val="4"/><a name="dataWidth" val="8"/>
                  <a name="contents">addr/data: 4 8
            01 1e 1d 2c e0 f0 6*0 01
            04 03 02
            </a>
                </comp>
              </circuit>
            </project>
        """.trimIndent()
        val original = success(xml)
        val again = success(CircCodec.writeProject(original))
        assertEquals(original.sourceVersion, again.sourceVersion)
        assertEquals(original.metadata, again.metadata)
        assertEquals(original.libraries, again.libraries)
        assertEquals(original.mainCircuit.attributes, again.mainCircuit.attributes)
        assertEquals(original.mainCircuit.components[0].attributes, again.mainCircuit.components[0].attributes)
        assertEquals(original.mainCircuit.components[0].extraXml, again.mainCircuit.components[0].extraXml)
        assertEquals(original.mainCircuit.components[1].attributes["contents"], again.mainCircuit.components[1].attributes["contents"])
        assertTrue(again.extraXml.any { "Button1" in it })
        assertTrue(again.extraXml.any { "<toolbar>" in it })
    }

    @Test
    fun emptyLibraryAttributeResolvesLocalSubcircuitAndSavesWithoutIt() {
        val xml = """
            <project source="2.7.1">
              <main name="main"/>
              <circuit name="main"><comp lib="" name="child" loc="(30,30)"/></circuit>
              <circuit name="child"/>
            </project>
        """.trimIndent()
        val loaded = success(xml)
        val instance = loaded.mainCircuit.components.single()
        assertEquals(ComponentKind.SUBCIRCUIT, instance.kind)
        assertEquals("child", instance.circuitName)
        val saved = CircCodec.writeProject(loaded)
        val reopened = success(saved).mainCircuit.components.single()
        assertEquals("child", reopened.circuitName)
        assertTrue("lib" !in reopened.xmlAttributes)
    }

    @Test
    fun resolvesCustomSubcircuitPortsUsingPinIdentityAndAnchor() {
        val project = success("""
            <project source="3.8.0" version="1.0"><lib name="0" desc="#Wiring"/>
              <main name="top"/>
              <circuit name="top"><comp name="child" loc="(200,200)"/></circuit>
              <circuit name="child"><a name="appearance" val="custom"/>
                <appear><rect x="50" y="50" width="60" height="80"/>
                  <text x="80" y="85" font-size="12">Module</text>
                  <circ-anchor facing="east" x="110" y="90"/>
                  <circ-port dir="in" pin="90,100" x="50" y="60"/>
                  <circ-port dir="out" pin="270,90" x="110" y="90"/>
                </appear>
                <comp lib="0" name="Pin" loc="(90,100)"><a name="label" val="input"/><a name="width" val="8"/></comp>
                <comp lib="0" name="Pin" loc="(270,90)"><a name="output" val="true"/><a name="width" val="8"/></comp>
              </circuit>
            </project>
        """.trimIndent())
        val component = project.mainCircuit.components.single()
        assertEquals(listOf(GridPoint(140,170)), component.inputPorts())
        assertEquals(listOf(GridPoint(200,200)), component.outputPorts())
        assertEquals(8, component.explicitPorts[0].width)
        assertEquals("import-1", component.explicitPorts[0].name)
        assertEquals("Module", component.appearanceTexts.single().text)
        assertEquals(ComponentBounds(-60,-40,0,40), component.explicitBounds)
        val roundtrip = success(CircCodec.writeProject(project))
        assertEquals(component.explicitPorts, roundtrip.mainCircuit.components.single().explicitPorts)
        assertIs<CircImportResult.Failure>(CircCodec.read(CircCodec.writeProject(project)))
    }

    @Test
    fun derivesSharedCircuitLabelWithoutWritingInstanceAttributes() {
        val project = success("""
            <project source="3.8.0" version="1.0"><main name="main"/>
              <circuit name="main"><comp name="REGISTRADOR_DE_INSTRUCOES" loc="(170,450)"/></circuit>
              <circuit name="REGISTRADOR_DE_INSTRUCOES">
                <a name="appearance" val="custom"/><a name="clabel" val="RI"/><a name="clabelup" val="north"/>
                <appear><rect x="49" y="51" width="62" height="80"/>
                  <circ-anchor facing="east" x="110" y="100"/>
                </appear>
              </circuit>
            </project>
        """.trimIndent())
        val instance = project.mainCircuit.components.single()
        assertEquals("", instance.label)
        assertEquals("RI", instance.sharedLabel)
        assertTrue(instance.appearanceTexts.isEmpty())
        val xml = CircCodec.writeProject(project)
        val instanceXml = CircXmlParser(xml).parse().children.first { it.name == "circuit" }
            .children.single { it.name == "comp" }
        assertTrue(instanceXml.children.none { it.attributes["name"] in setOf("label", "clabel", "sharedLabel") })
        assertEquals("RI", success(xml).mainCircuit.components.single().sharedLabel)
    }

    @Test
    fun legacySingleCircuitApiRetainsRootMetadataOnSave() {
        val xml = """
            <project source="3.8.0" version="1.0" custom="preserved">
            <lib name="0" desc="#Wiring"/><toolbar><tool name="Pin" lib="0"/></toolbar>
            <main name="main"/><circuit name="main"><a name="appearance" val="classic"/>
            <comp lib="0" name="Pin" loc="(10,20)"><a name="width" val="8"/><a name="facing" val="north"/><a name="radix" val="16"/></comp>
            </circuit></project>
        """.trimIndent()
        val document = assertIs<CircImportResult.Success>(CircCodec.read(xml)).document
        val again = success(CircCodec.write(document))
        assertEquals("preserved", again.metadata["custom"])
        assertEquals("3.8.0", again.sourceVersion)
        assertTrue(again.extraXml.any { "toolbar" in it })
        assertEquals("north", again.mainCircuit.components.single().facing)
        assertEquals("16", again.mainCircuit.components.single().attributes["radix"])
    }

    @Test
    fun rejectsUnknownComponentsAndBrokenReferencesAtomically() {
        for (component in listOf("<comp lib=\"0\" name=\"Mystery\" loc=\"(0,0)\"/>", "<comp name=\"missing\" loc=\"(0,0)\"/>")) {
            assertIs<CircProjectImportResult.Failure>(CircCodec.readProject("<project><lib name=\"0\" desc=\"#Wiring\"/><circuit name=\"main\">$component</circuit></project>"))
        }
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject("<project><circuit name=\"main\"><comp name=\"main\" loc=\"(0,0)\"/></circuit></project>"))
    }

    private fun success(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
}
