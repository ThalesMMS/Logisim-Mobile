package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.FpgaBoardResource
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.localization.LocalizedFailure
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer

/**
 * Display names for the FPGA identifiers the panels used to show raw: vendors, resource types, companion operations,
 * program transports and job states. The stored values (FPGAInformation Vendor, resource element names, wire names)
 * never change; unknown identifiers fall back to their raw text.
 */
internal object FpgaLabels {
    /** VendorSoftware.VENDORS names the tool chain of each vendor; the enum constants are storage values. */
    fun vendor(vendor: FpgaVendor): LocalizedText = message(when (vendor) {
        FpgaVendor.ALTERA -> "fpga_vendor_altera"
        FpgaVendor.XILINX -> "fpga_vendor_xilinx"
        FpgaVendor.VIVADO -> "fpga_vendor_vivado"
        FpgaVendor.OPEN_FPGA -> "fpga_vendor_open_fpga"
        FpgaVendor.UNKNOWN -> "fpga_vendor_unknown"
    })

    private val resourceTypeKeys = mapOf(
        "led" to "fpga_resource_type_led", "button" to "fpga_resource_type_button", "pin" to "fpga_resource_type_pin",
        "sevensegment" to "fpga_resource_type_sevensegment", "sevensegmentnodp" to "fpga_resource_type_sevensegmentnodp",
        "sevensegmentscanning" to "fpga_resource_type_sevensegmentscanning", "dipswitch" to "fpga_resource_type_dipswitch",
        "rgbled" to "fpga_resource_type_rgbled", "ledarray" to "fpga_resource_type_ledarray", "portio" to "fpga_resource_type_portio",
        "localbus" to "fpga_resource_type_localbus",
    )

    /**
     * A board resource type such as "DIPSwitch" or a board file's "LED", case-insensitive like the board reader. Evolution's
     * selector shows the raw element names; localized names go beyond parity and are recorded in the profile.
     */
    fun resourceType(type: String): LocalizedText =
        resourceTypeKeys[type.lowercase()]?.let(::message) ?: LocalizedText.literal(type)

    /** A resource's label, or its localized type when it has none. */
    fun resource(resource: FpgaBoardResource): LocalizedText =
        resource.label.takeIf { it.isNotBlank() }?.let(LocalizedText::literal) ?: resourceType(resource.type)

    private val operationKeys = mapOf(
        "fpga-synthesize" to "fpga_operation_synthesize", "fpga-ise-build" to "fpga_operation_ise_build",
        "fpga-ise-cpld-build" to "fpga_operation_ise_cpld_build", "fpga-quartus-build" to "fpga_operation_quartus_build",
        "fpga-program" to "fpga_operation_program",
    )

    fun operation(operation: String): LocalizedText = operationKeys[operation]?.let(::message) ?: LocalizedText.literal(operation)

    private val transportKeys = mapOf(
        "openfpgaloader" to "fpga_transport_openfpgaloader", "ise" to "fpga_transport_ise", "ise-cpld" to "fpga_transport_ise_cpld",
        "quartus" to "fpga_transport_quartus", "quartus-cpld" to "fpga_transport_quartus_cpld",
    )

    fun transport(transport: String): LocalizedText = transportKeys[transport]?.let(::message) ?: LocalizedText.literal(transport)

    fun jobState(state: CompanionJobState): LocalizedText = message(when (state) {
        CompanionJobState.QUEUED -> "fpga_companion_state_queued"
        CompanionJobState.RUNNING -> "fpga_companion_state_running"
        CompanionJobState.SUCCEEDED -> "fpga_companion_state_succeeded"
        CompanionJobState.FAILED -> "fpga_companion_state_failed"
        CompanionJobState.CANCELLED -> "fpga_companion_state_cancelled"
    })

    /** Labels for the reviewed plan parameters the Mac will run with; other keys are shown by name. */
    private val parameterKeys = mapOf(
        "flow" to "fpga_parameter_flow", "part" to "fpga_parameter_part", "package" to "fpga_parameter_package",
        "top" to "fpga_parameter_top", "language" to "fpga_parameter_language", "goal" to "fpga_parameter_goal",
        "constraint" to "fpga_parameter_constraint", "binarySof" to "fpga_parameter_binary_sof", "target" to "fpga_parameter_target",
        "bitstream" to "fpga_parameter_bitstream", "flash" to "fpga_parameter_flash", "board" to "fpga_parameter_board",
        "sources" to "fpga_parameter_sources", "family" to "fpga_parameter_family",
    )

    fun parameter(name: String): LocalizedText = parameterKeys[name]?.let(::message) ?: LocalizedText.literal(name)
}

internal fun companionJobState(state: CompanionJobState, text: Localizer): String = text(FpgaLabels.jobState(state))

/** A failure of a companion or programming request: a localized explanation with a next step, and the raw details. */
internal data class FpgaFailureNotice(val text: LocalizedText, val detail: String? = null)

private val protocolGuidance = mapOf(
    "fpga-flash-not-allowed" to "fpga_failure_flash_not_allowed",
    "invalid-plan" to "fpga_failure_invalid_plan",
    "invalid-fpga-job" to "fpga_failure_invalid_job",
    "invalid-fpga-result" to "fpga_failure_invalid_result",
    "invalid-artifact" to "fpga_failure_invalid_artifact",
    "confirmation-required" to "fpga_failure_confirmation_required",
    "fpga-part-mismatch" to "fpga_failure_part_mismatch",
    "invalid-response" to "fpga_failure_invalid_response",
)

/**
 * The shared mapping of companion and programming failures. Known protocol codes read as localized guidance; the
 * code and the host's English detail stay available as [FpgaFailureNotice.detail] for support.
 */
internal fun fpgaFailureNotice(failure: Exception): FpgaFailureNotice = when (failure) {
    is CompanionProtocolException -> {
        val detail = "${failure.code}: ${failure.message}"
        protocolGuidance[failure.code]?.let { FpgaFailureNotice(message(it), detail) }
            ?: FpgaFailureNotice(message("fpga_companion_error_detail", "code" to failure.code, "detail" to failure.text), detail)
    }
    is CompanionTransportException -> FpgaFailureNotice(message("fpga_companion_transport_error", "code" to failure.code))
    is LocalizedFailure -> FpgaFailureNotice(failure.text)
    else -> FpgaFailureNotice(message("fpga_companion_action_error", "detail" to (failure.message ?: failure::class.simpleName.orEmpty())))
}

/** [fpgaFailureNotice]'s text alone, for callers that keep one message. */
internal fun fpgaCompanionFailure(failure: Exception): LocalizedText = fpgaFailureNotice(failure).text
