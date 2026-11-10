package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message

internal data class AndroidDirectoryEntry(
    val id: String,
    val name: String,
    val directory: Boolean,
    val virtual: Boolean = false,
)

internal interface AndroidDirectoryAccess {
    fun children(parentId: String, maximumEntries: Int): List<AndroidDirectoryEntry>
    fun contained(documentId: String): Boolean
    fun read(documentId: String, maximumBytes: Int): DirectoryAssetContent?
}

/** Resolves display-name segments within an explicitly granted tree. IDs remain opaque. */
internal class AndroidDirectoryTree(private val rootId: String, private val access: AndroidDirectoryAccess) {
    fun circuitFiles(maximumEntries: Int): List<DirectoryCircuitFile> {
        if (maximumEntries !in 1..MAXIMUM_CIRCUITS) fail("invalid_limit")
        val scan = Scan()
        val remaining = ArrayDeque<Directory>()
        remaining.addLast(Directory(rootId, "", 0))
        val files = mutableListOf<DirectoryCircuitFile>()
        while (remaining.isNotEmpty()) {
            val directory = remaining.removeLast()
            for (entry in scan.children(directory.id, directory.depth)) {
                val path = if (directory.path.isEmpty()) entry.name else "${directory.path}/${entry.name}"
                if (entry.directory) remaining.addLast(Directory(entry.id, path, directory.depth + 1))
                else if (entry.name.endsWith(".circ", ignoreCase = true)) {
                    if (files.size == maximumEntries) fail("too_many_entries")
                    files += DirectoryCircuitFile(path, entry.name)
                }
            }
        }
        return files.sortedBy { it.path }
    }

    fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent? {
        if (maximumBytes !in 1..MAXIMUM_BYTES) fail("invalid_limit")
        val segments = pathSegments(relativePath)
        val scan = Scan()
        var parent = rootId
        for ((index, segment) in segments.withIndex()) {
            val entry = scan.children(parent, index).firstOrNull { it.name == segment } ?: return null
            if (index != segments.lastIndex) {
                if (!entry.directory) return null
                parent = entry.id
            } else {
                if (entry.directory) fail("not_file")
                if (!access.contained(entry.id)) fail("unverifiable")
                val content = access.read(entry.id, maximumBytes) ?: return null
                if (content.size > maximumBytes) fail("too_large")
                return content
            }
        }
        return null
    }

    private inner class Scan {
        private val ids = mutableSetOf(rootId)

        fun children(parentId: String, parentDepth: Int): List<AndroidDirectoryEntry> {
            if (parentId != rootId && !access.contained(parentId)) fail("unverifiable")
            val available = MAXIMUM_NODES - ids.size
            val children = access.children(parentId, available)
            if (children.size > available) fail("too_many_entries")
            if (children.isNotEmpty() && parentDepth >= MAXIMUM_DEPTH) fail("too_deep")
            val names = mutableSetOf<String>()
            for (entry in children) {
                if (!safeSegment(entry.name)) fail("invalid_path")
                if (!names.add(caseKey(entry.name))) fail("ambiguous")
                if (!ids.add(entry.id)) fail("alias")
                if (entry.virtual) fail("virtual")
                if (!access.contained(entry.id)) fail("unverifiable")
            }
            return children
        }
    }

    private fun pathSegments(path: String): List<String> {
        if (path.isEmpty() || path.startsWith('/') || path.endsWith('/') || path.contains("//")) fail("invalid_path")
        val segments = path.split('/')
        if (segments.any { !safeSegment(it) }) fail("invalid_path")
        if (segments.size > MAXIMUM_DEPTH) fail("too_deep")
        return segments
    }

    private fun safeSegment(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code in 127..159 }

    private fun caseKey(value: String): String = buildString(value.length) {
        value.forEach { append(it.uppercaseChar().lowercaseChar()) }
    }

    private fun fail(reason: String): Nothing =
        throw LocalizedArgumentException(message("android_directory_$reason"))

    private data class Directory(val id: String, val path: String, val depth: Int)

    private companion object {
        const val MAXIMUM_CIRCUITS = 4096
        const val MAXIMUM_NODES = 16_384
        const val MAXIMUM_DEPTH = 64
        const val MAXIMUM_BYTES = 2_000_000
    }
}
