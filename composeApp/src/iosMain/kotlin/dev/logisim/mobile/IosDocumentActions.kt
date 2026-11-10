@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSFileVersion
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import dev.logisim.mobile.core.CircXmlEncoding
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlin.native.ref.WeakReference

/** File-provider access stays on a worker queue; presentation and callbacks stay on the main queue. */
class IosDocumentActions(
    private val presenter: () -> UIViewController?,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val referenceKey: String = "workspace.external-document.v1",
) : DocumentActions {
    private val pickerDelegate = IosPickerDelegate(this)
    private var openHandler: ((DocumentOpenResult) -> Unit)? = null
    private val pendingOpen = ArrayDeque<DocumentOpenResult>()
    private var pickerOperation: PickerOperation? = null
    private var openGeneration = 0L

    override val currentReference: DocumentReference?
        get() {
            val stored = defaults.dictionaryForKey(referenceKey) ?: return null
            val id = stored["id"] as? String ?: return null
            val name = stored["name"] as? String ?: return null
            val mode = (stored["mode"] as? String)?.let { saved ->
                DocumentOpenMode.entries.firstOrNull { it.name == saved }
            } ?: return null
            return DocumentReference(id, name, mode == DocumentOpenMode.EDIT_IN_PLACE,
                stored["revision"] as? String, mode)
        }

    override fun retain(reference: DocumentReference?) {
        if (reference == null) beginOpen()
        restoreReference(reference)
    }

    override fun restoreReference(reference: DocumentReference?) {
        if (reference == null) {
            defaults.removeObjectForKey(referenceKey)
        } else {
            defaults.setObject(mapOf(
                "id" to reference.id,
                "name" to reference.name,
                "revision" to reference.revision.orEmpty(),
                "mode" to reference.mode.name,
                "workspaceFingerprint" to reference.revision.orEmpty(),
            ), forKey = referenceKey)
        }
    }

    /** A restored file link is valid only for the checkpoint that was saved with that link. */
    fun bindWorkspace(xml: String?) {
        val stored = defaults.dictionaryForKey(referenceKey) ?: return
        if (xml == null || stored["workspaceFingerprint"] != textFingerprint(xml)) {
            // Restoring an unrelated old link must not cancel a newer OS-open that
            // arrived while the legacy checkpoint was being loaded off the main thread.
            defaults.removeObjectForKey(referenceKey)
        }
    }

    /** Called only after the local checkpoint write succeeds. */
    fun workspaceSaved(xml: String) {
        val stored = defaults.dictionaryForKey(referenceKey) ?: return
        defaults.setObject(stored + ("workspaceFingerprint" to textFingerprint(xml)), forKey = referenceKey)
    }

    override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) {
        openHandler = handler
        if (handler != null) {
            val queued = pendingOpen.toList()
            pendingOpen.clear()
            queued.forEach(handler)
        }
    }

    override fun open(mode: DocumentOpenMode) {
        if (pickerOperation != null) {
            emitOpen(DocumentOpenResult.Failure(iosNotice("busy")))
            return
        }
        val type = UTType.typeWithIdentifier("org.logisim-evolution.circuit")
            ?: UTType.typeWithIdentifier("public.xml")
            ?: iosError("xml_type_unavailable")
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(type), asCopy = mode == DocumentOpenMode.IMPORT_COPY)
        picker.delegate = pickerDelegate
        picker.allowsMultipleSelection = false
        pickerOperation = PickerOperation.Open(picker, mode, beginOpen())
        if (!present(picker)) {
            pickerOperation = null
            emitOpen(DocumentOpenResult.Failure(iosNotice("open_picker_failed")))
        }
    }

    /** The receiving scene calls this for Files/Open With; no other scene's project is replaced. */
    fun openURL(url: NSURL) {
        read(url, DocumentOpenMode.EDIT_IN_PLACE, beginOpen())
    }

    override fun reopen(reference: DocumentReference) {
        val generation = beginOpen()
        background({
            readDocument(resolve(reference, requireFresh = true), reference.mode, retainedReference = reference)
        }) { result ->
            if (generation != openGeneration) return@background
            emitOpen(result.fold({ DocumentOpenResult.Opened(it) }, { DocumentOpenResult.Failure(it.localizedDescription(iosNotice("operation_failed"))) }))
        }
    }

    override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) {
        if (pickerOperation != null) {
            onResult(DocumentWriteResult.Failure(iosNotice("busy")))
            return
        }
        val temporary = runCatching { temporaryDocument(suggestedName, text) }.getOrElse {
            onResult(DocumentWriteResult.Failure(it.localizedDescription(iosNotice("operation_failed"))))
            return
        }
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(temporary), asCopy = true)
        picker.delegate = pickerDelegate
        pickerOperation = PickerOperation.Create(picker, onResult)
        if (!present(picker)) {
            pickerOperation = null
            onResult(DocumentWriteResult.Failure(iosNotice("destination_picker_failed")))
        }
    }

    override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
        if (!reference.writable || reference.mode != DocumentOpenMode.EDIT_IN_PLACE) {
            onResult(DocumentWriteResult.Failure(iosNotice("copy_requires_export")))
            return
        }
        background({ replaceDocument(reference, text) }) { result ->
            onResult(result.getOrElse { DocumentWriteResult.Failure(it.localizedDescription(iosNotice("operation_failed"))) })
        }
    }

    override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) {
        val temporary = runCatching { temporaryDocument(suggestedName, text) }.getOrElse {
            onResult(DocumentShareResult.Failure(it.localizedDescription(iosNotice("operation_failed"))))
            return
        }
        val activity = UIActivityViewController(listOf(temporary), applicationActivities = null)
        activity.completionWithItemsHandler = { _, completed, _, error ->
            onResult(when {
                error != null -> DocumentShareResult.Failure(error.localizedDescription)
                completed -> DocumentShareResult.Presented
                else -> DocumentShareResult.Cancelled
            })
        }
        if (!present(activity)) onResult(DocumentShareResult.Failure(iosNotice("share_failed")))
    }

    internal fun selectedDocuments(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val operation = pickerOperation?.takeIf { it.picker == controller } ?: return
        pickerOperation = null
        val url = didPickDocumentsAtURLs.singleOrNull() as? NSURL
        if (url == null) {
            when (operation) {
                is PickerOperation.Open -> if (operation.generation == openGeneration) emitOpen(DocumentOpenResult.Failure(iosNotice("pick_one")))
                is PickerOperation.Create -> operation.complete(DocumentWriteResult.Failure(iosNotice("provider_no_file")))
            }
            return
        }
        when (operation) {
            is PickerOperation.Open -> read(url, operation.mode, operation.generation)
            is PickerOperation.Create -> background({ readDocument(url, DocumentOpenMode.EDIT_IN_PLACE) }) { result ->
                operation.complete(result.fold({ DocumentWriteResult.Saved(it.reference) },
                    { DocumentWriteResult.Failure(it.localizedDescription(iosNotice("operation_failed"))) }))
            }
        }
    }

    internal fun pickerCancelled(controller: UIDocumentPickerViewController) {
        val operation = pickerOperation?.takeIf { it.picker == controller } ?: return
        pickerOperation = null
        when (operation) {
            is PickerOperation.Open -> if (operation.generation == openGeneration) emitOpen(DocumentOpenResult.Cancelled)
            is PickerOperation.Create -> operation.complete(DocumentWriteResult.Cancelled)
        }
    }

    private fun read(url: NSURL, mode: DocumentOpenMode, generation: Long) {
        background({ readDocument(url, mode) }) { result ->
            if (generation != openGeneration) return@background
            emitOpen(result.fold({ DocumentOpenResult.Opened(it) }, { DocumentOpenResult.Failure(it.localizedDescription(iosNotice("operation_failed"))) }))
        }
    }

    internal fun readReference(reference: DocumentReference): DocumentContent = readDocument(resolve(reference), reference.mode)

    internal fun readDocument(url: NSURL, mode: DocumentOpenMode, retainedReference: DocumentReference? = null): DocumentContent {
        requireLocalized(url.fileURL) { iosNotice("file_url_required") }
        return withSecurityScope(url) {
            coordinatedRead(url) { actual ->
                val data = fileData(actual, documentFilename(url))
                val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: ByteArray(0)
                val text = CircXmlEncoding.decode(bytes)
                val revision = sha256(data)
                if (retainedReference != null) {
                    return@coordinatedRead DocumentContent(
                        retainedReference.copy(name = documentFilename(url), revision = revision), text)
                }
                val source = if (mode == DocumentOpenMode.IMPORT_COPY) temporaryDocument(url.lastPathComponent ?: "circuit.circ", text) else url
                DocumentContent(reference(source, mode, revision), text)
            }
        }
    }

    internal fun replaceDocument(reference: DocumentReference, text: String): DocumentWriteResult {
        requireLocalized(text.encodeToByteArray().size.toULong() <= MAX_DOCUMENT_BYTES) { iosNotice("document_too_large") }
        val url = resolve(reference)
        return withSecurityScope(url) {
            coordinatedWrite(url, reference.name) { actual ->
                val current = sha256(fileData(actual, reference.name))
                if (reference.revision == null || current != reference.revision ||
                    !NSFileVersion.unresolvedConflictVersionsOfItemAtURL(actual).isNullOrEmpty()) {
                    DocumentWriteResult.Conflict(reference(url, DocumentOpenMode.EDIT_IN_PLACE, current))
                } else {
                    val content = NSString.create(string = text)
                    memScoped {
                        val failure = alloc<ObjCObjectVar<NSError?>>()
                        failure.value = null
                        checkLocalized(content.writeToURL(actual, atomically = true, encoding = NSUTF8StringEncoding, error = failure.ptr)) {
                            iosDocumentProviderNotice(failure.value, reference.name, iosNotice("write_failed"))
                        }
                    }
                    val saved = reference(url, DocumentOpenMode.EDIT_IN_PLACE, sha256(fileData(actual, reference.name)))
                    // A coordinated write can complete before a File Provider uploads the local bytes.
                    DocumentWriteResult.Saved(saved, message(Messages.IosFileSavedOnDevice, "name" to saved.name))
                }
            }
        }
    }

    private fun reference(url: NSURL, mode: DocumentOpenMode, revision: String): DocumentReference = memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        failure.value = null
        val bookmark = url.bookmarkDataWithOptions(0u, null, null, failure.ptr)
            ?: throw LocalizedStateException(iosDocumentProviderNotice(failure.value, documentFilename(url), iosNotice("bookmark_failed")))
        DocumentReference(bookmark.base64EncodedStringWithOptions(0u), url.lastPathComponent ?: "circuit.circ",
            mode == DocumentOpenMode.EDIT_IN_PLACE, revision, mode)
    }

    private fun resolve(reference: DocumentReference, requireFresh: Boolean = false): NSURL = memScoped {
        val data = NSData.create(base64EncodedString = reference.id, options = 0u)
            ?: iosError("invalid_bookmark")
        val failure = alloc<ObjCObjectVar<NSError?>>()
        failure.value = null
        val stale = alloc<BooleanVar>()
        stale.value = false
        val url = NSURL.URLByResolvingBookmarkData(data, NSURLBookmarkResolutionWithoutUI, null, stale.ptr, failure.ptr)
            ?: throw LocalizedStateException(iosDocumentProviderNotice(failure.value, reference.name, iosNotice("access_expired")))
        checkLocalized(!requireFresh || !stale.value) { iosNotice("access_expired") }
        url
    }

    private fun fileData(url: NSURL, name: String): NSData = memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        failure.value = null
        val path = url.path ?: iosError("provider_unavailable")
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, failure.ptr)
            ?: throw LocalizedStateException(iosDocumentProviderNotice(failure.value, name, iosNotice("provider_unavailable")))
        val size = (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue
            ?: iosError("provider_unavailable")
        requireLocalized(size <= MAX_DOCUMENT_BYTES) { iosNotice("document_too_large") }
        // Keep text and its CAS digest on the same immutable byte snapshot.
        val data = NSData.dataWithContentsOfURL(url, 0u, failure.ptr)
            ?: throw LocalizedStateException(iosDocumentProviderNotice(failure.value, name, iosNotice("provider_unavailable")))
        requireLocalized(data.length <= MAX_DOCUMENT_BYTES) { iosNotice("document_too_large") }
        data
    }

    private fun temporaryDocument(suggestedName: String, text: String): NSURL {
        requireLocalized(text.encodeToByteArray().size.toULong() <= MAX_DOCUMENT_BYTES) { iosNotice("document_too_large") }
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: iosError("export_prepare_failed")
        val directory = "$cache/LogisimExports/" + NSUUID().UUIDString
        checkLocalized(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
            iosNotice("export_prepare_failed")
        }
        val name = suggestedName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "circuit.circ" }
        val url = NSURL.fileURLWithPath("$directory/${if (name.endsWith(".circ", true)) name else "$name.circ"}")
        checkLocalized(NSString.create(string = text).writeToURL(url, true, NSUTF8StringEncoding, null)) {
            iosNotice("export_write_failed")
        }
        return url
    }

    private fun present(controller: UIViewController): Boolean {
        var host = presenter() ?: return false
        while (host.presentedViewController != null) host = host.presentedViewController!!
        if (host.view.window == null) return false
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            popover.sourceRect = host.view.bounds
        }
        host.presentViewController(controller, animated = true, completion = {
            if (controller is UIDocumentPickerViewController) controller.presentationController?.delegate = pickerDelegate
        })
        return true
    }

    private fun beginOpen(): Long {
        pendingOpen.clear()
        return ++openGeneration
    }

    private fun emitOpen(result: DocumentOpenResult) {
        openHandler?.invoke(result) ?: pendingOpen.addLast(result)
    }

    private fun <T> background(action: () -> T, completion: (Result<T>) -> Unit) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val result = runCatching(action)
            dispatch_async(dispatch_get_main_queue()) { completion(result) }
        }
    }

    private sealed interface PickerOperation {
        val picker: UIDocumentPickerViewController
        data class Open(override val picker: UIDocumentPickerViewController, val mode: DocumentOpenMode,
                        val generation: Long) : PickerOperation
        data class Create(override val picker: UIDocumentPickerViewController,
                          val complete: (DocumentWriteResult) -> Unit) : PickerOperation
    }

}

