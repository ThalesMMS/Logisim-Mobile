package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TclClientLocalizationTest {
    private val source = TclSourceFile("component.tcl", "expr 42")
    private val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8080"), CompanionToken.parse("a".repeat(32)))
    private val jobId = "b".repeat(32)
    private val face = TclInterface(listOf(TclSignalPort("sysclk_i", PortDirection.INPUT, 1), TclSignalPort("out_o", PortDirection.OUTPUT, 4)))

    private fun capabilities(allowed: Boolean = true) =
        """{"protocolVersion":1,"operations":[{"name":"tcl-run","allowed":$allowed,"available":true,"missingTools":[],"toolAlternatives":[],"requiresConfirmation":false,"description":"safe Tcl"},{"name":"tcl-step","allowed":$allowed,"available":true,"missingTools":[],"toolAlternatives":[],"requiresConfirmation":false,"description":"safe Tcl"}],"limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":8388608,"timeoutSeconds":30,"processMemoryBytes":536870912,"memoryEnforcement":"rlimit","osSandbox":false}}"""
    private fun job(operation: String, result: String, state: String = "succeeded", error: String = "null") =
        """{"jobId":"$jobId","operation":"$operation","state":"$state","result":$result,"error":$error,"logs":[]}"""
    private fun consoleResult(hash: String = source.sha256, result: String = "42", stdout: String = "", stderr: String = "") =
        CompanionJsonCodec.write(CompanionJsonObject(mapOf("mode" to CompanionJsonString("safe-tcl-v1"),
            "sourceSha256" to CompanionJsonString(hash), "result" to CompanionJsonString(result),
            "stdout" to CompanionJsonString(stdout), "stderr" to CompanionJsonString(stderr))))
    private fun stepRequest() = TclStepRequest("request1", source, face,
        listOf(TclInputFrame(mapOf("sysclk_i" to LogicVector.low(1)))))
    private class Transport(val response: (CompanionHttpRequest) -> String) : CompanionTransport {
        val requests = mutableListOf<CompanionHttpRequest>()
        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            requests += request
            return CompanionHttpResponse(200, response(request).encodeToByteArray(), "application/json")
        }
    }
    private fun Throwable.notice(): LocalizedText = localizedDescription(LocalizedText.literal("missing notice"))

    @Test
    fun invalidFileNamesRemainLiteralInsideTranslatedErrors() {
        val name = "../{name} → שלום.tcl"
        val error = assertFailsWith<LocalizedArgumentException> { TclSourceFile(name, "expr 42") }
        assertEquals("Tcl inputs require a plain file name: $name.", error.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("Entradas Tcl exigem um nome de arquivo simples: $name.", error.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun sourceSizeAndFrameCountKeepTheirSpecificIdentities() {
        val oversized = assertFailsWith<LocalizedArgumentException> { TclSourceFile("large.tcl", "á".repeat(128 * 1024 + 1)) }
        assertEquals(Messages.TclClientErrorSourceSize, oversized.text.key)
        val emptyHistory = assertFailsWith<LocalizedArgumentException> { TclStepRequest("request1", source, face, emptyList()) }
        assertEquals("Tcl replay requires 1 to 1024 input frames; received 0.", emptyHistory.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("A reprodução Tcl exige de 1 a 1024 quadros de entrada; recebidos 0.", emptyHistory.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun invalidRequestIdentitiesRemainLiteralData() {
        val request = "{request} → שלום"
        val error = assertFailsWith<LocalizedArgumentException> { TclConsoleRequest(request, source) }
        assertEquals("Invalid Tcl request identity: $request.", error.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("Identificação de solicitação Tcl inválida: $request.", error.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun unavailableClientDoesNotContactTheHost() = runBlocking {
        val transport = Transport { capabilities() }
        val error = assertFailsWith<LocalizedStateException> {
            CompanionTclClient(CompanionClient(connection, transport), "Host", available = false)
                .runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals(Messages.TclClientErrorHostRequired, error.text.key)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun deniedHostGrantsTranslateAndNeverSubmitWork() = runBlocking {
        val transport = Transport { capabilities(allowed = false) }
        val error = assertFailsWith<LocalizedStateException> {
            CompanionTclClient(CompanionClient(connection, transport), "Host").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals("The selected companion does not permit tcl-run or cannot find tclsh.", error.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("O companion selecionado não permite tcl-run ou não encontra tclsh.", error.notice().resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf("GET"), transport.requests.map { it.method })
    }

    @Test
    fun replyValidationPreservesSpecificDiagnosticIdentities() = runBlocking {
        val transport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-run", consoleResult("0".repeat(64))) }
        val error = assertFailsWith<LocalizedArgumentException> {
            CompanionTclClient(CompanionClient(connection, transport), "Host").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals(Messages.TclClientErrorSourceReply, error.text.key)
        assertEquals("The Tcl result does not match the approved source and execution mode.", error.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("O resultado Tcl não corresponde à fonte e ao modo de execução aprovados.", error.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun malformedLocalRepliesKeepProtocolExceptionCodes() = runBlocking {
        val resultTransport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-run", "42") }
        val resultError = assertFailsWith<CompanionProtocolException> {
            CompanionTclClient(CompanionClient(connection, resultTransport), "Host").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals("invalid-tcl", resultError.code)
        assertEquals("Expected a Tcl result object.", resultError.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("Esperado um objeto de resultado Tcl.", resultError.notice().resolve(AppLanguage.PORTUGUESE))
        val steps = """{"mode":"safe-replay-v1","sourceSha256":"${source.sha256}","delay":1,"steps":[true]}"""
        val stepTransport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-step", steps) }
        val stepError = assertFailsWith<CompanionProtocolException> {
            CompanionTclClient(CompanionClient(connection, stepTransport), "Host").step(stepRequest())
        }
        assertEquals("invalid-tcl", stepError.code)
        assertEquals("Expected a Tcl step object.", stepError.notice().resolve(AppLanguage.ENGLISH))
        assertEquals("Esperado um objeto de passo Tcl.", stepError.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun localExecutorFallbackIsTypedButRemoteErrorsRemainLiteral() = runBlocking {
        val emptyErrorTransport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-run", "null", "failed") }
        val fallback = assertFailsWith<CompanionProtocolException> {
            CompanionTclClient(CompanionClient(connection, emptyErrorTransport), "Host").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals("tcl-failed", fallback.code)
        assertEquals("O executor Tcl falhou.", fallback.notice().resolve(AppLanguage.PORTUGUESE))
        val detail = "Tcl/compiler {value} → שלום"
        val remoteError = CompanionJsonCodec.write(CompanionJsonObject(mapOf("code" to CompanionJsonString("tcl-script-error"),
            "message" to CompanionJsonString(detail), "details" to CompanionJsonObject(emptyMap()))))
        val transport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-run", "null", "failed", remoteError) }
        val error = assertFailsWith<CompanionProtocolException> {
            CompanionTclClient(CompanionClient(connection, transport), "Host").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals("tcl-script-error", error.code)
        assertEquals(detail, error.message)
        assertEquals(detail, error.notice().resolve(AppLanguage.ENGLISH))
        assertEquals(detail, error.notice().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun resultAndCompilerStreamsRemainUnmodified() = runBlocking {
        val result = "Tcl result {name} → שלום"
        val stdout = "stdout {result}\n"
        val stderr = "compiler warning {line}\n"
        val transport = Transport { if (it.url.endsWith("capabilities")) capabilities() else job("tcl-run", consoleResult(result = result, stdout = stdout, stderr = stderr)) }
        val reply = CompanionTclClient(CompanionClient(connection, transport), "Host").runConsole(TclConsoleRequest("request1", source))
        assertEquals(result, reply.result)
        assertEquals(stdout, reply.stdout)
        assertEquals(stderr, reply.stderr)
    }

    @Test
    fun protocolExceptionConstructorsPreserveTypeCodeStatusDetailsAndStringApi() {
        val details = CompanionJsonObject(mapOf("request" to CompanionJsonString("request1")))
        val text = message(Messages.TclClientErrorHostGrant, "operation" to "tcl-step")
        val typed = CompanionProtocolException("invalid-tcl", text, httpStatus = 422, details = details)
        assertEquals<Class<*>>(Exception::class.java, typed.javaClass.superclass)
        assertEquals("invalid-tcl", typed.code)
        assertEquals(422, typed.httpStatus)
        assertSame(details, typed.details)
        assertEquals("CompanionProtocolException(code=invalid-tcl, status=422)", typed.toString())
        assertEquals(text.resolve(AppLanguage.ENGLISH), typed.message)
        assertEquals(text.resolve(AppLanguage.PORTUGUESE), typed.notice().resolve(AppLanguage.PORTUGUESE))
        val raw = "Remote {operation} → שלום"
        val legacy = CompanionProtocolException(code = "legacy", message = raw, httpStatus = 400, details = details)
        assertEquals(raw, legacy.message)
        assertEquals(raw, legacy.notice().resolve(AppLanguage.PORTUGUESE))
        assertEquals(400, legacy.httpStatus)
        assertSame(details, legacy.details)
    }
}
