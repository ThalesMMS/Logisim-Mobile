package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

/** A portable Logisim project bundle. Files are addressed relative to the archive root. */
data class CircuitBundle(val mainFile: String, val files: Map<String, ByteArray>) {
    init {
        requireLocalized(mainFile in files) { message(Messages.CodecBundleMainMissing, "mainFile" to mainFile) }
        requireLocalized(mainFile != "manifest.xml" && '/' !in mainFile) { message(Messages.CodecBundleMainRoot) }
        requireLocalized(files.keys.all { BundlePaths.file(it) == it }) { message(Messages.CodecBundlePathsNoncanonical) }
    }
    fun text(path: String): String = files.getValue(BundlePaths.file(path)).decodeToString(throwOnInvalidSequence = true)
}

sealed interface CircBundleImportResult {
    data class Success(val bundle: CircuitBundle) : CircBundleImportResult
    data class Failure(val notices: List<LocalizedText>) : CircBundleImportResult {
        val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
}

object CircBundleCodec {
    const val MAX_FILE_BYTES = 2_000_000
    const val MAX_TOTAL_BYTES = 32_000_000
    const val MAX_FILES = 256

    fun read(bytes: ByteArray): CircBundleImportResult = try {
        val files = BundleZip.read(bytes)
        CircBundleImportResult.Success(CircuitBundle(mainFile(files), files))
    } catch (failure: Exception) {
        CircBundleImportResult.Failure(listOf(failure.localizedDescription(message(Messages.CodecBundleInvalid))))
    }

    private fun mainFile(files: Map<String, ByteArray>): String {
        val manifest = files["manifest.xml"] ?: throw LocalizedArgumentException(message(Messages.CodecBundleManifestMissing))
        val root = CircXmlParser(manifest.decodeToString(throwOnInvalidSequence = true)).parse()
        requireLocalized(root.name == "logisim" && root.attributes == mapOf("type" to "bundle", "version" to "1")) {
            message(Messages.CodecBundleManifestUnsupported)
        }
        val meta = root.children.singleOrNull { it.name == "meta" }
        val tool = meta?.children?.singleOrNull { it.name == "tool" }
        requireLocalized(tool != null && tool.attributes.keys == setOf("name", "version") && !tool.attributes["name"].isNullOrBlank() && !tool.attributes["version"].isNullOrBlank()) {
            message(Messages.CodecBundleToolIdentity)
        }
        val main = root.children.singleOrNull { it.name == "project" }?.children
            ?.singleOrNull { it.name == "files" }?.children
            ?.filter { it.name == "file" && it.attributes["main"] == "true" }
            ?.singleOrNull()
            ?: throw LocalizedArgumentException(message(Messages.CodecBundleMainUnique))
        requireLocalized(main.attributes == mapOf("main" to "true") && main.children.isEmpty()) { message(Messages.CodecBundleMainInvalid) }
        return BundlePaths.file(main.text.trim())
    }

    fun create(project: CircuitProject, registry: ComponentRegistry = ComponentCatalog.registry): CircuitBundle =
        CircuitBundle("main.circ", linkedMapOf(
            "manifest.xml" to manifestBytes(),
            "main.circ" to CircCodec.writeProject(project, registry).encodeToByteArray(),
        ))

    internal fun manifestBytes(): ByteArray =
        """<logisim type="bundle" version="1"><meta><tool name="Logisim Mobile" version="1"/></meta><project><files><file main="true">main.circ</file></files></project></logisim>""".encodeToByteArray()

