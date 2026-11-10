package dev.logisim.mobile

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** E2E-only adapter fixture. This activity never creates the editor, catalog, or SAP-1 simulator. */
class NativeAssetFixtureActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val records = Executors.newSingleThreadExecutor()
    private val buttons = mutableListOf<Button>()
    private lateinit var assets: AndroidDocumentAssetActions
    private lateinit var status: TextView
    private lateinit var directory: File
    private lateinit var runId: String
    private lateinit var sessionId: String
    private lateinit var fixtures: Map<String, DocumentAsset>
    private var operation: Operation? = null
    private var prepared = false
    private var destroyed = false

    private data class Operation(val id: String, val name: String) {
        fun json() = JSONObject().put("id", id).put("operation", name)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(packageName.endsWith(".e2e")) { "Native asset fixture requires an isolated E2E application" }
        sessionId = state?.getString("native-asset-session") ?: UUID.randomUUID().toString()
        runId = state?.getString("native-asset-run") ?: intent.getStringExtra("fixtureRun")
            ?: UUID.randomUUID().toString()
        check(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid fixture run identifier" }
        directory = File(filesDir, "native-asset-fixture/$runId").apply { check(isDirectory || mkdirs()) }
        operation = readJson(File(directory, "pending.json"))?.let { value ->
            runCatching { Operation(value.getString("id"), value.getString("operation")) }.getOrNull()
        }
        buildControls()
        assets = AndroidDocumentAssetActions(this, sessionId) { result -> recordAction(result, "recovered") }
        val printable = assets.supportsPrint
        records.execute {
            try {
                fixtures = payloads(runId)
                val manifest = JSONObject().put("packageName", packageName).put("runId", runId)
                    .put("sessionId", sessionId).put("pid", Process.myPid()).put("supportsPrint", printable)
                    .put("scope", "native-document-adapter-only")
                val entries = JSONObject()
                fixtures.forEach { (key, asset) ->
                    val bytes = asset.bytes
                    val path = "payload.$key"
                    writeAtomic(File(directory, path), bytes)
                    entries.put(key, describe(asset, bytes).put("path", path))
                }
                val image = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(fixtures.getValue("png").bytes, 0, fixtures.getValue("png").size, image)
                check(image.outWidth == 1 && image.outHeight == 1) { "Fixture PNG is invalid" }
                manifest.put("pngWidth", image.outWidth).put("pngHeight", image.outHeight)
                val pdfPages = PdfRenderer(ParcelFileDescriptor.open(File(directory, "payload.pdf"),
                    ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                check(pdfPages == 3) { "Fixture PDF must have three valid pages" }
                manifest.put("pdfPageCount", pdfPages).put("fixtures", entries)
                writeAtomic(File(directory, "manifest.json"), manifest.toString().encodeToByteArray())
                main.post {
                    if (!destroyed) {
                        prepared = true
                        status.text = if (operation == null) "Ready" else "Waiting: ${operation?.name}"
                        updateButtons()
                    }
                }
            } catch (error: Throwable) {
                writeAtomic(File(directory, "fixture-error.txt"), error.stackTraceToString().encodeToByteArray())
                main.post { if (!destroyed) status.text = "Fixture preparation failed: ${error.message}" }
            }
        }
    }

    private fun buildControls() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "Native binary adapter fixture"
            textSize = 20f
        })
        content.addView(TextView(this).apply {
            text = "E2E build only. Tests document adapters without loading the editor."
        })
        status = TextView(this).apply {
            contentDescription = "Native asset fixture status"
            text = "Preparing fixtures"
            textSize = 16f
        }
        content.addView(status)
        fun button(label: String, id: String, action: () -> Unit) {
            val control = Button(this).apply {
                text = label
                contentDescription = "fixture:$id"
                isEnabled = false
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { begin(id, action) }
            }
            buttons += control
            content.addView(control, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        button("Open PNG", "open-png") { assets.open(listOf("image/png"), 1024 * 1024, ::recordOpen) }
        button("Open raw", "open-raw") { assets.open(listOf("application/octet-stream"), 64 * 1024 * 1024, ::recordOpen) }
        button("Open tiny PNG", "open-tiny-png") { assets.open(listOf("image/png"), 32, ::recordOpen) }
        button("Create PNG", "create-png") { assets.create(fixtures.getValue("png"), ::recordAction) }
        button("Create SVG", "create-svg") { assets.create(fixtures.getValue("svg"), ::recordAction) }
        button("Create PDF", "create-pdf") { assets.create(fixtures.getValue("pdf"), ::recordAction) }
        button("Create memory.hex", "create-memory") { assets.create(fixtures.getValue("memory"), ::recordAction) }
        button("Share PNG", "share-png") { assets.share(fixtures.getValue("png"), ::recordAction) }
        button("Share SVG", "share-svg") { assets.share(fixtures.getValue("svg"), ::recordAction) }
        button("Share PDF", "share-pdf") { assets.share(fixtures.getValue("pdf"), ::recordAction) }
        button("Share memory.hex", "share-memory") { assets.share(fixtures.getValue("memory"), ::recordAction) }
        button("Print PDF", "print-pdf") { assets.printPdf(fixtures.getValue("pdf"), ::recordAction) }
        setContentView(ScrollView(this).apply {
            contentDescription = "Native asset fixture controls"
            addView(content)
        })
    }

    private fun begin(name: String, launch: () -> Unit) {
        if (!prepared || operation != null) return
        val current = Operation(UUID.randomUUID().toString(), name)
        operation = current
        status.text = "Waiting: $name"
        updateButtons()
        records.execute {
            try {
                writeAtomic(File(directory, "pending.json"), current.json().toString().encodeToByteArray())
                main.post { if (!destroyed && operation == current) launch() }
            } catch (error: Throwable) {
                enqueue(current, event(current, "failure", "fixture").put("noticeKey", "fixture_artifact_failed")
                    .put("error", error.toString()))
            }
        }
    }

    private fun recordOpen(result: AssetOpenResult) {
        val current = operation ?: Operation(UUID.randomUUID().toString(), "orphan-open")
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        records.execute {
            val value = try {
                when (result) {
                    is AssetOpenResult.Opened -> {
                        val bytes = result.asset.bytes
                        val path = "opened-${current.id}.bin"
                        writeAtomic(File(directory, path), bytes)
                        event(current, "opened", "callback", onMain)
                            .put("asset", describe(result.asset, bytes).put("path", path))
                    }
                    AssetOpenResult.Cancelled -> event(current, "cancelled", "callback", onMain)
                    is AssetOpenResult.Failure -> event(current, "failure", "callback", onMain)
                        .put("noticeKey", result.notice.key).put("notice", result.notice.resolve())
                }
            } catch (error: Throwable) {
                event(current, "failure", "fixture", onMain).put("noticeKey", "fixture_artifact_failed")
                    .put("error", error.toString())
            }
            saveEvent(current, value)
        }
    }

    private fun recordAction(result: AssetActionResult) = recordAction(result, "callback")

    private fun recordAction(result: AssetActionResult, source: String) {
        val current = operation ?: Operation(UUID.randomUUID().toString(), "orphan-recovery")
        val value = when (result) {
            AssetActionResult.Completed -> event(current, "completed", source)
            AssetActionResult.Cancelled -> event(current, "cancelled", source)
            is AssetActionResult.Failure -> event(current, "failure", source)
                .put("noticeKey", result.notice.key).put("notice", result.notice.resolve())
        }
        enqueue(current, value)
    }

    private fun enqueue(current: Operation, value: JSONObject) { records.execute { saveEvent(current, value) } }

    private fun saveEvent(current: Operation, value: JSONObject) {
        val file = File(directory, "results.json")
        val history = runCatching { JSONArray(AtomicFile(file).readFully().decodeToString()) }.getOrElse { JSONArray() }
        history.put(value)
        writeAtomic(file, history.toString().encodeToByteArray())
        if (readJson(File(directory, "pending.json"))?.optString("id") == current.id) AtomicFile(File(directory, "pending.json")).delete()
        main.post {
            if (!destroyed && operation?.id == current.id) {
                operation = null
                status.text = "${current.name}: ${value.getString("status")}"
                updateButtons()
            }
        }
    }

    private fun event(current: Operation, state: String, source: String, onMain: Boolean = Looper.myLooper() == Looper.getMainLooper()) =
        current.json().put("status", state).put("source", source).put("pid", Process.myPid())
            .put("mainThread", onMain).put("timestamp", System.currentTimeMillis())

    private fun describe(asset: DocumentAsset, bytes: ByteArray) = JSONObject()
        .put("name", asset.name).put("mimeType", asset.mimeType).put("bytes", bytes.size).put("sha256", sha256(bytes))

    private fun updateButtons() { buttons.forEach { it.isEnabled = prepared && operation == null } }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("native-asset-session", sessionId)
        outState.putString("native-asset-run", runId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        records.shutdown()
    }

    private fun readJson(file: File): JSONObject? = runCatching { JSONObject(AtomicFile(file).readFully().decodeToString()) }.getOrNull()

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private companion object {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        fun payloads(runId: String): Map<String, DocumentAsset> = linkedMapOf(
            "png" to DocumentAsset("fixture-$runId.png", "image/png", Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQTX79HwAElwJzVt2vfAAAAABJRU5ErkJggg==", Base64.NO_WRAP)),
            "svg" to DocumentAsset("fixture-$runId.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"160\" height=\"96\" viewBox=\"0 0 160 96\"><rect width=\"160\" height=\"96\" fill=\"white\"/><path d=\"M16 48H144M80 16V80\" stroke=\"#167624\" stroke-width=\"4\"/><circle cx=\"80\" cy=\"48\" r=\"12\" fill=\"#2563eb\"/></svg>".encodeToByteArray()),
            "pdf" to DocumentAsset("fixture-$runId.pdf", "application/pdf", pdf()),
            "memory" to DocumentAsset("fixture-$runId.hex", "text/plain", "v2.0 raw\n00 7f 80 ff\n".encodeToByteArray()),
        )

        fun pdf(): ByteArray {
            val objects = mutableListOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>",
            )
            for (page in 1..3) {
                val contents = 2 * page + 2
                objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents $contents 0 R /Resources << /Font << /F1 9 0 R >> >> >>"
                val color = when (page) { 1 -> "0.15 0.39 0.92"; 2 -> "0.09 0.46 0.14"; else -> "0.82 0.16 0.16" }
                val commands = "q 1 1 1 rg 0 0 300 200 re f $color rg 20 20 260 160 re f Q BT /F1 18 Tf 35 100 Td (Asset fixture page $page) Tj ET\n"
                objects += "<< /Length ${commands.encodeToByteArray().size} >>\nstream\n${commands}endstream"
            }
            objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
            val bytes = ByteArrayOutputStream()
            fun append(text: String) { bytes.write(text.encodeToByteArray()) }
            append("%PDF-1.4\n")
            val offsets = objects.mapIndexed { index, value ->
                val offset = bytes.size()
                append("${index + 1} 0 obj\n$value\nendobj\n")
                offset
            }
            val xref = bytes.size()
            append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
            append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return bytes.toByteArray()
        }
    }
}
