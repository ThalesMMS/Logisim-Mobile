package dev.logisim.mobile

import dev.logisim.mobile.core.HdlSourceFile
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

internal fun hdlSourceAsset(source: HdlSourceFile): DocumentAsset = DocumentAsset(
    source.path.substringAfterLast('/'),
    if (source.path.endsWith(".vhd", true) || source.path.endsWith(".vhdl", true)) "text/x-vhdl" else "text/x-verilog",
    source.content.encodeToByteArray(),
)

internal fun saveHdlSource(actions: DocumentAssetActions, source: HdlSourceFile,
    onNotice: (LocalizedText) -> Unit, onFailure: (LocalizedText) -> Unit,
    projectExports: ProjectGraphExportPolicy = ProjectGraphExportPolicy()) {
    if (!projectExports.ensureAvailable(onFailure)) return
    actions.create(hdlSourceAsset(source)) { result ->
        if (!projectExports.available) return@create
        when (result) {
            AssetActionResult.Completed -> onNotice(message(Messages.StatusFileSaved, "name" to source.path))
            is AssetActionResult.Failure -> onFailure(result.notice)
            AssetActionResult.Cancelled -> Unit
        }
    }
}

internal fun shareHdlSource(actions: DocumentAssetActions, source: HdlSourceFile,
    onNotice: (LocalizedText) -> Unit, onFailure: (LocalizedText) -> Unit,
    projectExports: ProjectGraphExportPolicy = ProjectGraphExportPolicy()) {
    if (!projectExports.ensureAvailable(onFailure)) return
    actions.share(hdlSourceAsset(source)) { result ->
        if (!projectExports.available) return@share
        when (result) {
            is AssetActionResult.Failure -> onFailure(result.notice)
            AssetActionResult.Completed -> onNotice(message(Messages.StatusSharePrompt))
            AssetActionResult.Cancelled -> Unit
        }
    }
}
