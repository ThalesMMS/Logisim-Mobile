@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsAliasFileKey
import platform.Foundation.NSUUID
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
import platform.posix.AT_SYMLINK_NOFOLLOW
import platform.posix.EINTR
import platform.posix.ELOOP
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_NOFOLLOW_ANY
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.dup
import platform.posix.fdopendir
import platform.posix.fstat
import platform.posix.fstatat
import platform.posix.open as posixOpen
import platform.posix.openat
import platform.posix.posix_errno
import platform.posix.read as posixRead
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.stat
import kotlin.native.ref.WeakReference

/** The Files folder grant is independent of a circuit document's security-scoped URL. */
class IosDirectoryAssetActions(private val presenter: () -> UIViewController?) : DirectoryAssetActions {
    private val delegate = IosDirectoryPickerDelegate(this)
    private var active: DirectoryPickerRequest? = null

    override fun open(onResult: (DirectoryAssetOpenResult) -> Unit) = directoryMain {
        if (active != null) {
            onResult(DirectoryAssetOpenResult.Failure(directoryNotice("busy")))
            return@directoryMain
        }
        val folder = UTType.typeWithIdentifier("public.folder")
        val host = currentPresenter()
        if (folder == null || host == null) {
            onResult(DirectoryAssetOpenResult.Failure(directoryNotice("picker_failed")))
            return@directoryMain
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(folder), asCopy = false)
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        active = DirectoryPickerRequest(picker, onResult)
        picker.popoverPresentationController?.let {
            it.sourceView = host.view
            it.sourceRect = host.view.bounds
        }
        host.presentViewController(picker, animated = true) {
            picker.presentationController?.delegate = delegate
        }
    }

    internal fun selected(controller: UIDocumentPickerViewController, urls: List<*>) {
        val request = active?.takeIf { it.picker == controller && !it.reading } ?: return
        request.reading = true
        val url = urls.singleOrNull() as? NSURL
        if (url == null) {
            complete(request, DirectoryAssetOpenResult.Failure(directoryNotice("pick_one")))
            return
        }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val result = runCatching { IosDirectoryAssetGrant.open(url) }
            directoryMain {
                complete(request, result.fold(
                    { DirectoryAssetOpenResult.Opened(it) },
                    { DirectoryAssetOpenResult.Failure(it.localizedDescription(directoryNotice("read_failed"))) },
                ))
            }
        }
    }

    internal fun cancelled(controller: UIDocumentPickerViewController) {
        val request = active?.takeIf { it.picker == controller && !it.reading } ?: return
        complete(request, DirectoryAssetOpenResult.Cancelled)
    }

    private fun complete(request: DirectoryPickerRequest, result: DirectoryAssetOpenResult) {
        if (active !== request) {
            (result as? DirectoryAssetOpenResult.Opened)?.grant?.release()
            return
        }
        active = null
        request.onResult(result)
    }

    private fun currentPresenter(): UIViewController? {
        var host = presenter() ?: return null
        while (host.presentedViewController != null) host = host.presentedViewController!!
        return host.takeIf { it.view.window != null && !it.beingDismissed }
    }

    private class DirectoryPickerRequest(
        val picker: UIDocumentPickerViewController,
        val onResult: (DirectoryAssetOpenResult) -> Unit,
        var reading: Boolean = false,
    )
}

/** Each operation captures its own URL and security scope. release never waits for filesystem I/O. */
internal class IosDirectoryAssetGrant private constructor(access: DirectoryRootAccess) : DirectoryAssetGrant {
    private val lock = NSLock()
    private var rootAccess: DirectoryRootAccess? = access
    override val id: String = NSUUID().UUIDString
    override val name: String = access.url.lastPathComponent?.takeIf { it.isNotBlank() } ?: "/"

    override fun circuitFiles(maximumEntries: Int): List<DirectoryCircuitFile> {
        requireLocalized(maximumEntries > 0) { directoryNotice("too_many_entries") }
        return withRoot { root, descriptor ->
            val files = mutableListOf<DirectoryCircuitFile>()
            val traversal = DirectoryTraversal(minOf(maximumEntries, MAX_DIRECTORY_NODES), files)
            traversal.walk(descriptor, root, "", 0)
            files.sortedBy { it.path }
        }
    }

