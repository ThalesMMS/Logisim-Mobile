@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedStateException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURLBookmarkCreationSuitableForBookmarkFile
import platform.Foundation.NSURLIsAliasFileKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeToURL
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_NOFOLLOW_ANY
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open as posixOpen
import platform.posix.posix_errno
import platform.posix.symlink

class IosDirectoryAssetActionsTest {
    @Test
    fun userDocumentsFolderSupportsAStoredCircuit() {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        withDirectoryAt(documents) { root ->
            val canonical = assertNotNull(root.URLByResolvingSymlinksInPath?.path)
            val flags = O_RDONLY or O_DIRECTORY or O_CLOEXEC
            val redundant = posixOpen(canonical, flags or O_NOFOLLOW or O_NOFOLLOW_ANY)
            val redundantError = if (redundant < 0) posix_errno() else 0
            if (redundant >= 0) close(redundant)
            val strong = posixOpen(canonical, flags or O_NOFOLLOW_ANY)
            val strongError = if (strong < 0) posix_errno() else 0
            println("Native Documents folder flag probe: redundantErrno=$redundantError, nofollowAnyErrno=$strongError, path=$canonical")
            assertTrue(strong >= 0, "O_NOFOLLOW_ANY must open the canonical Documents folder; errno=$strongError")
            close(strong)

            val xml = CircCodec.write(CircuitDocument("DocumentsFolder"))
            write(root, "project.circ", xml.encodeToByteArray())
            val grant = IosDirectoryAssetGrant.open(root)
            try {
                assertEquals(listOf(DirectoryCircuitFile("project.circ", "project.circ")), grant.circuitFiles(1))
                val opened = assertNotNull(grant.read("project.circ", 1024 * 1024))
                assertContentEquals(xml.encodeToByteArray(), opened.bytes)
                assertEquals("DocumentsFolder", assertIs<CircImportResult.Success>(CircCodec.read(opened.bytes.decodeToString())).document.name)
            } finally { grant.release() }
        }
    }

