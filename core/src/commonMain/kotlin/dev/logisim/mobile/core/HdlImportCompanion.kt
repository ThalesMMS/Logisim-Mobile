package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class HdlMemoryPolicy(val protocolValue: String) { NATIVE("native"), EXPAND("logic") }

data class HdlImportOptions(
    val sources: List<HdlInputFile>,
    val sourceNames: List<String> = sources.map { it.name },
    val format: HdlSourceFormat = HdlSourceFormat.VERILOG,
    val top: String = "",
    val defines: List<String> = emptyList(),
    val undefined: HdlUndefinedPolicy = HdlUndefinedPolicy.REJECT,
    val memoryPolicy: HdlMemoryPolicy = HdlMemoryPolicy.NATIVE,
) {
    fun request(): CompanionJobRequest {
        requireLocalized(sources.isNotEmpty() && sources.size <= 64 && sources.map { it.name.lowercase() }.distinct().size == sources.size) {
            message(Messages.HdlImportRequestFiles)
        }
        requireLocalized(sourceNames.isNotEmpty() && sourceNames.distinct().size == sourceNames.size &&
            sourceNames.all { name -> sources.any { it.name == name } }) { message(Messages.HdlImportRequestSources) }
        requireLocalized(top.isBlank() || Regex("[A-Za-z_][A-Za-z0-9_]*").matches(top)) { message(Messages.HdlImportRequestTop) }
        requireLocalized(defines.size <= 64 && defines.all { Regex("[A-Za-z_][A-Za-z0-9_]*(=[A-Za-z0-9_+.-]+)?").matches(it) }) {
            message(Messages.HdlImportRequestDefines)
        }
        val declaresTree = sources.any { '/' in it.name }
        val uploads = sources.mapIndexed { index, source ->
            CompanionInput.fromBytes(if (declaresTree) "hdl-input-$index" else source.name, source.text.encodeToByteArray())
        }
        val parameters = mutableMapOf<String, CompanionJson>(
            "format" to CompanionJsonString(when (format) {
                HdlSourceFormat.VERILOG -> "verilog"
                HdlSourceFormat.SYSTEM_VERILOG -> "system-verilog"
                HdlSourceFormat.BLIF -> "blif"
            }),
            "sourceNames" to CompanionJsonArray(sourceNames.map(::CompanionJsonString)),
            "top" to CompanionJsonString(top.trim()),
            "defines" to CompanionJsonArray(defines.map(::CompanionJsonString)),
            "includes" to CompanionJsonArray(listOf(CompanionJsonString("."))),
            "undefined" to CompanionJsonString(when (undefined) {
                HdlUndefinedPolicy.REJECT -> "error"
                HdlUndefinedPolicy.ZERO -> "0"
                HdlUndefinedPolicy.ONE -> "1"
            }),
            "memoryPolicy" to CompanionJsonString((if (format == HdlSourceFormat.BLIF) HdlMemoryPolicy.EXPAND else memoryPolicy).protocolValue),
            "wordCells" to CompanionJsonBoolean(format != HdlSourceFormat.BLIF),
        )
        if (declaresTree) parameters["inputPaths"] = CompanionJsonObject(uploads.zip(sources).associate { (upload, source) ->
            upload.name to CompanionJsonString(source.name)
        })
        return CompanionJobRequest("yosys-import", uploads, parameters)
    }
}

/** Decode only a completed, authenticated job and its verified artifact. */
fun CompanionJob.importHdl(options: HdlImportOptions, existingProject: CircuitProject,
    registry: ComponentRegistry = ComponentCatalog.registry): HdlImportResult {
    if (operation != "yosys-import" || state != CompanionJobState.SUCCEEDED) return HdlImportResult.Failure(listOf(
        HdlImportDiagnostic(error?.code ?: "import-incomplete", error?.message?.let(LocalizedText::literal) ?: message(Messages.HdlImportSynthesisIncomplete))))
    return try {
        val envelope = result as? CompanionJsonObject ?: throw LocalizedArgumentException(message(Messages.HdlImportSynthesisObject))
        requireLocalized(envelope.string("format") == "yosys-bit-netlist-v1") { message(Messages.HdlImportArtifactFormat) }
        val bytes = artifact(envelope.fields["netlist"] ?: throw LocalizedArgumentException(message(Messages.HdlImportArtifactMissing))).bytes
        val json = bytes.decodeToString(throwOnInvalidSequence = true)
        HdlNativeImporter.importJson(json, options.sources, options.top.takeIf { it.isNotBlank() }, options.undefined, existingProject, registry)
    } catch (error: Exception) {
        HdlImportResult.Failure(listOf(HdlImportDiagnostic("invalid-synthesis-artifact", error.localizedDescription(message(Messages.HdlImportArtifactInvalid)))))
    }
}
