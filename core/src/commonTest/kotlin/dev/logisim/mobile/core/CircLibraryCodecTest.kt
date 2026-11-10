package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CircLibraryCodecTest {
    private fun project(circuits: String, libraries: String = "", main: String = "main") =
        "<project source=\"5.1.0\">$libraries<main name=\"$main\"/>$circuits</project>"
    private val pin = "<comp name=\"Pin\" lib=\"w\" loc=\"(10,10)\"/>"
    private val wiring = "<lib name=\"w\" desc=\"#Wiring\"/>"

    @Test
    fun convertsEachLegacySourceBeforeCombiningItsLibrariesWithModernSources() {
        val old = project("""
            <circuit name="main">
              $pin
              <comp lib="child" name="Child" loc="(50,50)"/>
            </circuit>
        """.trimIndent(), """<lib name="w" desc="#Base"/><lib name="child" desc="file#child.circ"/>""")
            .replace("5.1.0", "2.0.3")
        val modern = project("<circuit name=\"Child\">$pin</circuit>", wiring, "Child")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(old, "root",
            CircuitLibraryResolver { _, path -> if (path == "child.circ") CircuitLibrarySource(path, modern) else null })).resolved
        assertTrue(loaded.migrations.any { it.rule == "legacy-project-converted" })
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(CircLibraryCodec.bundle(loaded))).resolved
        for (project in listOf(loaded.project, reopened.project)) {
            assertEquals("2.7.1", project.sourceVersion)
            val (rootPin, childInstance) = project.mainCircuit.components
            assertEquals(ComponentKind.INPUT, rootPin.kind)
            val child = project.circuit(childInstance.circuitName!!)!!
            assertEquals(ComponentKind.INPUT, child.components.single().kind)
        }
    }

    @Test
    fun resolvesNestedLibrariesAndKeepsSameNamedDefinitionsDistinct() {
        val deep = project("<circuit name=\"Shared\">$pin</circuit>", wiring, "Shared")
        val middle = project("<circuit name=\"Middle\"><comp name=\"Shared\" lib=\"c\" loc=\"(30,30)\"/></circuit>",
            "<lib name=\"c\" desc=\"file#deep.circ\"/>", "Middle")
        val other = project("<circuit name=\"Shared\"><a name=\"clabel\" val=\"other\"/></circuit>", main = "Shared")
        val root = project("<circuit name=\"main\"><comp name=\"Shared\" lib=\"b\" loc=\"(50,50)\"/><comp name=\"Shared\" lib=\"o\" loc=\"(100,100)\"/></circuit>",
            "<lib name=\"b\" desc=\"file#middle.circ\"/><lib name=\"o\" desc=\"file#other.circ\"/>")
        val sources = mapOf("deep.circ" to deep, "middle.circ" to middle, "other.circ" to other)
        val result = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, path ->
            sources[path]?.let { CircuitLibrarySource(path, it, "1") }
        })).resolved
        assertEquals(4, result.project.circuits.size)
        val instances = result.project.mainCircuit.components
        assertNotEquals(instances[0].circuitName, instances[1].circuitName)
        assertEquals("other", result.project.circuit(instances[1].circuitName!!)?.attributes?.get("clabel"))
        assertEquals(4, result.project.circuits.map { it.id }.distinct().size)
        assertTrue(result.changes(CircuitLibraryResolver { _, path -> sources[path]?.let { CircuitLibrarySource(path, it, "1") } }).isEmpty())
    }

    @Test
    fun emptyLibraryAttributeResolvesLocallyWithinEachSource() {
        val root = project("""
            <circuit name="main">
              <comp lib="" name="Shared" loc="(20,20)"/>
              <comp lib="ext" name="Wrapper" loc="(50,50)"/>
            </circuit>
            <circuit name="Shared"><a name="clabel" val="root"/></circuit>
        """.trimIndent(), """<lib name="ext" desc="file#child.circ"/>""")
        val child = project("""
            <circuit name="Wrapper"><comp lib="" name="Shared" loc="(30,30)"/></circuit>
            <circuit name="Shared"><a name="clabel" val="external"/></circuit>
        """.trimIndent(), main = "Wrapper")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root",
            CircuitLibraryResolver { _, path -> if (path == "child.circ") CircuitLibrarySource("child", child) else null })).resolved
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(CircLibraryCodec.bundle(loaded))).resolved
        for (project in listOf(loaded.project, reopened.project)) {
            val (local, external) = project.mainCircuit.components
            assertEquals("root", project.circuit(local.circuitName!!)?.attributes?.get("clabel"))
            val nested = project.circuit(external.circuitName!!)!!.components.single()
            assertEquals("external", project.circuit(nested.circuitName!!)?.attributes?.get("clabel"))
            assertTrue("lib" !in nested.xmlAttributes)
        }
    }

    @Test
    fun reportsMissingCyclesAndReplacedFiles() {
        val root = project("<circuit name=\"main\"/>", "<lib name=\"b\" desc=\"file#b.circ\"/>")
        val missing = assertIs<CircLibraryImportResult.Failure>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> null }))
        assertTrue(missing.diagnostics.single().contains("b.circ"))
        val cycle = project("<circuit name=\"b\"/>", "<lib name=\"r\" desc=\"file#root.circ\"/>", "b")
        val cyclic = assertIs<CircLibraryImportResult.Failure>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, path ->
            if (path == "b.circ") CircuitLibrarySource("b", cycle) else CircuitLibrarySource("root", root)
        }))
        assertTrue(cyclic.diagnostics.single().contains("Ciclo"))
        val child = project("<circuit name=\"child\"/>", main = "child")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> CircuitLibrarySource("b", child, "one") })).resolved
        assertEquals(1, loaded.changes(CircuitLibraryResolver { _, _ -> CircuitLibrarySource("b", child.replace("child", "changed"), "two") }).size)
        assertEquals(1, loaded.changes(CircuitLibraryResolver { _, _ -> null }).size)
    }

    @Test
    fun loadsRelativePathsInsidePortableArchive() {
        val child = project("<circuit name=\"Child\">$pin</circuit>", wiring, "Child")
        val root = project("<circuit name=\"main\"><comp name=\"Child\" lib=\"b\" loc=\"(30,30)\"/></circuit>",
            "<lib name=\"b\" desc=\"file#./library/child.circ\"/>")
        val base = CircBundleCodec.create(CircuitProject(listOf(CircuitDocument("main"))))
        val bundle = base.copy(files = base.files + mapOf("main.circ" to root.encodeToByteArray(), "library/child.circ" to child.encodeToByteArray()))
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle)).resolved
        assertEquals(2, loaded.project.circuits.size)
        assertEquals(ComponentKind.SUBCIRCUIT, loaded.project.mainCircuit.components.single().kind)
        assertTrue(loaded.project.libraries.values.none { it.startsWith("file#") })
        val flattened = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(CircBundleCodec.write(CircBundleCodec.create(loaded.project)))).bundle
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(flattened)).resolved
        assertEquals(loaded.project.circuits.map { it.id }, reopened.project.circuits.map { it.id })
    }

    @Test
    fun portableExportKeepsLibraryMetadataAndEditedDefinitionsWithoutAbsolutePaths() {
        val child = """<project source="3.8.0-HC" archive-note="keep-library-metadata"><lib name="w" desc="#Wiring"/>
            <options><a name="simrand" val="0"/></options><toolbar><tool lib="w" name="Pin"/></toolbar>
            <main name="Child" marker="keep-main"/><circuit name="Child" id="child-stable">$pin</circuit></project>""".trimIndent()
        val root = project("<circuit name=\"main\" id=\"main-stable\"><comp name=\"Child\" lib=\"b\" loc=\"(30,30)\"/></circuit>",
            "<lib name=\"b\" desc=\"file#/private/original/child.circ\"/>")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "/private/original/main.circ",
            CircuitLibraryResolver { _, _ -> CircuitLibrarySource("/private/original/child.circ", child, "rev1") })).resolved
        val definition = loaded.project.circuits.single { it.id != "main-stable" }
        val edited = loaded.project.withCircuit(definition.copy(components = listOf(definition.components.single().copy(label = "Edited"))))
            .renameCircuit(definition.name, "RenamedChild")
        val bundle = CircLibraryCodec.bundle(loaded, edited.copy(sourceVersion = "5.1.0-dev",
            metadata = edited.metadata + ("edited-root" to "preserved"),
            mainXml = "<main name=\"main\" edited=\"preserved\"/>"))
        assertEquals(3, bundle.files.size)
        val texts = bundle.files.values.joinToString { it.decodeToString() }
        assertTrue("/private/original" !in texts)
        assertTrue("keep-library-metadata" in texts)
        assertTrue("keep-main" in texts)
        assertTrue("<toolbar>" in texts)
        assertTrue("edited-root=\"preserved\"" in bundle.text("main.circ"))
        assertTrue("source=\"5.1.0-dev\"" in bundle.text("main.circ"))
        assertTrue("edited=\"preserved\"" in bundle.text("main.circ"))
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle)).resolved.project
        assertEquals(edited.circuits.map { it.id }.toSet(), reopened.circuits.map { it.id }.toSet())
        assertEquals("Edited", reopened.circuits.single { it.id == definition.id }.components.single().label)
        assertEquals("RenamedChild", reopened.mainCircuit.components.single().circuitName?.substringAfterLast('_'))
    }

    @Test
    fun packageRetainsEditsToBuiltinLibraryPreferences() {
        val root = project("<circuit name=\"main\">$pin</circuit>",
            "<lib name=\"w\" desc=\"#Wiring\" marker=\"before\"><tool name=\"Pin\"><a name=\"label\" val=\"old\"/></tool></lib>")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> null })).resolved
        val edited = loaded.project.copy(extraXml = loaded.project.extraXml.map {
            it.replace("marker=\"before\"", "marker=\"after\"").replace("val=\"old\"", "val=\"new\"")
        })
        val xml = CircLibraryCodec.bundle(loaded, edited).text("main.circ")
        val library = CircXmlParser(xml).parse().children.single { it.name == "lib" }
        assertEquals("after", library.attributes["marker"])
        assertEquals("new", library.children.single().children.single().attributes["val"])
    }

    @Test
    fun commentContainingTheSameXmlDoesNotReplaceTheRealInstance() {
        val component = "<comp name=\"Child\" lib=\"b\" loc=\"(30,30)\"/>"
        val root = project("<circuit name=\"main\"><!-- $component -->$component</circuit>",
            "<lib name=\"b\" desc=\"file#child.circ\"/>")
        val child = project("<circuit name=\"Child\"/>", main = "Child")
        val result = CircLibraryCodec.read(root, "main", CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", child) })
        assertIs<CircLibraryImportResult.Success>(result)
        assertEquals(ComponentKind.SUBCIRCUIT, result.resolved.project.mainCircuit.components.single().kind)
    }

    @Test
    fun bundleKeepsBuiltinAndExternalInstancesInTheirOriginalOrder() {
        val child = project("<circuit name=\"Child\">$pin</circuit>", wiring, "Child")
        val root = project("<circuit name=\"main\">$pin<comp name=\"Child\" lib=\"b\" loc=\"(40,40)\"/></circuit>",
            wiring + "<lib name=\"b\" desc=\"file#child.circ\"/>")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "main", CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", child) })).resolved
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(CircLibraryCodec.bundle(loaded))).resolved
        assertEquals(listOf(ComponentKind.INPUT, ComponentKind.SUBCIRCUIT), reopened.project.mainCircuit.components.map { it.kind })
        assertEquals(listOf(GridPoint(10, 10), GridPoint(40, 40)), reopened.project.mainCircuit.components.map { it.position })
    }

    @Test
    fun builtinToolbarToolDoesNotBecomeASameNamedSubcircuit() {
        val root = "<project>$wiring<toolbar><tool lib=\"w\" name=\"Pin\"/></toolbar><circuit name=\"main\"/><circuit name=\"Pin\"/></project>"
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> null })).resolved
        val bundle = CircLibraryCodec.bundle(loaded)
        val tool = CircXmlParser(bundle.text(bundle.mainFile)).parse().children.single { it.name == "toolbar" }.children.single()
        assertEquals("w", tool.attributes["lib"])
        assertEquals("Pin", tool.attributes["name"])
    }

    @Test
    fun renamingExternalCircuitUpdatesItsToolbarAndLibraryPreferences() {
        val child = "<project><main name=\"Child\"/><toolbar><tool name=\"Child\" lib=\"\"/></toolbar><circuit name=\"Child\"/></project>"
        val root = project("<circuit name=\"main\"><comp lib=\"b\" name=\"Child\" loc=\"(10,10)\"/></circuit>",
            "<lib name=\"b\" desc=\"file#child.circ\"><tool name=\"Child\"><a name=\"label\" val=\"custom\"/></tool></lib>")
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", child) })).resolved
        val oldName = loaded.project.mainCircuit.components.single().circuitName!!
        val bundle = CircLibraryCodec.bundle(loaded, loaded.project.renameCircuit(oldName, "Renamed"))
        val main = CircXmlParser(bundle.text(bundle.mainFile)).parse()
        assertEquals("Renamed", main.children.single { it.name == "lib" }.children.single().attributes["name"])
        val external = CircXmlParser(bundle.text(bundle.files.keys.single { it.startsWith("library/") })).parse()
        val tool = external.children.single { it.name == "toolbar" }.children.single()
        assertEquals("Renamed", tool.attributes["name"])
        assertTrue("lib" !in tool.attributes)
    }
}