    @Test
    fun recursiveListingKeepsCanonicalPathsAndCircuitExtensions() = withDirectory { root ->
        write(root, "main.circ", "abc".encodeToByteArray())
        write(root, "lib/Adder.CIRC", "child".encodeToByteArray())
        write(root, "lib/image.png", byteArrayOf(0, -1, 127))
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            assertEquals(root.lastPathComponent, grant.name)
            assertEquals(listOf(
                DirectoryCircuitFile("lib/Adder.CIRC", "Adder.CIRC"),
                DirectoryCircuitFile("main.circ", "main.circ"),
            ), grant.circuitFiles(2))
            assertContentEquals("child".encodeToByteArray(), assertNotNull(grant.read("lib/Adder.CIRC", 5)).bytes)
        } finally { grant.release() }
    }

    @Test
    fun readsExactBytesWithSha256AndDefensiveCopies() = withDirectory { root ->
        write(root, "main.circ", "abc".encodeToByteArray())
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            val content = assertNotNull(grant.read("main.circ", 3))
            assertEquals(3, content.size)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", content.revision)
            val copy = content.bytes
            copy[0] = 0
            assertContentEquals("abc".encodeToByteArray(), content.bytes)
            write(root, "main.circ", "abd".encodeToByteArray())
            assertNotEquals(content.revision, assertNotNull(grant.read("main.circ", 3)).revision)
        } finally { grant.release() }
    }

    @Test
    fun emptyResourcesHaveTheEmptySha256AndMissingFilesReturnNull() = withDirectory { root ->
        write(root, "empty.circ", byteArrayOf())
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            val empty = assertNotNull(grant.read("empty.circ", 1))
            assertEquals(0, empty.size)
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.revision)
            assertNull(grant.read("missing.circ", 1024))
            assertNull(grant.read("missing/child.circ", 1024))
        } finally { grant.release() }
    }

    @Test
    fun fileLimitIsCheckedBeforeCopyAndAcceptsExactlyTheLimit() = withDirectory { root ->
        write(root, "main.circ", byteArrayOf(0, -1, -128, 127))
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            assertContentEquals(byteArrayOf(0, -1, -128, 127), assertNotNull(grant.read("main.circ", 4)).bytes)
            val error = assertFailsWith<LocalizedArgumentException> { grant.read("main.circ", 3) }
            assertEquals("ios_directory_too_large", error.text.key)
            for (limit in listOf(0, -1)) {
                assertEquals("ios_directory_too_large", assertFailsWith<LocalizedArgumentException> {
                    grant.read("main.circ", limit)
                }.text.key)
            }
        } finally { grant.release() }
    }

    @Test
    fun platformLimitRejectsSparseFilesBeforeAllocation() = withDirectory { root ->
        val file = write(root, "large.circ", byteArrayOf(1))
        val handle = assertNotNull(NSFileHandle.fileHandleForWritingAtPath(assertNotNull(file.path)))
        try { handle.truncateFileAtOffset(64uL * 1024uL * 1024uL + 1uL) } finally { handle.closeFile() }
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            assertEquals("ios_directory_too_large", assertFailsWith<LocalizedArgumentException> {
                grant.read("large.circ", Int.MAX_VALUE)
            }.text.key)
        } finally { grant.release() }
    }

    @Test
    fun malformedPathsCannotEscapeOrAliasTheRoot() = withDirectory { root ->
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            val invalid = listOf("", "/etc/passwd", "../outside.circ", "lib/../main.circ", "./main.circ", "lib//main.circ",
                "main.circ/", "lib\\main.circ", "file:main.circ", "C:/main.circ", "main\u0000.circ", "main\n.circ", "main\u007f.circ", "main\u0085.circ")
            for (path in invalid) {
                assertEquals("ios_directory_unsafe_path", assertFailsWith<LocalizedArgumentException>(path) {
                    grant.read(path, 1024)
                }.text.key)
            }
        } finally { grant.release() }
    }

    @Test
    fun symlinkFilesAndDirectoriesAreNeitherListedNorFollowed() = withDirectory { root ->
        withDirectory { outside ->
            write(root, "safe.circ", byteArrayOf(1))
            val secret = write(outside, "secret.circ", byteArrayOf(42))
            assertEquals(0, symlink(secret.path, root.path + "/link.circ"))
            assertEquals(0, symlink(outside.path, root.path + "/linked"))
            val grant = IosDirectoryAssetGrant.open(root)
            try {
                assertEquals(listOf(DirectoryCircuitFile("safe.circ", "safe.circ")), grant.circuitFiles(10))
                for (path in listOf("link.circ", "linked/secret.circ")) {
                    assertEquals("ios_directory_unsafe_path", assertFailsWith<LocalizedStateException> {
                        grant.read(path, 1024)
                    }.text.key)
                }
                assertContentEquals(byteArrayOf(42), assertNotNull(IosDirectoryAssetGrant.open(outside).let {
                    try { it.read("secret.circ", 1024) } finally { it.release() }
                }).bytes)
            } finally { grant.release() }
        }
    }

    @Test
    fun finderAliasesCannotImportFilesOutsideTheSelectedFolder() = withDirectory { root ->
        withDirectory { outside ->
            val secret = write(outside, "secret.circ", byteArrayOf(42))
            val bookmark = assertNotNull(secret.bookmarkDataWithOptions(
                NSURLBookmarkCreationSuitableForBookmarkFile, null, null, null,
            ))
            val alias = NSURL.fileURLWithPath(root.path + "/linked.alias")
            check(NSURL.writeBookmarkData(bookmark, alias, 0u, null))
            val disguised = NSURL.fileURLWithPath(root.path + "/linked.circ")
            check(NSFileManager.defaultManager.moveItemAtPath(assertNotNull(alias.path), assertNotNull(disguised.path), null))
            assertEquals(true, (disguised.resourceValuesForKeys(listOf(NSURLIsAliasFileKey), null)
                ?.get(NSURLIsAliasFileKey) as? NSNumber)?.boolValue)
            val grant = IosDirectoryAssetGrant.open(root)
            try {
                assertEquals(emptyList(), grant.circuitFiles(10))
                assertEquals("ios_directory_unsafe_path", assertFailsWith<LocalizedStateException> {
                    grant.read("linked.circ", 1024)
                }.text.key)
            } finally { grant.release() }
        }
    }

    @Test
    fun replacingTheSelectedFolderDoesNotRetargetAnExistingGrant() = withDirectory { root ->
        write(root, "main.circ", byteArrayOf(1))
        val grant = IosDirectoryAssetGrant.open(root)
        val moved = root.path + "-moved"
        check(NSFileManager.defaultManager.moveItemAtPath(assertNotNull(root.path), moved, null))
        try {
            write(root, "main.circ", byteArrayOf(2))
            assertEquals("ios_directory_changed", assertFailsWith<LocalizedStateException> {
                grant.read("main.circ", 1024)
            }.text.key)
        } finally {
            grant.release()
            NSFileManager.defaultManager.removeItemAtPath(moved, null)
        }
    }

    @Test
    fun symlinkRootsAreRejectedEvenIfTheyPointToAnAccessibleDirectory() = withDirectory { root ->
        withDirectory { outside ->
            val link = root.path + "/linked"
            assertEquals(0, symlink(outside.path, link))
            assertEquals("ios_directory_unsafe_path", assertFailsWith<LocalizedStateException> {
                IosDirectoryAssetGrant.open(NSURL.fileURLWithPath(link))
            }.text.key)
        }
    }

    @Test
    fun fileCountOverflowFailsInsteadOfReturningATruncatedProject() = withDirectory { root ->
        write(root, "a.circ", byteArrayOf(1))
        write(root, "b.circ", byteArrayOf(2))
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            assertEquals("ios_directory_too_many_entries", assertFailsWith<LocalizedStateException> {
                grant.circuitFiles(1)
            }.text.key)
            assertEquals("ios_directory_too_many_entries", assertFailsWith<LocalizedArgumentException> {
                grant.circuitFiles(0)
            }.text.key)
            assertEquals(2, grant.circuitFiles(2).size)
        } finally { grant.release() }
    }

    @Test
    fun directoryDepthIsBoundedEvenWithoutCircuitFiles() = withDirectory { root ->
        val path = root.path + "/" + List(65) { "nested" }.joinToString("/")
        check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null))
        val grant = IosDirectoryAssetGrant.open(root)
        try {
            assertEquals("ios_directory_too_many_entries", assertFailsWith<LocalizedStateException> {
                grant.circuitFiles(100)
            }.text.key)
        } finally { grant.release() }
    }

    @Test
    fun releaseIsIdempotentAndDoesNotAffectAnIndependentGrant() = withDirectory { root ->
        write(root, "main.circ", byteArrayOf(1))
        val first = IosDirectoryAssetGrant.open(root)
        val second = IosDirectoryAssetGrant.open(root)
        assertNotEquals(first.id, second.id)
        first.release()
        first.release()
        try {
            assertEquals("ios_directory_released", assertFailsWith<LocalizedStateException> { first.circuitFiles(10) }.text.key)
            assertEquals("ios_directory_released", assertFailsWith<LocalizedStateException> { first.read("main.circ", 10) }.text.key)
            assertContentEquals(byteArrayOf(1), assertNotNull(second.read("main.circ", 10)).bytes)
        } finally { second.release() }
    }

    @Test
    fun nonFileUrlsAndPlainFilesCannotBecomeDirectoryGrants() = withDirectory { root ->
        val remote = assertNotNull(NSURL.URLWithString("https://example.invalid/folder"))
        assertEquals("ios_directory_file_url_required", assertFailsWith<LocalizedArgumentException> {
            IosDirectoryAssetGrant.open(remote)
        }.text.key)
        val file = write(root, "main.circ", byteArrayOf(1))
        assertEquals("ios_directory_unsafe_path", assertFailsWith<LocalizedStateException> {
            IosDirectoryAssetGrant.open(file)
        }.text.key)
    }

    private fun withDirectory(action: (NSURL) -> Unit) {
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        withDirectoryAt(cache, action)
    }

    private fun withDirectoryAt(parent: String, action: (NSURL) -> Unit) {
        val path = "$parent/LogisimDirectoryTest-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null))
        try { action(NSURL.fileURLWithPath(path)) } finally { NSFileManager.defaultManager.removeItemAtPath(path, null) }
    }

    private fun write(root: NSURL, relative: String, bytes: ByteArray): NSURL {
        val path = root.path + "/" + relative
        check(NSFileManager.defaultManager.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null))
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
        val url = NSURL.fileURLWithPath(path)
        check(data.writeToURL(url, atomically = true))
        return url
    }
}
