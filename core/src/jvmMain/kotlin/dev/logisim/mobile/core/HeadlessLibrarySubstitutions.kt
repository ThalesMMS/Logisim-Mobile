package dev.logisim.mobile.core

import java.nio.file.Files
import java.nio.file.Path

internal data class HeadlessLibrarySubstitution(val original: String, val replacement: String)

/** Resolves only file targets explicitly named on the command line. */
internal fun readHeadlessProjectWithSubstitutions(
    input: Path,
    substitutions: List<HeadlessLibrarySubstitution>,
): ResolvedCircuitProject {
    val root = input.toRealPath()
    val targets = linkedMapOf<Path, String>()
    for ((original, replacement) in substitutions) {
        require(original.isNotBlank() && replacement.isNotBlank()) { "Library substitution paths must not be empty." }
        val source = libraryPath(root, original)
        require(targets.putIfAbsent(source, replacement) == null) { "Repeated library substitution for '$original'." }
    }
    val used = mutableSetOf<Path>()
    val sources = mutableMapOf<Path, CircuitLibrarySource>()
    val imported = CircLibraryCodec.read(readHeadlessCircuitFile(root), root.toString(), CircuitLibraryResolver { from, reference ->
        val source = libraryPath(Path.of(from), reference)
        val replacement = targets[source] ?: return@CircuitLibraryResolver null
        used.add(source)
        val target = Path.of(replacement).toRealPath()
        sources.getOrPut(target) { CircuitLibrarySource(target.toString(), readHeadlessCircuitFile(target)) }
    })
    return when (imported) {
        is CircLibraryImportResult.Success -> {
            val unused = targets.keys - used
            require(unused.isEmpty()) { "No library reference matched: ${unused.joinToString()}." }
            imported.resolved
        }
        is CircLibraryImportResult.Failure -> throw IllegalArgumentException(imported.diagnostics.joinToString("; "))
    }
}

private fun libraryPath(from: Path, reference: String): Path =
    from.parent.resolve(reference.replace('\\', '/')).normalize()

internal fun readHeadlessCircuitFile(path: Path): String {
    require(Files.isRegularFile(path)) { "Not a regular circuit file: '$path'." }
    val limit = CircBundleCodec.MAX_TOTAL_BYTES
    val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit) { "Circuit file exceeds $limit bytes: '$path'." }
    return CircXmlEncoding.decode(bytes)
}