    override fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent? {
        requireLocalized(directoryPathIsCanonical(relativePath)) { directoryNotice("unsafe_path") }
        requireLocalized(maximumBytes > 0) { directoryNotice("too_large") }
        val limit = minOf(maximumBytes, MAX_DIRECTORY_FILE_BYTES)
        return withRoot(coordinateRoot = false) { root, descriptor ->
            val path = "$root/$relativePath"
            if (!directoryFileExistsSafely(descriptor, relativePath)) return@withRoot null
            if (directoryIsAlias(NSURL.fileURLWithPath(path))) directoryError("unsafe_path")
            directoryCoordinatedReadOrMissing(NSURL.fileURLWithPath(path)) { actual ->
                if (actual.URLByStandardizingPath?.path != path) directoryError("unsafe_path")
                val file = openRelativeFile(descriptor, relativePath) ?: return@directoryCoordinatedReadOrMissing null
                try {
                    if (directoryIsAlias(NSURL.fileURLWithPath(path))) directoryError("unsafe_path")
                    val bytes = readDirectoryFile(file, limit)
                    DirectoryAssetContent(bytes, directorySha256(bytes))
                } finally { close(file) }
            }
        }
    }

    override fun release() {
        lock.lock()
        try { rootAccess = null } finally { lock.unlock() }
    }

    private fun <T> withRoot(coordinateRoot: Boolean = true, action: (String, Int) -> T): T {
        lock.lock()
        val captured = try { rootAccess ?: directoryError("released") } finally { lock.unlock() }
        return directorySecurityScope(captured.url) {
            val useRoot: (NSURL) -> T = { actual ->
                if (directoryCanonicalRoot(actual) != captured.path) directoryError("changed")
                val descriptor = openDirectoryRoot(captured.path)
                try {
                    if (directoryIdentity(descriptor) != captured.identity) directoryError("changed")
                    action(captured.path, descriptor)
                } finally { close(descriptor) }
            }
            if (coordinateRoot) directoryCoordinatedRead(captured.url, useRoot) else useRoot(captured.url)
        }
    }

    companion object {
        fun open(url: NSURL): IosDirectoryAssetGrant {
            requireLocalized(url.fileURL) { directoryNotice("file_url_required") }
            val root = directorySecurityScope(url) {
                val selectedCanonical = directoryCanonicalRoot(url)
                directoryCoordinatedRead(url) { actual ->
                    val canonical = directoryCanonicalRoot(actual)
                    if (canonical != selectedCanonical) directoryError("changed")
                    val descriptor = openDirectoryRoot(canonical)
                    try { DirectoryRootAccess(url, canonical, directoryIdentity(descriptor)) }
                    finally { close(descriptor) }
                }
            }
            return IosDirectoryAssetGrant(root)
        }
    }
}

private data class DirectoryRootAccess(val url: NSURL, val path: String, val identity: DirectoryIdentity)
private data class DirectoryIdentity(val device: Int, val inode: ULong)
private fun directoryIdentity(descriptor: Int): DirectoryIdentity = memScoped {
    val info = alloc<stat>()
    if (fstat(descriptor, info.ptr) != 0) directoryError("read_failed")
    DirectoryIdentity(info.st_dev, info.st_ino)
}
private const val MAX_DIRECTORY_FILE_BYTES = 64 * 1024 * 1024
private const val MAX_DIRECTORY_NODES = 10_000
private const val MAX_DIRECTORY_DEPTH = 64

private class DirectoryTraversal(val maximumFiles: Int, val files: MutableList<DirectoryCircuitFile>) {
    private var visited = 0

