package dev.logisim.mobile.documents

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import dev.logisim.mobile.AndroidDocumentActions
import dev.logisim.mobile.AndroidDocumentStore
import dev.logisim.mobile.DocumentOpenMode
import dev.logisim.mobile.DocumentWriteResult
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocumentIntegrationInstrumentation : Instrumentation() {
    private var testArguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testArguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        if (testArguments.containsKey("mixedInputCase")) {
            dev.logisim.mobile.input.MixedInputScenarios(this).run(testArguments)
            return
        }
        val messages = mutableListOf<String>()
        var count = 0
        try {
            testArguments.getString("reopenUri")?.let { savedUri ->
                val content = AndroidDocumentStore(targetContext).read(Uri.parse(savedUri), DocumentOpenMode.IMPORT_COPY)
                check(Regex("<circuit\\s").findAll(content.text).count() == 7)
                finish(Activity.RESULT_OK, Bundle().apply {
                    putString("stream", "\nPERSISTED_DOCUMENT_OPENED=7\n")
                    putInt("tests", 1); putInt("failures", 0)
                })
                return
            }
            val ready = CountDownLatch(1)
            val bootstrapResult = AtomicInteger(Activity.RESULT_CANCELED)
            targetContext.sendOrderedBroadcast(Intent().setComponent(ComponentName(context.packageName,
                "dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver")), null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { bootstrapResult.set(resultCode); ready.countDown() }
            }, null, Activity.RESULT_CANCELED, null, null)
            check(ready.await(15, TimeUnit.SECONDS)) { "Fixture provider did not initialize" }
            check(bootstrapResult.get() == Activity.RESULT_OK) { "Fixture bootstrap failed" }
            val resolver = targetContext.contentResolver
            val authority = context.packageName + ".documents"
            val base = Uri.parse("content://$authority")
            fun uri(id: String) = DocumentsContract.buildDocumentUri(authority, id)
            fun run(name: String, body: () -> Unit) {
                resolver.call(base, "fixture:reset", null, null)
                body()
                count++
                messages += "PASS: $name"
            }
            val store = AndroidDocumentStore(targetContext)
            val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            run("UTF-8 document import from an external provider") {
                val content = store.read(uri("writable.circ"), DocumentOpenMode.IMPORT_COPY, grants)
                check(content.text == DocumentFixtureProvider.XML)
                check(content.reference.id.startsWith("content://"))
                check(!content.reference.writable)
                check(content.reference.name == "writable.circ")
            }
            run("read-only provider metadata") {
                val content = store.read(uri("readonly.circ"), DocumentOpenMode.EDIT_IN_PLACE, grants)
                check(!content.reference.writable)
                check(store.write(content.reference, "changed") is DocumentWriteResult.Failure)
                check(store.read(uri("readonly.circ"), DocumentOpenMode.IMPORT_COPY, grants).text == DocumentFixtureProvider.XML)
            }
            run("provider export and readback") {
                val destination = requireNotNull(DocumentsContract.createDocument(resolver, uri("root"),
                    AndroidDocumentActions.MIME_TYPE, "export.circ"))
                val result = store.writeNew(destination, DocumentFixtureProvider.XML, grants)
                check(result is DocumentWriteResult.Saved)
                check(store.read(destination, DocumentOpenMode.IMPORT_COPY, grants).text == DocumentFixtureProvider.XML)
            }
            run("Save As policy preserves an existing original") {
                val content = store.read(uri("writable.circ"), DocumentOpenMode.EDIT_IN_PLACE, grants)
                check(!content.reference.writable)
                check(store.write(content.reference, "changed") is DocumentWriteResult.Failure)
                check(store.writeNew(uri("writable.circ"), "changed", grants) is DocumentWriteResult.Conflict)
                check(store.read(uri("writable.circ"), DocumentOpenMode.IMPORT_COPY, grants).text == DocumentFixtureProvider.XML)
            }
            run("conflict leaves external contents unchanged") {
                val opened = store.read(uri("atomic.circ"), DocumentOpenMode.EDIT_IN_PLACE, grants)
                val changed = "external editor"
                resolver.call(base, "fixture:replace", "atomic.circ", Bundle().apply { putByteArray("bytes", changed.toByteArray()) })
                check(store.write(opened.reference, "our changes") is DocumentWriteResult.Conflict)
                check(store.read(uri("atomic.circ"), DocumentOpenMode.IMPORT_COPY, grants).text == changed)
            }
            run("atomic save and retry confirm the same URI and all payload bytes") {
                val source = uri("atomic.circ")
                val opened = store.read(source, DocumentOpenMode.EDIT_IN_PLACE, grants)
                check(opened.reference.writable)
                val text = DocumentFixtureProvider.XML + "\n<!--" + "ã".repeat(600_000) + "-->"
                resolver.call(base, "fixture:next-atomic-result", "lost-reply", null)
                val saved = store.write(opened.reference, text) as DocumentWriteResult.Saved
                check(saved.reference.id == opened.reference.id)
                check(saved.reference.revision == AndroidDocumentStore.digest(text.toByteArray()))
                check(store.read(source, DocumentOpenMode.IMPORT_COPY, grants).text == text)
                check(store.write(opened.reference, text) == saved)
            }
            run("interrupted atomic upload preserves original bytes and can be retried") {
                val source = uri("atomic.circ")
                val opened = store.read(source, DocumentOpenMode.EDIT_IN_PLACE, grants)
                resolver.call(base, "fixture:next-atomic-result", "interrupt", null)
                val text = DocumentFixtureProvider.XML + "<!-- Our changes -->"
                check(store.write(opened.reference, text) is DocumentWriteResult.Failure)
                check(store.read(source, DocumentOpenMode.IMPORT_COPY, grants).text == opened.text)
                check(store.write(opened.reference, text) is DocumentWriteResult.Saved)
            }
            run("provider detects a race after the client's preflight read") {
                val source = uri("atomic.circ")
                val opened = store.read(source, DocumentOpenMode.EDIT_IN_PLACE, grants)
                resolver.call(base, "fixture:next-atomic-result", "race", null)
                check(store.write(opened.reference, "our changes") is DocumentWriteResult.Conflict)
                check(store.read(source, DocumentOpenMode.IMPORT_COPY, grants).text == "concurrent edit")
            }
            run("revoked access reports failure") {
                val opened = store.read(uri("atomic.circ"), DocumentOpenMode.EDIT_IN_PLACE, grants)
                resolver.call(base, "fixture:deny", null, null)
                val error = runCatching { store.read(uri("writable.circ"), DocumentOpenMode.IMPORT_COPY, grants) }.exceptionOrNull()
                check(error is SecurityException)
                check(store.write(opened.reference, "our changes") is DocumentWriteResult.Failure)
                resolver.call(base, "fixture:allow", null, null)
                check(store.read(uri("writable.circ"), DocumentOpenMode.IMPORT_COPY, grants).text == DocumentFixtureProvider.XML)
                check(store.read(uri("atomic.circ"), DocumentOpenMode.IMPORT_COPY, grants).text == opened.text)
            }
            run("moved document reports failure") {
                val error = runCatching { store.read(uri("missing.circ"), DocumentOpenMode.IMPORT_COPY, grants) }.exceptionOrNull()
                check(error is java.io.FileNotFoundException)
            }
            run("invalid UTF-8 is rejected without rewriting the source") {
                val bytes = byteArrayOf(0xc3.toByte(), 0x28)
                resolver.call(base, "fixture:replace", "writable.circ", Bundle().apply { putByteArray("bytes", bytes) })
                val error = runCatching { store.read(uri("writable.circ"), DocumentOpenMode.IMPORT_COPY, grants) }.exceptionOrNull()
                check(error is LocalizedArgumentException && error.text.key == Messages.CodecXmlEncodingInvalid &&
                    error.text.arguments["encoding"] == "UTF-8")
                check(resolver.openInputStream(uri("writable.circ"))!!.use { it.readBytes() }.contentEquals(bytes))
            }
            run("share names cannot escape the dedicated cache") {
                check(AndroidDocumentActions.fileName("../../a.circ") == "a.circ")
                check(AndroidDocumentActions.fileName("C:\\private\\a") == "a.circ")
                check(AndroidDocumentActions.fileName("\n") == "circuito.circ")
            }
            run("Open With accepts a text/plain circuit URI") {
                val intent = Intent(Intent.ACTION_VIEW, uri("writable.circ")).apply {
                    setDataAndType(uri("writable.circ"), "text/plain")
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val activities = targetContext.packageManager.queryIntentActivities(intent, 0)
                check(activities.any { it.activityInfo.packageName == targetContext.packageName })
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "\n" + messages.joinToString("\n") + "\nDOCUMENT_TESTS_PASSED=$count\n")
                putInt("tests", count); putInt("failures", 0)
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "\n" + messages.joinToString("\n") + "\nFAIL: ${error.stackTraceToString()}\n")
                putInt("tests", count + 1); putInt("failures", 1)
            })
        }
    }
}
