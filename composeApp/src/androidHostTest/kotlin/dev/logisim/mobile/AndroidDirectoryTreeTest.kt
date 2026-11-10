package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidDirectoryTreeTest {
    @Test fun listsNestedCircuitsWithOriginalCaseWithoutReadingPayloads() {
        val access = FakeAccess(mapOf(
            ROOT to listOf(file("root-file", "z.CiRc"), dir("lib-id", "Lib"), file("notes", "readme.txt")),
            "lib-id" to listOf(file("nested", "adder.CIRC"), dir("nested-dir", "More")),
            "nested-dir" to listOf(file("second", "other.circ")),
        ))
        assertEquals(listOf(
            DirectoryCircuitFile("Lib/More/other.circ", "other.circ"),
            DirectoryCircuitFile("Lib/adder.CIRC", "adder.CIRC"),
            DirectoryCircuitFile("z.CiRc", "z.CiRc"),
        ), AndroidDirectoryTree(ROOT, access).circuitFiles(3))
        assertTrue(access.reads.isEmpty())
        assertEquals(16_383, access.queries.first().second)
    }

    @Test fun exactLookupKeepsOpaqueIdsSeparateFromDisplayPaths() {
        val folderId = "primary:a/b:opaque"
        val fileId = "provider://folder/nested/document:7"
        val content = DirectoryAssetContent("<project/>".encodeToByteArray(), "revision:7/unchanged")
        val access = FakeAccess(mapOf(ROOT to listOf(dir(folderId, "Lib")),
            folderId to listOf(file(fileId, "adder.CIRC"))), mapOf(fileId to content))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertSame(content, tree.read("Lib/adder.CIRC", 100))
        assertEquals(listOf(fileId to 100), access.reads)
        assertEquals("revision:7/unchanged", tree.read("Lib/adder.CIRC", 100)?.revision)
        assertNull(tree.read("lib/adder.CIRC", 100))
        assertNull(tree.read("Lib/adder.circ", 100))
        assertEquals(listOf(fileId to 100, fileId to 100), access.reads)
    }

    @Test fun missingPathsReturnNullWithoutQueryingUnselectedDirectories() {
        val content = DirectoryAssetContent(byteArrayOf(7), "r1")
        val access = FakeAccess(mapOf(ROOT to listOf(dir("chosen", "Chosen"), dir("other", "Other")),
            "chosen" to listOf(file("payload", "gate.lib"))), mapOf("payload" to content))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertSame(content, tree.read("Chosen/gate.lib", 10))
        assertEquals(listOf(ROOT, "chosen"), access.queries.map { it.first })
        assertNull(tree.read("Missing/gate.lib", 10))
        assertNull(tree.read("Chosen/missing.circ", 10))
        assertTrue(access.queries.none { it.first == "other" })
        assertEquals(listOf("payload" to 10), access.reads)
    }

    @Test fun fileExtensionsDoNotRestrictLibraryReads() {
        val content = DirectoryAssetContent(byteArrayOf(0, 0x80.toByte(), 0xff.toByte()), "binary-revision")
        val access = FakeAccess(mapOf(ROOT to listOf(file("library-id", "library.data"))), mapOf("library-id" to content))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertEquals(emptyList(), tree.circuitFiles(1))
        assertContentEquals(byteArrayOf(0, 0x80.toByte(), 0xff.toByte()), tree.read("library.data", 3)?.bytes)
    }

    @Test fun invalidRelativePathsFailBeforeAnyProviderCall() {
        val paths = listOf("", "/a.circ", "//a.circ", "a/", "a//b", ".", "..", "./a", "a/../b",
            "a/./b", "a\\b", "C:/a", "content://provider/a", "a:b", "a\u0000b", "a\nb", "a\u0080b")
        for (path in paths) {
            val access = FakeAccess()
            assertFailure("invalid_path") { AndroidDirectoryTree(ROOT, access).read(path, 1) }
            assertTrue(access.queries.isEmpty(), path)
            assertTrue(access.containmentCalls.isEmpty(), path)
        }
    }

    @Test fun unsafeNamesAreRejectedEvenWhenTheyAreNotCircuitFiles() {
        for (name in listOf("", ".", "..", "../other", "a/b", "a\\b", "a:b", "a\u0000b", "a\u007fb")) {
            val access = FakeAccess(mapOf(ROOT to listOf(file("good", "good.circ"), file("bad", name))))
            assertFailure("invalid_path") { AndroidDirectoryTree(ROOT, access).circuitFiles(1) }
            assertTrue(access.reads.isEmpty())
        }
    }

    @Test fun circuitOverflowFailsInsteadOfReturningTheFirstFile() {
        val access = FakeAccess(mapOf(ROOT to listOf(file("a", "a.circ"), file("b", "b.circ"))))
        assertFailure("too_many_entries") { AndroidDirectoryTree(ROOT, access).circuitFiles(1) }
        assertTrue(access.reads.isEmpty())
        assertEquals(1, access.queries.size)
    }

    @Test fun circuitLimitAccepts4096AndRejectsInvalidBounds() {
        val entries = List(4096) { file("id-$it", "file-$it.circ") }
        assertEquals(4096, AndroidDirectoryTree(ROOT, FakeAccess(mapOf(ROOT to entries))).circuitFiles(4096).size)
        for (limit in listOf(-1, 0, 4097, Int.MAX_VALUE)) {
            val access = FakeAccess()
            assertFailure("invalid_limit") { AndroidDirectoryTree(ROOT, access).circuitFiles(limit) }
            assertTrue(access.queries.isEmpty())
        }
    }

    @Test fun totalNodeBudgetIncludesTheRootAndIsPassedToEachQuery() {
        val atLimit = listOf(dir("nested", "Nested")) + List(16_381) { file("note-$it", "note-$it.txt") }
        val access = FakeAccess(mapOf(ROOT to atLimit, "nested" to listOf(file("last", "last.circ"))))
        assertEquals(listOf(DirectoryCircuitFile("Nested/last.circ", "last.circ")),
            AndroidDirectoryTree(ROOT, access).circuitFiles(1))
        assertEquals(listOf(ROOT to 16_383, "nested" to 1), access.queries)

        val overflow = FakeAccess(mapOf(ROOT to List(16_384) { file("id-$it", "note-$it.txt") }))
        assertFailure("too_many_entries") { AndroidDirectoryTree(ROOT, overflow).circuitFiles(1) }
    }

    @Test fun exhaustedNodeBudgetStillChecksWhetherTheLastDirectoryIsEmpty() {
        val root = listOf(dir("nested", "Nested")) + List(16_382) { file("note-$it", "note-$it.txt") }
        val empty = FakeAccess(mapOf(ROOT to root, "nested" to emptyList()))
        assertEquals(emptyList(), AndroidDirectoryTree(ROOT, empty).circuitFiles(1))
        assertEquals("nested" to 0, empty.queries.last())

        val overflow = FakeAccess(mapOf(ROOT to root, "nested" to listOf(file("last", "last.circ"))))
        assertFailure("too_many_entries") { AndroidDirectoryTree(ROOT, overflow).circuitFiles(1) }
        assertEquals("nested" to 0, overflow.queries.last())
        assertFailure("too_many_entries") { AndroidDirectoryTree(ROOT, overflow).read("Nested/last.circ", 10) }
    }

    @Test fun sixtyFourSegmentsAreAllowedButDeeperTreesFailClosed() {
        val accepted = chain(63)
        val acceptedPath = (1..63).joinToString("/") { "d$it" } + "/leaf.circ"
        val access = FakeAccess(accepted, mapOf("leaf" to DirectoryAssetContent(byteArrayOf(1))))
        assertEquals(listOf(DirectoryCircuitFile(acceptedPath, "leaf.circ")),
            AndroidDirectoryTree(ROOT, access).circuitFiles(1))
        assertContentEquals(byteArrayOf(1), AndroidDirectoryTree(ROOT, access).read(acceptedPath, 1)?.bytes)

        assertFailure("too_deep") { AndroidDirectoryTree(ROOT, FakeAccess(chain(64))).circuitFiles(1) }
        val tooDeepRead = FakeAccess()
        assertFailure("too_deep") { AndroidDirectoryTree(ROOT, tooDeepRead).read("d/".repeat(64) + "leaf.circ", 1) }
        assertTrue(tooDeepRead.queries.isEmpty())
    }

    @Test fun rootAliasesDuplicateIdsAndCyclesAreRejected() {
        val graphs = listOf(
            mapOf(ROOT to listOf(file(ROOT, "root.circ"))),
            mapOf(ROOT to listOf(file("shared", "a.circ"), file("shared", "b.circ"))),
            mapOf(ROOT to listOf(dir("d", "Dir")), "d" to listOf(dir("d", "Again"))),
            mapOf(ROOT to listOf(dir("a", "A"), dir("b", "B")),
                "a" to listOf(file("shared", "a.circ")), "b" to listOf(file("shared", "b.circ"))),
        )
        for (graph in graphs) assertFailure("alias") { AndroidDirectoryTree(ROOT, FakeAccess(graph)).circuitFiles(10) }
    }

    @Test fun caseCollisionsAreRejectedInUnrelatedNonCircuitSiblingsToo() {
        for (names in listOf("A.circ" to "a.CIRC", "Readme.txt" to "README.TXT", "σ.txt" to "ς.txt")) {
            val access = FakeAccess(mapOf(ROOT to listOf(file("target", "target.circ"),
                file("one", names.first), file("two", names.second))))
            assertFailure("ambiguous") { AndroidDirectoryTree(ROOT, access).circuitFiles(10) }
            assertFailure("ambiguous") { AndroidDirectoryTree(ROOT, access).read("target.circ", 10) }
            assertTrue(access.reads.isEmpty())
        }
    }

    @Test fun theSameDisplayNameInDifferentDirectoriesIsUnambiguous() {
        val access = FakeAccess(mapOf(ROOT to listOf(dir("a", "A"), dir("b", "B")),
            "a" to listOf(file("first", "main.circ")), "b" to listOf(file("second", "main.circ"))),
            mapOf("first" to DirectoryAssetContent(byteArrayOf(1)), "second" to DirectoryAssetContent(byteArrayOf(2))))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertEquals(listOf(DirectoryCircuitFile("A/main.circ", "main.circ"),
            DirectoryCircuitFile("B/main.circ", "main.circ")), tree.circuitFiles(2))
        assertContentEquals(byteArrayOf(2), tree.read("B/main.circ", 10)?.bytes)
    }

    @Test fun virtualAndUnverifiableEntriesFailBeforeReading() {
        for (directory in listOf(false, true)) {
            val access = FakeAccess(mapOf(ROOT to listOf(AndroidDirectoryEntry("virtual", "entry", directory, virtual = true))))
            assertFailure("virtual") { AndroidDirectoryTree(ROOT, access).circuitFiles(1) }
            assertFailure("virtual") { AndroidDirectoryTree(ROOT, access).read("entry", 10) }
            assertTrue(access.reads.isEmpty())
        }
        val outside = FakeAccess(mapOf(ROOT to listOf(file("outside", "outside.circ"))))
        outside.containment = { _, _ -> false }
        assertFailure("unverifiable") { AndroidDirectoryTree(ROOT, outside).read("outside.circ", 10) }
        assertTrue(outside.reads.isEmpty())
    }

    @Test fun containmentIsCheckedAgainBeforeDescendingOrReadingContent() {
        val directory = FakeAccess(mapOf(ROOT to listOf(dir("d", "Dir")), "d" to listOf(file("f", "leaf.circ"))))
        directory.containment = { id, call -> id != "d" || call == 1 }
        assertFailure("unverifiable") { AndroidDirectoryTree(ROOT, directory).read("Dir/leaf.circ", 10) }
        assertEquals(listOf(ROOT), directory.queries.map { it.first })

        val fileAccess = FakeAccess(mapOf(ROOT to listOf(file("f", "leaf.circ"))))
        fileAccess.containment = { _, call -> call == 1 }
        assertFailure("unverifiable") { AndroidDirectoryTree(ROOT, fileAccess).read("leaf.circ", 10) }
        assertTrue(fileAccess.reads.isEmpty())
    }

    @Test fun aDirectoryIsNotAFileAndAFileCannotBeAnAncestor() {
        val access = FakeAccess(mapOf(ROOT to listOf(dir("d", "Dir"), file("f", "file.circ"))))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertFailure("not_file") { tree.read("Dir", 10) }
        assertNull(tree.read("file.circ/other", 10))
        assertNull(tree.read("missing.circ", 10))
        assertNull(tree.read("file.circ", 10))
        assertEquals(listOf("f" to 10), access.reads)
        assertTrue(access.queries.all { it.first == ROOT })
    }

    @Test fun contentLimitAcceptsTwoMillionBytesAndRejectsOversizedReaderResults() {
        val exact = DirectoryAssetContent(ByteArray(2_000_000) { (it and 255).toByte() }, "max-revision")
        val access = FakeAccess(mapOf(ROOT to listOf(file("f", "large.lib"))), mapOf("f" to exact))
        assertSame(exact, AndroidDirectoryTree(ROOT, access).read("large.lib", 2_000_000))
        assertEquals(listOf("f" to 2_000_000), access.reads)
        assertEquals("max-revision", exact.revision)

        val overflow = FakeAccess(mapOf(ROOT to listOf(file("f", "large.lib"))),
            mapOf("f" to DirectoryAssetContent(byteArrayOf(1, 2, 3, 4))))
        assertFailure("too_large") { AndroidDirectoryTree(ROOT, overflow).read("large.lib", 3) }
        for (limit in listOf(-1, 0, 2_000_001, Int.MAX_VALUE)) {
            val unused = FakeAccess()
            assertFailure("invalid_limit") { AndroidDirectoryTree(ROOT, unused).read("file.circ", limit) }
            assertTrue(unused.queries.isEmpty())
        }
    }

    @Test fun contentKeepsDefensiveBytesAndTheReadersRevision() {
        val source = byteArrayOf(1, 2, 3)
        val content = DirectoryAssetContent(source, "opaque:revision/with/slashes")
        source[0] = 99
        val access = FakeAccess(mapOf(ROOT to listOf(file("f", "data.lib"))), mapOf("f" to content))
        val loaded = AndroidDirectoryTree(ROOT, access).read("data.lib", 3)!!
        loaded.bytes[1] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.bytes)
        assertEquals("opaque:revision/with/slashes", loaded.revision)
    }

    @Test fun providerScopeDenialNeverFallsBackToAnUnscopedRead() {
        val access = FakeAccess(mapOf(ROOT to listOf(file("foreign:opaque/id", "outside.circ"))),
            mapOf("foreign:opaque/id" to DirectoryAssetContent("outside".encodeToByteArray())))
        access.containment = { _, _ -> throw SecurityException("Provider rejected tree-scoped query") }
        assertFailsWith<SecurityException> { AndroidDirectoryTree(ROOT, access).read("outside.circ", 100) }
        assertTrue(access.reads.isEmpty())
        assertEquals(listOf(ROOT), access.queries.map { it.first })
    }

    @Test fun aDocumentMovedOutsideTheTreeAfterListingIsNotRead() {
        val access = FakeAccess(mapOf(ROOT to listOf(file("opaque:file", "main.circ"))),
            mapOf("opaque:file" to DirectoryAssetContent("original".encodeToByteArray(), "old")))
        val tree = AndroidDirectoryTree(ROOT, access)
        assertEquals(listOf(DirectoryCircuitFile("main.circ", "main.circ")), tree.circuitFiles(1))
        access.containment = { _, _ -> false }
        assertFailure("unverifiable") { tree.read("main.circ", 100) }
        assertTrue(access.reads.isEmpty())
    }

    private fun assertFailure(reason: String, action: () -> Unit) {
        val error = assertFailsWith<LocalizedArgumentException>(block = action)
        assertEquals("android_directory_$reason", error.text.key)
    }

    private fun chain(directories: Int): Map<String, List<AndroidDirectoryEntry>> {
        val graph = mutableMapOf<String, List<AndroidDirectoryEntry>>()
        var parent = ROOT
        for (number in 1..directories) {
            val id = "opaque:$number/dir"
            graph[parent] = listOf(dir(id, "d$number"))
            parent = id
        }
        graph[parent] = listOf(file("leaf", "leaf.circ"))
        return graph
    }

    private class FakeAccess(
        private val graph: Map<String, List<AndroidDirectoryEntry>> = emptyMap(),
        private val contents: Map<String, DirectoryAssetContent> = emptyMap(),
    ) : AndroidDirectoryAccess {
        val queries = mutableListOf<Pair<String, Int>>()
        val reads = mutableListOf<Pair<String, Int>>()
        val containmentCalls = mutableListOf<String>()
        private val containmentCounts = mutableMapOf<String, Int>()
        var containment: (String, Int) -> Boolean = { _, _ -> true }

        override fun children(parentId: String, maximumEntries: Int): List<AndroidDirectoryEntry> {
            queries += parentId to maximumEntries
            return graph[parentId].orEmpty()
        }

        override fun contained(documentId: String): Boolean {
            containmentCalls += documentId
            val count = (containmentCounts[documentId] ?: 0) + 1
            containmentCounts[documentId] = count
            return containment(documentId, count)
        }

        override fun read(documentId: String, maximumBytes: Int): DirectoryAssetContent? {
            reads += documentId to maximumBytes
            return contents[documentId]
        }
    }

    private companion object {
        const val ROOT = "primary:opaque/root"
        fun dir(id: String, name: String) = AndroidDirectoryEntry(id, name, directory = true)
        fun file(id: String, name: String) = AndroidDirectoryEntry(id, name, directory = false)
    }
}
