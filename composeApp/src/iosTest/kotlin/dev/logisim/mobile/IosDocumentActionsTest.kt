@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSDate
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileHandle
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.closeFile
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToURL

class IosDocumentActionsTest {
    @Test
    fun importedCopyKeepsOriginalUntouchedAndSurvivesAChangedSource() = withFile("<project>ação</project>") { url ->
        val actions = IosDocumentActions({ null })
        val copy = actions.readDocument(url, DocumentOpenMode.IMPORT_COPY)
        assertFalse(copy.reference.writable)
        assertEquals(DocumentOpenMode.IMPORT_COPY, copy.reference.mode)
        var saveResult: DocumentWriteResult? = null
        actions.save(copy.reference, "<project>local</project>") { saveResult = it }
        assertIs<DocumentWriteResult.Failure>(saveResult)
        write(url, "<project>changed externally</project>")
        assertEquals("<project>ação</project>", actions.readReference(copy.reference).text)
        assertEquals("<project>changed externally</project>", actions.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE).text)
    }

    @Test
    fun changedExternalFileIsNotOverwrittenUntilItsNewRevisionIsAccepted() = withFile("<project>one</project>") { url ->
        val actions = IosDocumentActions({ null })
        val original = actions.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE)
        write(url, "<project>two</project>")
        val conflict = assertIs<DocumentWriteResult.Conflict>(actions.replaceDocument(original.reference, "<project>local</project>"))
        assertEquals("<project>two</project>", actions.readReference(conflict.current).text)
        val saved = assertIs<DocumentWriteResult.Saved>(actions.replaceDocument(conflict.current, "<project>local</project>"))
        assertEquals("<project>local</project>", actions.readReference(saved.reference).text)
    }

    @Test
    fun laterOpenWinsWhileAnEarlierProviderReadIsBlocked() = withFile("<first/>") { first ->
        withFile("<second/>") { second ->
            val locked = dispatch_semaphore_create(0)
            val release = dispatch_semaphore_create(0)
            val drained = dispatch_semaphore_create(0)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                NSFileCoordinator(null).coordinateWritingItemAtURL(first, 0u, null) {
                    dispatch_semaphore_signal(locked)
                    dispatch_semaphore_wait(release, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000))
                }
                // A coordinated write can enter only after the preceding read has finished.
                NSFileCoordinator(null).coordinateWritingItemAtURL(first, 0u, null) {
                    dispatch_semaphore_signal(drained)
                }
            }
            assertEquals(0, dispatch_semaphore_wait(locked, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000)))
            val opened = mutableListOf<String>()
            val actions = IosDocumentActions({ null })
            actions.setOpenHandler { result -> opened += assertIs<DocumentOpenResult.Opened>(result).content.text }
            try {
                actions.openURL(first)
                actions.openURL(second)
                awaitMain { opened.isNotEmpty() }
                assertEquals(listOf("<second/>"), opened)
            } finally { dispatch_semaphore_signal(release) }
            assertEquals(0, dispatch_semaphore_wait(drained, dispatch_time(DISPATCH_TIME_NOW, 5_000_000_000)))
            // Drain callbacks queued by both coordinated operations before checking the visible result.
            val deadline = NSDate.dateWithTimeIntervalSinceNow(0.1)
            NSRunLoop.mainRunLoop.runUntilDate(deadline)
            assertEquals(listOf("<second/>"), opened)
        }
    }

    @Test
    fun retainedBookmarkIsRestoredByANewActionInstance() = withFile("<project>persisted</project>") { url ->
        val domain = "LogisimDocumentTest." + NSUUID().UUIDString
        val defaults = NSUserDefaults(suiteName = domain)
        try {
            val first = IosDocumentActions({ null }, defaults)
            val reference = first.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE).reference
            first.retain(reference)
            val second = IosDocumentActions({ null }, defaults)
            val restored = assertNotNull(second.currentReference)
            assertEquals(reference, restored)
            assertEquals("<project>persisted</project>", second.readReference(restored).text)
            second.retain(null)
            assertEquals(null, first.currentReference)
        } finally { defaults.removePersistentDomainForName(domain) }
    }

    @Test
    fun removedProviderFileFailsToReopen() = withFile("<project/>") { url ->
        val actions = IosDocumentActions({ null })
        val reference = actions.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE).reference
        NSFileManager.defaultManager.removeItemAtURL(url, null)
        assertFailsWith<IllegalStateException> { actions.readReference(reference) }
    }

    @Test
    fun recentBookmarkRefreshesTheSameOriginalAndReportsARemovedFile() = withFile("<provider-original/>") { url ->
        val reference = IosDocumentActions({ null }).readDocument(url, DocumentOpenMode.EDIT_IN_PLACE).reference
        val edited = "<provider-edited/>"
        assertTrue(NSString.create(string = edited).writeToURL(url, false, NSUTF8StringEncoding, null))
        val restarted = IosDocumentActions({ null })
        var opened: DocumentOpenResult? = null
        restarted.setOpenHandler { opened = it }
        restarted.reopen(reference)
        awaitMain { opened != null }
        val reopened = assertIs<DocumentOpenResult.Opened>(opened).content
        assertEquals(reference.id, reopened.reference.id)
        assertEquals(reference.name, reopened.reference.name)
        assertNotEquals(reference.revision, reopened.reference.revision)
        assertEquals(edited, reopened.text)

        opened = null
        assertTrue(NSFileManager.defaultManager.removeItemAtURL(url, null))
        restarted.reopen(reference)
        awaitMain { opened != null }
        assertIs<DocumentOpenResult.Failure>(opened)
    }

    @Test
    fun incomingNetworkUrlIsRejectedBeforeReading() {
        val actions = IosDocumentActions({ null })
        val url = assertNotNull(NSURL.URLWithString("https://example.invalid/project.circ"))
        assertFailsWith<IllegalArgumentException> { actions.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE) }
    }

    @Test
    fun oversizedDocumentIsRejectedBeforeLoadingItsBytes() = withFile("<project/>") { url ->
        val handle = assertNotNull(NSFileHandle.fileHandleForWritingAtPath(assertNotNull(url.path)))
        try { handle.truncateFileAtOffset(16uL * 1024uL * 1024uL + 1uL) } finally { handle.closeFile() }
        val error = assertFailsWith<LocalizedArgumentException> {
            IosDocumentActions({ null }).readDocument(url, DocumentOpenMode.EDIT_IN_PLACE)
        }
        assertEquals("ios_document_too_large", error.text.key)
    }

    @Test
    fun restoredReferenceIsDetachedFromADifferentWorkspaceCheckpoint() = withDefaults { defaults ->
        val first = IosDocumentActions({ null }, defaults)
        first.retain(DocumentReference("opaque", "first.circ", true, "external-revision", DocumentOpenMode.EDIT_IN_PLACE))
        first.workspaceSaved("<first/>")
        val restored = IosDocumentActions({ null }, defaults)
        restored.bindWorkspace("<second/>")
        assertEquals(null, restored.currentReference)
    }

    @Test
    fun matchingEditedWorkspaceKeepsTheExternalReference() = withDefaults { defaults ->
        val first = IosDocumentActions({ null }, defaults)
        val reference = DocumentReference("opaque", "first.circ", true, "external-revision", DocumentOpenMode.EDIT_IN_PLACE)
        first.retain(reference)
        first.workspaceSaved("<local-edits/>")
        val restored = IosDocumentActions({ null }, defaults)
        restored.bindWorkspace("<local-edits/>")
        assertEquals(reference, restored.currentReference)
    }

    @Test
    fun restoringAnUnrelatedBookmarkDoesNotCancelAQueuedSystemOpen() = withDefaults { defaults ->
        withFile("<incoming/>") { url ->
            val actions = IosDocumentActions({ null }, defaults)
            actions.retain(DocumentReference("old", "old.circ", true, "old", DocumentOpenMode.EDIT_IN_PLACE))
            actions.workspaceSaved("<old/>")
            actions.openURL(url)
            actions.bindWorkspace("<another-restored-workspace/>")
            var opened: DocumentOpenResult? = null
            actions.setOpenHandler { opened = it }
            awaitMain { opened != null }
            assertEquals("<incoming/>", assertIs<DocumentOpenResult.Opened>(opened).content.text)
            assertEquals(null, actions.currentReference)
        }
    }

    @Test
    fun catalogAssociationRestorationDoesNotCancelAnIncomingSystemOpen() = withDefaults { defaults ->
        withFile("<incoming/>") { url ->
            val actions = IosDocumentActions({ null }, defaults)
            val previous = DocumentReference("old", "old.circ", true, "old", DocumentOpenMode.EDIT_IN_PLACE)
            actions.retain(previous)
            actions.openURL(url)
            actions.restoreReference(null)
            var opened: DocumentOpenResult? = null
            actions.setOpenHandler { opened = it }
            awaitMain { opened != null }
            assertEquals("<incoming/>", assertIs<DocumentOpenResult.Opened>(opened).content.text)
            assertEquals(null, actions.currentReference)
        }
    }

    @Test
    fun sceneLinksDoNotReplaceAnotherScenesReference() = withDefaults { defaults ->
        val first = IosDocumentActions({ null }, defaults, "scene.first")
        val second = IosDocumentActions({ null }, defaults, "scene.second")
        val a = DocumentReference("opaque-a", "a.circ", true, "a", DocumentOpenMode.EDIT_IN_PLACE)
        val b = DocumentReference("opaque-b", "b.circ", true, "b", DocumentOpenMode.EDIT_IN_PLACE)
        first.retain(a)
        second.retain(b)
        second.retain(null)
        assertEquals(a, first.currentReference)
        assertEquals(null, second.currentReference)
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
        while (!condition() && deadline.timeIntervalSinceNow > 0.0) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(condition(), "Expected document callback on the main queue")
    }

    private fun withDefaults(action: (NSUserDefaults) -> Unit) {
        val domain = "LogisimDocumentTest." + NSUUID().UUIDString
        val defaults = NSUserDefaults(suiteName = domain)
        try { action(defaults) } finally { defaults.removePersistentDomainForName(domain) }
    }

    private fun withFile(contents: String, action: (NSURL) -> Unit) {
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val directory = "$cache/LogisimDocumentTest-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val url = NSURL.fileURLWithPath("$directory/projeto.circ")
        try {
            write(url, contents)
            action(url)
        } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    private fun write(url: NSURL, text: String) {
        check(NSString.create(string = text).writeToURL(url, true, NSUTF8StringEncoding, null))
    }
}
