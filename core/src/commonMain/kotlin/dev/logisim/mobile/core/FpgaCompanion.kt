package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class FpgaBuildGoal(val wireName: String) { SYNTHESIS("synthesis"), BITSTREAM("bitstream") }

internal const val QUARTUS_MAX_V_BUILD_TARGET = "max-v-5m570z"
private const val QUARTUS_DEFAULT_BUILD_TARGET = "de0-cv"
private const val BASYS2_ISE_PROFILE = "basys2"
private const val QUARTUS_EPM7128S_BUILD_TARGET = "max-7000-epm7128s"
private const val QUARTUS_EPM7256S_BUILD_TARGET = "max-7000-epm7256s"
private val quartusPofTargets = setOf(QUARTUS_MAX_V_BUILD_TARGET, QUARTUS_EPM7128S_BUILD_TARGET, QUARTUS_EPM7256S_BUILD_TARGET)

internal val FpgaBoardDefinition.isReptarSpartan6: Boolean
    get() = name == "REPTAR_SPARTAN_6" && device.vendor == FpgaVendor.XILINX &&
        device.part.equals("XC6SLX150T", ignoreCase = true) &&
        device.packageName.equals("FGG900", ignoreCase = true) && device.speedGrade == "3"

private val FpgaBoardDefinition.isBasys2: Boolean
    get() = name.equals("BASYS2", ignoreCase = true) && device.vendor == FpgaVendor.XILINX &&
        device.part.equals("XC3S100E", ignoreCase = true) &&
        device.packageName.equals("CP132", ignoreCase = true) && device.speedGrade == "4"

private val FpgaBoardDefinition.iseBitstreamProfile: String?
    get() = when {
        unusedPinPull == FpgaPull.FLOAT && isBasys2 -> BASYS2_ISE_PROFILE
        unusedPinPull == FpgaPull.FLOAT && isReptarSpartan6 -> "reptar-spartan-6"
        device.vendor != FpgaVendor.XILINX -> null
        name == "DIGILENT_SPARTAN_3E_STARTER" && device.part.equals("XC3S500E", ignoreCase = true) &&
            device.packageName.equals("FG320", ignoreCase = true) && device.speedGrade == "5" &&
            unusedPinPull == FpgaPull.FLOAT -> "digilent-spartan-3e-starter"
        name == "GECKO4LED" && device.part.equals("XC3S5000", ignoreCase = true) &&
            device.packageName.equals("FG676", ignoreCase = true) && device.speedGrade == "5" &&
            unusedPinPull == FpgaPull.DOWN -> "gecko4led"
        else -> null
    }

private val vivadoParts = setOf("xc7a15tcpg236-1", "xc7a35tcpg236-1", "xc7a35tftg256-1", "xc7z010clg400-1")
private val FpgaDevice.vivadoPart get() = "$part$packageName$speedGrade".lowercase()

fun FpgaFlow.companionBuildOperation(board: FpgaBoardDefinition? = null): String? = when (this) {
    FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40 -> "fpga-synthesize"
    FpgaFlow.VIVADO -> "fpga-vivado-build"
    FpgaFlow.ISE -> if (board?.isMicrolabtleCpld() == true) "fpga-ise-cpld-build" else "fpga-ise-build"
    FpgaFlow.QUARTUS -> "fpga-quartus-build"
}

fun FpgaFlow.companionBuildGoals(): List<FpgaBuildGoal> = when (companionBuildOperation()) {
    "fpga-synthesize" -> FpgaBuildGoal.entries
    null -> emptyList()
    else -> listOf(FpgaBuildGoal.BITSTREAM)
}

internal fun FpgaBoardDefinition.isMicrolabtleCpld(): Boolean =
    name.equals("MICROLABTLE", ignoreCase = true) && device.vendor == FpgaVendor.XILINX &&
        device.part.equals("XC2C64", ignoreCase = true) && device.packageName.equals("VQ44", ignoreCase = true) &&
        device.speedGrade == "5" && unusedPinPull == FpgaPull.FLOAT

internal val FpgaDevice.quartusFamilyName: String
    get() = when (family.lowercase()) {
        "cyclone iv" -> "Cyclone IV E"
        "max10" -> "MAX 10"
        else -> family
    }

private data class QuartusFpgaDevice(
    val family: String,
    val part: String,
    val packageName: String,
    val speedGrade: String,
    val target: String = part.lowercase(),
) {
    fun matches(device: FpgaDevice): Boolean =
        family.equals(device.quartusFamilyName, ignoreCase = true) &&
            part.equals(device.part, ignoreCase = true) &&
            packageName.equals(device.packageName, ignoreCase = true) &&
            speedGrade.equals(device.speedGrade, ignoreCase = true)
}

