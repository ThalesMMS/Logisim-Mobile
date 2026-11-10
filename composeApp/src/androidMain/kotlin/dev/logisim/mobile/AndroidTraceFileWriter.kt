package dev.logisim.mobile

import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import java.io.OutputStream

/** Uses the already opened SAF stream; tests exercise the same UTF-8 writes against a real file. */
internal class AndroidTraceFileWriter(
    override val name: String,
    private val output: OutputStream,
    private val execute: (() -> Unit) -> Unit = { it() },
    private val onMain: (() -> Unit) -> Unit = { it() },
    private val onClosed: (TraceFileWriter) -> Unit = {},
) : TraceFileWriter {
    @Volatile private var closed = false

    override fun append(text: String, onResult: (AssetActionResult) -> Unit) {
        if (closed) { onResult(AssetActionResult.Failure(message("trace_file_write_failed"))); return }
        run(onResult) {
            check(!closed)
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    override fun close(onResult: (AssetActionResult) -> Unit) {
        if (closed) { onResult(AssetActionResult.Completed); return }
        run(onResult) {
            if (!closed) {
                closed = true
                try { output.close() } finally { onMain { onClosed(this) } }
            }
        }
    }

    private fun run(onResult: (AssetActionResult) -> Unit, action: () -> Unit) {
        execute {
            val result = runCatching(action).fold({ AssetActionResult.Completed },
                { AssetActionResult.Failure(it.localizedDescription(message("trace_file_write_failed"))) })
            onMain { onResult(result) }
        }
    }
}
