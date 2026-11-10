@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.UIPrintInfoOutputType.UIPrintInfoOutputGeneral
import platform.UIKit.UIPresentationController
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.native.ref.WeakReference

/** Files operations check byte limits before copying or decoding provider content. */
class IosDocumentAssetActions(private val presenter: () -> UIViewController?) : DocumentAssetActions {
    override val traceFiles: TraceFileActions = IosTraceFileActions(presenter)
    override val analysisTables: AnalysisTableFiles = IosAnalysisTableFiles(presenter)
    private val pickerDelegate = IosAssetPickerDelegate(this)
    private var sequence = 0L
    private var activeRequest: Long? = null
    private var pickerOperation: AssetPickerOperation? = null

    override val supportsBatchCreate: Boolean get() = true
    override val supportsPrint: Boolean get() = UIPrintInteractionController.isPrintingAvailable()

    override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = assetMain {
        val id = begin() ?: return@assetMain onResult(AssetOpenResult.Failure(assetNotice("busy")))
        val allowed = acceptedAssetTypes(mimeTypes)
        if (maximumBytes <= 0 || allowed.isEmpty()) {
            complete(id, onResult, AssetOpenResult.Failure(assetNotice("unsupported_type")))
            return@assetMain
        }
        val types = allowed.mapNotNull {
            if (it == "application/octet-stream") UTType.typeWithIdentifier("public.data") else UTType.typeWithMIMEType(it)
        }
        if (types.isEmpty()) {
            complete(id, onResult, AssetOpenResult.Failure(assetNotice("unsupported_type")))
            return@assetMain
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.delegate = pickerDelegate
        picker.allowsMultipleSelection = false
        pickerOperation = AssetPickerOperation.Open(id, picker, allowed, minOf(maximumBytes, MAX_ASSET_BYTES), onResult)
        if (!present(picker)) {
            pickerOperation = null
            complete(id, onResult, AssetOpenResult.Failure(assetNotice("picker_failed")))
        }
    }

    override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = createBatch(listOf(asset), onResult)

    override fun createBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) = assetMain {
        val id = begin() ?: return@assetMain onResult(AssetActionResult.Failure(assetNotice("busy")))
        if (assets.isEmpty() || assets.sumOf { it.size.toLong() } > MAXIMUM_IMAGE_BATCH_BYTES) {
            complete(id, onResult, AssetActionResult.Failure(assetNotice("too_large")))
            return@assetMain
        }
        assetBackground({
            val staged = mutableListOf<TemporaryAsset>()
            try {
                for (asset in assets) {
                    try {
                        staged += temporaryAsset(asset)
                    } catch (error: Exception) {
                        if (assets.size == 1) throw error
                        throw LocalizedStateException(message("visual_export_batch_file_error", "name" to asset.name,
                            "reason" to error.localizedDescription(assetNotice("export_failed"))))
                    }
                }
                staged.toList()
            } catch (error: Throwable) {
                staged.forEach(TemporaryAsset::remove)
                throw error
            }
        }) { result ->
            result.fold({ temporary ->
                val picker = UIDocumentPickerViewController(forExportingURLs = temporary.map { it.url }, asCopy = true)
                picker.delegate = pickerDelegate
                pickerOperation = AssetPickerOperation.Create(id, picker, temporary, onResult)
                if (!present(picker)) {
                    pickerOperation = null
                    temporary.forEach(TemporaryAsset::remove)
                    complete(id, onResult, AssetActionResult.Failure(assetNotice("picker_failed")))
                }
            }, {
                complete(id, onResult, AssetActionResult.Failure(it.localizedDescription(assetNotice("export_failed"))))
            })
        }
    }

