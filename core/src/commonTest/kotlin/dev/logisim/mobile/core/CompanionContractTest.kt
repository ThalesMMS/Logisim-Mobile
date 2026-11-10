package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionContractTest {
    @Test
    fun jsonPreservesValuesAndRejectsAmbiguousOrMalformedEnvelopes() {
        val source = """{"message":"ação💡\n\t", "nested":[true,false,null,-1.25e2],"object":{"a":1}}"""
        val value = CompanionJsonCodec.parseObject(source)
        assertEquals("ação💡\n\t", value.string("message"))
        assertEquals(value, CompanionJsonCodec.parse(CompanionJsonCodec.write(value)))
        for (invalid in listOf("{\"a\":1,\"a\":2}", "{\"x\":{\"a\":1,\"a\":2}}", "[1,]", "{\"x\":NaN}",
            "{\"x\":Infinity}", "{\"x\":1e999}", "{\"x\":01}", "{}{}", "\"\\uD800\"", "\"\\uDC00\"", "\"\\u+041\"", "\"\\u-001\"")) {
            assertFailsWith<CompanionProtocolException>(invalid) { CompanionJsonCodec.parse(invalid) }
        }
    }

    @Test
    fun planNumbersCompareByExactValueRatherThanSpellingOrRoundedDoubles() {
        for ((left, right) in listOf("1e0" to "1.0", "-0.0" to "0", "1.2500e2" to "125", "123000e-3" to "123.0")) {
            assertTrue(CompanionJsonNumber(left).sameValue(CompanionJsonNumber(right)))
        }
        assertFalse(CompanionJsonNumber("9007199254740992").sameValue(CompanionJsonNumber("9007199254740993")))
        assertFalse(CompanionJsonNumber("1e-999").sameValue(CompanionJsonNumber("0")))
    }

    @Test
    fun endpointAndTransportPermitOnlyScopedHttpAndDefaultTls() {
        assertEquals("http://127.0.0.1:8042", CompanionEndpoint.parse("http://127.0.0.1:8042/").url)
        assertEquals("http://[::1]:8042", CompanionEndpoint.parse("http://[::1]:8042").url)
        assertEquals("https://companion.example.org", CompanionEndpoint.parse("https://companion.example.org").url)
        for (invalid in listOf("http://localhost:8042", "http://192.168.1.1:8042", "http://127.0.0.1.evil", "http://127.1",
            "https://user:token@example.org", "https://example.org/path", "https://example.org?token=x", "https://example.org:0")) {
            assertFailsWith<IllegalArgumentException>(invalid) { CompanionEndpoint.parse(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            CompanionHttpRequest("http://127.0.0.1/v1/jobs", "GET", mapOf("Authorization" to "bad\r\nHeader: value"), null, 100, 100)
        }
    }

    @Test
    fun hashesInputCopiesAndValidatesEveryArtifactByte() {
        val bytes = "abc".encodeToByteArray()
        val input = CompanionInput.fromBytes("source.v", bytes)
        bytes[0] = 0
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", input.sha256)
        val artifact = CompanionJsonCodec.parseObject("""{"name":"result.bin","size":3,"sha256":"${input.sha256}","base64":"YWJj"}""")
        assertEquals("abc", CompanionArtifact.read(artifact).bytes.decodeToString())
        for (invalid in listOf(artifact.copy(fields = artifact.fields + ("size" to CompanionJsonNumber(4L))),
            artifact.copy(fields = artifact.fields + ("sha256" to CompanionJsonString("0".repeat(64)))),
            artifact.copy(fields = artifact.fields + ("base64" to CompanionJsonString("YWJj\n"))),
            artifact.copy(fields = artifact.fields + ("name" to CompanionJsonString("../result.bin"))))) {
            assertFailsWith<CompanionProtocolException> { CompanionArtifact.read(invalid) }
        }
        assertFailsWith<CompanionProtocolException> { CompanionArtifact.read(artifact, 2) }
    }

    @Test
    fun tokensAndRequestBodiesNeverAppearInDefaultDescriptions() {
        val tokenText = "test-secret-".repeat(4)
        val token = CompanionToken.parse(tokenText)
        val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"), token)
        val request = CompanionHttpRequest(connection.endpoint.url + "/v1/capabilities", "GET",
            mapOf("Authorization" to "Bearer $tokenText"), "secret-payload".encodeToByteArray(), 100, 100)
        assertFalse(token.toString().contains(tokenText))
        assertFalse(connection.toString().contains(tokenText))
        assertFalse(request.toString().contains(tokenText))
        assertFalse(request.toString().contains("secret-payload"))
        assertTrue(token.toString().contains("redacted"))
    }
}
