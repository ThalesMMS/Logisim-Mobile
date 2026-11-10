package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.CompanionCapabilities
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionTclClient
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.DiagnosticTarget
import dev.logisim.mobile.core.FpgaCircuitDiagnostic
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaDiagnosticSeverity
import dev.logisim.mobile.core.FpgaGenerationOptions
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.QUARTUS_SOF_MAX_BYTES
import dev.logisim.mobile.core.archive
import dev.logisim.mobile.core.fpgaArchive
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One editor scene owns its verified companion; no token is written to project or session storage. */
internal class AdditionalEditorToolsConnection {
    var client by mutableStateOf<CompanionClient?>(null)
        private set
    var capabilities by mutableStateOf<CompanionCapabilities?>(null)
        private set

    fun connect(client: CompanionClient, capabilities: CompanionCapabilities) {
        this.client = client
        this.capabilities = capabilities
    }

    fun disconnect() {
        client = null
        capabilities = null
    }
}

internal data class AdditionalFpgaBinding(val client: CompanionClient?, val session: FpgaCompanionSession)

/** A reviewed or running FPGA operation stays on its original verified host until the user leaves it. */
internal class AdditionalFpgaToolsState {
    val mapping = FpgaMappingSession()
    private var binding = AdditionalFpgaBinding(null, FpgaCompanionSession())

    fun bind(client: CompanionClient?): AdditionalFpgaBinding {
        val session = binding.session
        if (binding.client !== client && session.displayedPlan == null && !session.retainsClient)
            binding = AdditionalFpgaBinding(client, FpgaCompanionSession())
        return binding
    }
}

