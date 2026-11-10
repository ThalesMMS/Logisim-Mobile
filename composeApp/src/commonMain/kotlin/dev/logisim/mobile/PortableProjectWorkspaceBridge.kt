package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.WorkspaceAttachment
import dev.logisim.mobile.core.WorkspaceAttachmentReadResult
import dev.logisim.mobile.core.localization.LocalizedStateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PortableSourceAttachment = "portable-source"

/** A scene supplies its catalog bridge; a tool never chooses a workspace or native bookmark itself. */
class PortableProjectWorkspaceAccess(
    val opener: PortableProjectCopyOpener,
    val sourceArchiveForIdentity: suspend (Long) -> DocumentAsset?,
)

internal class PortableProjectWorkspaceBridge(
    private val scope: CoroutineScope,
    private val registry: ComponentRegistry,
    private val mount: Long,
    private val isCurrent: (mount: Long, identity: Long) -> Boolean,
    private val openPrepared: (
        name: String, xml: String, attachments: Map<String, WorkspaceAttachment>,
        expectedMount: Long, expectedIdentity: Long, completed: (WorkspacePreparedCopyResult) -> Unit,
    ) -> Unit,
    private val readAttachment: suspend (mount: Long, identity: Long, key: String) -> WorkspaceAttachmentReadResult,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val serialize: (CircuitProject) -> String = { CircCodec.writeProject(it, registry) },
) : PortableProjectCopyOpener {
    override fun openCopy(copy: PortableProjectCopy, expectedDocumentIdentity: Long,
        onResult: (PortableProjectCopyOpenResult) -> Unit) {
        if (!scope.isActive || !isCurrent(mount, expectedDocumentIdentity)) {
            onResult(PortableProjectCopyOpenResult.Cancelled)
            return
        }
        scope.launch {
            try {
                val prepared = withContext(workerDispatcher) {
                    serialize(copy.project) to WorkspaceAttachment(copy.sourceArchive.name,
                        copy.sourceArchive.mimeType, copy.sourceArchive.bytes)
                }
                if (!isCurrent(mount, expectedDocumentIdentity)) {
                    onResult(PortableProjectCopyOpenResult.Cancelled)
                    return@launch
                }
                openPrepared(copy.name, prepared.first, mapOf(PortableSourceAttachment to prepared.second),
                    mount, expectedDocumentIdentity) { result ->
                    if (scope.isActive) onResult(when (result) {
                        is WorkspacePreparedCopyResult.Opened -> PortableProjectCopyOpenResult.Opened(result.documentIdentity)
                        WorkspacePreparedCopyResult.Cancelled -> PortableProjectCopyOpenResult.Cancelled
                        is WorkspacePreparedCopyResult.Failure -> PortableProjectCopyOpenResult.Failure(result.notice)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(mount, expectedDocumentIdentity)) {
                    onResult(PortableProjectCopyOpenResult.Failure(portableProjectNotice(failure, "portable_copy_not_opened")))
                } else onResult(PortableProjectCopyOpenResult.Cancelled)
            }
        }
    }

    suspend fun sourceArchiveForIdentity(identity: Long): DocumentAsset? = when (
        val result = readAttachment(mount, identity, PortableSourceAttachment)
    ) {
        is WorkspaceAttachmentReadResult.Success -> DocumentAsset(result.attachment.name,
            result.attachment.mimeType, result.attachment.bytes)
        WorkspaceAttachmentReadResult.Missing -> null
        is WorkspaceAttachmentReadResult.Failure -> throw LocalizedStateException(result.notice)
    }

    fun access(): PortableProjectWorkspaceAccess = PortableProjectWorkspaceAccess(this, ::sourceArchiveForIdentity)
}
