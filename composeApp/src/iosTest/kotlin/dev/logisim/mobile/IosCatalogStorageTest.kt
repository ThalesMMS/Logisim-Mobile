@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceOpenResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_group_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_wait

class IosCatalogStorageTest {
    @Test
    fun atomicFilesPreserveUnicodeAndRejectPathTraversal() = withDirectory { directory ->
        val storage = IosCatalogStorage(directory)
        storage.writeAtomically("example.workspace", "ação\n<project/>")
        assertEquals("ação\n<project/>", storage.read("example.workspace"))
        storage.writeAtomically("example.workspace", "replaced")
        assertEquals("replaced", storage.read("example.workspace"))
        assertTrue("example.workspace" in storage.listNames())
        assertFailsWith<IllegalArgumentException> { storage.writeAtomically("../outside", "bad") }
    }

    @Test
    fun catalogReopensFromDiskAndDetectsStaleRevisions() = withDirectory { directory ->
        fun repository() = WorkspaceCatalogRepository(IosCatalogStorage(directory),
            validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
        val created = assertIs<WorkspaceMutationResult.Success>(repository().create("Example", XML))
        val reopened = assertIs<WorkspaceOpenResult.Success>(repository().open(created.document.info.id))
        assertEquals(XML, reopened.document.xml)
        val saved = assertIs<WorkspaceMutationResult.Success>(repository().rename(created.document.info.id,
            created.document.info.revision, "Renamed"))
        assertIs<WorkspaceMutationResult.Conflict>(repository().save(created.document.info.id,
            created.document.info.revision, XML))
        assertEquals("Renamed", assertIs<WorkspaceOpenResult.Success>(repository().open(saved.document.info.id)).document.info.name)
    }

    @Test
    fun independentStorageInstancesSerializeConcurrentUpdates() = withDirectory { directory ->
        IosCatalogStorage(directory).writeAtomically("counter.txt", "0")
        val group = dispatch_group_create()
        repeat(20) {
            dispatch_group_async(group, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                val storage = IosCatalogStorage(directory)
                storage.withExclusiveAccess {
                    storage.writeAtomically("counter.txt", ((storage.read("counter.txt")?.toInt() ?: 0) + 1).toString())
                }
            }
        }
        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)
        assertEquals("20", IosCatalogStorage(directory).read("counter.txt"))
    }

    private fun withDirectory(action: (String) -> Unit) {
        val directory = NSTemporaryDirectory() + "LogisimCatalogTest-" + NSUUID().UUIDString
        try { action(directory) } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    companion object { private const val XML = "<project source=\"3.8.0\" version=\"1.0\"><main name=\"main\"/><circuit name=\"main\"/></project>" }
}