private const val MAX_DOCUMENT_BYTES = 16777216uL

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private class IosPickerDelegate(owner: IosDocumentActions) : NSObject(), UIDocumentPickerDelegateProtocol,
    UIAdaptivePresentationControllerDelegateProtocol {
    private val owner = WeakReference(owner)

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        owner.get()?.selectedDocuments(controller, didPickDocumentsAtURLs)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        owner.get()?.pickerCancelled(controller)
    }

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        (presentationController.presentedViewController as? UIDocumentPickerViewController)?.let {
            owner.get()?.pickerCancelled(it)
        }
    }
}

private fun <T> withSecurityScope(url: NSURL, block: () -> T): T {
    val acquired = url.startAccessingSecurityScopedResource()
    return try { block() } finally { if (acquired) url.stopAccessingSecurityScopedResource() }
}

private fun <T> coordinatedRead(url: NSURL, block: (NSURL) -> T): T = memScoped {
    val failure = alloc<ObjCObjectVar<NSError?>>()
    failure.value = null
    var value: Result<T>? = null
    NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, failure.ptr) { actual ->
        value = runCatching { block(actual ?: iosError("provider_no_access")) }
    }
    failure.value?.let {
        NSLog("Coordinated document read failed: %@ (%ld)", it.domain, it.code)
        throw LocalizedStateException(iosDocumentProviderNotice(it, documentFilename(url), iosNotice("provider_no_access")))
    }
    (value ?: iosError("provider_read_incomplete")).getOrThrow()
}