/** Companion changes update its job tools without recreating the editor's trace/vector tools. */
@Composable
fun rememberAdditionalEditorTools(
    registry: ComponentRegistry,
    assets: DocumentAssetActions? = null,
    documents: DocumentActions? = null,
    onNotice: (LocalizedText) -> Unit = {},
    onFailure: (LocalizedText) -> Unit = {},
    highContrastSignals: Boolean = false,
    signalRadix: String? = null,
    gateShape: EditorGateShape = EditorGateShape.ANSI,
    portableProjects: PortableProjectWorkspaceAccess? = null,
    directories: DirectoryAssetActions? = null,
    boardCatalog: FpgaBoardCatalogStore? = null,
    fpgaOptions: FpgaGenerationOptions = FpgaGenerationOptions(),
    hdlType: EditorHdlType = EditorHdlType.VHDL,
): List<EditorTool> {
    val notice by rememberUpdatedState(onNotice)
    val failure by rememberUpdatedState(onFailure)
    val connection = remember { AdditionalEditorToolsConnection() }
    val fpgaState = remember { AdditionalFpgaToolsState() }
    val fpgaBinding = fpgaState.bind(connection.client)
    val scope = rememberCoroutineScope()
    val visualExportTool = remember(registry, assets, highContrastSignals, signalRadix, gateShape) {
        circuitVisualExportEditorTool(registry, assets, CircuitDrawingStyle(highContrastSignals, signalRadix, gateShape))
    }
    val stable = remember(registry, assets, documents, connection, visualExportTool) {
        createAdditionalStaticEditorTools(assets, connection, { notice(it) }, visualExportTool)
    }
    val fpgaCatalog = remember(boardCatalog) { boardCatalog ?: FpgaBoardCatalogStore() }
    val currentFpgaOptions by rememberUpdatedState(fpgaOptions)
    val currentHdlType by rememberUpdatedState(hdlType)
    val fpga = remember(registry, assets, documents, fpgaBinding, fpgaCatalog) {
        val fpga = fpgaEditorTool(
            reviewProvider = { context ->
                val project = context.project.copy(mainCircuitName = context.document.name)
                val options = currentFpgaOptions
                // The FPGA build generates Evolution's HdlType; with None, Download.prepareDownload stops at FpgaGuiSelectHdl.
                val language = currentHdlType.language
                val review = FpgaCircuitRules.reviewForFpga(project, language ?: HdlLanguage.VHDL, registry, options)
                if (language == null) review.copy(design = null, diagnostics = listOf(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR,
                    message("hdl_select_language"), DiagnosticTarget(project.mainCircuit.id))) + review.diagnostics)
                else if (language == HdlLanguage.VERILOG && review.design == null && project.circuits.any { document ->
                        document.components.any { it.kind == ExternalIoComponents.ReptarLocalBus }
                    }) {
                    // Reptar Local Bus has only a VHDL generator, as in Evolution.
                    FpgaCircuitRules.reviewForFpga(project, HdlLanguage.VHDL, registry, options)
                } else review
            },
            onExport = assets?.let { actions -> { plan ->
                scope.launch {
                    try {
                        val asset = withContext(Dispatchers.Default) { fpgaBuildPlanAsset(plan) }
                        saveAdditionalAsset(actions, asset, { notice(it) }, { failure(it) })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure(error.localizedDescription(message("additional_fpga_archive_failed")))
                    }
                }
                Unit
            } },
            onPickBoard = assets?.let { actions -> { picked -> pickFpgaBoard(actions, picked) { failure(it) } } },
            companionClient = fpgaBinding.client,
            onArtifacts = assets?.let { actions -> { artifacts ->
                scope.launch {
                    try {
                        val asset = withContext(Dispatchers.Default) { fpgaArtifactArchiveAsset(artifacts) }
                        saveAdditionalAsset(actions, asset, { notice(it) }, { failure(it) })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure(error.localizedDescription(message("additional_fpga_archive_failed")))
                    }
                }
                Unit
            } },
            mappingSession = fpgaState.mapping,
            companionSession = fpgaBinding.session,
            onPickMapping = assets?.let { actions -> { picked -> pickFpgaBoard(actions, picked) { failure(it) } } },
            boardCatalog = fpgaCatalog,
            boardAssets = assets,
            onPickBitstream = assets?.let { actions -> { picked ->
                actions.open(listOf("application/octet-stream"), QUARTUS_SOF_MAX_BYTES) { result ->
                    when (result) {
                        is AssetOpenResult.Opened -> {
                            if (isFpgaBitstream(result.asset)) picked(result.asset)
                            else failure(message("fpga_program_invalid_import"))
                        }
                        is AssetOpenResult.Failure -> failure(result.notice)
                        AssetOpenResult.Cancelled -> Unit
                    }
                }
            } },
        )
        fpga.copy(content = { context ->
            val strings = rememberLocalizer()
            Column(Modifier.fillMaxSize()) {
                if (fpgaBinding.client !== connection.client)
                    Text(strings("additional_fpga_original_host"), modifier = Modifier.padding(12.dp))
                Box(Modifier.weight(1f)) { fpga.content(context) }
            }
        })
    }
    val tcl = remember(connection.client, assets) {
        tclEditorTool(connection.client?.let { CompanionTclClient(it, "Companion") }, assets) { context ->
            TclComponentActions(sendEvent = context.sendComponentEvent)
        }
    }
    val hdl = remember(registry, connection, assets) {
        listOf(hdlImportTool({ connection.client }, registry, assets), hdlSimulationTool { connection.client })
    }
    val help = remember(registry) {
        helpExamplesEditorTool(
            onOpenExample = { context, example -> context.openExample(example) },
            registry = registry,
            onOpenTool = { context, toolId -> context.openTool(toolId) },
        )
    }
    val externalIo = remember(connection) { externalIoEditorTools { connection.client } }
    val portable = remember(assets, portableProjects, directories, registry) {
        if (assets != null && portableProjects != null) listOf(portableProjectEditorTool(assets,
            portableProjects.opener, portableProjects.sourceArchiveForIdentity, directories, registry = registry)) else emptyList()
    }
    val jar = remember(assets, connection) {
        assets?.let { listOf(jarCompanionTool(it) { connection.client }) }.orEmpty()
    }
    return remember(stable, fpga, tcl, hdl, help, externalIo, portable, jar) {
        stable + fpga + tcl + hdl + help + externalIo + portable + jar
    }
}

