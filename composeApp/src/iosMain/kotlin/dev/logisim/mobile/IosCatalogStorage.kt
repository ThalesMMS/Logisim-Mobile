@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.requireLocalized
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.EINTR
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.open

class IosCatalogStorage(
    private val directory: String = applicationWorkspaceDirectory(),
) : WorkspaceCatalogStorage {
    override fun <T> withExclusiveAccess(action: () -> T): T {
        // flock on a second descriptor blocks even in the same thread; a nested call already holds the lock.
        if (directory in HeldCatalogLocks.directories) return action()
        prepareDirectory()
        val descriptor = open("$directory/catalog.lock", O_CREAT or O_RDWR, 384)
        checkLocalized(descriptor >= 0) { iosNotice("catalog_lock_failed") }
        try {
            while (flock(descriptor, LOCK_EX) != 0) {
                checkLocalized(errno == EINTR) { iosNotice("catalog_lock_failed") }
            }
            HeldCatalogLocks.directories += directory
            try {
                return action()
            } finally {
                HeldCatalogLocks.directories -= directory
            }
        } finally {
            flock(descriptor, LOCK_UN)
            close(descriptor)
        }
    }

    override fun listNames(): List<String> {
        prepareDirectory()
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
            ?.filterIsInstance<String>()
            ?: iosError("catalog_read_failed")
    }

    override fun read(name: String): String? {
        val path = path(name)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()
            ?: iosError("catalog_read_failed")
    }

    override fun writeAtomically(name: String, contents: String) {
        prepareDirectory()
        checkLocalized(NSString.create(string = contents).writeToFile(path(name), true, NSUTF8StringEncoding, null)) {
            iosNotice("catalog_write_failed")
        }
    }

    override val supportsDelete: Boolean get() = true
    override fun delete(name: String) {
        val path = path(name)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        checkLocalized(NSFileManager.defaultManager.removeItemAtPath(path, null)) { iosNotice("catalog_write_failed") }
    }

    private fun prepareDirectory() {
        checkLocalized(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
            iosNotice("catalog_create_failed")
        }
    }

    private fun path(name: String): String {
        requireLocalized(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..") {
            iosNotice("catalog_invalid_name")
        }
        return "$directory/$name"
    }
}

private fun applicationWorkspaceDirectory(): String {
    val root = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: iosError("catalog_unavailable")
    return "$root/LogisimMobile/workspace"
}

@kotlin.native.concurrent.ThreadLocal
private object HeldCatalogLocks {
    val directories = mutableSetOf<String>()
}
