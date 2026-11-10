package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlinx.coroutines.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*

class CatalogWorkspaceRuntimeTest {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private class Disk(private val uiThread: Long) : WorkspaceCatalogStorage {
        val directory: Path = Files.createTempDirectory("workspace-actor-")
        private val lock = ReentrantLock()
        @Volatile var nextCatalogGate: Gate? = null
        @Volatile var failNextCatalog = false
        @Volatile var blockedReadName: String? = null
        @Volatile var nextReadGate: Gate? = null
        override fun <T> withExclusiveAccess(action: () -> T): T {
            assertNotEquals(uiThread, Thread.currentThread().id, "storage transaction ran on UI")
            return lock.withLock(action)
        }
        override fun read(name: String): String? {
            assertNotEquals(uiThread, Thread.currentThread().id, "storage read ran on UI")
            if (name == blockedReadName) nextReadGate?.let { gate ->
                nextReadGate = null; gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "UI did not release the deliberately slow read" }
            }
            return directory.resolve(name).takeIf { Files.exists(it) }?.let { Files.readString(it) }
        }
        override fun listNames(): List<String> {
            assertNotEquals(uiThread, Thread.currentThread().id, "storage listing ran on UI")
            return Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() }
        }
        override fun writeAtomically(name: String, contents: String) {
            assertNotEquals(uiThread, Thread.currentThread().id, "storage write ran on UI")
            if (name == WorkspaceCatalogRepository.CATALOG_FILE) {
                nextCatalogGate?.let { gate ->
                    nextCatalogGate = null; gate.entered.countDown()
                    check(gate.release.await(5, TimeUnit.SECONDS)) { "UI did not release the deliberately slow write" }
                }
                if (failNextCatalog) { failNextCatalog = false; error("interrupted catalog commit") }
            }
            val temporary = Files.createTempFile(directory, "atomic-", ".tmp")
            Files.writeString(temporary, contents)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        fun remove() { Files.list(directory).use { paths -> paths.forEach(Files::delete) }; Files.delete(directory) }
    }
    private class Native(
        private val uiThread: Long, override val supportsEditInPlace: Boolean = true,
    ) : DocumentActions {
        var retained: DocumentReference? = null
        var pendingWrite: ((DocumentWriteResult) -> Unit)? = null
        var savedText: String? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { assertEquals(uiThread, Thread.currentThread().id); retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) = Unit
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) {
            assertEquals(uiThread, Thread.currentThread().id); savedText = text; pendingWrite = onResult
        }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) = create(reference.name, text, onResult)
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun finish(result: DocumentWriteResult) { val callback = pendingWrite; pendingWrite = null; callback?.invoke(result) }
    }
    private class Window(val runtime: CatalogWorkspaceRuntime, val control: EditorSessionControl) {
        var xml = ""
        var identity = 0L
        var enabled = false
        var active = false
        var pauses = 0
        suspend fun attach() {
            until { runtime.state.workspace != null }
            val workspace = runtime.state.workspace!!
            if (control.isAttached) control.detach()
            xml = workspace.editorXml!!; identity = 0
            control.attach({ pauses++ }, { active = it }, { WorkspaceViewState() }, {},
                identity = { identity }, exportXml = { xml }, documentWritable = { true },
                setInteractionEnabled = { enabled = it })
            runtime.editorAttached(workspace.editorMount)
            until { !runtime.state.busy }
            assertTrue(enabled)
        }
        fun replace(next: String) {
            val previous = xml; val oldIdentity = identity
            xml = next; identity++
            runtime.projectReplaced(runtime.state.workspace!!.editorMount,
                EditorProjectReplacement(previous, next, oldIdentity, identity))
        }
        suspend fun open(id: WorkspaceProjectId) {
            var result: Boolean? = null
            runtime.openProject(id) { result = it }
            until { result != null }
            assertTrue(result!!)
            attach()
        }
    }
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"
    private fun reference() = DocumentReference("opaque-A", "A.circ", true, "sha-A", DocumentOpenMode.EDIT_IN_PLACE)
    private companion object {
        suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(1) }
    }

    @Test fun generatedReplacementKeepsTheOriginalUntilStorageAcceptsIt() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "analysis-target", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            val original = CircuitProject(listOf(CircuitDocument("main"), CircuitDocument("Generated")))
            runtime.start(CircCodec.writeProject(original))
            window.attach()
            runtime.flush()
            runtime.awaitPersistence()
            val workspace = runtime.state.workspace!!
            val editor = EditorState(window.xml)
            val destination = editor.project.circuit("Generated")!!
            val table = TruthTable(listOf("A"), listOf("Y"), listOf(listOf(TruthCell.ONE), listOf(TruthCell.ZERO)))
            val generated = AnalysisCircuitGenerator.generate(table, "new")
            val added = installGeneratedCircuit(editor.project, generated, "Custom name")
            assertNotNull(added.circuit("Custom name"))
            assertEquals(destination, added.circuit(destination.name))
            assertFailsWith<IllegalArgumentException> { installGeneratedCircuit(editor.project, generated, destination.name) }
            val next = installGeneratedCircuit(editor.project, generated, destination.name, destination)
            val edited = next.circuit(destination.name)!!
            assertEquals(destination.id, edited.id)
            assertTrue(AnalysisCircuitGenerator.verify(table, edited))
            assertEquals(editor.project.mainCircuit, next.mainCircuit)
            val replacement = CircCodec.writeProject(next)

            suspend fun submit(): Boolean {
                var result: Boolean? = null
                runtime.commitEditorChange(workspace.editorMount, window.identity, replacement) { saved ->
                    if (saved) {
                        editor.applyPersistedProject(next)
                        window.xml = replacement
                    }
                    result = saved
                }
                until { result != null }
                return result!!
            }

            disk.failNextCatalog = true
            assertFalse(submit())
            assertEquals(destination, editor.project.circuit(destination.name))
            assertEquals(workspace.session.document!!.xml, runtime.state.workspace!!.session.document!!.xml)
            assertFalse(runtime.state.workspace!!.session.dirty)
            assertTrue(submit())
            assertEquals(edited, editor.project.circuit(destination.name))
            val storedXml = runtime.state.workspace!!.session.document!!.xml
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(storedXml))
            assertTrue(AnalysisCircuitGenerator.verify(table, reopened.project.circuit(destination.name)!!))
            assertEquals(workspace.editorMount, runtime.state.workspace!!.editorMount)
            editor.undo()
            assertEquals(destination, editor.project.circuit(destination.name))
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun explicitExtensionRegistryIsUsedForPersistenceAndReopening() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "gray-extension", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach()
            val original = runtime.state.workspace!!.editorProjectId!!
            val reference = ExtensionReferenceScanner.inspect(grayLegacyProjectXml).jarLibraries.single()
            val catalog = installedGrayCatalog().withLegacyLibrary(reference, GrayCodeExtension.provider.manifest.id)
            runtime.updateExtensionRegistry(catalog.registry)
            window.replace(grayLegacyProjectXml)
            runtime.awaitPersistence()
            val migrated = assertNotNull(runtime.state.workspace!!.editorProjectId)
            assertNotEquals(original, migrated)
            assertFalse(runtime.state.workspace!!.readOnly)
            window.open(original)
            window.open(migrated)
            assertEquals(grayLegacyProjectXml, window.xml)
            val state = EditorState(window.xml, catalog.registry)
            assertTrue(state.documentWritable)
            assertEquals(3, state.document.components.size)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun slowCommitLeavesUiResponsiveAndOrdersReplacementBeforeItsLaterEdit() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "ui", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            val gate = Gate(); disk.nextCatalogGate = gate
            window.replace(xml("B"))
            assertNull(runtime.state.workspace!!.editorProjectId)
            until { gate.entered.count == 0L }
            var heartbeat = false
            launch { delay(1); heartbeat = true }
            until { heartbeat }
            assertEquals(1L, gate.release.count)
            window.xml = xml("B-latest")
            runtime.documentChanged(runtime.state.workspace!!.editorMount, window.xml)
            gate.release.countDown()
            runtime.awaitPersistence()
            val b = runtime.state.workspace!!.editorProjectId!!
            assertNotEquals(a, b)
            assertEquals(xml("B-latest"), runtime.state.workspace!!.session.xml)
            window.open(a); assertEquals(xml("A"), runtime.state.workspace!!.session.xml)
            window.open(b); assertEquals(xml("B-latest"), runtime.state.workspace!!.session.xml)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun anEarlierAttachmentCannotReleaseTheGateOfAPendingSwitch() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val replacementGate = Gate(); val switchGate = Gate()
        var armSwitchGate = false
        var original: WorkspaceProjectId? = null
        val runtime = CatalogWorkspaceRuntime(disk, "attachment-order", control, deliveryContext = coroutineContext,
            onStateChanged = { state ->
                val id = state.workspace?.editorProjectId
                if (armSwitchGate && id != null && id != original) {
                    armSwitchGate = false
                    disk.nextCatalogGate = switchGate
                }
            })
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach()
            original = runtime.state.workspace!!.editorProjectId!!
            disk.nextCatalogGate = replacementGate
            armSwitchGate = true
            window.replace(xml("B"))
            until { replacementGate.entered.count == 0L }
            runtime.editorAttached(runtime.state.workspace!!.editorMount)
            var opened: Boolean? = null
            runtime.openProject(original!!) { opened = it }
            assertFalse(window.enabled)
            replacementGate.release.countDown()
            until { switchGate.entered.count == 0L }
            assertTrue(runtime.state.busy)
            assertFalse(window.enabled, "an attachment preceding SWITCH must not re-enable the old editor")
            switchGate.release.countDown()
            until { opened != null }; assertTrue(opened!!)
            window.attach()
            assertEquals(original, runtime.state.workspace!!.editorProjectId)
            assertEquals(xml("A"), window.xml)
        } finally {
            replacementGate.release.countDown(); switchGate.release.countDown()
            runtime.close(); runtime.awaitClosed(); disk.remove()
        }
    }

    @Test fun newProjectsCopyTheCurrentTemplateAndKeepItsOptionsIndependent() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id)
        val templates = ProjectTemplateStore(disk, ComponentCatalog.registry)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "templates", control, deliveryContext = coroutineContext, projectTemplates = templates)
        val window = Window(runtime, control)
        val template = """<project source="2.7.1" version="1.0">
            <lib name="0" desc="#Wiring"/>
            <main name="Starter"/>
            <options><a name="simlimit" val="2048"/><a name="tickFrequencyHz" val="12.5"/></options>
            <circuit name="Helper"/>
            <circuit name="Starter"><comp lib="0" loc="(40,40)" name="Pin"/></circuit>
            </project>"""
        suspend fun create(name: String, circuitAttributes: Map<String, String> = emptyMap()): WorkspaceProjectId {
            var created: Boolean? = null
            runtime.createProject(name, circuitAttributes = circuitAttributes) { created = it }
            until { created != null }
            assertEquals(true, created)
            window.attach()
            return assertNotNull(runtime.state.workspace!!.editorProjectId)
        }
        try {
            runtime.start(xml("Existing"))
            window.attach()
            withContext(Dispatchers.Default) {
                templates.install(("\uFEFF" + template).encodeToByteArray())
                assertFailsWith<IllegalArgumentException> { templates.install("<not-a-project/>".encodeToByteArray()) }
                assertEquals(template, ProjectTemplateStore(disk, ComponentCatalog.registry).read())
            }
            val first = create("First copy")
            assertEquals(template, window.xml)
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(window.xml)).project
            assertEquals("Starter", project.mainCircuitName)
            assertEquals(listOf("Helper", "Starter"), project.circuits.map { it.name })
            assertEquals(1, project.mainCircuit.components.size)
            assertEquals(2048, SimulationProjectOptions.read(project).propagationLimit)
            assertEquals(12.5, SimulationProjectOptions.read(project).tickFrequencyHz)
            val editor = EditorState(initialXml = null, defaultTickFrequencyHz = 99.0)
            editor.newDocument(withContext(Dispatchers.Default) { templates.readProject() })
            assertEquals("Starter", editor.project.mainCircuitName)
            assertEquals(12.5, SimulationProjectOptions.read(editor.project).tickFrequencyHz)

            val edited = template.replace("(40,40)", "(60,40)")
            window.xml = edited
            runtime.documentChanged(runtime.state.workspace!!.editorMount, edited)
            runtime.flush()
            runtime.awaitPersistence()
            val second = create("Second copy")
            assertNotEquals(first, second)
            assertEquals(template, window.xml)
            window.open(first)
            assertEquals(edited, window.xml)

            withContext(Dispatchers.Default) { templates.remove() }
            // #313: without a template, the new main circuit takes the circuit creation defaults.
            val classic = CreationDefaults(circuitAppearance = "classic", namedCircuitBoxesFixedSize = false).circuitAttributes()
            create("Without a template", classic)
            val empty = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(window.xml)).project
            assertEquals("main", empty.mainCircuitName)
            assertTrue(empty.mainCircuit.components.isEmpty())
            assertEquals(classic, empty.mainCircuit.attributes.filterKeys { it in classic })
            window.open(second)
            assertEquals(template, window.xml)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun aLoadingMountNeverCapturesTheDetachedPreviousEditorsXml() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "readiness", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach()
            control.detach()
            var created: Boolean? = null
            runtime.createProject("B") { created = it }
            until { created != null }; assertTrue(created!!)
            val mount = runtime.state.workspace!!.editorMount
            runtime.editorAttached(mount) // App is still preparing its new VM.
            assertFalse(control.isAttached)
            runtime.foregroundChanged(false)
            runtime.awaitPersistence()
            assertNotEquals(xml("A"), runtime.state.workspace!!.session.xml)
            runtime.foregroundChanged(true)
            window.attach()
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(runtime.state.workspace!!.session.xml!!)).project
            assertEquals("main", project.mainCircuitName)
            assertTrue(window.enabled)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun preparedCopyAnnouncesOnlyAfterDurabilityAndTheNewEditorAttachmentAck() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui); val native = Native(ui)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "prepared", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            val origin = runtime.state.workspace!!.editorProjectId!!
            val oldMount = runtime.state.workspace!!.editorMount
            val source = WorkspaceAttachment("source.zip", "application/zip", byteArrayOf(1, 2, 3, 4))
            var result: WorkspacePreparedCopyResult? = null
            runtime.openPreparedCopy("Portable", xml("Portable"), mapOf("portable-source" to source), oldMount, window.identity) { result = it }
            until { runtime.state.workspace!!.editorProjectId != origin }
            val stored = runtime.state.workspace!!
            assertNotNull(stored.session.document!!.attachments["portable-source"])
            assertNull(result, "catalog commit is not the editor readiness acknowledgement")
            assertTrue(runtime.state.busy)
            assertIs<WorkspaceAttachmentReadResult.Failure>(runtime.readAttachment(oldMount, window.identity, "portable-source"))
            window.attach()
            val opened = assertIs<WorkspacePreparedCopyResult.Opened>(result)
            assertEquals(stored.editorProjectId, opened.projectId)
            assertEquals(stored.editorMount, opened.mount)
            assertEquals(control.documentIdentity, opened.documentIdentity)
            assertTrue(control.isAttached)
            assertFalse(runtime.state.busy)
            assertNull(native.currentReference, "an internal copy cannot inherit the previous native document")
            val read = assertIs<WorkspaceAttachmentReadResult.Success>(runtime.readAttachment(opened.mount, opened.documentIdentity, "portable-source"))
            assertEquals(source, read.attachment)
            assertIs<WorkspaceAttachmentReadResult.Failure>(runtime.readAttachment(oldMount, 0, "portable-source"))
            assertEquals(WorkspaceAttachmentReadResult.Missing, runtime.readAttachment(opened.mount, opened.documentIdentity, "no-such-key"))
            val hash = stored.session.document!!.attachments.getValue("portable-source").sha256
            Files.delete(disk.directory.resolve("attachment-$hash.workspace"))
            assertIs<WorkspaceAttachmentReadResult.Failure>(runtime.readAttachment(opened.mount, opened.documentIdentity, "portable-source"))
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun failedPreparedCopyNeverAnnouncesOpenedOrAssignsItsArchiveToTheOldProject() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "failed-copy", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach(); runtime.flush(); runtime.awaitPersistence()
            val before = runtime.state.workspace!!
            disk.failNextCatalog = true
            var result: WorkspacePreparedCopyResult? = null
            runtime.openPreparedCopy("Portable", xml("Portable"), mapOf("portable-source" to WorkspaceAttachment(
                "source.zip", "application/zip", byteArrayOf(5))), before.editorMount, window.identity) { result = it }
            until { result != null }
            assertIs<WorkspacePreparedCopyResult.Failure>(result)
            assertEquals(before.editorProjectId, runtime.state.workspace!!.editorProjectId)
            assertEquals(xml("A"), window.xml)
            assertTrue(window.enabled)
            assertEquals(WorkspaceAttachmentReadResult.Missing,
                runtime.readAttachment(before.editorMount, window.identity, "portable-source"))
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun anAttachmentReadThatLosesItsScopeFailsInsteadOfPretendingThereWasNoArchive() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "read-scope", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        val gate = Gate()
        try {
            runtime.start(xml("A")); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            var result: WorkspacePreparedCopyResult? = null
            runtime.openPreparedCopy("Portable", xml("Portable"), mapOf("portable-source" to WorkspaceAttachment(
                "source.zip", "application/zip", byteArrayOf(6))), runtime.state.workspace!!.editorMount, window.identity) { result = it }
            until { runtime.state.workspace!!.editorProjectId != a }; window.attach()
            val opened = assertIs<WorkspacePreparedCopyResult.Opened>(result)
            val reference = runtime.state.workspace!!.session.document!!.attachments.getValue("portable-source")
            disk.blockedReadName = "attachment-${reference.sha256}.workspace"; disk.nextReadGate = gate
            val reading = async { runtime.readAttachment(opened.mount, opened.documentIdentity, "portable-source") }
            until { gate.entered.count == 0L }
            var switched: Boolean? = null
            runtime.openProject(a) { switched = it }
            gate.release.countDown()
            assertIs<WorkspaceAttachmentReadResult.Failure>(reading.await())
            until { switched != null }; assertTrue(switched!!); window.attach()
            assertEquals(a, runtime.state.workspace!!.editorProjectId)
        } finally { gate.release.countDown(); runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun failedSwitchKeepsCurrentEditorBufferAndReleasesItsInteractionGate() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id); val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "rollback", control, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A")); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            var created: Boolean? = null
            runtime.createProject("B") { created = it }
            until { created != null }; assertTrue(created!!); window.attach()
            val b = runtime.state.workspace!!.editorProjectId!!
            window.open(a)
            window.xml = xml("A-local")
            disk.failNextCatalog = true
            var opened: Boolean? = null
            runtime.openProject(b) { opened = it }
            assertFalse(window.enabled)
            until { opened != null }
            assertFalse(opened!!)
            assertEquals(a, runtime.state.workspace!!.editorProjectId)
            assertEquals(xml("A-local"), window.xml)
            assertEquals(window.xml, runtime.state.workspace!!.session.xml)
            assertTrue(runtime.state.workspace!!.session.dirty)
            assertTrue(window.enabled)
            runtime.flush(); runtime.awaitPersistence()
            assertFalse(runtime.state.workspace!!.session.dirty)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun twoWindowsKeepCasConflictsAndNeverOverwriteTheOtherLocalBuffer() = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id)
        val firstControl = EditorSessionControl(); val secondControl = EditorSessionControl()
        val first = CatalogWorkspaceRuntime(disk, "first", firstControl, deliveryContext = coroutineContext)
        val second = CatalogWorkspaceRuntime(disk, "second", secondControl, deliveryContext = coroutineContext)
        val a = Window(first, firstControl); val b = Window(second, secondControl)
        try {
            first.start(xml("A")); a.attach()
            second.start(); b.attach()
            a.xml = xml("first-edit"); first.documentChanged(first.state.workspace!!.editorMount, a.xml)
            first.awaitPersistence()
            b.xml = xml("second-edit"); second.documentChanged(second.state.workspace!!.editorMount, b.xml)
            second.awaitPersistence()
            assertEquals(a.xml, second.state.workspace!!.session.conflict?.xml)
            assertEquals(b.xml, second.state.workspace!!.session.xml)
            assertEquals(a.xml, first.state.workspace!!.session.xml)
        } finally { first.close(); second.close(); first.awaitClosed(); second.awaitClosed(); disk.remove() }
    }

    @Test fun lateNativeSaveAcknowledgesOnlyItsOriginalProjectOnTheUiDispatcher() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui); val native = Native(ui)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "native", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            var saved: DocumentWriteResult? = null
            runtime.documents!!.save(reference(), window.xml) { saved = it }
            var created: Boolean? = null
            runtime.createProject("B") { created = it }
            until { created != null }; window.attach()
            native.finish(DocumentWriteResult.Saved(reference().copy(revision = "sha-A-new")))
            runtime.awaitPersistence()
            assertIs<DocumentWriteResult.Cancelled>(saved)
            assertEquals(xml("A"), native.savedText)
            assertNull(native.currentReference)
            window.open(a)
            assertEquals("sha-A-new", native.currentReference?.revision)
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun closeSaveWritesTheLinkedOriginalAndWaitsForProviderSuccess() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui); val native = Native(ui)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "close-save-original", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            window.xml = xml("A-edited")
            val failures = listOf(
                DocumentWriteResult.Failure("Provider access revoked"),
                DocumentWriteResult.Conflict(reference().copy(revision = "updated-by-another-app")),
            )
            for (failure in failures) {
                var closed: Boolean? = null
                runtime.closeEditorSession(a, null, save = true) { closed = it }
                until { native.pendingWrite != null }
                assertNull(closed)
                assertEquals(xml("A-edited"), native.savedText)
                assertFalse(window.enabled)
                native.finish(failure)
                until { closed != null }
                assertFalse(closed!!)
                assertEquals(a, runtime.state.workspace!!.editorProjectId)
                assertEquals(xml("A-edited"), runtime.state.workspace!!.session.xml)
                assertTrue(window.enabled)
                assertTrue(runtime.state.workspace!!.notices.isNotEmpty())
            }

            var closed: Boolean? = null
            runtime.closeEditorSession(a, null, save = true) { closed = it }
            until { native.pendingWrite != null }
            native.finish(DocumentWriteResult.Saved(reference().copy(revision = "saved-A")))
            until { closed != null }
            assertTrue(closed!!)
            assertTrue(runtime.state.workspace!!.editorClosed)
            window.open(a)
            assertEquals(xml("A-edited"), window.xml)
            assertEquals("saved-A", native.currentReference?.revision)
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun savingAnInactiveOriginalUsesItsOwnContentAndDoesNotPauseTheActiveSession() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui); val native = Native(ui)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "close-inactive-original", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            window.xml = xml("A-edited")
            var created: Boolean? = null
            runtime.createProject("B") { created = it }
            until { created != null }
            assertTrue(created!!)
            window.attach()
            val b = runtime.state.workspace!!.editorProjectId!!
            val pauses = window.pauses
            var closed: Boolean? = null
            runtime.closeEditorSession(a, b, save = true) { closed = it }
            until { native.pendingWrite != null }
            assertNull(closed)
            assertEquals(xml("A-edited"), native.savedText)
            assertTrue(window.enabled && window.active)
            native.finish(DocumentWriteResult.Saved(reference().copy(revision = "saved-A")))
            until { closed != null }
            assertTrue(closed!!)
            assertEquals(b, runtime.state.workspace!!.editorProjectId)
            assertEquals(pauses, window.pauses)
            assertNull(native.currentReference)
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun closeSaveOnCopyOnlyProvidersPreservesTheWorkspaceWithoutWritingTheOriginal() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui)
        val native = Native(ui, supportsEditInPlace = false)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "close-copy-only", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            val a = runtime.state.workspace!!.editorProjectId!!
            window.xml = xml("A-edited")
            var closed: Boolean? = null
            runtime.closeEditorSession(a, null, save = true) { closed = it }
            until { closed != null }
            assertTrue(closed!!)
            assertNull(native.savedText)
            window.open(a)
            assertEquals(xml("A-edited"), window.xml)
            assertEquals("sha-A", native.currentReference?.revision)
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun disposalFlushesLatestDetachedSnapshotAndKeepsLateNativeMetadataOrdered() = runBlocking<Unit> {
        val ui = Thread.currentThread().id; val disk = Disk(ui); val native = Native(ui)
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "closed", control, nativeDocuments = native, deliveryContext = coroutineContext)
        val window = Window(runtime, control)
        try {
            runtime.start(xml("A"), reference()); window.attach()
            window.xml = xml("A-final")
            runtime.documents!!.save(reference(), window.xml) {}
            control.detach()
            runtime.close()
            runtime.awaitPersistence()
            native.finish(DocumentWriteResult.Saved(reference().copy(revision = "sha-final")))
            runtime.awaitClosed()
            val restoredControl = EditorSessionControl()
            val restored = CatalogWorkspaceRuntime(disk, "closed", restoredControl, nativeDocuments = Native(ui), deliveryContext = coroutineContext)
            try {
                restored.start(); val next = Window(restored, restoredControl); next.attach()
                assertEquals(xml("A-final"), restored.state.workspace!!.session.xml)
                assertEquals("sha-final", restored.documents?.currentReference?.revision)
            } finally { restored.close(); restored.awaitClosed() }
        } finally { runtime.close(); native.finish(DocumentWriteResult.Cancelled); runtime.awaitClosed(); disk.remove() }
    }
}