    fun write(bundle: CircuitBundle): ByteArray {
        requireLocalized(bundle.files.size <= MAX_FILES) { message(Messages.CodecBundleFileCountLimit) }
        requireLocalized(bundle.files.keys.map { it.lowercase() }.distinct().size == bundle.files.size) { message(Messages.CodecBundleCaseCollision) }
        requireLocalized(bundle.files.values.all { it.size <= MAX_FILE_BYTES } && bundle.files.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) {
            message(Messages.CodecBundleSizeLimit)
        }
        requireLocalized(mainFile(bundle.files) == bundle.mainFile) { message(Messages.CodecBundleMainMismatch) }
        return BundleZip.write(bundle.files)
    }
}

internal object BundlePaths {
    fun file(raw: String): String {
        val path = raw.replace('\\', '/')
        requireLocalized(path.isNotEmpty() && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { message(Messages.CodecBundleAbsolutePath, "raw" to raw) }
        requireLocalized(path.none { it.code < 32 } && path.length <= 512) { message(Messages.CodecBundleNameInvalid) }
        val parts = path.split('/')
        requireLocalized(parts.none { it.isEmpty() || it == "." || it == ".." }) { message(Messages.CodecBundlePathInvalid, "raw" to raw) }
        requireLocalized(parts.none { ':' in it || it.endsWith('.') || it.endsWith(' ') ||
            it.substringBefore('.').uppercase() in reservedNames }) { message(Messages.CodecBundleNameNonportable, "raw" to raw) }
        requireLocalized(parts.size == 1 || parts.size == 2 && parts[0] == "library") { message(Messages.CodecBundleLibraryLocation) }
        return path
    }

    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun resolve(from: String, relative: String): String {
        val normalized = relative.replace('\\', '/')
        requireLocalized(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { message(Messages.CodecBundleAbsoluteReference, "relative" to relative) }
        val parts = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in normalized.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { requireLocalized(parts.isNotEmpty()) { message(Messages.CodecBundleReferenceEscape, "relative" to relative) }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        return file(parts.joinToString("/"))
    }
}

internal object BundleZip {
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        requireLocalized(bytes.size <= CircBundleCodec.MAX_TOTAL_BYTES + 1_000_000) { message(Messages.CodecZipSizeLimit) }
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
            bytes.u32(it) == 0x06054b50L && it + 22 + bytes.u16(it + 20) == bytes.size
        } ?: throw LocalizedArgumentException(message(Messages.CodecZipDirectoryMissing))
        requireLocalized(bytes.u16(end + 4) == 0 && bytes.u16(end + 6) == 0) { message(Messages.CodecZipSplitUnsupported) }
        val count = bytes.u16(end + 10)
        requireLocalized(count == bytes.u16(end + 8) && count in 1..CircBundleCodec.MAX_FILES) { message(Messages.CodecZipFileCountInvalid) }
        val directorySize = bytes.u32(end + 12).checkedInt()
        val directory = bytes.u32(end + 16).checkedInt()
        requireLocalized(directory.toLong() + directorySize == end.toLong()) { message(Messages.CodecZipDirectoryInvalid) }
        var cursor = directory
        var total = 0L
        val files = linkedMapOf<String, ByteArray>()
        val portableNames = mutableSetOf<String>()
        repeat(count) {
            requireLocalized(bytes.u32(cursor) == 0x02014b50L && cursor + 46 <= end) { message(Messages.CodecZipEntryInvalid) }
            val flags = bytes.u16(cursor + 8)
            val method = bytes.u16(cursor + 10)
            val crc = bytes.u32(cursor + 16)
            val compressedSize = bytes.u32(cursor + 20).checkedInt()
            val size = bytes.u32(cursor + 24).checkedInt()
            val nameSize = bytes.u16(cursor + 28)
            val extraSize = bytes.u16(cursor + 30)
            val commentSize = bytes.u16(cursor + 32)
            requireLocalized(bytes.u16(cursor + 34) == 0 && flags and 1 == 0 && method in setOf(0, 8)) { message(Messages.CodecZipMethodUnsupported) }
            requireLocalized(size <= CircBundleCodec.MAX_FILE_BYTES && compressedSize <= CircBundleCodec.MAX_TOTAL_BYTES) { message(Messages.CodecZipFileSizeLimit) }
            total += size
            requireLocalized(total <= CircBundleCodec.MAX_TOTAL_BYTES) { message(Messages.CodecZipExpandedSizeLimit) }
            val unixMode = (bytes.u32(cursor + 38) ushr 16).toInt()
            requireLocalized(unixMode and 0xf000 != 0xa000) { message(Messages.CodecZipSymlinkUnsupported) }
            val local = bytes.u32(cursor + 42).checkedInt()
            requireLocalized(cursor.toLong() + 46 + nameSize + extraSize + commentSize <= end) { message(Messages.CodecZipNameTruncated) }
            val rawName = bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize)
            requireLocalized(flags and 0x800 != 0 || rawName.all { it >= 0 }) { message(Messages.CodecZipNameEncoding) }
            val name = rawName.decodeToString(throwOnInvalidSequence = true)
            cursor += 46 + nameSize + extraSize + commentSize
            val directoryEntry = name.endsWith('/')
            if (directoryEntry) {
                requireLocalized(name == "library/" && size == 0) { message(Messages.CodecZipDirectoryUnsupported, "name" to name) }
            }
            val path = if (directoryEntry) name else BundlePaths.file(name)
            requireLocalized(portableNames.add(path.lowercase())) { message(Messages.CodecZipNameDuplicate, "path" to path) }
            requireLocalized(bytes.u32(local) == 0x04034b50L && local + 30 <= directory) { message(Messages.CodecZipLocalHeaderInvalid) }
            requireLocalized(bytes.u16(local + 6) == flags && bytes.u16(local + 8) == method) { message(Messages.CodecZipHeadersInconsistent) }
            if (flags and 8 == 0) {
                requireLocalized(bytes.u32(local + 14) == crc && bytes.u32(local + 18) == compressedSize.toLong() && bytes.u32(local + 22) == size.toLong()) {
                    message(Messages.CodecZipLocalSizeMismatch)
                }
            }
            val localNameSize = bytes.u16(local + 26)
            val localExtraSize = bytes.u16(local + 28)
            val data = local.toLong() + 30 + localNameSize + localExtraSize
            requireLocalized(data + compressedSize <= directory && data <= Int.MAX_VALUE) { message(Messages.CodecZipContentTruncated) }
            requireLocalized(bytes.copyOfRange(local + 30, local + 30 + localNameSize).contentEquals(rawName)) { message(Messages.CodecZipNamesInconsistent) }
            val compressed = bytes.copyOfRange(data.toInt(), data.toInt() + compressedSize)
            val content = if (method == 0) compressed.also { requireLocalized(it.size == size) { message(Messages.CodecZipSizeInconsistent) } }
                else BundleDeflate.inflate(compressed, size)
            requireLocalized(crc32(content) == crc) { message(Messages.CodecZipChecksumInvalid, "path" to path) }
            if (flags and 8 != 0) {
                val descriptor = data.toInt() + compressedSize
                val candidates = listOf(descriptor, descriptor + 4).filter { it.toLong() + 12 <= directory }
                requireLocalized(candidates.any {
                    (it == descriptor || bytes.u32(descriptor) == 0x08074b50L) &&
                        bytes.u32(it) == crc && bytes.u32(it + 4) == compressedSize.toLong() && bytes.u32(it + 8) == size.toLong()
                }) { message(Messages.CodecZipDescriptorInconsistent) }
            }
            if (!directoryEntry) files[path] = content
        }
        requireLocalized(cursor == directory + directorySize) { message(Messages.CodecZipDirectoryContent) }
        return files
    }