    override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = assetMain {
        val id = begin() ?: return@assetMain onResult(AssetActionResult.Failure(assetNotice("busy")))
        prepare(id, asset, onResult) { temporary ->
            val activity = UIActivityViewController(listOf(temporary.url), applicationActivities = null)
            activity.completionWithItemsHandler = { _, completed, _, error -> assetMain {
                temporary.remove()
                complete(id, onResult, when {
                    error != null -> AssetActionResult.Failure(LocalizedText.literal(error.localizedDescription))
                    completed -> AssetActionResult.Completed
                    else -> AssetActionResult.Cancelled
                })
            } }
            if (!present(activity)) {
                temporary.remove()
                complete(id, onResult, AssetActionResult.Failure(assetNotice("share_failed")))
            }
        }
    }

    override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = assetMain {
        val id = begin() ?: return@assetMain onResult(AssetActionResult.Failure(assetNotice("busy")))
        if (!supportsPrint || assetPrintInProgress) {
            complete(id, onResult, AssetActionResult.Failure(assetNotice("print_unavailable")))
            return@assetMain
        }
        if (asset.mimeType.lowercase() != "application/pdf") {
            complete(id, onResult, AssetActionResult.Failure(assetNotice("pdf_required")))
            return@assetMain
        }
        assetPrintInProgress = true
        prepare(id, asset, { result ->
            assetPrintInProgress = false
            onResult(result)
        }) { temporary ->
            val host = currentPresenter()
            if (host == null || !UIPrintInteractionController.canPrintURL(temporary.url)) {
                assetPrintInProgress = false
                temporary.remove()
                complete(id, onResult, AssetActionResult.Failure(assetNotice("print_unavailable")))
                return@prepare
            }
            val printController = UIPrintInteractionController.sharedPrintController()
            printController.printInfo = UIPrintInfo.printInfo().apply {
                outputType = UIPrintInfoOutputGeneral
                jobName = asset.name
            }
            printController.printingItem = temporary.url
            val completion: (UIPrintInteractionController?, Boolean, NSError?) -> Unit = { _, completed, error -> assetMain {
                if (activeRequest != id) return@assetMain
                printController.printingItem = null
                assetPrintInProgress = false
                temporary.remove()
                complete(id, onResult, when {
                    error != null -> AssetActionResult.Failure(LocalizedText.literal(error.localizedDescription))
                    completed -> AssetActionResult.Completed
                    else -> AssetActionResult.Cancelled
                })
            } }
            val presented = if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
                printController.presentFromRect(host.view.bounds, inView = host.view, animated = true, completionHandler = completion)
            } else printController.presentAnimated(true, completionHandler = completion)
            if (!presented) {
                printController.printingItem = null
                assetPrintInProgress = false
                temporary.remove()
                complete(id, onResult, AssetActionResult.Failure(assetNotice("print_unavailable")))
            }
        }
    }

    internal fun selectedDocuments(controller: UIDocumentPickerViewController, urls: List<*>) {
        val operation = pickerOperation?.takeIf { it.picker == controller } ?: return
        pickerOperation = null
        when (operation) {
            is AssetPickerOperation.Open -> {
                val url = urls.singleOrNull() as? NSURL
                if (url == null) {
                    complete(operation.id, operation.onResult, AssetOpenResult.Failure(assetNotice("pick_one")))
                } else assetBackground({ readAsset(url, operation.allowed, operation.maximumBytes) }) { result ->
                    complete(operation.id, operation.onResult, result.fold(
                        { AssetOpenResult.Opened(it) },
                        { AssetOpenResult.Failure(it.localizedDescription(assetNotice("read_failed"))) },
                    ))
                }
            }
            is AssetPickerOperation.Create -> {
                operation.temporary.forEach(TemporaryAsset::remove)
                val confirmed = urls.count { it is NSURL }
                val expected = operation.temporary.size
                val result = when {
                    confirmed == expected && urls.size == expected -> AssetActionResult.Completed
                    expected == 1 -> AssetActionResult.Failure(assetNotice("export_failed"))
                    else -> AssetActionResult.Failure(message("visual_export_batch_unconfirmed",
                        "confirmed" to confirmed, "expected" to expected))
                }
                complete(operation.id, operation.onResult, result)
            }
        }
    }

    internal fun pickerCancelled(controller: UIDocumentPickerViewController) {
        val operation = pickerOperation?.takeIf { it.picker == controller } ?: return
        pickerOperation = null
        when (operation) {
            is AssetPickerOperation.Open -> complete(operation.id, operation.onResult, AssetOpenResult.Cancelled)
            is AssetPickerOperation.Create -> {
                operation.temporary.forEach(TemporaryAsset::remove)
                complete(operation.id, operation.onResult, AssetActionResult.Cancelled)
            }
        }
    }

    /** The caller and the 64 MiB platform limit apply before copying or decoding provider bytes. */
    internal fun readAsset(url: NSURL, allowed: Set<String>, maximumBytes: Int): DocumentAsset {
        requireLocalized(url.fileURL) { assetNotice("file_url_required") }
        requireLocalized(maximumBytes > 0) { assetNotice("too_large") }
        val limit = minOf(maximumBytes, MAX_ASSET_BYTES).toULong()
        val acquired = url.startAccessingSecurityScopedResource()
        return try {
            assetCoordinatedRead(url) { actual -> memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                val path = actual.path ?: assetError("read_failed")
                val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error.ptr)
                    ?: throwAssetError(error.value, "read_failed")
                val size = (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue
                    ?: assetError("read_failed")
                requireLocalized(size in 1uL..limit) { assetNotice("too_large") }
                val data = NSData.dataWithContentsOfURL(actual, NSDataReadingMappedIfSafe, error.ptr)
                    ?: throwAssetError(error.value, "read_failed")
                requireLocalized(data.length in 1uL..limit) { assetNotice("too_large") }
                val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
                    ?: assetError("read_failed")
                val mimeType = imageMimeType(bytes)?.takeIf { it in allowed }
                    ?: "application/octet-stream".takeIf { it in allowed }
                    ?: assetError("unsupported_type")
                DocumentAsset(actual.lastPathComponent ?: "asset", mimeType, bytes)
            } }
        } finally {
            if (acquired) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun prepare(id: Long, asset: DocumentAsset, onResult: (AssetActionResult) -> Unit, ready: (TemporaryAsset) -> Unit) {
        assetBackground({ temporaryAsset(asset) }) { result ->
            result.fold(ready) { complete(id, onResult, AssetActionResult.Failure(it.localizedDescription(assetNotice("export_failed")))) }
        }
    }

    private fun begin(): Long? {
        if (activeRequest != null) return null
        return (++sequence).also { activeRequest = it }
    }

    private fun <T> complete(id: Long, onResult: (T) -> Unit, result: T) {
        if (activeRequest != id) return
        activeRequest = null
        onResult(result)
    }

    private fun currentPresenter(): UIViewController? {
        var host = presenter() ?: return null
        while (host.presentedViewController != null) host = host.presentedViewController!!
        return host.takeIf { it.view.window != null && !it.beingDismissed }
    }

    private fun present(controller: UIViewController): Boolean {
        val host = currentPresenter() ?: return false
        controller.popoverPresentationController?.let {
            it.sourceView = host.view
            it.sourceRect = host.view.bounds
        }
        host.presentViewController(controller, animated = true) {
            if (controller is UIDocumentPickerViewController) controller.presentationController?.delegate = pickerDelegate
        }
        return true
    }

    private sealed interface AssetPickerOperation {
        val id: Long
        val picker: UIDocumentPickerViewController
        data class Open(override val id: Long, override val picker: UIDocumentPickerViewController,
            val allowed: Set<String>, val maximumBytes: Int, val onResult: (AssetOpenResult) -> Unit) : AssetPickerOperation
        data class Create(override val id: Long, override val picker: UIDocumentPickerViewController,
            val temporary: List<TemporaryAsset>, val onResult: (AssetActionResult) -> Unit) : AssetPickerOperation
    }
}

