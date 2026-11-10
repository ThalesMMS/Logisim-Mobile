package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.WorkspaceViewState

data class EditorProjectReplacement(
    val previousXml: String,
    val xml: String,
    val previousIdentity: Long,
    val identity: Long,
    val importReport: EditorImportReport? = null,
)

/** A window can pause and save editor view state without owning Compose's editor internals. */
class EditorSessionControl {
    private var binding: Binding? = null
    private var pendingView: WorkspaceViewState? = null
    private var active = true
    private var interactionsEnabled = true
    var isAttached by mutableStateOf(false)
        private set
    private var lastView: WorkspaceViewState? = null
    private var lastXml: String? = null
    private var lastIdentity: Long? = null
    private var lastWritable: Boolean? = null
    private var lastImportReport: EditorImportReport? = null

    val activationAllowed: Boolean get() = active
    val documentIdentity: Long? get() = binding?.identity?.invoke() ?: lastIdentity
    val documentWritable: Boolean? get() = binding?.documentWritable?.invoke() ?: lastWritable

    fun exportXml(): String? {
        val current = binding
        return if (current == null) lastXml else current.exportXml()
    }

    fun exportImportReport(): EditorImportReport? {
        val current = binding
        return if (current == null) lastImportReport else current.exportImportReport()
    }

    fun pause() { binding?.pause?.invoke() }
    fun setActive(active: Boolean) {
        this.active = active
        binding?.setActive?.invoke(active)
    }
    fun setInteractionEnabled(enabled: Boolean) {
        interactionsEnabled = enabled
        binding?.setInteractionEnabled?.invoke(enabled)
    }
    fun exportViewState(): WorkspaceViewState? = binding?.exportView?.invoke() ?: pendingView ?: lastView
    fun importViewState(view: WorkspaceViewState) {
        pendingView = view
        binding?.importView?.invoke(view)
    }

    internal fun attach(
        pause: () -> Unit,
        setActive: (Boolean) -> Unit,
        exportView: () -> WorkspaceViewState,
        importView: (WorkspaceViewState) -> Unit,
        identity: () -> Long = { 0L },
        exportXml: () -> String? = { null },
        documentWritable: () -> Boolean = { true },
        exportImportReport: () -> EditorImportReport? = { null },
        setInteractionEnabled: (Boolean) -> Unit = {},
    ) {
        binding = Binding(pause, setActive, exportView, importView, identity, exportXml, documentWritable, exportImportReport, setInteractionEnabled)
        setActive(active)
        setInteractionEnabled(interactionsEnabled)
        pendingView?.let(importView)
        pendingView = null
        isAttached = true
    }
    internal fun detach() {
        binding?.let { current ->
            lastView = current.exportView()
            lastXml = current.exportXml()
            lastIdentity = current.identity()
            lastWritable = current.documentWritable()
            lastImportReport = current.exportImportReport()
        }
        binding = null
        isAttached = false
    }

    private data class Binding(
        val pause: () -> Unit,
        val setActive: (Boolean) -> Unit,
        val exportView: () -> WorkspaceViewState,
        val importView: (WorkspaceViewState) -> Unit,
        val identity: () -> Long,
        val exportXml: () -> String?,
        val documentWritable: () -> Boolean,
        val exportImportReport: () -> EditorImportReport?,
        val setInteractionEnabled: (Boolean) -> Unit,
    )
}
