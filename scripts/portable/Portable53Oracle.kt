package dev.logisim.mobile.qualification

import com.cburch.logisim.Main as DesktopMain
import com.cburch.logisim.circuit.CircuitState
import com.cburch.logisim.circuit.SubcircuitFactory
import com.cburch.logisim.data.Location
import com.cburch.logisim.file.Loader
import com.cburch.logisim.proj.Project
import dev.logisim.mobile.StoredWorkspaceDocumentLinks
import dev.logisim.mobile.core.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

private val expectedOutputs = mapOf("out-first53" to "00001000", "out-second53" to "00000011")
private val expectedComponents = mapOf(
    "Top53" to setOf("source-first53", "instance-first53", "out-first53", "source-second53", "instance-second53", "out-second53"),
    "Bridge53" to setOf("bridge-in53", "bridge-cell53", "bridge-out53"),
    "Cell53" to setOf("cell-in53", "cell-out53"),
)
private val sourceMarkers = mapOf("main53" to "Top53", "bridge53" to "Bridge53", "cell53" to "Cell53")
private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun project(xml: String): CircuitProject = when (val read = CircCodec.readProject(xml)) {
    is CircProjectImportResult.Success -> read.project
    is CircProjectImportResult.Failure -> error(read.diagnostics.joinToString())
}
private fun resolved(bytes: ByteArray): Pair<CircuitBundle, ResolvedCircuitProject> {
    val bundle = when (val unpacked = CircBundleCodec.read(bytes)) {
        is CircBundleImportResult.Success -> unpacked.bundle
        is CircBundleImportResult.Failure -> error(unpacked.diagnostics.joinToString())
    }
    val loaded = when (val result = CircLibraryCodec.read(bundle)) {
        is CircLibraryImportResult.Success -> result.resolved
        is CircLibraryImportResult.Failure -> error(result.diagnostics.joinToString())
    }
    return bundle to loaded
}
private fun documentMap(project: CircuitProject): Map<String, CircuitDocument> {
    check(project.circuits.size == 3) { "Expected 3 referenced definitions, found ${project.circuits.size}" }
    return expectedComponents.mapValues { (name, ids) ->
        project.circuits.single { circuit -> circuit.components.map { it.id }.toSet() == ids }
            .also { check(it.components.size == ids.size) { "Duplicate components in $name" } }
    }
}
private fun verifyProject(project: CircuitProject): Map<String, Any?> {
    val documents = documentMap(project)
    val top = documents.getValue("Top53")
    val bridge = documents.getValue("Bridge53")
    val cell = documents.getValue("Cell53")
    check(project.mainCircuit.id == top.id && top.id == "circuit-top53")
    check(project.circuits.sumOf { it.components.size } == 11)
    check(project.circuits.sumOf { it.wires.size } == 7)
    val instances = top.components.filter { it.kind == ComponentKind.SUBCIRCUIT }
    check(instances.map { it.id }.toSet() == setOf("instance-first53", "instance-second53"))
    check(instances.all { it.circuitName == bridge.name }) { "The two top instances no longer share Bridge53" }
    check(instances.associate { it.id to it.label } == mapOf("instance-first53" to "FIRST53", "instance-second53" to "SECOND53"))
    check(bridge.components.single { it.id == "bridge-cell53" }.circuitName == cell.name)
    val state = CircuitSimulator().settle(project)
    check(!state.oscillating) { state.diagnostics.joinToString() }
    check(state.circuitStates.size == 4) { "Expected two Bridge53 and two Cell53 runtime instances" }
    val outputs = expectedOutputs.mapValues { (id, expected) ->
        state.vectorValues[id]?.display.also { check(it == expected) { "$id expected $expected, found $it" } }
    }
    return mapOf("circuitIds" to documents.mapValues { it.value.id }, "definitions" to 3,
        "components" to 11, "wires" to 7, "runtimeInstances" to state.circuitStates.size,
        "componentIds" to documents.mapValues { it.value.components.map { component -> component.id }.sorted() },
        "topLabels" to instances.map { it.label }.sorted(), "outputs" to outputs)
}
private fun verifyBundle(bytes: ByteArray, fixture: File, expectOpaque: Boolean): Pair<CircuitBundle, Map<String, Any?>> {
    val (bundle, loaded) = resolved(bytes)
    check(loaded.sources.size == 3)
    val byMarker = loaded.sources.values.associateBy { requireNotNull(it.root.attributes["qualification-source"]) }
    check(byMarker.keys == sourceMarkers.keys) { "Source metadata or separation was lost: ${byMarker.keys}" }
    for ((marker, circuitName) in sourceMarkers) {
        check(byMarker.getValue(marker).circuits.keys == setOf(circuitName))
        val main = byMarker.getValue(marker).root.children.single { it.name == "main" }
        val expected = mapOf("main53" to "main53", "bridge53" to "bridge-main53", "cell53" to "cell-main53").getValue(marker)
        check(main.attributes["marker"] == expected) { "Source main metadata was lost in $marker" }
    }
    check(byMarker.getValue("main53").root.attributes["qualification"] == "portable53-v1")
    check(byMarker.getValue("main53").dependencies.values.toSet() == setOf(byMarker.getValue("bridge53").source.id))
    check(byMarker.getValue("bridge53").dependencies.values.toSet() == setOf(byMarker.getValue("cell53").source.id))
    check(byMarker.getValue("cell53").dependencies.isEmpty())
    val opaque = linkedMapOf<String, String>()
    for ((entry, reference) in mapOf("readme53.txt" to "readme53.txt", "library/unreferenced53.circ" to "unreferenced53.circ")) {
        val content = bundle.files[entry]
        if (expectOpaque) check(content != null && content.contentEquals(File(fixture, "opaque/$reference").readBytes())) { "Opaque entry $entry was lost or changed" }
        if (content != null) opaque[entry] = sha(content)
    }
    val summary = verifyProject(loaded.project) + mapOf<String, Any?>("sourceMarkers" to byMarker.keys.sorted(),
        "sourcePaths" to byMarker.mapValues { it.value.source.id }, "zipEntries" to bundle.files.keys.sorted(),
        "opaqueEntries" to opaque, "archiveSha256" to sha(bytes))
    return bundle to summary
}
private fun desktop(bundle: CircuitBundle): Map<String, Any?> {
    DesktopMain.headless = true
    val directory = Files.createTempDirectory("portable53-desktop-")
    var desktopProject: Project? = null
    try {
        for ((name, bytes) in bundle.files) {
            val destination = directory.resolve(name).normalize()
            check(destination.startsWith(directory))
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
        }
        val file = Loader(null).openLogisimFile(directory.resolve(bundle.mainFile).toFile()) ?: error("Desktop rejected the bundle main")
        desktopProject = Project(file)
        val top = file.mainCircuit
        check(top.name == "Top53" && top.nonWires.size == 6)
        val instances = top.nonWires.filter { it.factory is SubcircuitFactory }
        check(instances.size == 2)
        val bridge = (instances[0].factory as SubcircuitFactory).subcircuit
        check(bridge === (instances[1].factory as SubcircuitFactory).subcircuit) { "Desktop split the shared Bridge53 definition" }
        check(bridge.name == "Bridge53" && bridge.nonWires.size == 3)
        val nested = bridge.nonWires.single { it.factory is SubcircuitFactory }
        val cell = (nested.factory as SubcircuitFactory).subcircuit
        check(cell.name == "Cell53" && cell.nonWires.size == 2)
        val state = CircuitState.createRootState(desktopProject, top, Thread.currentThread())
        state.propagator.propagate()
        check(!state.propagator.isOscillating)
        val outputs = listOf("out-first53" to 100, "out-second53" to 300).associate { (id, y) ->
            val value = state.getValue(Location.create(300, y, true))
            val bits = buildString { for (bit in value.width - 1 downTo 0) append(when (value.get(bit)) {
                com.cburch.logisim.data.Value.TRUE -> '1'; com.cburch.logisim.data.Value.FALSE -> '0'; else -> '?' }) }
            check(bits == expectedOutputs.getValue(id)) { "Desktop $id expected ${expectedOutputs[id]}, found $bits" }
            id to bits
        }
        val jar = File(DesktopMain::class.java.protectionDomain.codeSource.location.toURI())
        return mapOf("status" to "PASSED", "jarSha256" to sha(jar.readBytes()),
            "definitions" to listOf(top.name, bridge.name, cell.name), "topInstances" to 2, "outputs" to outputs)
    } finally {
        desktopProject?.simulator?.shutDown()
        directory.toFile().deleteRecursively()
    }
}
private class ReadOnlyStorage(private val directory: File) : WorkspaceCatalogStorage {
    override fun <T> withExclusiveAccess(action: () -> T): T = action()
    override fun read(name: String): String? {
        check(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in setOf(".", ".."))
        return File(directory, name).takeIf(File::isFile)?.readText()
    }
    override fun writeAtomically(name: String, contents: String): Unit = error("Qualification never modifies native evidence")
    override fun listNames(): List<String> = directory.listFiles()?.filter(File::isFile)?.map(File::getName).orEmpty()
}
private data class Snapshot(val id: String?, val ids: Set<String>, val document: WorkspaceDocument?,
    val source: WorkspaceAttachment?, val windows: Map<String, String?>)
