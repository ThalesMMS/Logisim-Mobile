@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToURL
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
import platform.posix.O_APPEND
import platform.posix.O_WRONLY
import platform.posix.close as closeDescriptor
import platform.posix.fsync
import platform.posix.open
import platform.posix.write
import kotlin.native.ref.WeakReference

/** Retains security scope for the recording and coordinates each append with the file provider. */
class IosTraceFileActions(private val presenter: () -> UIViewController?) : TraceFileActions {
    private val delegate = TracePickerDelegate(this)
    private var picker: UIDocumentPickerViewController? = null
    private var callback: ((TraceFileOpenResult) -> Unit)? = null
    private var temporary: NSURL? = null

    override fun select(append: Boolean, onResult: (TraceFileOpenResult) -> Unit) =
        selectDestination(append, "signals.tsv", onResult)

    override fun create(name: String, mimeType: String, onResult: (TraceFileOpenResult) -> Unit) =
        selectDestination(false, name, onResult)

    private fun selectDestination(append: Boolean, name: String, onResult: (TraceFileOpenResult) -> Unit) {
        if (callback != null) { onResult(TraceFileOpenResult.Failure(message("trace_file_picker_busy"))); return }
        callback = onResult
        try {
            val controller = if (append) {
                UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTType.typeWithIdentifier("public.data")!!), asCopy = false)
            } else {
                val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
                val directory = "$root/TraceExports/${NSUUID().UUIDString}"
                checkLocalized(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) { message("trace_file_provider_failed") }
                val url = NSURL.fileURLWithPath("$directory/$name")
                temporary = url
                checkLocalized(NSData().writeToURL(url, atomically = true)) { message("trace_file_provider_failed") }
                UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
            }
            controller.delegate = delegate
            controller.allowsMultipleSelection = false
            picker = controller
            var host = presenter()
            while (host?.presentedViewController != null) host = host.presentedViewController
            checkLocalized(host?.view?.window != null) { message("trace_file_provider_failed") }
            controller.popoverPresentationController?.let { it.sourceView = host!!.view; it.sourceRect = host!!.view.bounds }
            host!!.presentViewController(controller, animated = true) { controller.presentationController?.delegate = delegate }
        } catch (error: Throwable) {
            complete(TraceFileOpenResult.Failure(error.localizedDescription(message("trace_file_provider_failed"))))
        }
    }

    internal fun selected(controller: UIDocumentPickerViewController, urls: List<*>) {
        if (controller != picker) return
        val url = urls.singleOrNull() as? NSURL
        if (url == null) { complete(TraceFileOpenResult.Failure(message("trace_file_provider_failed"))); return }
        val writer = IosTraceWriter(url)
        // Verify provider write permission before starting the capture.
        writer.append("") { result ->
            if (controller != picker) {
                writer.close {}
                return@append
            }
            if (result is AssetActionResult.Failure) {
                writer.close {}
                complete(TraceFileOpenResult.Failure(result.notice))
            } else complete(TraceFileOpenResult.Opened(writer))
        }
    }

    internal fun cancelled(controller: UIDocumentPickerViewController) {
        if (controller == picker) complete(TraceFileOpenResult.Cancelled)
    }

    private fun complete(result: TraceFileOpenResult) {
        val receive = callback
        callback = null
        picker = null
        temporary?.URLByDeletingLastPathComponent?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        temporary = null
        receive?.invoke(result)
    }
}

private class IosTraceWriter(private val url: NSURL) : TraceFileWriter {
    override val name = url.lastPathComponent ?: "signals.tsv"
    private val scoped = url.startAccessingSecurityScopedResource()
    private var closed = false

    override fun append(text: String, onResult: (AssetActionResult) -> Unit) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val result = runCatching {
                checkLocalized(!closed && url.fileURL) { message("trace_file_provider_failed") }
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    error.value = null
                    var wrote = false
                    var failure: Throwable? = null
                    NSFileCoordinator(null).coordinateWritingItemAtURL(url, 0u, error.ptr) { actual ->
                        try {
                            val path = actual?.path
                            checkLocalized(path != null) { message("trace_file_provider_failed") }
                            val descriptor = open(path!!, O_WRONLY or O_APPEND)
                            checkLocalized(descriptor >= 0) { message("trace_file_provider_failed") }
                            try {
                                val bytes = text.encodeToByteArray()
                                if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
                                    var offset = 0
                                    while (offset < bytes.size) {
                                        val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).toULong())
                                        checkLocalized(count > 0) { message("trace_file_write_failed") }
                                        offset += count.toInt()
                                    }
                                }
                                checkLocalized(fsync(descriptor) == 0) { message("trace_file_write_failed") }
                                wrote = true
                            } finally {
                                checkLocalized(closeDescriptor(descriptor) == 0) { message("trace_file_write_failed") }
                            }
                        } catch (problem: Throwable) { failure = problem }
                    }
                    failure?.let { throw it }
                    checkLocalized(error.value == null && wrote) { message("trace_file_write_failed") }
                }
            }.fold({ AssetActionResult.Completed }, { AssetActionResult.Failure(it.localizedDescription(message("trace_file_write_failed"))) })
            dispatch_async(dispatch_get_main_queue()) { onResult(result) }
        }
    }

    override fun close(onResult: (AssetActionResult) -> Unit) {
        if (!closed) {
            closed = true
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
        onResult(AssetActionResult.Completed)
    }
}

private class TracePickerDelegate(owner: IosTraceFileActions) : NSObject(), UIDocumentPickerDelegateProtocol,
    UIAdaptivePresentationControllerDelegateProtocol {
    private val owner = WeakReference(owner)
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        owner.get()?.selected(controller, didPickDocumentsAtURLs)
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { owner.get()?.cancelled(controller) }
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        (presentationController.presentedViewController as? UIDocumentPickerViewController)?.let { owner.get()?.cancelled(it) }
    }
}
