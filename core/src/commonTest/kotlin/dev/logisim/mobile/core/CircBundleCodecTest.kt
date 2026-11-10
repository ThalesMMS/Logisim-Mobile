package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CircBundleCodecTest {
    @Test
    fun decodesFixedAndDynamicDeflateFixturesOnEveryPlatform() {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("hello hello hello", BundleDeflate.inflate(bytes("cb48cdc9c957c8409000"), 17).decodeToString())
        assertEquals("Logisim ".repeat(1000), BundleDeflate.inflate(bytes("edc5310d00300800302b13848f858370e03f4108edd3e89f93f5c2b66ddbb66ddbb66ddbb6edc32f"), 8000).decodeToString())
        assertFailsWith<IllegalArgumentException> { BundleDeflate.inflate(bytes("cb48cdc9c957c8409000"), 8) }
    }

    @Test
    fun portableBundleReopensEveryCircuitAndPreservesMainMetadata() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project><main name="root" marker="keep"/><circuit name="root" id="root-id"><comp name="child" loc="(0,0)"/></circuit><circuit name="child" id="child-id"/></project>
        """.trimIndent())).project
        val encoded = CircBundleCodec.write(CircBundleCodec.create(project))
        val bundle = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(encoded)).bundle
        assertEquals("main.circ", bundle.mainFile)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(bundle.text(bundle.mainFile))).project
        assertEquals(listOf("root-id", "child-id"), reopened.circuits.map { it.id })
        assertEquals("child", reopened.mainCircuit.components.single().circuitName)
        assertEquals("keep", CircXmlParser(reopened.mainXml!!).parse().attributes["marker"])
        assertContentEquals(encoded, CircBundleCodec.write(bundle))
    }

    @Test
    fun corruptedEntryFailsItsChecksum() {
        val archive = CircBundleCodec.write(CircBundleCodec.create(CircuitProject(listOf(CircuitDocument("main")))))
        val corrupted = archive.copyOf()
        val start = 30 + "manifest.xml".encodeToByteArray().size
        corrupted[start] = (corrupted[start].toInt() xor 1).toByte()
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(corrupted))
        val localChecksum = archive.copyOf()
        localChecksum[14] = (localChecksum[14].toInt() xor 1).toByte()
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(localChecksum))
    }

    @Test
    fun pathsCannotEscapeThePackage() {
        for (path in listOf("../main.circ", "/main.circ", "C:\\main.circ", "library/../main.circ", "library/deep/child.circ", "library//child.circ", "library/CON.circ", "library/file.", "library/file:stream")) {
            assertFailsWith<IllegalArgumentException> { BundlePaths.file(path) }
        }
        assertEquals("library/child.circ", BundlePaths.resolve("main.circ", "./library/child.circ"))
        assertEquals("library/child.circ", BundlePaths.resolve("library/parent.circ", "child.circ"))
        assertFailsWith<IllegalArgumentException> { BundlePaths.resolve("main.circ", "../outside.circ") }
    }

    @Test
    fun rejectsManifestAttributesTheDesktopDoesNotAccept() {
        val bundle = CircBundleCodec.create(CircuitProject(listOf(CircuitDocument("main"))))
        val manifest = bundle.text("manifest.xml").replace("type=\"bundle\"", "extra=\"unsupported\" type=\"bundle\"")
        val bytes = BundleZip.write(bundle.files + ("manifest.xml" to manifest.encodeToByteArray()))
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(bytes))
    }
}
