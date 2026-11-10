package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

fun HdlIpDesign.companionRequest(start: Boolean): CompanionJobRequest {
    requireLocalized(isExecutable) { message(Messages.HdlIpResolveDiagnostics) }
    return CompanionJobRequest(if (start) "hdl.start" else "hdl.validate", parameters = mapOf(
        "backend" to CompanionJsonString(if (backend == HdlIpBackend.VHDL) "vhdl" else "blif"),
        "source" to CompanionJsonString(source),
        "topLevel" to CompanionJsonString(topLevel),
        "parameters" to CompanionJsonObject(vhdlGenericActuals.mapValues { CompanionJsonString(it.value) }),
        "ports" to CompanionJsonArray(ports.map { port -> CompanionJsonObject(mapOf(
            "name" to CompanionJsonString(port.name),
            "direction" to CompanionJsonString(when (port.direction) {
                PortDirection.INPUT -> "in"
                PortDirection.OUTPUT -> "out"
                PortDirection.INOUT -> "inout"
            }),
            "width" to CompanionJsonNumber(port.width.toLong()), "kind" to CompanionJsonString(port.kind),
        )) }),
    ))
}

fun hdlStepRequest(
    sessionId: String, inputs: Map<String, LogicVector>, advanceNs: Long, inoutPorts: Set<String> = emptySet(),
): CompanionJobRequest {
    requireLocalized(Regex("[0-9a-f]{32}").matches(sessionId)) { message(Messages.HdlIpInvalidSession) }
    requireLocalized(advanceNs in 1..1_000_000) { message(Messages.HdlIpStepRange) }
    return CompanionJobRequest("hdl.step", parameters = mapOf(
        "sessionId" to CompanionJsonString(sessionId), "advanceNs" to CompanionJsonNumber(advanceNs),
        "inputs" to CompanionJsonObject(inputs.mapValues { (name, vector) ->
            CompanionJsonString((vector.width - 1 downTo 0).joinToString("") { index ->
                val bit = vector.bit(index)
                if (name in inoutPorts && bit == LogicValue.UNKNOWN) "Z" else bit.display
            })
        }),
    ))
}

fun hdlSessionRequest(sessionId: String, reset: Boolean): CompanionJobRequest {
    requireLocalized(Regex("[0-9a-f]{32}").matches(sessionId)) { message(Messages.HdlIpInvalidSession) }
    return CompanionJobRequest(if (reset) "hdl.reset" else "hdl.cancel",
        parameters = mapOf("sessionId" to CompanionJsonString(sessionId)))
}

data class HdlCosimulationSample(
    val sessionId: String,
    val timeNs: Long,
    val sourceSha256: String,
    val outputs: Map<String, LogicVector>,
    val diagnostics: String,
)

class HdlCosimulationFailure(val code: String, val notice: LocalizedText) : LocalizedStateException(notice) {
    override val message: String get() = notice.resolve(AppLanguage.ENGLISH)
}

/** A failed job or invalid sample cannot be interpreted as a known circuit value. */
fun CompanionJob.hdlSample(expectedOutputs: Map<String, Int>, expectedSession: String? = null): HdlCosimulationSample {
    if (state != CompanionJobState.SUCCEEDED) throw HdlCosimulationFailure(error?.code ?: "hdl-incomplete",
        error?.message?.let { message(Messages.HdlIpJobFailureReason, "reason" to it) } ?: message(Messages.HdlIpJobIncomplete))
    val envelope = result as? CompanionJsonObject
        ?: throw HdlCosimulationFailure("invalid-hdl-sample", message(Messages.HdlIpExpectedResult))
    val ok = (envelope.fields["ok"] as? CompanionJsonBoolean)?.value
        ?: throw HdlCosimulationFailure("invalid-hdl-sample", message(Messages.HdlIpExpectedResult))
    if (!ok) {
        val reason = (envelope.fields["message"] as? CompanionJsonString)?.value
        throw HdlCosimulationFailure((envelope.fields["code"] as? CompanionJsonString)?.value ?: "hdl-failed",
            reason?.let { message(Messages.HdlIpJobFailureReason, "reason" to it) } ?: message(Messages.HdlIpFailed))
    }
    val session = (envelope.fields["sessionId"] as? CompanionJsonString)?.value.orEmpty()
    requireLocalized(Regex("[0-9a-f]{32}").matches(session) && (expectedSession == null || session == expectedSession)) {
        message(Messages.HdlIpUnexpectedSession)
    }
    val time = (envelope.fields["timeNs"] as? CompanionJsonNumber)?.value?.toLongOrNull()
    requireLocalized(time != null && time >= 0) { message(Messages.HdlIpInvalidTime) }
    val digest = (envelope.fields["sourceSha256"] as? CompanionJsonString)?.value.orEmpty()
    requireLocalized(Regex("[0-9a-f]{64}").matches(digest)) { message(Messages.HdlIpInvalidDigest) }
    val values = (envelope.fields["outputs"] as? CompanionJsonObject)?.fields
        ?: throw HdlCosimulationFailure("invalid-hdl-sample", message(Messages.HdlIpExpectedResult))
    requireLocalized(values.keys == expectedOutputs.keys) { message(Messages.HdlIpOutputNames) }
    val outputs = expectedOutputs.mapValues { (name, width) ->
        requireLocalized(width in 1..64) { message(Messages.HdlIpOutputWidthRange) }
        val text = ((values.getValue(name) as? CompanionJsonObject)?.fields?.get("logic") as? CompanionJsonString)?.value
        requireLocalized(text != null) { message(Messages.HdlIpInvalidOutput, "name" to name) }
        requireLocalized(text.length == width && text.all { it in "01?E" }) { message(Messages.HdlIpInvalidOutputLogic, "name" to name) }
        LogicVector.fromBits(text.reversed().map { when (it) {
            '0' -> LogicValue.LOW; '1' -> LogicValue.HIGH; '?' -> LogicValue.UNKNOWN; else -> LogicValue.ERROR
        } })
    }
    return HdlCosimulationSample(session, time, digest, outputs,
        (envelope.fields["diagnostics"] as? CompanionJsonString)?.value.orEmpty())
}