internal fun createAdditionalStaticEditorTools(
    assets: DocumentAssetActions?,
    connection: AdditionalEditorToolsConnection,
    onNotice: (LocalizedText) -> Unit,
    visualExportTool: EditorTool,
): List<EditorTool> = buildList {
    add(EditorTool("legal-notices", "Licenses", titleText = message("additional_legal_title")) { LegalNoticesButton() })
    add(EditorTool("companion-pairing", "Companion", titleText = message("additional_companion_title")) {
        val strings = rememberLocalizer()
        if (connection.client == null) {
            CompanionPairingPanel(onConnected = { client, capabilities ->
                connection.connect(client, capabilities)
                onNotice(message("additional_companion_verified"))
            }, onDisconnected = connection::disconnect)
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings("additional_companion_verified"))
                connection.capabilities?.let { capabilities ->
                    Text(strings("additional_companion_protocol", "version" to capabilities.protocolVersion,
                        "bytes" to capabilities.limits.inputBytes))
                    for (operation in capabilities.operations) {
                        val status = when {
                            !operation.allowed -> "additional_companion_disabled"
                            !operation.available -> "additional_companion_tools_missing"
                            operation.requiresConfirmation -> "additional_companion_confirmation_required"
                            else -> "additional_companion_available"
                        }
                        Text(strings("additional_companion_operation", "operation" to operation.name, "status" to message(status)))
                    }
                }
                Button(onClick = connection::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(strings("additional_companion_disconnect"))
                }
            }
        }
    })
    add(if (assets == null) createSocDebuggerTool() else createSocDebuggerTool(assets))
    if (assets != null) add(assemblyViewerEditorTool(assets))
    val imagePicker = assets?.annotationImagePicker()
    add(annotationEditorTool(imagePicker))
    add(appearanceEditorTool(imagePicker))
    add(visualExportTool)
}

internal fun pickFpgaBoard(
    assets: DocumentAssetActions,
    onPicked: (String) -> Unit,
    onFailure: (LocalizedText) -> Unit,
) {
    try {
        assets.open(listOf("application/xml", "text/xml", "application/octet-stream"), 2_000_000) { result ->
            when (result) {
                is AssetOpenResult.Opened -> {
                    try {
                        onPicked(result.asset.bytes.decodeToString(throwOnInvalidSequence = true))
                    } catch (_: CharacterCodingException) {
                        onFailure(message("additional_fpga_board_utf8"))
                    }
                }
                is AssetOpenResult.Failure -> onFailure(result.notice)
                AssetOpenResult.Cancelled -> Unit
            }
        }
    } catch (error: Exception) {
        onFailure(error.localizedDescription(message("additional_fpga_board_failed")))
    }
}

internal fun fpgaBuildPlanAsset(plan: FpgaBuildPlan): DocumentAsset =
    DocumentAsset("${plan.topLevelName}-fpga.zip", "application/zip", plan.archive())

internal fun fpgaArtifactArchiveAsset(artifacts: List<CompanionArtifact>): DocumentAsset =
    DocumentAsset("fpga-artifacts.zip", "application/zip", fpgaArchive(artifacts))

internal fun saveAdditionalAsset(
    assets: DocumentAssetActions,
    asset: DocumentAsset,
    onNotice: (LocalizedText) -> Unit,
    onFailure: (LocalizedText) -> Unit,
) {
    try {
        assets.create(asset) { result ->
            when (result) {
                AssetActionResult.Completed -> onNotice(message("status_file_saved", "name" to asset.name))
                is AssetActionResult.Failure -> onFailure(result.notice)
                AssetActionResult.Cancelled -> Unit
            }
        }
    } catch (error: Exception) {
        onFailure(error.localizedDescription(message("additional_fpga_archive_failed")))
    }
}
