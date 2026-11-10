package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

const val FPGA_VENDOR_ARTIFACT_MAX_BYTES = 5 * 1024 * 1024

/** A deterministic ordinary ZIP of the generated HDL, constraints, and build files. */
fun FpgaBuildPlan.archive(): ByteArray {
    requireLocalized(canSynthesize) { message("fpga_companion_invalid_project") }
    requireLocalized(files.map { it.path }.distinct().size == files.size) {
        message("fpga_archive_invalid_files")
    }
    return fpgaArchive(files.associate { it.path to it.content.encodeToByteArray() }, maxEntryBytes = 2_000_000)
}

/** Also accepts already verified companion artifacts, including binary bitstreams. */
fun fpgaArchive(files: Map<String, ByteArray>): ByteArray = fpgaArchive(files, FPGA_VENDOR_ARTIFACT_MAX_BYTES)

fun fpgaArchive(artifacts: List<CompanionArtifact>): ByteArray {
    requireLocalized(artifacts.map { it.name.lowercase() }.distinct().size == artifacts.size) {
        message("fpga_archive_invalid_files")
    }
    val verifiedSof = artifacts.singleOrNull()?.takeIf { it.name == "design.sof" && it.quartusSofPart != null }
    return fpgaArchive(artifacts.associate { it.name to it.bytes }, FPGA_VENDOR_ARTIFACT_MAX_BYTES, verifiedSof != null)
}

private fun fpgaArchive(files: Map<String, ByteArray>, maxEntryBytes: Int, verifiedSof: Boolean = false): ByteArray {
    requireLocalized(files.size in 1..256 && files.keys.map { it.lowercase() }.distinct().size == files.size) {
        message("fpga_archive_invalid_files")
    }
    val contents = files.entries.sortedBy { it.key }.associate { it.key to it.value }
    requireLocalized(contents.all { (name, bytes) ->
        val limit = if (verifiedSof && name == "design.sof") QUARTUS_SOF_MAX_BYTES else maxEntryBytes
        bytes.size <= limit
    } && contents.values.sumOf { it.size.toLong() } <= 32_000_000) {
        message("fpga_archive_size_limit")
    }
    return BundleZip.write(contents) { path ->
        requireLocalized(FpgaDesignRules.safePath(path)) { message("fpga_archive_invalid_path", "path" to path) }
        path
    }
}
