@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.O_RDONLY
import platform.posix.close as closeDescriptor
import platform.posix.mkstemp
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.read as readDescriptor
import platform.posix.unlink
import kotlin.native.ref.WeakReference

internal class IosAnalysisTableFiles(private val presenter: () -> UIViewController?) : AnalysisTableFiles {
    private var picker: UIDocumentPickerViewController? = null
    private var callback: ((AnalysisTableSelection) -> Unit)? = null
    private val delegate = IosAnalysisTablePickerDelegate(this)

    override fun select(onResult: (AnalysisTableSelection) -> Unit) {
        if (callback != null) return onResult(AnalysisTableSelection.Failure(message("ios_asset_busy")))
        val type = UTType.typeWithIdentifier("public.data")
        var host = presenter()
        while (host?.presentedViewController != null) host = host.presentedViewController
        val sourceView = host?.view
        if (type == null || host == null || sourceView?.window == null) {
            return onResult(AnalysisTableSelection.Failure(message("ios_asset_picker_failed")))
        }
        val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(type), asCopy = false)
        controller.delegate = delegate
        controller.allowsMultipleSelection = false
        picker = controller
        callback = onResult
        controller.popoverPresentationController?.let {
            it.sourceView = sourceView
            it.sourceRect = sourceView.bounds
        }
        host.presentViewController(controller, animated = true) { controller.presentationController?.delegate = delegate }
    }

    override fun createSpoolFile(): AnalysisSpoolFile = memScoped {
        val template = (NSTemporaryDirectory().trimEnd('/') + "/logisim-table-XXXXXX").cstr.getPointer(this)
        val descriptor = mkstemp(template)
        if (descriptor < 0) tableFileFailed()
        val path = template.toKString()
        // The descriptor owns the contents, so a terminated editor cannot leave a staged table behind.
        IosAnalysisSpoolFile(descriptor, path.takeUnless { unlink(it) == 0 })
    }

    internal fun selected(controller: UIDocumentPickerViewController, urls: List<*>) {
        val url = urls.singleOrNull() as? NSURL
        complete(controller, if (url?.fileURL == true) AnalysisTableSelection.Opened(IosAnalysisTableInput(url))
        else AnalysisTableSelection.Failure(message("ios_asset_pick_one")))
    }

    internal fun cancelled(controller: UIDocumentPickerViewController) = complete(controller, AnalysisTableSelection.Cancelled)

    private fun complete(controller: UIDocumentPickerViewController, result: AnalysisTableSelection) {
        if (controller != picker) return
        val receive = callback
        callback = null
        picker = null
        receive?.invoke(result)
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private class IosAnalysisTablePickerDelegate(owner: IosAnalysisTableFiles) : NSObject(), UIDocumentPickerDelegateProtocol,
    UIAdaptivePresentationControllerDelegateProtocol {
    private val owner = WeakReference(owner)
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        owner.get()?.selected(controller, didPickDocumentsAtURLs)
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        owner.get()?.cancelled(controller)
    }
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        (presentationController.presentedViewController as? UIDocumentPickerViewController)?.let {
            owner.get()?.cancelled(it)
        }
    }
}

private class IosAnalysisTableInput(private val url: NSURL) : AnalysisTableInput {
    private var descriptor = -1
    private var scoped = false
    override val name: String get() = url.lastPathComponent ?: tableFileFailed()

    override fun read(buffer: ByteArray): Int {
        if (descriptor < 0) tableFileFailed()
        val count = buffer.usePinned { readDescriptor(descriptor, it.addressOf(0), buffer.size.toULong()) }
        if (count < 0) tableFileFailed()
        return if (count == 0L) -1 else count.toInt()
    }

    override fun <T> withReadAccess(operation: () -> T): T {
        scoped = url.startAccessingSecurityScopedResource()
        try {
            return memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                var result: Result<T>? = null
                NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, error.ptr) { actual ->
                    descriptor = actual?.path?.let { open(it, O_RDONLY) } ?: -1
                    if (descriptor >= 0) {
                        // The provider's read lock covers parsing, not just opening the descriptor.
                        result = runCatching(operation)
                    }
                }
                val completed = result ?: tableFileFailed()
                completed.getOrThrow()
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (descriptor >= 0) closeDescriptor(descriptor)
        descriptor = -1
        if (scoped) url.stopAccessingSecurityScopedResource()
        scoped = false
    }
}

private class IosAnalysisSpoolFile(private var descriptor: Int, private val remainingPath: String?) : AnalysisSpoolFile {
    private val lock = NSLock()

    override fun write(offset: Int, bytes: ByteArray, count: Int) = locked {
        bytes.usePinned { pinned ->
            var written = 0
            while (written < count) {
                val result = pwrite(descriptor, pinned.addressOf(written), (count - written).toULong(), (offset + written).toLong())
                if (result <= 0) tableFileFailed()
                written += result.toInt()
            }
        }
    }

    override fun read(offset: Int, bytes: ByteArray, count: Int) = locked {
        bytes.usePinned { pinned ->
            var received = 0
            while (received < count) {
                val result = pread(descriptor, pinned.addressOf(received), (count - received).toULong(), (offset + received).toLong())
                if (result <= 0) tableFileFailed()
                received += result.toInt()
            }
        }
    }

    override fun close() = locked {
        if (descriptor >= 0) closeDescriptor(descriptor)
        descriptor = -1
        remainingPath?.let { unlink(it) }
        Unit
    }

    private inline fun <T> locked(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}

private fun tableFileFailed(): Nothing = throw LocalizedArgumentException(message("ios_asset_read_failed"))
