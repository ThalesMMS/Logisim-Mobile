package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.systemAppLanguage


internal object WorkspaceCatalogFormat {
    fun writeCatalog(catalog: WorkspaceCatalogRepository.Catalog): String = envelope("CATALOG", Tokens().apply {
        requireLocalized(catalog.projects.size <= 100_000) { message(Messages.WorkspaceErrorCatalogLimit) }
        add(catalog.activeId?.value.orEmpty()); add(catalog.legacyHandled); add(catalog.projects.size)
        catalog.projects.forEach { info(it) }
    }.toString())

    fun readCatalog(raw: String): WorkspaceCatalogRepository.Catalog? = readEnvelope(raw, "CATALOG")?.let { data -> runCatching {
        val tokens = Reader(data)
        val active = tokens.next().takeIf(String::isNotEmpty)?.let(::WorkspaceProjectId)
        val handled = tokens.boolean()
        val projects = List(tokens.count(100_000)) { tokens.info() }
        require(projects.map { it.id }.distinct().size == projects.size)
        require(active == null || projects.any { it.id == active && !it.deleted })
        tokens.finish()
        WorkspaceCatalogRepository.Catalog(projects, active, handled)
    }.getOrNull() }

    fun writeDocument(document: WorkspaceDocument): String = envelope("PROJECT", Tokens().apply {
        requireLocalized(document.viewState.selectedComponentIds.size <= 100_000 && document.viewState.viewports.size <= 100_000 &&
            document.viewState.selectedWires.size <= 100_000 && document.viewState.activeInstancePath.size <= 256) {
            message(Messages.WorkspaceErrorViewLimit)
        }
        info(document.info.copy(history = emptyList())); add(document.xml); add(document.viewState.activeCircuitId.orEmpty())
        add(document.viewState.selectedComponentIds.size)
        document.viewState.selectedComponentIds.sorted().forEach(::add)
        add(document.viewState.viewportKeyFormat.name)
        add(document.viewState.viewports.size)
        document.viewState.viewports.entries.sortedBy { it.key }.forEach { (id, viewport) ->
            add(id); add(viewport.scale); add(viewport.panX); add(viewport.panY)
        }
        add(document.viewState.activeInstancePath.size)
        document.viewState.activeInstancePath.forEach(::add)
        add(document.viewState.selectedWires.size)
        document.viewState.selectedWires.sortedWith(compareBy({ it.from.x }, { it.from.y }, { it.to.x }, { it.to.y })).forEach {
            add(it.from.x); add(it.from.y); add(it.to.x); add(it.to.y)
        }
        WorkspaceAttachmentBlobs.validateReferences(document.attachments)
        add(document.attachments.size)
        document.attachments.entries.sortedBy { it.key }.forEach { (key, reference) ->
            add(key); add(reference.name); add(reference.mimeType); add(reference.size); add(reference.sha256)
        }
    }.toString(), version = "4")

    fun readDocument(raw: String): WorkspaceDocument? = readEnvelope(raw, "PROJECT", setOf("1", "2", "3", "4"))?.let { data -> runCatching {
        val version = raw.substringAfter('\n').substringBefore('\n')
        val tokens = Reader(data)
        val info = tokens.info()
        val xml = tokens.next()
        val active = tokens.next().takeIf(String::isNotEmpty)
        val selection = List(tokens.count(100_000)) { tokens.next() }
        require(selection.distinct().size == selection.size)
        val selected = selection.toSet()
        val keyFormat = if (version == "4") WorkspaceViewportKeyFormat.valueOf(tokens.next()) else WorkspaceViewportKeyFormat.LEGACY
        val viewportEntries = List(tokens.count(100_000)) {
            tokens.next() to WorkspaceViewport(tokens.next().toFloat(), tokens.next().toFloat(), tokens.next().toFloat())
        }
        require(viewportEntries.map { it.first }.distinct().size == viewportEntries.size)
        val viewports = viewportEntries.toMap()
        val path = if (version != "1") List(tokens.count(256)) { tokens.next() } else emptyList()
        val wireEntries = if (version != "1") List(tokens.count(100_000)) {
            WireSegment(GridPoint(tokens.next().toInt(), tokens.next().toInt()), GridPoint(tokens.next().toInt(), tokens.next().toInt()))
        } else emptyList()
        require(wireEntries.distinct().size == wireEntries.size)
        val attachmentEntries = if (version == "3" || version == "4") List(tokens.count(WorkspaceAttachmentBlobs.MAX_ATTACHMENTS)) {
            tokens.next() to WorkspaceAttachmentReference(tokens.next(), tokens.next(), tokens.next().toLong(), tokens.next())
        } else emptyList()
        require(attachmentEntries.map { it.first }.distinct().size == attachmentEntries.size)
        val attachments = attachmentEntries.toMap()
        WorkspaceAttachmentBlobs.validateReferences(attachments)
        tokens.finish()
        WorkspaceDocument(info, xml, WorkspaceViewState(active, selected, viewports, path, wireEntries.toSet(), keyFormat), attachments = attachments)
    }.getOrNull() }

