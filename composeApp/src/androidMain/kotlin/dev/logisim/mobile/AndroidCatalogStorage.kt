package dev.logisim.mobile

import android.util.AtomicFile
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AndroidCatalogStorage(directory: File) : WorkspaceCatalogStorage {
    private val directory = directory.canonicalFile
    private val lock = locks.getOrPut(this.directory.path) { ReentrantLock() }

    override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock action()
        ensureDirectory()
        RandomAccessFile(file("catalog.lock"), "rw").use { stream -> stream.channel.lock().use { action() } }
    }

    override fun read(name: String): String? = try {
        AtomicFile(file(name)).readFully().toString(Charsets.UTF_8)
    } catch (_: FileNotFoundException) { null }

    override fun listNames(): List<String> = directory.listFiles()?.filter(File::isFile)?.map(File::getName).orEmpty()

    override fun writeAtomically(name: String, contents: String) {
        ensureDirectory()
        val destination = AtomicFile(file(name))
        val stream = destination.startWrite()
        try { stream.write(contents.toByteArray(Charsets.UTF_8)); destination.finishWrite(stream) }
        catch (error: Exception) { destination.failWrite(stream); throw error }
    }

    override val supportsDelete: Boolean get() = true
    override fun delete(name: String) {
        // AtomicFile removes the file and any backup or partial write it left.
        AtomicFile(file(name)).delete()
    }

    private fun ensureDirectory() = checkLocalized(directory.isDirectory || directory.mkdirs()) { message(Messages.ErrorStorageDirectory) }
    private fun file(name: String): File {
        requireLocalized(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in setOf(".", "..")) { message(Messages.ErrorStorageFilename) }
        return File(directory, name)
    }
    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
}
