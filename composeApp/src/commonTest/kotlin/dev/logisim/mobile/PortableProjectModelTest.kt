package dev.logisim.mobile

import dev.logisim.mobile.core.CircBundleCodec
import dev.logisim.mobile.core.CircBundleImportResult
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircLibraryCodec
import dev.logisim.mobile.core.CircLibraryImportResult
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ExtensionReferenceScanner
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableProjectModelTest {
    private val child = """<project source="5.1.0" library-note="retained"><main name="Child"/>
        <circuit name="Child" id="child-stable"><a name="clabel" val="child label"/></circuit></project>"""
    private val root = """<project source="5.1.0"><lib name="b" desc="file#../logic/child.circ"/>
        <main name="main"/><circuit name="main" id="root-stable"><comp lib="b" name="Child" loc="(30,30)"/></circuit></project>"""

    private class Grant(val contents: MutableMap<String, String>) : DirectoryAssetGrant {
        override val id = "content://selected-directory/opaque:identity"
        override val name = "Project sources"
        var releases = 0
        val reads = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        var readOverride: ((String, Int) -> DirectoryAssetContent?)? = null
        override fun circuitFiles(maximumEntries: Int) = contents.keys.map { DirectoryCircuitFile(it, it.substringAfterLast('/')) }
        override fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent? {
            check(maximumBytes == CircBundleCodec.MAX_FILE_BYTES)
            reads += relativePath
            val count = (counts[relativePath] ?: 0) + 1
            counts[relativePath] = count
            return readOverride?.invoke(relativePath, count) ?: contents[relativePath]?.let {
                DirectoryAssetContent(it.encodeToByteArray(), "revision-1")
            }
        }
        override fun release() { releases++ }
    }
    private fun granted() = Grant(mutableMapOf("project/main.circ" to root, "logic/child.circ" to child))
    private fun prepared(grant: Grant = granted()) = assertIs<PortableProjectReadResult.Ready>(
        preparePortableDirectory(grant, "project/main.circ")).copy
    private fun bundle(asset: DocumentAsset) = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(asset.bytes)).bundle
    private fun project(asset: DocumentAsset) = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle(asset))).resolved.project

    @Test
    fun approvedGrayProjectRoundTripsWithItsLegacyDescriptor() {
        val reference = ExtensionReferenceScanner.inspect(grayLegacyProjectXml).jarLibraries.single()
        val installed = installedGrayCatalog()
        val registry = installed.withLegacyLibrary(reference, GrayCodeExtension.provider.manifest.id).registry
        val original = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(grayLegacyProjectXml, registry)).project
        val archive = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(original, registry = registry)).asset
        val packaged = bundle(archive)
        assertEquals(setOf("manifest.xml", "main.circ"), packaged.files.keys)
        assertEquals(reference.descriptor, ExtensionReferenceScanner.inspect(packaged.text("main.circ")).jarLibraries.single().descriptor)
        val unbound = assertIs<PortableProjectReadResult.Failure>(preparePortableBundle(archive, installed.registry))
        assertTrue(unbound.notices.single().resolve().contains(reference.descriptor))

        val reopened = assertIs<PortableProjectReadResult.Ready>(preparePortableBundle(archive, registry)).copy
        val main = reopened.project.mainCircuit
        val added = registry.create(GrayCodeExtension.incrementerKind, "added", GridPoint(100, 100))
        val edited = reopened.project.withCircuit(main.copy(components = main.components + added))
        val reexported = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(edited, reopened.sourceArchive, registry)).asset
        val reexportedXml = bundle(reexported).text("main.circ")
        assertEquals(reference.descriptor, ExtensionReferenceScanner.inspect(reexportedXml).jarLibraries.single().descriptor)
        val roundTrip = assertIs<PortableProjectReadResult.Ready>(preparePortableBundle(reexported, registry)).copy.project
        assertEquals(listOf(reference.descriptor, "#dev.logisim.gray"), roundTrip.mainCircuit.components.mapNotNull {
            registry.definition(it.kind)?.takeIf { definition -> definition.fileName == "Gray Code Incrementer" }?.library
        })

        val grant = Grant(mutableMapOf("main.circ" to grayLegacyProjectXml))
        val directory = assertIs<PortableProjectReadResult.Ready>(preparePortableDirectory(grant, "main.circ", registry)).copy
        assertEquals(reference.descriptor, ExtensionReferenceScanner.inspect(bundle(directory.sourceArchive).text("main.circ")).jarLibraries.single().descriptor)
        assertEquals(listOf("main.circ", "main.circ"), grant.reads)
        assertEquals(1, grant.releases)
    }

    @Test
    fun grantedSourceGraphBecomesAnIndependentPortableWorkspaceSnapshot() {
        val grant = granted()
        val copy = prepared(grant)
        assertEquals(1, grant.releases)
        assertEquals(setOf("project/main.circ", "logic/child.circ"), grant.reads.toSet())
        assertEquals("main", copy.name)
        assertEquals(2, copy.project.circuits.size)
        assertEquals(ComponentKind.SUBCIRCUIT, copy.project.mainCircuit.components.single().kind)
        assertEquals("root-stable", copy.project.mainCircuit.id)
        val packaged = bundle(copy.sourceArchive)
        assertEquals(3, packaged.files.size)
        val xml = packaged.files.filterKeys { it.endsWith(".circ") }.values.joinToString { it.decodeToString() }
        assertTrue("content://" !in xml)
        assertTrue("file#./library/" in xml)
        assertTrue("library-note=\"retained\"" in xml)
        grant.contents.clear()
        val exported = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(copy.project, copy.sourceArchive)).asset
        assertEquals(copy.project.circuits.map { it.id }.toSet(), project(exported).circuits.map { it.id }.toSet())
        assertEquals(1, grant.releases)
    }

    private fun openedDocument(xml: String = root, name: String = "main.circ") =
        DocumentContent(DocumentReference("picked", name, false), xml)

    @Test
    fun guidedOpenContinuesOnlyTheOriginalDocumentThroughTheAuthorizedFolder() {
        val legacyRoot = root.replace("5.1.0", "2.7.1")
        assertTrue(needsExternalLibraryFolder(CircCodec.readProject(legacyRoot)))
        assertFalse(needsExternalLibraryFolder(CircCodec.readProject(grayLegacyProjectXml)))
        assertFalse(needsExternalLibraryFolder(CircCodec.readProject("<project/>")))
        val grant = granted().also { it.contents["project/main.circ"] = "\uFEFF" + legacyRoot }
        val copy = assertIs<PortableProjectReadResult.Ready>(
            preparePortableDirectoryForDocument(grant, openedDocument(legacyRoot), ComponentCatalog.registry)).copy
        assertEquals(1, grant.releases)
        assertEquals(setOf("project/main.circ", "logic/child.circ"), grant.reads.toSet())
        assertEquals("root-stable", copy.project.mainCircuit.id)
        assertEquals(ComponentKind.SUBCIRCUIT, copy.project.mainCircuit.components.single().kind)
        assertEquals(2, project(copy.sourceArchive).circuits.size)
        assertEquals("\uFEFF" + legacyRoot, grant.contents["project/main.circ"])
    }

    @Test
    fun guidedOpenRejectsDifferentRootsAndAmbiguousFoldersBeforeReadingLibraries() {
        val wrong = granted().also { it.contents["project/main.circ"] = root.replace("root-stable", "different") }
        val ambiguous = granted().also { it.contents["other/main.circ"] = root }
        val missing = granted().also { it.contents.remove("project/main.circ") }
        for ((grant, expectedKey) in listOf(wrong to "portable_guided_source_mismatch",
            ambiguous to "portable_guided_folder_match", missing to "portable_guided_folder_match")) {
            val result = assertIs<PortableProjectReadResult.Failure>(
                preparePortableDirectoryForDocument(grant, openedDocument(), ComponentCatalog.registry))
            assertEquals(expectedKey, result.notices.single().key)
            assertTrue("logic/child.circ" !in grant.reads)
            assertEquals(1, grant.releases)
        }
    }

    @Test
    fun guidedOpenDoesNotReadBeyondTheGrantedFolder() {
        val escaping = root.replace("../logic/child.circ", "../../logic/child.circ")
        val grant = granted().also { it.contents["project/main.circ"] = escaping }
        val result = assertIs<PortableProjectReadResult.Failure>(
            preparePortableDirectoryForDocument(grant, openedDocument(escaping), ComponentCatalog.registry))
        assertEquals("portable_directory_reference_outside", result.notices.single().key)
        assertEquals(listOf("project/main.circ"), grant.reads)
        assertEquals(1, grant.releases)
    }

    @Test
    fun sourceSidecarPreservesSeparateDefinitionsAfterWorkspaceXmlReloadAndRename() {
        val copy = prepared()
        val old = copy.project.mainCircuit.components.single().circuitName!!
        val edited = copy.project.renameCircuit(old, "Renamed")
        val restored = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(edited))).project
        val exported = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(restored, copy.sourceArchive)).asset
        val packaged = bundle(exported)
        assertEquals(3, packaged.files.size)
        val external = packaged.files.filterKeys { it.startsWith("library/") }.values.single().decodeToString()
        assertTrue("name=\"Renamed\"" in external)
        assertTrue("library-note=\"retained\"" in external)
        assertEquals(restored.circuits.map { it.id }.toSet(), project(exported).circuits.map { it.id }.toSet())
    }

    @Test
    fun opaqueFilesAndUnreferencedCircuitsSurviveBundleExport() {
        val source = prepared().sourceArchive
        val original = bundle(source)
        val extras = mapOf("notes.txt" to "KEEP notes".encodeToByteArray(),
            "library/unused.circ" to "<project><circuit name=\"unused\"/></project>".encodeToByteArray())
        val asset = DocumentAsset("extras.lsebdl", PORTABLE_PROJECT_MIME_TYPE,
            CircBundleCodec.write(original.copy(files = original.files + extras)))
        val opened = assertIs<PortableProjectReadResult.Ready>(preparePortableBundle(asset)).copy
        val exported = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(opened.project, opened.sourceArchive)).asset
        val files = bundle(exported).files
        for ((name, bytes) in extras) assertContentEquals(bytes, files[name])
    }

    @Test
    fun opaqueFileCollisionFailsWithoutOverwritingTheOriginalArchive() {
        val copy = prepared()
        val original = bundle(copy.sourceArchive)
        val next = bundle(assertIs<PortableProjectExportResult.Ready>(exportPortableProject(copy.project, copy.sourceArchive)).asset)
        val nextPath = next.files.keys.single { it.startsWith("library/") }
        assertTrue(nextPath !in original.files)
        val bytes = CircBundleCodec.write(original.copy(files = original.files + (nextPath to "KEEP".encodeToByteArray())))
        val opened = assertIs<PortableProjectReadResult.Ready>(preparePortableBundle(DocumentAsset("collision.lsebdl", PORTABLE_PROJECT_MIME_TYPE, bytes))).copy
        val result = assertIs<PortableProjectExportResult.Failure>(exportPortableProject(opened.project, opened.sourceArchive))
        assertEquals("portable_bundle_extra_collision", result.notices.single().key)
        assertContentEquals(bytes, opened.sourceArchive.bytes)
    }

    @Test
    fun detectsRootSourceChangeBeforeOpeningWorkspaceCopy() {
        val grant = granted()
        grant.readOverride = { path, count ->
            grant.contents[path]?.let { DirectoryAssetContent(it.encodeToByteArray(), if (path == "project/main.circ" && count > 1) "new-revision" else "revision-1") }
        }
        val result = assertIs<PortableProjectReadResult.Failure>(preparePortableDirectory(grant, "project/main.circ"))
        assertEquals("portable_directory_changed", result.notices.single().key)
        assertEquals(1, grant.releases)
    }

    @Test
    fun detectsExternalSourceChangeBeforeOpeningWorkspaceCopy() {
        val grant = granted()
        grant.readOverride = { path, count ->
            grant.contents[path]?.let { DirectoryAssetContent(it.encodeToByteArray(), if (path == "logic/child.circ" && count > 1) "new-revision" else "revision-1") }
        }
        val result = assertIs<PortableProjectReadResult.Failure>(preparePortableDirectory(grant, "project/main.circ"))
        assertTrue(result.notices.isNotEmpty())
        assertEquals(1, grant.releases)
    }

    @Test
    fun outsideAbsoluteAndNetworkReferencesNeverReachTheGrantedReader() {
        for (reference in listOf("../../outside.circ", "/private/outside.circ", "https://example.test/lib.circ", "C:\\lib.circ", "\\\\server\\share\\lib.circ", "\\lib.circ")) {
            val grant = granted()
            grant.contents["project/main.circ"] = root.replace("../logic/child.circ", reference)
            assertIs<PortableProjectReadResult.Failure>(preparePortableDirectory(grant, "project/main.circ"))
            assertEquals(listOf("project/main.circ"), grant.reads)
            assertEquals(1, grant.releases)
        }
    }

    @Test
    fun missingDependencyRejectsImportAndReleasesTheFolder() {
        val grant = granted()
        grant.contents.remove("logic/child.circ")
        assertIs<PortableProjectReadResult.Failure>(preparePortableDirectory(grant, "project/main.circ"))
        assertEquals(1, grant.releases)
    }

    @Test
    fun providerSizeAndXmlEncodingFailuresAreTypedAndReleaseTheFolder() {
        val failures = listOf(
            byteArrayOf(0xff.toByte()) to message(Messages.CodecXmlEncodingInvalid, "encoding" to "UTF-8").key,
            ByteArray(CircBundleCodec.MAX_FILE_BYTES + 1) to "portable_directory_file_too_large",
        )
        for ((bytes, expectedKey) in failures) {
            val grant = granted()
            grant.readOverride = { _, _ -> DirectoryAssetContent(bytes) }
            val result = assertIs<PortableProjectReadResult.Failure>(preparePortableDirectory(grant, "project/main.circ"))
            assertEquals(expectedKey, result.notices.single().key)
            assertEquals(1, grant.releases)
        }
    }

    @Test
    fun brokenSourceSidecarIsRejectedRatherThanExportingAFlattenedReplacement() {
        val result = exportPortableProject(CircuitProject(listOf(CircuitDocument("main"))), DocumentAsset("broken.lsebdl", PORTABLE_PROJECT_MIME_TYPE, byteArrayOf(1, 2)))
        assertIs<PortableProjectExportResult.Failure>(result)
    }

    @Test
    fun malformedBundleDoesNotInvokeWorkspaceOpenerOrChangeFileReference() {
        var reference = "previous-editable.circ"
        var opens = 0
        val opener = PortableProjectCopyOpener { _, _, completed ->
            opens++
            reference = ""
            completed(PortableProjectCopyOpenResult.Opened(2))
        }
        val result = preparePortableBundle(DocumentAsset("broken.lsebdl", PORTABLE_PROJECT_MIME_TYPE, byteArrayOf(0, 1)))
        if (result is PortableProjectReadResult.Ready) opener.openCopy(result.copy, 1) {}
        assertIs<PortableProjectReadResult.Failure>(result)
        assertEquals(0, opens)
        assertEquals("previous-editable.circ", reference)
    }

    @Test
    fun acceptedCopyHasANewIdentityAndSourcesNeverFollowAnotherWorkspace() {
        val copy = prepared()
        val session = PortableProjectSourceSession()
        var currentIdentity = 41L
        var nativeReference: String? = "old-editable.circ"
        val opener = PortableProjectCopyOpener { accepted, expected, completed ->
            assertEquals(41L, expected)
            assertEquals(copy, accepted)
            nativeReference = null
            currentIdentity = 42L
            completed(PortableProjectCopyOpenResult.Opened(currentIdentity))
        }
        opener.openCopy(copy, currentIdentity) { result ->
            session.opened(assertIs<PortableProjectCopyOpenResult.Opened>(result).documentIdentity, copy)
        }
        assertNull(nativeReference)
        assertNotEquals(41L, currentIdentity)
        assertNull(session.archiveFor(41))
        assertContentEquals(copy.sourceArchive.bytes, session.archiveFor(42)!!.bytes)
        session.retainOnly(43)
        assertNull(session.archiveFor(42))
        assertNull(session.archiveFor(43))
    }

    @Test
    fun pathsAndDirectoryEntriesRejectAmbiguityBeforeShowingProjectChoices() {
        assertEquals("logic/child.circ", DirectoryAssetPaths.resolve("project/main.circ", "../logic/child.circ"))
        assertEquals("logic/child.circ", DirectoryAssetPaths.resolve("project/main.circ", "..\\logic\\child.circ"))
        for (path in listOf("../x", "/x", "a//b", "a/./b", "a/../b", "a\\b", "x:y", "a\n"))
            assertFailsWith<LocalizedArgumentException> { DirectoryAssetPaths.canonical(path) }
        val grant = object : DirectoryAssetGrant {
            override val id = "grant"
            override val name = "folder"
            override fun circuitFiles(maximumEntries: Int) = listOf(DirectoryCircuitFile("a.circ", "a"), DirectoryCircuitFile("a.circ", "b"))
            override fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent? = null
            override fun release() = Unit
        }
        assertFailsWith<LocalizedArgumentException> { portableDirectoryFiles(grant) }
    }

    @Test
    fun plainProjectExportReopensAsAStandaloneBundle() {
        val original = CircuitProject(listOf(CircuitDocument("main", id = "stable-main")))
        val exported = assertIs<PortableProjectExportResult.Ready>(exportPortableProject(original)).asset
        assertEquals("main.lsebdl", exported.name)
        assertEquals(PORTABLE_PROJECT_MIME_TYPE, exported.mimeType)
        assertEquals("stable-main", project(exported).mainCircuit.id)
    }
}