private const val MAX_ASSET_BYTES = 64 * 1024 * 1024
private val imageMimeTypes = setOf("image/png", "image/jpeg", "image/gif")
private val assetExtensions = mapOf(
    "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/svg+xml" to "svg", "application/pdf" to "pdf",
    "application/json" to "json", "application/x-logisim-bundle" to "lsebdl", "application/x-tex" to "tex",
    "application/x-logisim-circuit" to "circ", "text/csv" to "csv", "application/zip" to "zip",
)
// UIKit has one shared print controller across all of this application's windows. Accessed only on main.
private var assetPrintInProgress = false

private fun acceptedAssetTypes(requested: List<String>): Set<String> = requested.map { it.lowercase() }
    .flatMap { when (it) {
        "*/*" -> imageMimeTypes + "application/octet-stream"
        "image/*" -> imageMimeTypes
        "image/jpg" -> setOf("image/jpeg")
        else -> setOf(it)
    } }
    .filterTo(mutableSetOf()) { it in imageMimeTypes || it == "application/octet-stream" }

private fun imageMimeType(bytes: ByteArray): String? {
    fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
    return when {
        starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        starts(255, 216, 255) -> "image/jpeg"
        starts(71, 73, 70, 56, 55, 97) || starts(71, 73, 70, 56, 57, 97) -> "image/gif"
        else -> null
    }
}