    fun write(files: Map<String, ByteArray>, validatePath: (String) -> String = BundlePaths::file): ByteArray {
        val output = Bytes()
        val offsets = linkedMapOf<String, Int>()
        for ((path, content) in files) {
            val name = validatePath(path).encodeToByteArray()
            offsets[path] = output.size
            output.u32(0x04034b50).u16(20).u16(0x800).u16(0).u16(0).u16(0)
                .u32(crc32(content)).u32(content.size.toLong()).u32(content.size.toLong()).u16(name.size).u16(0).bytes(name).bytes(content)
        }
        val directory = output.size
        for ((path, content) in files) {
            val name = path.encodeToByteArray()
            output.u32(0x02014b50).u16(20).u16(20).u16(0x800).u16(0).u16(0).u16(0)
                .u32(crc32(content)).u32(content.size.toLong()).u32(content.size.toLong()).u16(name.size).u16(0).u16(0)
                .u16(0).u16(0).u32(0).u32(offsets.getValue(path).toLong()).bytes(name)
        }
        val length = output.size - directory
        output.u32(0x06054b50).u16(0).u16(0).u16(files.size).u16(files.size).u32(length.toLong()).u32(directory.toLong()).u16(0)
        return output.array()
    }

    private fun ByteArray.u16(at: Int): Int {
        requireLocalized(at >= 0 && at.toLong() + 2 <= size) { message(Messages.CodecZipTruncated) }
        return (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    }
    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    private fun Long.checkedInt(): Int { requireLocalized(this in 0..Int.MAX_VALUE.toLong()) { message(Messages.CodecZip64Unsupported) }; return toInt() }
    private fun crc32(bytes: ByteArray): Long {
        var crc = -1
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
        }
        return crc.inv().toLong() and 0xffffffffL
    }
    private class Bytes {
        private var data = ByteArray(1024)
        var size = 0
            private set
        fun bytes(bytes: ByteArray): Bytes { grow(bytes.size); bytes.copyInto(data, size); size += bytes.size; return this }
        fun u16(value: Int): Bytes { grow(2); data[size++] = value.toByte(); data[size++] = (value ushr 8).toByte(); return this }
        fun u32(value: Long): Bytes = u16(value.toInt()).u16((value ushr 16).toInt())
        private fun grow(added: Int) { if (size + added > data.size) data = data.copyOf(maxOf(data.size * 2, size + added)) }
        fun array(): ByteArray = data.copyOf(size)
    }
}
