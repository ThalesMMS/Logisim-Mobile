package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText

/** The user explicitly selected this directory; a single-document grant is insufficient. */
interface DirectoryAssetActions {
    /** Show the native directory picker and deliver exactly one result on the UI thread. */
    fun open(onResult: (DirectoryAssetOpenResult) -> Unit)
}

sealed interface DirectoryAssetOpenResult {
    data class Opened(val grant: DirectoryAssetGrant) : DirectoryAssetOpenResult
    data object Cancelled : DirectoryAssetOpenResult
    data class Failure(val notice: LocalizedText) : DirectoryAssetOpenResult
}

data class DirectoryCircuitFile(val path: String, val name: String)

class DirectoryAssetContent(bytes: ByteArray, val revision: String = "") {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    val size: Int get() = content.size
}

/**
 * A scoped source reader, never an editable document reference. All paths are canonical and relative
 * to this grant. list/read run on an I/O worker, never on the UI thread. Native implementations must
 * enforce containment again, refuse filesystem symlinks or provider aliases escaping that scope,
 * and bound traversal before returning a result.
 * The caller releases the grant when the source session is replaced or disposed.
 */
interface DirectoryAssetGrant {
    val id: String
    val name: String
    fun circuitFiles(maximumEntries: Int): List<DirectoryCircuitFile>
    fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent?
    /** End this reader's access without revoking a grant owned by another session; idempotent, no throw. */
    fun release()
}