private val quartusFpgaDevices = listOf(
    QuartusFpgaDevice("Cyclone V", "5CEBA4F23C7", "FBGA 484", "C7", QUARTUS_DEFAULT_BUILD_TARGET),
    QuartusFpgaDevice("Cyclone V", "5CSXFC6D6F31C6", "FBGA 896", "6"),
    QuartusFpgaDevice("Cyclone IV E", "EP4CE22F17C7", "FBGA 256", "7"),
    QuartusFpgaDevice("Cyclone", "EP1C12Q240C8", "PQFP 240", "7"),
    QuartusFpgaDevice("Cyclone V", "5CGXFC5C6F27C7", "FBGA 672", "C7"),
    QuartusFpgaDevice("Cyclone II", "EP2C20F484C8", "FBGA 484", "C8"),
    QuartusFpgaDevice("Cyclone IV E", "EP4CE15F23C8", "FBGA 432", "C8"),
    QuartusFpgaDevice("Cyclone IV E", "EP4CE30F23C8", "FBGA 432", "C8"),
    QuartusFpgaDevice("Cyclone 10 LP", "10CL016YF484C8G", "FBGA 484", "8"),
    QuartusFpgaDevice("MAX 10", "10M08SAU169C8G", "UBGA 169", "C8"),
    QuartusFpgaDevice("Cyclone III", "EP3C16F484C6", "FBGA 484", "6"),
    QuartusFpgaDevice("Cyclone IV E", "EP4CE22F17C6", "FBGA 256", "6"),
    QuartusFpgaDevice("MAX 10", "10M50DAF484C7G", "FBGA 484", "7"),
    QuartusFpgaDevice("Cyclone V", "5CSEBA6U23I7", "UBGA 672", "7"),
    QuartusFpgaDevice("Cyclone V", "5CSEMA5F31C6", "FBGA 896", "6"),
)

internal fun FpgaDevice.quartusMax7000BuildTarget(): String? {
    if (vendor != FpgaVendor.ALTERA || speedGrade != "7") return null
    return when {
        family.equals("MAX 7000", ignoreCase = true) && part.equals("EPM7128SLC84-7", ignoreCase = true) &&
            packageName.equals("PQFP 84", ignoreCase = true) -> QUARTUS_EPM7128S_BUILD_TARGET
        family.equals("MAX7000S", ignoreCase = true) && part.equals("EPM7256SQC208-7", ignoreCase = true) &&
            packageName.equals("RQFP 208", ignoreCase = true) -> QUARTUS_EPM7256S_BUILD_TARGET
        else -> null
    }
}

internal fun FpgaBoardDefinition.quartusProgrammingExtension(): String =
    if (device.family.equals("MAX V", ignoreCase = true) || device.quartusMax7000BuildTarget() != null) "pof" else "sof"

fun CompanionJobRequest.quartusProgrammingFilename(): String =
    if ((parameters["target"] as? CompanionJsonString)?.value?.let(quartusPofTargets::contains) == true) "design.pof" else "design.sof"

internal fun FpgaBoardDefinition.quartusBuildTarget(): String? = with(device) {
    when {
        vendor != FpgaVendor.ALTERA -> null
        family.equals("MAX V", ignoreCase = true) && part.equals("5M570ZF256C5", ignoreCase = true) &&
            packageName.equals("FBGA 256", ignoreCase = true) && speedGrade == "5" -> QUARTUS_MAX_V_BUILD_TARGET
        else -> quartusMax7000BuildTarget()?.takeIf { unusedPinPull == FpgaPull.FLOAT }
            ?: quartusFpgaDevices.firstOrNull { it.matches(device) }?.target
    }
}

fun FpgaBoardDefinition.supportsCompanionBuild(flow: FpgaFlow): Boolean = when (flow) {
    FpgaFlow.VIVADO -> device.vendor == FpgaVendor.VIVADO && device.vivadoPart in vivadoParts
    FpgaFlow.ISE -> isMicrolabtleCpld() || iseBitstreamProfile != null
    FpgaFlow.QUARTUS -> quartusBuildTarget() != null
    else -> flow.companionBuildOperation() != null
}

