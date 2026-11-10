package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HdlSimulationCompanionTest {
    private fun sample(ok: Boolean = true, value: String = "10?E", session: String = "a".repeat(32)): CompanionJob {
        val result = CompanionJsonCodec.parseObject("""{"ok":$ok,"status":"ready","sessionId":"$session",
            "timeNs":7,"sourceSha256":"${"b".repeat(64)}","outputs":{"y":{"stdLogic":"10UX","logic":"$value"}},
            "diagnostics":"real simulator","code":"simulation_failed","message":"analysis failed"}""")
        return CompanionJob("c".repeat(32), "hdl.step", CompanionJobState.SUCCEEDED, result, null, emptyList())
    }

    @Test fun wireSamplePreservesBitOrderUnknownsAndErrors() {
        val sample = sample().hdlSample(mapOf("y" to 4), "a".repeat(32))
        assertEquals("10?E", sample.outputs.getValue("y").display)
        assertEquals(7L, sample.timeNs)
        assertEquals("real simulator", sample.diagnostics)
        val request = hdlStepRequest(sample.sessionId, mapOf("a" to sample.outputs.getValue("y")), 3)
        val inputs = request.parameters.getValue("inputs") as CompanionJsonObject
        assertEquals("10?E", inputs.string("a"))
    }

    @Test fun failedOrMismatchedSamplesNeverReturnKnownValues() {
        assertFailsWith<HdlCosimulationFailure> { sample(ok = false).hdlSample(mapOf("y" to 4)) }
        assertFailsWith<IllegalArgumentException> { sample().hdlSample(mapOf("other" to 4)) }
        assertFailsWith<IllegalArgumentException> { sample().hdlSample(mapOf("y" to 3)) }
        assertFailsWith<IllegalArgumentException> { sample(value = "10ZZ").hdlSample(mapOf("y" to 4)) }
        assertFailsWith<IllegalArgumentException> { sample().hdlSample(mapOf("y" to 4), "f".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { hdlStepRequest("a".repeat(32), emptyMap(), 0) }
    }
}
