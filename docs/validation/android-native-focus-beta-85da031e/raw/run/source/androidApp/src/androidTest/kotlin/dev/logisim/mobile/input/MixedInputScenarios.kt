package dev.logisim.mobile.input

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Runs against the mounted editor. Every document oracle comes from its visible Copy action. */
class MixedInputScenarios(private val instrumentation: Instrumentation) {
    private val target = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }
    private val events = JSONArray()
    private lateinit var inputs: NativeMixedInput
    private val results = JSONArray()
    private lateinit var directory: File
    private var exportSequence = 0
    private var fixture = Fixture("uninitialized")
    private lateinit var loadedFixture: CircuitProject
    private val readiness = NativeReadinessDiagnostics.Trace<JSONObject>(128)
    private val waitSummaries = NativeReadinessDiagnostics.Trace<JSONObject>(32)
    private var diagnosticErrors = 0
    private var rootReadSequence = 0L
    private var clickSequence = 0L
    private var waitSequence = 0L
    private data class WaitContext(val id: Long, val description: String, var attempt: Int = 0)
    private var currentWait: WaitContext? = null

    fun run(arguments: Bundle) {
        var failures = 0
        var completed = 0
        val source = arguments.getString("sourceRevision").orEmpty()
        val expectedApk = arguments.getString("expectedApkSha256").orEmpty()
        val selection = arguments.getString("mixedInputCase").orEmpty()
        val apk = File(target.applicationInfo.sourceDir)
        val receipt = JSONObject().put("status", "NOT_RUN").put("declaredSourceRevision", source)
            .put("packageName", target.packageName).put("cases", results).put("events", events)
            .put("scope", "Synthesized Android system input. Physical mouse, trackpad and pen ergonomics are not qualified.")
        try {
            check(target.packageName.endsWith(".e2e")) { "Mixed input requires an isolated .e2e package" }
            check(source.matches(Regex("[0-9a-f]{40}"))) { "A complete sourceRevision is required" }
            check(expectedApk.matches(Regex("[0-9a-f]{64}"))) { "An expectedApkSha256 is required" }
            check(sha256(apk) == expectedApk) { "Installed APK does not match the requested artifact" }
            inputs = NativeMixedInput(instrumentation, events)
            directory = File(target.filesDir, "mixed-input/" + UUID.randomUUID()).apply { check(mkdirs()) }
            receipt.put("artifact", JSONObject().put("apkPath", apk.path).put("sha256Before", expectedApk))
            receipt.put("evidenceDirectory", directory.path).put("status", "RUNNING")
            val cases = linkedMapOf<String, () -> Unit>(
                "cancel" to ::cancelledDrag,
                "mixed" to ::mixedSelectionAndUndo,
                "context" to ::mouseContextMenu,
                "focus" to ::textFocusDoesNotEditTheCircuit,
                "scroll" to ::scrollPanAndAnchoredZoom,
                "pinch" to ::twoPointerZoomDoesNotEditTheCircuit,
                "focusloss" to ::focusLossCancelsTheDrag,
                "io-release" to ::focusLossReleasesTheHeldButton,
            )
            val requested = if (selection == "all") cases else {
                check(selection in cases) { "Unknown mixedInputCase: $selection" }
                linkedMapOf(selection to cases.getValue(selection))
            }
            for ((name, test) in requested) {
                val item = JSONObject().put("case", name).put("status", "RUNNING")
                results.put(item)
                val start = SystemClock.elapsedRealtime()
                item.put("startedAtElapsedMillis", start)
                try {
                    fixture = Fixture(name + "_" + UUID.randomUUID().toString().take(8))
                    openFixture()
                    test()
                    screenshot(name + "-complete")
                    item.put("status", "PASS")
                    completed++
                    runCatching { instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nMIXED_INPUT_PASS=$name\n") }) }
                } catch (error: Throwable) {
                    failures++
                    item.put("status", "FAIL").put("error", error.stackTraceToString())
                    snapshot(name + "-failure")
                    throw error
                } finally {
                    val finished = SystemClock.elapsedRealtime()
                    item.put("elapsedMillis", finished - start).put("finishedAtElapsedMillis", finished)
                    persist(receipt)
                }
            }
            val after = sha256(apk)
            receipt.getJSONObject("artifact").put("sha256After", after)
            check(after == expectedApk) { "Installed artifact changed during the input proof" }
            receipt.put("status", "PASS")
                .put("qualification", if (selection == "all") "SYNTHESIZED_SYSTEM_INPUT_PASS" else "PARTIAL")
        } catch (error: Throwable) {
            receipt.put("status", "FAIL").put("qualification", "NOT_QUALIFIED")
                .put("error", error.stackTraceToString())
            if (failures == 0) failures = 1
        } finally {
            if (::directory.isInitialized) persist(receipt)
            instrumentation.finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply {
                    putInt("tests", completed + failures)
                    putInt("failures", failures)
                    putString("stream", "\nMIXED_INPUT_RESULT=" + receipt.toString() + "\n")
                })
        }
    }

    private fun cancelledDrag() {
        mode(Messages.ActionSelect)
        val original = export("cancel-before")
        val before = observedTargetBaseline("cancel")
        val start = before.target
        val scale = scale(original, (before.frame.output.x - before.frame.input.x).toFloat())
        val proof = JSONObject().put("before", baselineRecord(before))
        results.getJSONObject(results.length() - 1).put("cancelViewportProof", proof)
        inputs.drag(NativeMixedInput.Tool.TOUCH, start.x.toFloat(), start.y.toFloat(),
            start.x.toFloat() + 50 * scale, start.y.toFloat() + 20 * scale, 160, true)
        assertSameDocument(original, export("cancel-after"))
        val again = observedTargetPoint(before, proof, GridPoint(0, 0), scale.toDouble(), "cancel")
        inputs.drag(NativeMixedInput.Tool.TOUCH, again.x.toFloat(), again.y.toFloat(),
            again.x.toFloat() + 50 * scale, again.y.toFloat() + 20 * scale, 160, false)
        val moved = export("cancel-valid-move")
        assertPosition(moved, "mi-not", GridPoint(250, 120))
        assertConnectivity(moved)
        undoOnCanvas()
        assertSameDocument(original, export("cancel-one-undo"))
    }

    private fun mixedSelectionAndUndo() {
        mode(Messages.ActionSelect)
        val original = export("mixed-before")
        val before = observedTargetBaseline("mixed")
        val start = before.target
        val scale = scale(original, (before.frame.output.x - before.frame.input.x).toFloat())
        val proof = JSONObject().put("before", baselineRecord(before))
        results.getJSONObject(results.length() - 1).put("mixedViewportProof", proof)
        inputs.tap(NativeMixedInput.Tool.TOUCH, start.x.toFloat(), start.y.toFloat())
        inputs.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val keyboard = export("mixed-keyboard")
        assertPosition(keyboard, "mi-not", GridPoint(210, 100))
        assertConnectivity(keyboard)
        val delta = NativeTargetGeometry.verifiedDelta(original, keyboard, GridPoint(10, 0))
        val stylus = observedTargetPoint(before, proof, delta, scale.toDouble(), "mixed")
        inputs.drag(NativeMixedInput.Tool.STYLUS, stylus.x.toFloat(), stylus.y.toFloat(),
            stylus.x.toFloat() + 50 * scale, stylus.y.toFloat() + 20 * scale, 160, false)
        val moved = export("mixed-stylus")
        assertPosition(moved, "mi-not", GridPoint(260, 120))
        assertConnectivity(moved)
        undoOnCanvas()
        assertSameDocument(keyboard, export("mixed-stylus-undo"))
        undoOnCanvas()
        assertSameDocument(original, export("mixed-keyboard-undo"))
    }

    private fun mouseContextMenu() {
        mode(Messages.ActionSelect)
        val original = export("context-before")
        val point = componentCenter("mi-not")
        val originalWindow = root("context:editor-window").windowId
        inputs.secondaryTap(point.x, point.y)
        val menu = waitNode("separate mouse context popup") {
            automation.windows.asSequence().filter { it.id != originalWindow }
                .mapNotNull { it.root }.firstOrNull { candidate ->
                    candidate.packageName?.toString() == target.packageName &&
                        nodes(candidate).none { matchesTag(it, "editor-canvas") } &&
                        findAction(labels(Messages.ActionCopy), candidate) != null &&
                        findAction(labels(Messages.ActionDelete), candidate) != null &&
                        findAction(labels(Messages.ActionDuplicate), candidate) != null
                }
        }
        check(menu.windowId != originalWindow)
        results.getJSONObject(results.length() - 1).put("contextPopupWindowId", menu.windowId)
            .put("editorWindowId", originalWindow)
        click(checkNotNull(findAction(labels(Messages.ActionDuplicate), menu)), "context:Duplicate")
        waitFor("original editor after Duplicate") {
            val observed = canvasObservation(readActionTree(root("context:return-to-editor"), "context:return-to-editor"))
            val ready = NativeCanvasObservation.editorReturned(observed, originalWindow)
            diagnostic("context-editor-return") {
                JSONObject().put("expectedWindowId", originalWindow).put("observedWindowId", observed.windowId)
                    .put("complete", observed.complete).put("ready", ready)
            }
            if (ready) results.getJSONObject(results.length() - 1).put("contextEditorReturnWindowId", observed.windowId)
            ready
        }
        val duplicated = export("context-duplicate")
        check(duplicated.mainCircuit.components.size == 4)
        val copies = duplicated.mainCircuit.components.filter { it.label == fixture.notLabel }
        check(copies.size == 2 && copies.map { it.id }.toSet().size == 2)
        check(copies.map { it.position }.toSet() == setOf(GridPoint(200, 100), GridPoint(220, 120)))
        undoOnCanvas()
        assertSameDocument(original, export("context-undo"))
    }

    private fun textFocusDoesNotEditTheCircuit() {
        mode(Messages.ActionSelect)
        val original = loadedFixture
        val before = observedTargetBaseline("focus")
        val point = before.target
        val scale = scale(original, (before.frame.output.x - before.frame.input.x).toFloat())
        val proof = JSONObject().put("before", baselineRecord(before)).put("pixelsPerWorld", scale.toDouble())
        results.getJSONObject(results.length() - 1).put("focusViewportProof", proof)
        inputs.tap(NativeMixedInput.Tool.MOUSE, point.x.toFloat(), point.y.toFloat())
        if (nodes(root("focus:inspector-presence")).none { it.isEditable && it.text?.toString() == fixture.notLabel }) {
            toolbar(Messages.A11yOpenProperties, listOf("editor-command-toolbar"))
        }
        val field = waitNode("NOT label field") {
            nodes(root("focus:label-field")).firstOrNull { it.isEditable && it.text?.toString() == fixture.notLabel }
        }
        check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "FOCUS_ONLY")
        }))
        waitFor("label focus") {
            nodes(root("focus:label-focus")).any { it.isEditable && it.isFocused && it.text?.toString() == "FOCUS_ONLY" }
        }
        inputs.chordCtrl(KeyEvent.KEYCODE_D)
        inputs.key(KeyEvent.KEYCODE_DPAD_LEFT)
        inputs.chordCtrl(KeyEvent.KEYCODE_A)
        inputs.key(KeyEvent.KEYCODE_FORWARD_DEL)
        waitFor("focused Delete handled by text field") {
            nodes(root("focus:empty-draft")).any { it.isEditable && it.isFocused && it.text.isNullOrEmpty() }
        }
        screenshot("focus-text-field")
        clickAction(labels(Messages.ActionApply))
        closeInspector()
        val after = export("focus-after")
        check(after.mainCircuit.components.size == 3) { "A text-field shortcut edited the component collection" }
        check(after.mainCircuit.wires.size == 2)
        assertPosition(after, "mi-not", GridPoint(200, 100))
        check(after.mainCircuit.components.single { it.id == "mi-not" }.label.isEmpty())
        val delta = NativeTargetGeometry.verifiedDelta(original, after, GridPoint(0, 0), expectedLabel = "")
        val canvas = observedFocusLabelClearPoint(before, proof, delta)
        inputs.tap(NativeMixedInput.Tool.MOUSE, canvas.x.toFloat(), canvas.y.toFloat())
        inputs.chordCtrl(KeyEvent.KEYCODE_D)
        val duplicated = export("focus-canvas-duplicate")
        check(duplicated.mainCircuit.components.size == 4) {
            "The same shortcut did not reach the focused canvas"
        }
        val copies = duplicated.mainCircuit.components.filter { it.kind == ComponentKind.NOT && it.label.isEmpty() }
        check(copies.size == 2 && copies.map { it.id }.toSet().size == 2 &&
            copies.map { it.position }.toSet() == setOf(GridPoint(200, 100), GridPoint(220, 120)))
    }

    private fun scrollPanAndAnchoredZoom() {
        mode(Messages.ActionSelect)
        val original = export("scroll-before")
        zoomOut(2)
        val center = componentCenter("mi-not")
        val beforeDistance = horizontalSpan()
        inputs.scroll(center.x, center.y, 0f, 1f, true)
        waitFor("Ctrl-scroll zooms in") { horizontalSpan() > beforeDistance + 3f }
        val anchored = componentCenter("mi-not")
        assertNear(center, anchored, 3f, "Ctrl-scroll anchor")
        val beforePan = componentCenter("mi-not")
        inputs.scroll(beforePan.x, beforePan.y, 1f, 1f, false)
        waitFor("unmodified scroll pans") { distance(componentCenter("mi-not"), beforePan) > 5f }
        val pan = componentCenter("mi-not")
        inputs.scroll(pan.x, pan.y, -1f, -1f, false)
        waitFor("inverse scroll restores pan") { distance(componentCenter("mi-not"), beforePan) <= 3f }
        assertSameDocument(original, export("scroll-after"))
    }

    private fun twoPointerZoomDoesNotEditTheCircuit() {
        mode(Messages.ActionSelect)
        val original = export("pinch-before")
        zoomOut(4)
        val center = componentCenter("mi-not")
        val beforeDistance = horizontalSpan()
        inputs.pinch(center.x, center.y, 70f, 140f, 200)
        waitFor("expanding touch pinch zooms in") { horizontalSpan() > beforeDistance + 3f }
        check(abs(horizontalSpan() / beforeDistance - 2f) < 0.06f) { "Touch pinch did not preserve the injected span ratio" }
        assertNear(center, componentCenter("mi-not"), 4f, "pinch anchor")
        assertSameDocument(original, export("pinch-after"))
    }

    private fun focusLossCancelsTheDrag() {
        mode(Messages.ActionSelect)
        val original = export("focusloss-before")
        val start = componentCenter("mi-not")
        val scale = scale(original)
        inputs.dragInterrupted(NativeMixedInput.Tool.TOUCH, start.x, start.y,
            start.x + 50 * scale, start.y + 20 * scale, 160) {
            focusLabel(fixture.notLabel)
            screenshot("focusloss-field-focused")
            closeInspector()
        }
        assertSameDocument(original, export("focusloss-after-up"))
    }

    private fun focusLossReleasesTheHeldButton() {
        mode(Messages.ActionSimulate)
        val low = outputStates("a11y_value_low")
        val high = outputStates("a11y_value_high")
        waitFor("button output starts low") { outputState() in low }
        val point = componentCenter("mi-button")
        inputs.hold(NativeMixedInput.Tool.TOUCH, point.x, point.y) {
            waitFor("held button output is high") { outputState() in high }
            screenshot("io-held-high")
            focusLabel(fixture.buttonLabel)
            screenshot("io-field-focused")
            closeInspector()
            // This check precedes the helper's UP, so UP cannot mask missing focus cleanup.
            waitFor("focus loss releases button before pointer UP") { outputState() in low }
            screenshot("io-released-before-up")
        }
        waitFor("button stays released after pointer UP") { outputState() in low }
        val project = export("io-release-after")
        check(project.mainCircuit.components.size == 2 && project.mainCircuit.wires.size == 1)
    }

    private fun focusLabel(label: String) {
        if (nodes(root("focusLabel:inspector-presence")).none { it.isEditable && it.text?.toString() == label }) {
            toolbar(Messages.A11yOpenProperties, listOf("editor-command-toolbar"))
        }
        val field = waitNode("selected component label field") {
            nodes(root("focusLabel:field")).firstOrNull { it.isEditable && it.text?.toString() == label }
        }
        check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        waitFor("label owns input focus") {
            nodes(root("focusLabel:owns-focus")).any { it.isEditable && it.isFocused && it.text?.toString() == label }
        }
    }

    private fun closeInspector() {
        findTag("editor-inspector-sheet")?.let { sheet ->
            val dismissible = nodes(sheet).filter { node ->
                node.isVisibleToUser && node.isEnabled &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
            }
            check(dismissible.size == 1) { "Inspector must expose exactly one native dismiss action" }
            check(dismissible.single().performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                "Native inspector dismiss was rejected"
            }
            waitFor("inspector dismissed") { findTag("editor-inspector-sheet") == null }
        }
        waitFor("canvas after inspector") { findTag("editor-canvas") != null }
    }

    private fun outputStates(valueKey: String): Set<String> = AppLanguage.entries.map { language ->
        message("a11y_explorer_component_state",
            "kind" to message("component_output"), "value" to message(valueKey)).resolve(language)
    }.toSet()

    private fun outputState(): String? {
        val node = findTag("canvas-component:mi-output") ?: return null
        return stateDescription(node)
    }

    private fun stateDescription(node: AccessibilityNodeInfo): String? =
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString()
        else node.extras.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY")?.toString()

    private fun openFixture() {
        val latch = CountDownLatch(1)
        var result = Activity.RESULT_CANCELED
        var preparedUri: String? = null
        var providerError: String? = null
        target.sendOrderedBroadcast(Intent(DocumentFixtureBootstrapReceiver.ACTION_PREPARE_CIRCUIT)
            .setComponent(ComponentName(instrumentation.context.packageName,
                "dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver"))
            .putExtra("xml", fixture.xml)
            .putExtra(DocumentFixtureBootstrapReceiver.EXTRA_DISPLAY_NAME, fixture.name + ".circ"),
            null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result = resultCode
                    preparedUri = resultData
                    providerError = getResultExtras(false)?.getString(DocumentFixtureBootstrapReceiver.RESULT_ERROR)
                    latch.countDown()
                }
            }, null, Activity.RESULT_CANCELED, null, null)
        check(latch.await(15, TimeUnit.SECONDS) && result == Activity.RESULT_OK) {
            "Fixture owner failed to prepare its source: ${providerError.orEmpty()}"
        }
        val authority = instrumentation.context.packageName + ".documents"
        val uri = Uri.parse(checkNotNull(preparedUri) { "Fixture owner returned no source URI" })
        val id = DocumentsContract.getDocumentId(uri)
        check(uri.scheme == "content" && uri.authority == authority && uri.query == null && uri.fragment == null &&
            uri.pathSegments == listOf("document", id) &&
            id.matches(Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}-${Regex.escape(fixture.name)}\\.circ"))) {
            "Fixture owner returned an unexpected source URI"
        }
        target.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setClassName(target.packageName, "dev.logisim.mobile.MainActivity")
            setDataAndType(uri, "application/x-logisim-circuit")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        val readiness = NativeFixtureReadiness()
        waitFor("fresh mixed-input fixture") { deadline ->
            val tree = readActionTree(root("openFixture:fresh-fixture"), "openFixture:fresh-fixture")
            val reportPresent = tree.complete && tree.models.indices.any { index ->
                tree.models[index].visible && matchesTag(tree.resourceIds[index], "editor-import-report")
            }
            diagnostic("import-report-check") {
                JSONObject().put("rootReadSequenceAtCall", tree.rootReadId).put("windowId", tree.windowId)
                    .put("evaluated", tree.complete).put("detected", if (tree.complete) reportPresent else JSONObject.NULL)
                    .put("closeAlreadyAttempted", readiness.closeAttempted())
            }
            val action = if (reportPresent && !readiness.closeAttempted()) resolveAction(labels(Messages.ActionClose), tree) else null
            if (reportPresent) diagnostic("import-report-close-resolution") {
                JSONObject().put("rootReadSequenceAtCall", tree.rootReadId).put("windowId", tree.windowId)
                    .put("evaluated", !readiness.closeAttempted()).put("resolved", action != null)
                    .put("complete", tree.complete).put("closeAlreadyAttempted", readiness.closeAttempted())
            }
            val labelEvaluated = tree.complete && !reportPresent
            val descriptions = if (labelEvaluated) tree.models.mapNotNull { it.description } else emptyList()
            val found = labelEvaluated && descriptions.any { it.contains(fixture.primaryLabel) }
            diagnostic("fixture-primary-label") {
                JSONObject().put("rootReadSequenceAtCall", tree.rootReadId).put("windowId", tree.windowId)
                    .put("expected", fixture.primaryLabel).put("evaluated", labelEvaluated)
                    .put("found", if (labelEvaluated) found else JSONObject.NULL)
                    .put("skipReason", if (!tree.complete) "INCOMPLETE_TREE" else if (reportPresent) "IMPORT_REPORT_PRESENT" else JSONObject.NULL)
                    .put("observedMiLabels", JSONArray(descriptions.filter { it.contains("MI_") }.distinct().take(8).map { it.take(256) }))
            }
            val outcome = readiness.step(NativeFixtureReadiness.Observation(tree.complete, reportPresent, action != null, found),
                { SystemClock.elapsedRealtime() }, deadline) {
                click(checkNotNull(action).clickTarget, "openFixture:Close") { SystemClock.elapsedRealtime() < deadline }
            }
            diagnostic("fixture-readiness") {
                JSONObject().put("rootReadSequenceAtCall", tree.rootReadId).put("windowId", tree.windowId)
                    .put("outcome", outcome.name).put("deadlineMillis", deadline)
                    .put("closeAlreadyAttempted", readiness.closeAttempted())
            }
            outcome == NativeFixtureReadiness.Outcome.READY
        }
        toolbar(Messages.A11yFitCircuit, listOf("editor-command-toolbar"))
        waitFor("visible fixture") { findTag("canvas-component:${fixture.primaryId}") != null }
        val loaded = export("fixture")
        check(loaded.mainCircuit.name == fixture.name)
        check(loaded.mainCircuit.components.size == fixture.componentCount && loaded.mainCircuit.wires.size == fixture.wireCount)
        if (!fixture.buttonFixture) assertConnectivity(loaded)
        loadedFixture = loaded
    }

    private fun export(name: String): CircuitProject {
        val clipboard = checkNotNull(target.getSystemService(ClipboardManager::class.java))
        val nonce = "mixed-input-" + UUID.randomUUID()
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("mixed input nonce", nonce)) }
        toolbar(Messages.A11yImportExportProject, listOf("editor-project-toolbar", "editor-command-toolbar"))
        waitNode("XML dialog") { nodes(root("export:XML-dialog")).firstOrNull { it.text?.toString() in labels(Messages.ProjectXmlTitle) } }
        clickAction(labels(Messages.ActionCopy))
        var text = ""
        waitFor("new complete XML clipboard") {
            instrumentation.runOnMainSync { text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(target)?.toString().orEmpty() }
            text != nonce && text.contains("</project>") && CircCodec.readProject(text) is CircProjectImportResult.Success
        }
        val project = (CircCodec.readProject(text) as CircProjectImportResult.Success).project
        check(project.mainCircuit.name == fixture.name) { "Export belongs to a different document" }
        File(directory, "${exportSequence++}-$name.circ").writeText(text)
        clickAction(labels(Messages.ActionClose))
        waitFor("editor returned from XML") { findTag("editor-canvas") != null }
        return project
    }

    private fun mode(key: String) {
        toolbar(key, listOf("editor-command-toolbar"))
        val names = labels(key)
        var ready: ResolvedNativeAction? = null
        val bar = waitNode("command toolbar") { findTag("editor-command-toolbar") }
        if (findAction(names, bar) != null) {
            waitFor("selected interaction mode") {
                ready = findTag("editor-command-toolbar")?.let { resolveAction(names, it) }
                ready?.selection == NativeActionTargets.Selection.ACTIVE
            }
        } else {
            click(waitNode("command overflow") { findTag("editor-command-toolbar-overflow") }, "mode:overflow")
            waitFor("selected interaction mode in menu") {
                ready = findTag("editor-command-toolbar-menu")?.let { resolveAction(names, it) }
                ready?.selection == NativeActionTargets.Selection.ACTIVE
            }
            clickAction(labels(Messages.ActionClose))
        }
        val state = checkNotNull(ready)
        results.getJSONObject(results.length() - 1).put("mode", JSONObject().put("key", key)
            .put("selection", state.selection.name).put("clickTarget", nodeRecord(state.clickTarget))
            .put("semanticNodes", JSONArray().also { array -> state.semanticNodes.forEach { array.put(nodeRecord(it)) } }))
        waitFor("canvas after selecting mode") { findTag("editor-canvas") != null }
    }

    private fun zoomOut(times: Int) {
        repeat(times) {
            val before = horizontalSpan()
            clickAction(labels(Messages.A11yZoomOut))
            waitFor("zoom out applied") { horizontalSpan() < before - 1f }
        }
    }

    private fun undoOnCanvas() {
        var point: NativeCanvasObservation.Point? = null
        var windowId = -1
        waitFor("Select mode and visible anchor before Undo") {
            val tree = readActionTree(root("undo:select-anchor"), "undo:select-anchor")
            val selectActive = resolveAction(labels(Messages.ActionSelect), tree)?.selection == NativeActionTargets.Selection.ACTIVE
            point = NativeCanvasObservation.undoFocus(canvasObservation(tree), selectActive)
            windowId = tree.windowId
            point != null
        }
        val anchor = checkNotNull(point)
        diagnostic("undo-focus-anchor") {
            JSONObject().put("windowId", windowId).put("selectActive", true).put("point", pointRecord(anchor))
        }
        inputs.tap(NativeMixedInput.Tool.MOUSE, anchor.x.toFloat(), anchor.y.toFloat())
        inputs.chordCtrl(KeyEvent.KEYCODE_Z)
    }

    private fun toolbar(key: String, tags: List<String>) {
        val names = labels(key)
        for (tag in tags) {
            findTag(tag)?.let { bar ->
                findAction(names, bar)?.let { click(it, "toolbar:$tag:$key"); return }
            }
        }
        for (tag in tags) {
            val overflow = findTag("$tag-overflow") ?: continue
            click(overflow, "toolbar:$tag:overflow")
            val menu = waitNode("$tag menu") { findTag("$tag-menu") }
            val target = findAction(names, menu)
            if (target != null) { click(target, "toolbar:$tag:menu:$key"); return }
            clickAction(labels(Messages.ActionClose))
        }
        error("Toolbar action is unavailable: $key")
    }

    private fun componentCenter(id: String): Point {
        val node = waitNode("component $id") { findTag("canvas-component:$id") }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0)
        return Point(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun scale(project: CircuitProject, horizontalPixels: Float = horizontalSpan()): Float {
        val components = project.mainCircuit.components.associateBy { it.id }
        fun worldCenter(id: String): Float = components.getValue(id).bounds().let { (it.left + it.right) / 2f }
        val worldSpan = worldCenter("mi-output") - worldCenter("mi-input")
        val actual = horizontalPixels / worldSpan
        check(actual.isFinite() && actual > 0.05f)
        return actual
    }

    private fun horizontalSpan(): Float = componentCenter("mi-output").x - componentCenter("mi-input").x

    private fun canvasObservation(tree: NativeTree): NativeCanvasObservation.Snapshot =
        NativeCanvasObservation.Snapshot(tree.windowId, tree.complete, tree.nodes.mapIndexed { index, node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            NativeCanvasObservation.Node(tree.resourceIds[index], node.windowId, tree.models[index].visible,
                tree.models[index].enabled, NativeCanvasObservation.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
        })

    private fun observedTargetBaseline(context: String): NativeCanvasObservation.Baseline {
        var observed: NativeCanvasObservation.Baseline? = null
        waitFor("observed $context target and viewport anchors") {
            val tree = readActionTree(root("$context:baseline"), "$context:baseline")
            observed = NativeCanvasObservation.capture(canvasObservation(tree), "canvas-component:mi-not")
            observed != null
        }
        return checkNotNull(observed)
    }

    private fun observedTargetPoint(before: NativeCanvasObservation.Baseline, proof: JSONObject, delta: GridPoint,
                                    pixelsPerWorld: Double, context: String): NativeCanvasObservation.Point {
        var point: NativeCanvasObservation.Point? = null
        proof.put("worldDelta", JSONObject().put("x", delta.x).put("y", delta.y))
            .put("pixelsPerWorld", pixelsPerWorld)
            .put("pointKind", if (delta == GridPoint(0, 0)) "REUSED_OBSERVATION" else "TRANSLATED_FROM_XML")
        waitFor("unchanged viewport for $context target") {
            val observed = canvasObservation(readActionTree(root("$context:target-viewport"), "$context:target-viewport"))
            point = NativeCanvasObservation.translate(before, observed, delta.x.toDouble(), delta.y.toDouble(), pixelsPerWorld)
            proof.put("after", frameRecord(NativeCanvasObservation.viewport(observed)))
                .put("lastObservedWindowId", observed.windowId).put("complete", observed.complete)
                .put("validated", point != null)
            point != null
        }
        return checkNotNull(point).also {
            proof.put("authorizedPoint", pointRecord(it))
                .put(if (delta == GridPoint(0, 0)) "reusedPoint" else "translatedPoint", pointRecord(it))
        }
    }

    private fun observedFocusLabelClearPoint(before: NativeCanvasObservation.Baseline, proof: JSONObject,
                                             verifiedDelta: GridPoint): NativeCanvasObservation.Point {
        check(verifiedDelta == GridPoint(0, 0)) { "Label-clear focus requires unchanged world position" }
        proof.put("worldDelta", JSONObject().put("x", 0).put("y", 0))
            .put("labelClearVerified", true).put("pointKind", "TRANSLATED_BY_COMMON_ANCHORS")
        var point: NativeCanvasObservation.Point? = null
        waitFor("focus label-clear common viewport translation") {
            val observed = canvasObservation(readActionTree(root("focus:label-clear-frame"), "focus:label-clear-frame"))
            val after = NativeCanvasObservation.viewport(observed)
            point = NativeCanvasObservation.focusAfterLabelClear(before, observed)
            proof.put("after", frameRecord(after)).put("lastObservedWindowId", observed.windowId)
                .put("complete", observed.complete).put("validated", point != null)
            if (after != null) proof.put("anchorDisplacements", JSONObject()
                .put("input", JSONObject().put("x", after.input.x - before.frame.input.x).put("y", after.input.y - before.frame.input.y))
                .put("output", JSONObject().put("x", after.output.x - before.frame.output.x).put("y", after.output.y - before.frame.output.y)))
            point != null
        }
        return checkNotNull(point).also {
            proof.put("authorizedPoint", pointRecord(it)).put("translatedPoint", pointRecord(it))
        }
    }

    private fun boundsRecord(bounds: NativeCanvasObservation.Bounds): JSONObject = JSONObject()
        .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)

    private fun pointRecord(point: NativeCanvasObservation.Point): JSONObject = JSONObject().put("x", point.x).put("y", point.y)

    private fun frameRecord(frame: NativeCanvasObservation.Frame?): Any = frame?.let {
        JSONObject().put("windowId", it.windowId).put("canvas", boundsRecord(it.canvas))
            .put("inputBounds", boundsRecord(it.inputBounds)).put("input", pointRecord(it.input))
            .put("outputBounds", boundsRecord(it.outputBounds)).put("output", pointRecord(it.output))
    } ?: JSONObject.NULL

    private fun baselineRecord(before: NativeCanvasObservation.Baseline): JSONObject = JSONObject()
        .put("frame", frameRecord(before.frame)).put("targetTag", "canvas-component:mi-not")
        .put("targetBounds", boundsRecord(before.targetBounds)).put("targetPoint", pointRecord(before.target))

    private fun assertPosition(project: CircuitProject, id: String, expected: GridPoint) {
        check(project.mainCircuit.components.single { it.id == id }.position == expected) {
            "Expected $id at $expected"
        }
    }

    private fun assertConnectivity(project: CircuitProject) {
        val document = project.mainCircuit
        val components = document.components.associateBy { it.id }
        val input = components.getValue("mi-input").outputPorts().single()
        val gateIn = components.getValue("mi-not").inputPorts().single()
        val gateOut = components.getValue("mi-not").outputPorts().single()
        val output = components.getValue("mi-output").inputPorts().single()
        val networks = CircuitNetworks(document)
        check(networks.notices.isEmpty())
        check(networks.bitNetwork(input, 0) == networks.bitNetwork(gateIn, 0))
        check(networks.bitNetwork(gateOut, 0) == networks.bitNetwork(output, 0))
        check(networks.bitNetwork(input, 0) != networks.bitNetwork(output, 0)) { "The move shorted both logic nets" }
    }

    private fun assertSameDocument(expected: CircuitProject, actual: CircuitProject) {
        check(CircCodec.writeProject(expected) == CircCodec.writeProject(actual)) { "The gesture changed the exported document" }
    }

    private fun labels(key: String) = AppLanguage.entries.map { message(key).resolve(it) }.toSet()

    private class WindowRead(val id: Int, val type: Int, val active: Boolean, val focused: Boolean) {
        var outcome = "SKIPPED_BY_POLICY"
        var rootPackage: String? = null
        var rootWindowId: Int? = null
        var startedAtMillis: Long? = null
        var elapsedMillis: Long? = null
        var error: String? = null

        fun record(): JSONObject = JSONObject().put("id", id).put("type", type)
            .put("active", active).put("focused", focused).put("outcome", outcome)
            .put("package", rootPackage ?: JSONObject.NULL).put("rootWindowId", rootWindowId ?: JSONObject.NULL)
            .put("startedAtMillis", startedAtMillis ?: JSONObject.NULL)
            .put("elapsedMillis", elapsedMillis ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)
    }

    private fun root(callsite: String): AccessibilityNodeInfo {
        val id = ++rootReadSequence
        val start = SystemClock.elapsedRealtime()
        val wait = currentWait?.copy()
        var activeEnd: Long? = null
        var activePackage: String? = null
        var activeWindowId: Int? = null
        var activeOutcome = "QUERY_STARTED"
        var outcome = "ERROR"
        var failurePhase: String? = "active-root"
        var failure: String? = null
        var windows: List<AccessibilityWindowInfo>? = null
        var windowsQueryStarted: Long? = null
        var windowsQueryElapsed: Long? = null
        var inspectedWindows = 0
        var acceptedWindow: Int? = null
        val observations = mutableListOf<WindowRead>()
        try {
            val active = automation.rootInActiveWindow
            activeEnd = SystemClock.elapsedRealtime()
            activePackage = active?.packageName?.toString()
            activeWindowId = active?.windowId
            activeOutcome = if (active == null) "NULL_ROOT" else "OTHER_PACKAGE"
            if (activePackage == target.packageName) {
                activeOutcome = "ACCEPTED"
                outcome = "ACCEPTED_ACTIVE_ROOT"
                failurePhase = null
                return checkNotNull(active)
            }
            active?.recycle()
            failurePhase = "window-enumeration"
            windowsQueryStarted = SystemClock.elapsedRealtime()
            windows = automation.windows
            windowsQueryElapsed = SystemClock.elapsedRealtime() - windowsQueryStarted
            for (window in windows) {
                inspectedWindows++
                val observed = WindowRead(window.id, window.type, window.isActive, window.isFocused)
                if (observations.size < 16) observations += observed
                if (observed.type != AccessibilityWindowInfo.TYPE_APPLICATION || !observed.focused) continue
                failurePhase = "window-root:${observed.id}"
                observed.startedAtMillis = SystemClock.elapsedRealtime()
                var candidate: AccessibilityNodeInfo? = null
                var accepted = false
                try {
                    // The same single acquisition supplies validation, diagnostics and return value.
                    candidate = window.root
                    observed.elapsedMillis = SystemClock.elapsedRealtime() - checkNotNull(observed.startedAtMillis)
                    observed.rootPackage = candidate?.packageName?.toString()
                    observed.rootWindowId = candidate?.windowId
                    observed.outcome = if (candidate == null) "NULL_ROOT" else "OTHER_PACKAGE"
                    if (observed.rootPackage == target.packageName) {
                        observed.outcome = "ACCEPTED"
                        acceptedWindow = observed.id
                        accepted = true
                        outcome = "ACCEPTED_FOCUSED_APPLICATION"
                        failurePhase = null
                        return checkNotNull(candidate)
                    }
                } catch (error: Throwable) {
                    observed.elapsedMillis = SystemClock.elapsedRealtime() - checkNotNull(observed.startedAtMillis)
                    observed.outcome = "ERROR"
                    observed.error = errorDescription(error)
                    throw error
                } finally {
                    if (!accepted) candidate?.recycle()
                }
            }
            outcome = "NO_ACCEPTED_ROOT"
            failurePhase = "acceptance"
            error("Target editor is not the focused application")
        } catch (error: Throwable) {
            failure = errorDescription(error)
            throw error
        } finally {
            val end = SystemClock.elapsedRealtime()
            diagnostic("root-read", wait) {
                JSONObject().put("readId", id).put("callsite", callsite.take(160))
                    .put("startedAtMillis", start).put("elapsedMillis", end - start)
                    .put("outcome", outcome).put("failurePhase", failurePhase ?: JSONObject.NULL)
                    .put("error", failure ?: JSONObject.NULL)
                    .put("activeRoot", JSONObject().put("outcome", activeOutcome)
                        .put("package", activePackage ?: JSONObject.NULL).put("windowId", activeWindowId ?: JSONObject.NULL)
                        .put("elapsedMillis", (activeEnd ?: end) - start)
                        .put("windowMetadata", "NOT_QUERIED_FROM_NODE"))
                    .put("windowsEnumeration", if (windowsQueryStarted == null) "NOT_REQUESTED" else if (windows == null) "ERROR" else "COMPLETE")
                    .put("windowsQueryStartedAtMillis", windowsQueryStarted ?: JSONObject.NULL)
                    .put("windowsQueryElapsedMillis", windowsQueryElapsed ?: JSONObject.NULL)
                    .put("windowsListed", windows?.size ?: JSONObject.NULL).put("windowsInspected", inspectedWindows)
                    .put("inspectionComplete", windows?.let { inspectedWindows == it.size } ?: false)
                    .put("windowRecordsOmitted", inspectedWindows - observations.size)
                    .put("acceptedWindowId", acceptedWindow ?: activeWindowId?.takeIf { outcome == "ACCEPTED_ACTIVE_ROOT" } ?: JSONObject.NULL)
                    .put("windows", JSONArray().also { array -> observations.forEach { array.put(it.record()) } })
            }
            windows?.forEach { runCatching { it.recycle() } }
        }
    }

    private fun nodes(node: AccessibilityNodeInfo, callsite: String = "nodes"): List<AccessibilityNodeInfo> {
        val start = SystemClock.elapsedRealtime()
        val result = mutableListOf<AccessibilityNodeInfo>()
        var missingChildren = 0
        var finished = false
        fun collect(current: AccessibilityNodeInfo) {
            result += current
            repeat(current.childCount) { index ->
                val child = current.getChild(index)
                if (child == null) missingChildren++ else collect(child)
            }
        }
        try {
            collect(node)
            finished = true
            return result
        } finally {
            diagnostic("tree-read") {
                JSONObject().put("callsite", callsite.take(160)).put("rootReadSequenceAtCall", rootReadSequence)
                    .put("windowId", node.windowId).put("nodeCount", result.size)
                    .put("missingChildren", missingChildren).put("complete", finished && missingChildren == 0)
                    .put("traversalFinished", finished)
                    .put("startedAtMillis", start).put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            }
        }
    }

    private fun matchesTag(node: AccessibilityNodeInfo, tag: String): Boolean = matchesTag(node.viewIdResourceName, tag)

    private fun matchesTag(resourceId: String?, tag: String): Boolean =
        resourceId == tag || resourceId?.endsWith(":id/$tag") == true

    private fun findTag(tag: String): AccessibilityNodeInfo? = nodes(root("findTag:$tag"), "findTag:$tag").firstOrNull {
        it.isVisibleToUser && matchesTag(it, tag)
    }

    private data class ResolvedNativeAction(
        val clickTarget: AccessibilityNodeInfo,
        val selection: NativeActionTargets.Selection,
        val semanticNodes: List<AccessibilityNodeInfo>,
    )

    private fun findAction(names: Set<String>, parent: AccessibilityNodeInfo = root("findAction:${names.joinToString("|").take(120)}")): AccessibilityNodeInfo? =
        resolveAction(names, parent)?.clickTarget

    private data class NativeTree(
        val nodes: List<AccessibilityNodeInfo>,
        val models: List<NativeActionTargets.Node>,
        val resourceIds: List<String?>,
        val complete: Boolean,
        val missingChildren: Int,
        val nodeLimitReached: Boolean,
        val rootReadId: Long,
        val windowId: Int,
    )

    private fun readActionTree(parent: AccessibilityNodeInfo, callsite: String): NativeTree {
        val start = SystemClock.elapsedRealtime()
        val rootReadId = rootReadSequence
        val windowId = parent.windowId
        val nativeNodes = mutableListOf<AccessibilityNodeInfo>()
        val actionNodes = mutableListOf<NativeActionTargets.Node>()
        val resourceIds = mutableListOf<String?>()
        var complete = true
        var traversalFinished = false
        var missingChildren = 0
        var nodeLimitReached = false
        fun collect(node: AccessibilityNodeInfo, parentIndex: Int) {
            if (nativeNodes.size >= 10_000) { complete = false; nodeLimitReached = true; return }
            val index = nativeNodes.size
            nativeNodes += node
            resourceIds += node.viewIdResourceName
            actionNodes += NativeActionTargets.Node(parentIndex, node.text?.toString(),
                node.contentDescription?.toString(), node.isVisibleToUser, node.isEnabled, node.isClickable,
                node.isSelected, node.isCheckable, node.isChecked)
            repeat(node.childCount) { childIndex ->
                val child = node.getChild(childIndex)
                if (child == null) { complete = false; missingChildren++ } else collect(child, index)
            }
        }
        try {
            collect(parent, -1)
            traversalFinished = true
            return NativeTree(nativeNodes, actionNodes, resourceIds, complete, missingChildren,
                nodeLimitReached, rootReadId, windowId)
        } finally {
            diagnostic("tree-read") {
                JSONObject().put("callsite", callsite.take(160))
                    .put("windowId", windowId).put("rootReadSequenceAtCall", rootReadId)
                    .put("nodeCount", nativeNodes.size).put("missingChildren", missingChildren)
                    .put("nodeLimitReached", nodeLimitReached).put("complete", complete && traversalFinished)
                    .put("traversalFinished", traversalFinished).put("startedAtMillis", start)
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            }
        }
    }

    private fun resolveAction(names: Set<String>, parent: AccessibilityNodeInfo): ResolvedNativeAction? =
        resolveAction(names, readActionTree(parent, "resolveAction:${names.joinToString("|").take(120)}"))

    private fun resolveAction(names: Set<String>, tree: NativeTree): ResolvedNativeAction? {
        val start = SystemClock.elapsedRealtime()
        var outcome = "ERROR"
        try {
            if (!tree.complete) { outcome = "INCOMPLETE"; return null }
            val action = NativeActionTargets.resolveAction(tree.models, names)
            if (action == null) { outcome = "UNRESOLVED"; return null }
            outcome = "RESOLVED"
            return ResolvedNativeAction(tree.nodes[action.clickTarget], action.selection,
                action.semanticNodes.map { tree.nodes[it] })
        } finally {
            diagnostic("action-resolution") {
                JSONObject().put("labels", names.joinToString("|").take(160))
                    .put("windowId", tree.windowId).put("rootReadSequenceAtCall", tree.rootReadId)
                    .put("nodeCount", tree.nodes.size).put("missingChildren", tree.missingChildren)
                    .put("nodeLimitReached", tree.nodeLimitReached).put("complete", tree.complete)
                    .put("reusedTree", true).put("outcome", outcome).put("startedAtMillis", start)
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            }
        }
    }

    private fun clickAction(names: Set<String>) =
        click(waitNode("action $names") { findAction(names) }, "clickAction:${names.joinToString("|").take(120)}")

    private fun click(node: AccessibilityNodeInfo, callsite: String = "click", beforeEmit: () -> Boolean = { true }) {
        val id = ++clickSequence
        val start = SystemClock.elapsedRealtime()
        var emitted = false
        var accepted: Boolean? = null
        var failure: String? = null
        var emissionBlocked = false
        try {
            check(node.isVisibleToUser && node.isEnabled && node.isClickable)
            diagnostic("action-click-call") {
                JSONObject().put("clickId", id).put("callsite", callsite.take(160))
                    .put("phase", "BEFORE_CALL")
                    .put("actionId", AccessibilityNodeInfo.ACTION_CLICK).put("rootReadSequenceAtCall", rootReadSequence)
                    .put("node", diagnosticNodeRecord(node)).put("startedAtMillis", start)
            }
            if (!beforeEmit()) { emissionBlocked = true; return }
            emitted = true
            accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            check(accepted == true) { "Native click was rejected" }
        } catch (error: Throwable) {
            failure = errorDescription(error)
            throw error
        } finally {
            diagnostic("action-click-result") {
                JSONObject().put("clickId", id).put("callsite", callsite.take(160))
                    .put("rootReadSequenceAtCall", rootReadSequence)
                    .put("emitted", emitted).put("accepted", accepted ?: JSONObject.NULL)
                    .put("emissionBlocked", emissionBlocked)
                    .put("error", failure ?: JSONObject.NULL).put("startedAtMillis", start)
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            }
        }
    }

    private fun waitNode(description: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(description) { result = find(); result != null }
        return checkNotNull(result)
    }

    private fun waitFor(description: String, ready: (Long) -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 15_000
        val previousWait = currentWait
        val context = WaitContext(++waitSequence, description.take(160))
        val state = NativeReadinessDiagnostics.WaitState()
        currentWait = context
        try {
            do {
                context.attempt = state.attempts() + 1
                try {
                    val returned = ready(deadline)
                    state.returned(returned, SystemClock.elapsedRealtime())
                    diagnostic("wait-attempt") { waitStateRecord(state) }
                    if (returned) return
                } catch (error: IllegalStateException) {
                    state.threw(errorDescription(error), SystemClock.elapsedRealtime())
                    diagnostic("wait-attempt") { waitStateRecord(state) }
                } catch (error: Throwable) {
                    state.threw(errorDescription(error), SystemClock.elapsedRealtime())
                    diagnostic("wait-attempt") { waitStateRecord(state) }
                    throw error
                }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Timed out waiting for $description. Last outcome: ${state.outcome()}. " +
                "Historical exception at attempt ${state.historicalExceptionAttempt()}: ${state.historicalException()}")
        } finally {
            val end = SystemClock.elapsedRealtime()
            runCatching {
                waitSummaries.record(waitStateRecord(state).put("waitId", context.id).put("description", context.description)
                    .put("startedAtMillis", start).put("elapsedMillis", end - start))
            }.onFailure { diagnosticErrors++ }
            currentWait = previousWait
        }
    }

    private fun errorDescription(error: Throwable): String =
        (error.javaClass.name + ": " + error.message.orEmpty()).take(512)

    private fun waitStateRecord(state: NativeReadinessDiagnostics.WaitState): JSONObject = JSONObject()
        .put("attempts", state.attempts()).put("lastOutcome", state.outcome().name)
        .put("lastOutcomeAtMillis", state.lastOutcomeAtMillis())
        .put("historicalException", state.historicalException() ?: JSONObject.NULL)
        .put("historicalExceptionAttempt", state.historicalExceptionAttempt())
        .put("historicalExceptionAtMillis", state.historicalExceptionAtMillis())

    private fun diagnostic(kind: String, wait: WaitContext? = currentWait?.copy(), record: () -> JSONObject) {
        runCatching {
            readiness.record(record().put("kind", kind).put("atElapsedMillis", SystemClock.elapsedRealtime())
                .put("waitId", wait?.id ?: JSONObject.NULL).put("waitAttempt", wait?.attempt ?: JSONObject.NULL)
                .put("waitDescription", wait?.description ?: JSONObject.NULL))
        }.onFailure { diagnosticErrors++ }
    }

    private fun diagnosticRecords(trace: NativeReadinessDiagnostics.Trace<JSONObject>, characterBudget: Int): JSONObject {
        val snapshot = trace.snapshot()
        val retained = mutableListOf<NativeReadinessDiagnostics.Entry<JSONObject>>()
        var used = 0
        for (entry in snapshot.records.asReversed()) {
            val size = entry.value.toString().length + 64
            if (used + size > characterBudget) break
            retained += entry
            used += size
        }
        retained.reverse()
        return JSONObject().put("capacity", snapshot.capacity).put("totalRecords", snapshot.totalRecords)
            .put("droppedRecords", snapshot.totalRecords - retained.size)
            .put("capacityDroppedRecords", snapshot.droppedRecords)
            .put("sizeDroppedRecords", snapshot.records.size - retained.size)
            .put("characterBudget", characterBudget).put("estimatedCharacters", used)
            .put("firstSequence", retained.firstOrNull()?.sequence ?: JSONObject.NULL)
            .put("lastSequence", retained.lastOrNull()?.sequence ?: JSONObject.NULL)
            .put("records", JSONArray().also { array -> retained.forEach {
                array.put(JSONObject().put("sequence", it.sequence).put("record", it.value))
            } })
    }

    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun snapshot(name: String) {
        runCatching { screenshot(name) }
        runCatching {
            val lines = mutableListOf<String>()
            fun dump(node: AccessibilityNodeInfo, path: String) {
                lines += nodeRecord(node).put("path", path).toString()
                repeat(node.childCount) { child -> node.getChild(child)?.let { dump(it, "$path/$child") } }
            }
            dump(root("snapshot:$name"), "0")
            File(directory, "$name-tree.txt").writeText(lines.joinToString("\n"))
        }
    }

    private fun nodeRecord(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        return JSONObject().put("resource-id", node.viewIdResourceName ?: JSONObject.NULL)
            .put("class", node.className?.toString() ?: JSONObject.NULL)
            .put("package", node.packageName?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("content-desc", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("bounds", bounds.toShortString()).put("windowId", node.windowId)
            .put("childCount", node.childCount)
            .put("selected", node.isSelected).put("checked", node.isChecked).put("checkable", node.isCheckable)
            .put("enabled", node.isEnabled).put("clickable", node.isClickable)
            .put("visible", node.isVisibleToUser).put("focused", node.isFocused)
            .put("stateDescription", stateDescription(node) ?: JSONObject.NULL)
            .put("actionIds", JSONArray().also { ids -> node.actionList.forEach { ids.put(it.id) } })
    }

    private fun diagnosticNodeRecord(node: AccessibilityNodeInfo): JSONObject = nodeRecord(node).also { record ->
        for (key in listOf("text", "content-desc", "stateDescription", "resource-id", "class", "package")) {
            if (!record.isNull(key)) record.put(key, record.getString(key).take(512))
        }
    }

    private fun persist(receipt: JSONObject) {
        runCatching {
            receipt.put("readinessDiagnostics", diagnosticRecords(readiness, 48 * 1024)
                .put("loggingErrors", diagnosticErrors).put("waitSummaries", diagnosticRecords(waitSummaries, 16 * 1024)))
        }.onFailure { diagnosticErrors++ }
        File(directory, "result.json").writeText(receipt.toString(2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun assertNear(expected: Point, actual: Point, tolerance: Float, description: String) {
        check(distance(expected, actual) <= tolerance) { "$description moved from $expected to $actual" }
    }

    private fun distance(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
    private data class Point(val x: Float, val y: Float)

    private class Fixture(val name: String) {
        val buttonFixture = name.startsWith("io-release_")
        val primaryId = if (buttonFixture) "mi-button" else "mi-not"
        val notLabel = "MI_${name}_NOT"
        val buttonLabel = "MI_${name}_BUTTON"
        val primaryLabel = if (buttonFixture) buttonLabel else notLabel
        val componentCount = if (buttonFixture) 2 else 3
        val wireCount = if (buttonFixture) 1 else 2
        val xml = if (buttonFixture) """<?xml version="1.0" encoding="UTF-8"?>
            <project source="3.8.0" version="1.0">
              <lib desc="#Wiring" name="0"/><lib desc="#I/O" name="1"/><main name="$name"/>
              <circuit name="$name">
                <comp lib="1" loc="(100,100)" name="Button" logisim-mobile-id="mi-button">
                  <a name="facing" val="east"/><a name="label" val="$buttonLabel"/>
                </comp>
                <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="mi-output">
                  <a name="facing" val="west"/><a name="type" val="output"/><a name="width" val="1"/>
                  <a name="appearance" val="classic"/><a name="label" val="MI_${name}_OUT"/>
                </comp>
                <wire from="(100,100)" to="(300,100)"/>
              </circuit>
            </project>""".trimIndent() else """<?xml version="1.0" encoding="UTF-8"?>
            <project source="3.8.0" version="1.0">
              <lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><main name="$name"/>
              <circuit name="$name">
                <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="mi-input">
                  <a name="facing" val="east"/><a name="type" val="input"/><a name="width" val="1"/>
                  <a name="appearance" val="classic"/><a name="label" val="MI_${name}_IN"/>
                </comp>
                <comp lib="1" loc="(200,100)" name="NOT Gate" logisim-mobile-id="mi-not">
                  <a name="facing" val="east"/><a name="width" val="1"/><a name="size" val="30"/>
                  <a name="out" val="01"/><a name="label" val="$notLabel"/>
                </comp>
                <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="mi-output">
                  <a name="facing" val="west"/><a name="type" val="output"/><a name="width" val="1"/>
                  <a name="appearance" val="classic"/><a name="label" val="MI_${name}_OUT"/>
                </comp>
                <wire from="(100,100)" to="(170,100)"/><wire from="(200,100)" to="(300,100)"/>
              </circuit>
            </project>""".trimIndent()
    }
}
