package dev.logisim.mobile.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** JVM storage used by desktop tools and filesystem-level recovery tests. */
class FileWorkspaceCatalogStorage(directory: File) : WorkspaceCatalogStorage {
    private val directory = directory.canonicalFile
    private val lock = locks.getOrPut(this.directory.path) { ReentrantLock() }

    override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock action()
        ensureDirectory()
        FileChannel.open(file("catalog.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    override fun read(name: String): String? = file(name).let { if (it.exists()) it.readText() else null }

    override fun listNames(): List<String> = directory.listFiles()?.filter(File::isFile)?.map(File::getName).orEmpty()

    override fun writeAtomically(name: String, contents: String) {
        ensureDirectory()
        val destination = file(name).toPath()
        val temporary = Files.createTempFile(directory.toPath(), ".write-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("O sistema de arquivos não suporta substituição atômica.", error)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun ensureDirectory() = check(directory.isDirectory || directory.mkdirs()) { "Não foi possível criar a pasta do catálogo." }
    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in setOf(".", "..")) { "Nome de arquivo interno inválido." }
        return File(directory, name)
    }

    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
}