private fun snapshot(directory: File): Snapshot {
    val storage = ReadOnlyStorage(directory)
    val repository = WorkspaceCatalogRepository(storage, validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
    val catalog = repository.list(includeDeleted = true)
    check(!catalog.readOnly && catalog.notices.isEmpty()) { catalog.diagnostics.joinToString() }
    val id = catalog.activeProjectId
    val document = id?.let { active -> when (val opened = repository.open(active)) {
        is WorkspaceOpenResult.Success -> opened.document.also { check(!it.readOnly && it.notices.isEmpty()) }
        else -> error("Active workspace did not open: $opened")
    } }
    val source = id?.let { active -> when (val read = repository.readAttachment(active, "portable-source")) {
        is WorkspaceAttachmentReadResult.Success -> read.attachment
        WorkspaceAttachmentReadResult.Missing -> null
        is WorkspaceAttachmentReadResult.Failure -> error(read.message)
    } }
    val links = StoredWorkspaceDocumentLinks(storage)
    val parser = StoredWorkspaceDocumentLinks::class.java.getDeclaredMethod("read", String::class.java).apply { isAccessible = true }
    val windows = storage.listNames().filter { it.startsWith("window-") && it.endsWith(".workspace-link") }.associate { name ->
        @Suppress("UNCHECKED_CAST") val values = parser.invoke(links, storage.read(name)!!) as List<String>
        check(values.size == 2)
        values[0] to links.windowProject(values[0])?.value
    }
    return Snapshot(id?.value, catalog.projects.map { it.id.value }.toSet(), document, source, windows)
}
private fun workspace(before: File, imported: File, restarted: File, reopened: File, fixture: File, expectOpaque: Boolean): Map<String, Any?> {
    val a = snapshot(before); val b = snapshot(imported); val c = snapshot(restarted); val d = snapshot(reopened)
    val id = requireNotNull(b.id)
    check(id !in a.ids) { "Import reused an existing workspace identity" }
    check(c.id == id) { "Restart mounted another workspace" }
    check(b.ids.containsAll(a.ids)) { "Import removed an earlier workspace" }
    check(c.ids.containsAll(b.ids)) { "Restart removed an earlier workspace" }
    val reopenedId = requireNotNull(d.id)
    check(reopenedId !in a.ids + b.ids + c.ids) { "Reopening reused an existing workspace identity" }
    check(d.ids.containsAll(a.ids + b.ids + c.ids)) { "Reopening removed an earlier workspace" }
    check(!File(imported, "external-$id.workspace-link").exists() && !File(restarted, "external-$id.workspace-link").exists()) {
        "The portable copy inherited a native editable-file association"
    }
    check(!File(reopened, "external-$reopenedId.workspace-link").exists()) { "The reopened copy inherited a native editable-file association" }
    check(id in b.windows.values && id in c.windows.values && reopenedId in d.windows.values) { "Window routing does not point at the portable workspace" }
    val original = requireNotNull(b.source) { "Imported workspace has no portable-source attachment" }
    val restored = requireNotNull(c.source) { "Restarted workspace has no portable-source attachment" }
    check(original.mimeType == "application/x-logisim-bundle" && restored.mimeType == original.mimeType)
    check(original.bytes.contentEquals(restored.bytes)) { "Source archive changed across restart" }
    val (_, firstArchive) = verifyBundle(original.bytes, fixture, expectOpaque)
    val (_, restartedArchive) = verifyBundle(restored.bytes, fixture, expectOpaque)
    val initialProject = verifyProject(project(requireNotNull(b.document).xml))
    val restoredProject = verifyProject(project(requireNotNull(c.document).xml))
    check(initialProject["circuitIds"] == firstArchive["circuitIds"] && restoredProject["circuitIds"] == restartedArchive["circuitIds"])
    check(initialProject["circuitIds"] == restoredProject["circuitIds"])
    val reopenedSource = requireNotNull(d.source) { "Reopened copy has no portable-source attachment" }
    check(reopenedSource.mimeType == original.mimeType)
    val (_, reopenedArchive) = verifyBundle(reopenedSource.bytes, fixture, expectOpaque)
    val reopenedProject = verifyProject(project(requireNotNull(d.document).xml))
    check(reopenedProject["circuitIds"] == restoredProject["circuitIds"] && reopenedArchive["circuitIds"] == reopenedProject["circuitIds"])
    return mapOf("status" to "STORAGE_SNAPSHOTS_VALIDATED", "beforeProjectId" to a.id,
        "importedProjectId" to id, "restartedProjectId" to c.id, "reopenedProjectId" to reopenedId,
        "catalogProjectIds" to mapOf("before" to a.ids.sorted(), "imported" to b.ids.sorted(), "restarted" to c.ids.sorted(), "reopened" to d.ids.sorted()),
        "freshWorkspace" to true, "freshReopenedWorkspace" to true, "nativeReferenceAbsent" to true, "sourceArchiveSha256" to sha(original.bytes),
        "sourceArchiveBytes" to original.size, "reopenedSourceArchiveSha256" to sha(reopenedSource.bytes),
        "importedWindows" to b.windows, "restartedWindows" to c.windows, "reopenedWindows" to d.windows,
        "project" to restoredProject, "sourceArchive" to restartedArchive,
        "scope" to "Catalog/attachment snapshots only; native UI execution and process restart must be recorded separately")
}
private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.flatMap { when (it) {
        '\\' -> "\\\\".toList(); '"' -> "\\\"".toList(); '\n' -> "\\n".toList(); '\r' -> "\\r".toList(); '\t' -> "\\t".toList()
        else -> if (it.code < 32) "\\u${it.code.toString(16).padStart(4, '0')}".toList() else listOf(it)
    } }.joinToString("") + "\""
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    is Number, is Boolean -> value.toString()
    else -> error("Unsupported report value ${value::class}")
}
fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { _, error -> error.printStackTrace(); System.exit(1) }
    val report: Map<String, Any?> = when (args.firstOrNull()) {
        "artifact" -> {
            require(args.size in 6..7)
            val bytes = File(args[1]).readBytes()
            val (bundle, summary) = verifyBundle(bytes, File(args[3]), args[4].toBooleanStrict())
            if (args.size == 7) {
                val (_, peer) = verifyBundle(File(args[6]).readBytes(), File(args[3]), args[4].toBooleanStrict())
                check(summary["circuitIds"] == peer["circuitIds"] && summary["componentIds"] == peer["componentIds"]) { "Identities changed between exports of the same workspace" }
            }
            mapOf("status" to if (args[5] == "fixture") "PREPARATION_ARTIFACT_VALIDATED" else "APP_EXPORT_ARTIFACT_VALIDATED",
                "origin" to args[5], "artifactPath" to File(args[1]).canonicalPath, "mobile" to summary, "desktop" to desktop(bundle))
        }
        "workspace" -> {
            require(args.size == 8)
            workspace(File(args[1]), File(args[2]), File(args[3]), File(args[4]), File(args[6]), args[7].toBooleanStrict())
        }
        else -> error("Usage: artifact bundle output.json fixture-dir expect-opaque origin [peer] | workspace before imported restarted reopened output.json fixture-dir expect-opaque")
    }
    val output = File(if (args[0] == "artifact") args[2] else args[5])
    output.parentFile?.mkdirs(); output.writeText(json(report) + "\n")
    println(output.canonicalPath)
    System.exit(0)
}