    fun usesUnsupportedCatalogVersion(raw: String): Boolean = raw.startsWith("LOGISIM-CATALOG\n") &&
        raw.substringAfter('\n').substringBefore('\n') != "1"

    fun usesUnsupportedProjectVersion(raw: String): Boolean {
        if (!raw.startsWith("LOGISIM-PROJECT\n")) return false
        val version = raw.substringAfter('\n').substringBefore('\n')
        if (version !in setOf("1", "2", "3", "4")) return true
        if (version != "4") return false
        val body = readEnvelope(raw, "PROJECT", setOf("4")) ?: return false
        return runCatching {
            val tokens = Reader(body)
            tokens.info(); tokens.next(); tokens.next()
            repeat(tokens.count(100_000)) { tokens.next() }
            tokens.next() !in WorkspaceViewportKeyFormat.entries.map { it.name }
        }.getOrDefault(false)
    }

    private fun envelope(kind: String, body: String, version: String = "1"): String = "LOGISIM-$kind\n$version\n${checksum(body)}\n\n$body".also {
        requireLocalized(it.length <= 20_000_000) { message(Messages.WorkspaceErrorSize) }
    }
    private fun readEnvelope(raw: String, kind: String, versions: Set<String> = setOf("1")): String? {
        if (raw.length > 20_000_000) return null
        val split = raw.indexOf("\n\n")
        if (split !in 1..128) return null
        val header = raw.substring(0, split).split('\n')
        val body = raw.substring(split + 2)
        return body.takeIf { header.size == 3 && header[0] == "LOGISIM-$kind" && header[1] in versions && header[2] == checksum(body) }
    }

    internal fun checksum(text: String): String {
        var hash = 0xcbf29ce484222325uL
        text.encodeToByteArray().forEach { hash = (hash xor it.toUByte().toULong()) * 0x100000001b3uL }
        return hash.toString(16)
    }

    private class Tokens {
        private val text = StringBuilder()
        fun add(value: Any) { val string = value.toString(); text.append(string.length).append(':').append(string) }
        fun info(info: WorkspaceProjectInfo) {
            requireLocalized(info.history.size <= 100_000) { message(Messages.WorkspaceErrorHistoryLimit) }
            add(info.id.value); add(info.name); add(info.revision); add(info.createdAtMillis)
            add(info.updatedAtMillis); add(info.deleted); add(info.previousRevision)
            add(info.history.size); info.history.forEach(::add)
        }
        override fun toString() = text.toString()
    }
    private class Reader(private val text: String) {
        private var offset = 0
        fun next(): String {
            val separator = text.indexOf(':', offset)
            require(separator in offset..offset + 8)
            val size = text.substring(offset, separator).toInt()
            require(size >= 0 && size <= text.length - separator - 1)
            offset = separator + 1 + size
            return text.substring(separator + 1, offset)
        }
        fun boolean() = next().let { require(it == "true" || it == "false"); it == "true" }
        fun count(maximum: Int) = next().toInt().also { require(it in 0..maximum) }
        fun info(): WorkspaceProjectInfo {
            val id = WorkspaceProjectId(next()); val name = next(); val revision = next().toLong()
            val created = next().toLong(); val updated = next().toLong(); val deleted = boolean(); val previous = next().toLong()
            val history = List(count(100_000)) { next().toLong() }
            require(name.isNotBlank() && revision > 0 && previous in 0 until revision)
            require(history.distinct().size == history.size && history.all { it in 1 until revision })
            return WorkspaceProjectInfo(id, name, revision, created, updated, deleted, previous, history)
        }
        fun finish() = require(offset == text.length)
    }
}