private fun <T> coordinatedWrite(url: NSURL, name: String, block: (NSURL) -> T): T = memScoped {
    val failure = alloc<ObjCObjectVar<NSError?>>()
    failure.value = null
    var value: Result<T>? = null
    NSFileCoordinator(null).coordinateWritingItemAtURL(url, NSFileCoordinatorWritingForReplacing, failure.ptr) { actual ->
        value = runCatching { block(actual ?: iosError("provider_no_destination")) }
    }
    failure.value?.let {
        NSLog("Coordinated document write failed: %@ (%ld)", it.domain, it.code)
        throw LocalizedStateException(iosDocumentProviderNotice(it, name, iosNotice("provider_no_destination")))
    }
    (value ?: iosError("provider_write_incomplete")).getOrThrow()
}

private fun documentFilename(url: NSURL): String = url.lastPathComponent ?: "circuit.circ"

private fun sha256(data: NSData): String = memScoped {
    val output = allocArray<ByteVar>(CC_SHA256_DIGEST_LENGTH)
    CC_SHA256(data.bytes, data.length.toUInt(), output.reinterpret())
    (0 until CC_SHA256_DIGEST_LENGTH).joinToString("") { output[it].toUByte().toString(16).padStart(2, '0') }
}

private fun textFingerprint(text: String): String = sha256(
    NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)
        ?: iosError("invalid_utf8"),
)

internal fun iosNotice(key: String): LocalizedText = LocalizedText("ios_$key")

internal fun iosError(key: String): Nothing = throw LocalizedStateException(iosNotice(key))