internal data class TemporaryAsset(val url: NSURL, val directory: String) {
    fun remove() { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
}

internal fun temporaryAsset(asset: DocumentAsset): TemporaryAsset {
    requireLocalized(asset.size in 1..MAX_ASSET_BYTES) { assetNotice("too_large") }
    val mime = asset.mimeType.lowercase()
    val extension = assetExtensions[mime]
    requireLocalized(extension != null || mime == "application/octet-stream" || mime == "text/plain") {
        assetNotice("unsupported_export")
    }
    val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: assetError("export_failed")
    val directory = "$root/LogisimAssetExports/" + NSUUID().UUIDString
    checkLocalized(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) { assetNotice("export_failed") }
    try {
        val clean = asset.name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() && it != "." && it != ".." } ?: if (extension == null) "document.bin" else "circuit"
        val name = if (extension == null || clean.endsWith(".$extension", ignoreCase = true)) clean else "$clean.$extension"
        val url = NSURL.fileURLWithPath("$directory/$name")
        val bytes = asset.bytes
        if (extension == "pdf") requireLocalized(bytes.size >= 5 && bytes.copyOfRange(0, 5).decodeToString() == "%PDF-") {
            assetNotice("pdf_required")
        }
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        checkLocalized(data.writeToURL(url, atomically = true)) { assetNotice("export_failed") }
        return TemporaryAsset(url, directory)
    } catch (error: Throwable) {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
        throw error
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private class IosAssetPickerDelegate(owner: IosDocumentAssetActions) : NSObject(), UIDocumentPickerDelegateProtocol,
    UIAdaptivePresentationControllerDelegateProtocol {
    private val owner = WeakReference(owner)
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        owner.get()?.selectedDocuments(controller, didPickDocumentsAtURLs)
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { owner.get()?.pickerCancelled(controller) }
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        (presentationController.presentedViewController as? UIDocumentPickerViewController)?.let { owner.get()?.pickerCancelled(it) }
    }
}

private fun <T> assetCoordinatedRead(url: NSURL, action: (NSURL) -> T): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var result: Result<T>? = null
    NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, error.ptr) { actual ->
        result = runCatching { action(actual ?: assetError("read_failed")) }
    }
    error.value?.let { throwAssetError(it, "read_failed") }
    (result ?: assetError("read_failed")).getOrThrow()
}

private fun assetMain(action: () -> Unit) { dispatch_async(dispatch_get_main_queue(), action) }

private fun <T> assetBackground(action: () -> T, completion: (Result<T>) -> Unit) {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
        val result = runCatching(action)
        assetMain { completion(result) }
    }
}

private fun assetNotice(key: String): LocalizedText = message("ios_asset_$key")
private fun assetError(key: String): Nothing = throw LocalizedStateException(assetNotice(key))
private fun throwAssetError(error: NSError?, fallback: String): Nothing =
    throw LocalizedStateException(error?.let { LocalizedText.literal(it.localizedDescription) } ?: assetNotice(fallback))
