package dev.logisim.mobile.core

import java.io.Closeable
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

internal class HdlCompanionTestHost private constructor(val root: File, private val process: Process, val client: CompanionClient) : Closeable {
    suspend fun execute(request: CompanionJobRequest): CompanionJob = withTimeout(45_000) {
        val plan = client.plan(request)
        var job = client.submit(request, plan.confirm())
        while (!job.state.terminal) {
            delay(25)
            job = client.status(job.jobId)
        }
        job
    }

    override fun close() {
        process.outputStream.close()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        fun start(vararg requiredTools: String): HdlCompanionTestHost {
            val path = listOf("/opt/homebrew/bin") + System.getenv("PATH").orEmpty().split(File.pathSeparator)
            fun executable(name: String) = path.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
            val python = executable("python3")
            assumeTrue("Python 3 is optional for companion integration tests", python != null)
            requiredTools.forEach { assumeTrue("Optional HDL tool is unavailable: $it", executable(it) != null) }
            val root = sequenceOf(System.getenv("LOGISIM_HDL_COMPANION_SOURCE")?.let(::File), File(".."), File("."))
                .filterNotNull().map { it.canonicalFile }.first { File(it, "scripts/hdl-companion-fixture.py").isFile }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val builder = ProcessBuilder(checkNotNull(python).absolutePath, File(root, "scripts/hdl-companion-fixture.py").absolutePath)
                .directory(root).redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment()["LOGISIM_HDL_TEST_TOKEN"] = token
            builder.environment()["PYTHONPATH"] = root.absolutePath
            builder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            builder.environment()["PATH"] = path.joinToString(File.pathSeparator)
            val process = builder.start()
            try {
                val port = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
                    .get(10, TimeUnit.SECONDS)?.toIntOrNull()
                check(port in 1..65535) { "The loopback companion fixture failed to start." }
                return HdlCompanionTestHost(root, process, CompanionClient(CompanionConnection(
                    CompanionEndpoint.parse("http://127.0.0.1:$port"), CompanionToken.parse(token))))
            } catch (error: Exception) {
                process.outputStream.close()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                throw error
            }
        }
    }
}