    fun walk(descriptor: Int, root: String, relative: String, depth: Int) {
        if (depth > MAX_DIRECTORY_DEPTH) directoryError("too_many_entries")
        val duplicate = dup(descriptor)
        if (duplicate < 0) directoryError("read_failed")
        val stream = fdopendir(duplicate)
        if (stream == null) {
            close(duplicate)
            directoryError("read_failed")
        }
        try {
            while (true) {
                set_posix_errno(0)
                val entry = readdir(stream)
                if (entry == null) {
                    if (posix_errno() != 0) directoryError("read_failed")
                    break
                }
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                if (++visited > MAX_DIRECTORY_NODES) directoryError("too_many_entries")
                val path = if (relative.isEmpty()) name else "$relative/$name"
                if (!directoryPathIsCanonical(path)) continue
                val type = memScoped {
                    val info = alloc<stat>()
                    if (fstatat(descriptor, name, info.ptr, AT_SYMLINK_NOFOLLOW) != 0) directoryError("read_failed")
                    info.st_mode.toInt() and S_IFMT
                }
                if (type == S_IFLNK) continue
                if (type != S_IFREG && type != S_IFDIR) continue
                if (directoryIsAlias(NSURL.fileURLWithPath("$root/$path"))) continue
                if (type == S_IFDIR) {
                    val child = openat(descriptor, name, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                    if (child < 0) directoryOpenError()
                    try { walk(child, root, path, depth + 1) } finally { close(child) }
                } else if (name.endsWith(".circ", ignoreCase = true)) {
                    if (files.size >= maximumFiles) directoryError("too_many_entries")
                    files += DirectoryCircuitFile(path, name)
                }
            }
        } finally { closedir(stream) }
    }
}

private fun directoryPathIsCanonical(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
    path.none { it == '\\' || it == ':' || it.isISOControl() } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

private fun directoryCanonicalRoot(url: NSURL): String {
    requireLocalized(url.fileURL) { directoryNotice("file_url_required") }
    val path = url.URLByStandardizingPath?.path ?: directoryError("read_failed")
    val type = memScoped {
        val info = alloc<stat>()
        if (platform.posix.lstat(path, info.ptr) != 0) directoryError("read_failed")
        info.st_mode.toInt() and S_IFMT
    }
    if (type != S_IFDIR || directoryIsAlias(url)) directoryError("unsafe_path")
    val parent = url.URLByDeletingLastPathComponent?.URLByResolvingSymlinksInPath?.path
        ?: directoryError("unsafe_path")
    val leaf = url.lastPathComponent?.takeIf { it.isNotEmpty() && it != "/" } ?: directoryError("unsafe_path")
    return "${parent.trimEnd('/')}/$leaf"
}

/** One open preserves the sandbox grant and rejects symlinks in every path component. */
private fun openDirectoryRoot(path: String): Int {
    // Darwin rejects combining NOFOLLOW with NOFOLLOW_ANY. ANY already refuses
    // symlinks in every component, including the final directory.
    val descriptor = posixOpen(path, O_RDONLY or O_DIRECTORY or O_NOFOLLOW_ANY or O_CLOEXEC)
    if (descriptor < 0) directoryOpenError()
    return descriptor
}

/** Refuse links before asking a provider to coordinate a child URL. The later open checks again. */
private fun directoryFileExistsSafely(root: Int, path: String): Boolean = memScoped {
    var current = dup(root)
    if (current < 0) directoryError("read_failed")
    try {
        val segments = path.split('/')
        for (segment in segments.dropLast(1)) {
            val next = openat(current, segment, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
            if (next < 0) {
                if (posix_errno() == ENOENT) return@memScoped false
                directoryOpenError()
            }
            close(current)
            current = next
        }
        val info = alloc<stat>()
        if (fstatat(current, segments.last(), info.ptr, AT_SYMLINK_NOFOLLOW) != 0) {
            if (posix_errno() == ENOENT) return@memScoped false
            directoryOpenError()
        }
        if (info.st_mode.toInt() and S_IFMT != S_IFREG) directoryError("unsafe_path")
        true
    } finally { close(current) }
}

private fun openRelativeFile(root: Int, path: String): Int? {
    var current = dup(root)
    if (current < 0) directoryError("read_failed")
    try {
        val segments = path.split('/')
        for ((index, segment) in segments.withIndex()) {
            val isFile = index == segments.lastIndex
            val flags = O_RDONLY or O_NOFOLLOW or O_CLOEXEC or if (isFile) O_NONBLOCK else O_DIRECTORY
            val next = openat(current, segment, flags)
            if (next < 0) {
                if (posix_errno() == ENOENT) return null
                directoryOpenError()
            }
            close(current)
            current = next
        }
        return current.also { current = -1 }
    } finally { if (current >= 0) close(current) }
}

private fun readDirectoryFile(descriptor: Int, maximumBytes: Int): ByteArray = memScoped {
    val info = alloc<stat>()
    if (fstat(descriptor, info.ptr) != 0) directoryError("read_failed")
    if (info.st_mode.toInt() and S_IFMT != S_IFREG) directoryError("unsafe_path")
    requireLocalized(info.st_size in 0..maximumBytes.toLong()) { directoryNotice("too_large") }
    val bytes = ByteArray(info.st_size.toInt())
    var received = 0
    if (bytes.isNotEmpty()) bytes.usePinned { target ->
        while (received < bytes.size) {
            val count = posixRead(descriptor, target.addressOf(received), (bytes.size - received).toULong())
            when {
                count > 0 -> received += count.toInt()
                count == 0L -> break
                posix_errno() != EINTR -> directoryError("read_failed")
            }
        }
    }
    val probe = alloc<ByteVar>()
    while (true) {
        val count = posixRead(descriptor, probe.ptr, 1u)
        if (count > 0) directoryError(if (received >= maximumBytes) "too_large" else "changed")
        if (count == 0L) break
        if (posix_errno() != EINTR) directoryError("read_failed")
    }
    if (received != bytes.size) directoryError("changed")
    bytes
}

private fun directoryIsAlias(url: NSURL): Boolean = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val values = url.resourceValuesForKeys(listOf(NSURLIsAliasFileKey), error.ptr)
        ?: directoryError("read_failed")
    if (error.value != null) directoryError("read_failed")
    (values[NSURLIsAliasFileKey] as? NSNumber)?.boolValue == true
}

private fun directoryOpenError(): Nothing = directoryError(
    if (posix_errno() == ELOOP || posix_errno() == ENOTDIR) "unsafe_path" else "read_failed",
)

private fun directorySha256(bytes: ByteArray): String {
    val digest = ByteArray(32)
    digest.usePinned { target ->
        if (bytes.isEmpty()) CC_SHA256(null, 0u, target.addressOf(0).reinterpret<UByteVar>())
        else bytes.usePinned { source -> CC_SHA256(source.addressOf(0), bytes.size.toUInt(), target.addressOf(0).reinterpret<UByteVar>()) }
    }
    return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

private inline fun <T> directorySecurityScope(url: NSURL, action: () -> T): T {
    val acquired = url.startAccessingSecurityScopedResource()
    return try { action() } finally { if (acquired) url.stopAccessingSecurityScopedResource() }
}

private fun <T> directoryCoordinatedRead(url: NSURL, action: (NSURL) -> T): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var result: Result<T>? = null
    NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, error.ptr) { actual ->
        result = runCatching { action(actual ?: directoryError("read_failed")) }
    }
    if (error.value != null) directoryError("read_failed")
    (result ?: directoryError("read_failed")).getOrThrow()
}

private fun <T> directoryCoordinatedReadOrMissing(url: NSURL, action: (NSURL) -> T?): T? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var result: Result<T?>? = null
    NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, error.ptr) { actual ->
        result = runCatching { action(actual ?: directoryError("read_failed")) }
    }
    error.value?.let {
        if (it.domain == NSCocoaErrorDomain && (it.code == NSFileReadNoSuchFileError || it.code == NSFileNoSuchFileError)) {
            return@memScoped null
        }
        directoryError("read_failed")
    }
    (result ?: directoryError("read_failed")).getOrThrow()
}

private fun directoryNotice(key: String): LocalizedText = message("ios_directory_$key")
private fun directoryError(key: String): Nothing = throw LocalizedStateException(directoryNotice(key))
private fun directoryMain(action: () -> Unit) { dispatch_async(dispatch_get_main_queue(), action) }

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private class IosDirectoryPickerDelegate(owner: IosDirectoryAssetActions) : NSObject(), UIDocumentPickerDelegateProtocol,
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