/** Uploads HDL and constraints only. The host constructs its own fixed synthesis commands. */
fun FpgaBuildPlan.companionRequest(goal: FpgaBuildGoal = FpgaBuildGoal.SYNTHESIS): CompanionJobRequest {
    requireLocalized(canSynthesize) { message("fpga_companion_invalid_project") }
    val operation = flow.companionBuildOperation(board)
        ?: throw LocalizedArgumentException(message("fpga_companion_unsupported_flow"))
    requireLocalized(goal in flow.companionBuildGoals()) {
        message("fpga_companion_unsupported_flow")
    }
    requireLocalized(board.supportsCompanionBuild(flow)) {
        message(when (flow) {
            FpgaFlow.ISE -> "fpga_companion_ise_unsupported_board"
            FpgaFlow.QUARTUS -> "fpga_companion_quartus_profile_only"
            else -> "fpga_companion_unsupported_flow"
        })
    }
    val sources = files.filter { it.path.substringAfterLast('.').lowercase() in setOf("v", "sv", "vhd", "vhdl") }
    requireLocalized(sources.isNotEmpty()) { message("fpga_companion_missing_source") }
    val vhdl = sources.first().path.substringAfterLast('.').lowercase() in setOf("vhd", "vhdl")
    requireLocalized(sources.all { (it.path.substringAfterLast('.').lowercase() in setOf("vhd", "vhdl")) == vhdl }) {
        message("fpga_companion_mixed_language")
    }
    val uploads = sources.mapIndexed { index, source ->
        // Exported module names carry hierarchy; physical upload names need no directories.
        FpgaSourceFile("source-${index.toString().padStart(4, '0')}.${if (vhdl) "vhd" else "v"}", source.content)
    }.toMutableList()
    val language = CompanionJsonString(if (vhdl) "vhdl" else "verilog")
    val sourceNames = CompanionJsonArray(uploads.map { CompanionJsonString(it.path) })
    if (flow == FpgaFlow.QUARTUS) {
        requireLocalized(topLevelName == FpgaProjectGenerator.TOP_LEVEL) { message("fpga_companion_invalid_project") }
        for (name in listOf("pins.qsf", "timing.sdc")) {
            uploads += files.single { it.path == name }
        }
        val parameters = mutableMapOf<String, CompanionJson>("language" to language, "sources" to sourceNames)
        val target = requireNotNull(board.quartusBuildTarget())
        if (target != QUARTUS_DEFAULT_BUILD_TARGET) {
            parameters["target"] = CompanionJsonString(target)
        }
        return CompanionJobRequest(operation, uploads.map { CompanionInput.fromBytes(it.path, it.content.encodeToByteArray()) }, parameters)
    }
    if (flow == FpgaFlow.ISE) {
        requireLocalized(topLevelName == FpgaProjectGenerator.TOP_LEVEL) { message("fpga_companion_invalid_project") }
        val parameters = linkedMapOf<String, CompanionJson>("language" to language, "sources" to sourceNames)
        board.iseBitstreamProfile?.takeUnless { it == BASYS2_ISE_PROFILE }?.let { profile ->
            parameters["profile"] = CompanionJsonString(profile)
        }
        uploads += files.single { it.path == "pins.ucf" }
        return CompanionJobRequest(operation, uploads.map { CompanionInput.fromBytes(it.path, it.content.encodeToByteArray()) },
            parameters)
    }
    val parameters = linkedMapOf<String, CompanionJson>(
        "top" to CompanionJsonString(topLevelName),
        "language" to language,
        "sources" to sourceNames,
    )
    if (flow == FpgaFlow.VIVADO) {
        uploads += files.single { it.path == "pins.xdc" }
        parameters["part"] = CompanionJsonString(board.device.vivadoPart)
        parameters["constraint"] = CompanionJsonString("pins.xdc")
    } else {
        parameters["flow"] = CompanionJsonString(if (flow == FpgaFlow.OPEN_ECP5) "ecp5" else "ice40")
        parameters["part"] = CompanionJsonString(board.device.part.lowercase())
        parameters["package"] = CompanionJsonString(board.device.packageName)
        parameters["goal"] = CompanionJsonString(goal.wireName)
        if (goal == FpgaBuildGoal.BITSTREAM) {
            val name = if (flow == FpgaFlow.OPEN_ECP5) "pins.lpf" else "pins.pcf"
            uploads += files.single { it.path == name }
            parameters["constraint"] = CompanionJsonString(name)
            board.clock?.let { parameters["frequency_mhz"] = CompanionJsonNumber((it.frequencyHz / 1_000_000.0).toString()) }
        }
    }
    return CompanionJobRequest(operation, uploads.map { CompanionInput.fromBytes(it.path, it.content.encodeToByteArray()) }, parameters)
}
