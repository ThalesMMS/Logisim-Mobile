package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message

enum class ProjectSearchScope { ALL, PROJECT, LIBRARY, COMMANDS }

data class SearchLibraryEntry(
    val kind: ComponentKind,
    val title: String,
    val library: String,
    val keywords: List<String> = emptyList(),
    val id: String = kind.name,
)

data class SearchCommand(
    val id: String,
    val title: String,
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val path: String? = null,
    val shortcut: String? = null,
)

sealed interface ProjectSearchTarget {
    data class Project(val location: CircuitNavigationTarget) : ProjectSearchTarget
    data class Library(val kind: ComponentKind) : ProjectSearchTarget
    data class Command(val id: String) : ProjectSearchTarget
}

data class ProjectSearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val target: ProjectSearchTarget,
    val enabled: Boolean = true,
)

class ProjectSearchIndex private constructor(private val entries: List<Entry>) {
    val size: Int get() = entries.size

    fun search(query: String, scope: ProjectSearchScope = ProjectSearchScope.ALL, limit: Int = 100): List<ProjectSearchResult> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val normalized = normalizeSearchText(query)
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        val ranked = List(12) { mutableListOf<ProjectSearchResult>() }
        for (entry in entries) {
            if (scope != ProjectSearchScope.ALL && entry.scope != scope) continue
            val acronymMatch = normalized.length >= 2 && normalized == entry.acronym
            if (!acronymMatch && !tokens.all { it in entry.searchText }) continue
            val rank = when {
                normalized.isEmpty() -> 0
                entry.title == normalized -> 0
                acronymMatch -> 1
                entry.title.startsWith(normalized) -> 2
                tokens.all { token -> entry.titleWords.any { it.startsWith(token) } } -> 3
                normalized in entry.title -> 4
                else -> 5
            }
            val matches = ranked[rank * 2 + if (entry.result.enabled) 0 else 1]
            if (matches.size < limit) matches += entry.result
        }
        return ranked.asSequence().flatten().take(limit).toList()
    }

    private data class Entry(
        val result: ProjectSearchResult,
        val scope: ProjectSearchScope,
        val title: String,
        val searchText: String,
        val titleWords: List<String>,
        val acronym: String?,
    )

    companion object {
        fun build(
            project: CircuitProject,
            library: List<SearchLibraryEntry> = emptyList(),
            commands: List<SearchCommand> = emptyList(),
            language: AppLanguage = AppLanguage.PORTUGUESE,
        ): ProjectSearchIndex {
            val entries = mutableListOf<Entry>()
            fun add(result: ProjectSearchResult, scope: ProjectSearchScope, keywords: List<String> = emptyList()) {
                val title = normalizeSearchText(result.title)
                val words = title.split(' ')
                val acronym = if (scope == ProjectSearchScope.COMMANDS) words.mapNotNull { word ->
                    word.firstOrNull(Char::isLetterOrDigit)
                }.joinToString("") else null
                entries += Entry(result, scope, title,
                    normalizeSearchText((listOf(result.title, result.subtitle) + keywords).joinToString(" ")), words, acronym)
            }
            fun circuitEntries(circuit: CircuitDocument, path: List<String>, instanceLabel: String? = null, breadcrumb: List<String> = emptyList()) {
                val location = CircuitNavigationTarget(circuit.id, circuit.name, path)
                val prefix = if (path.isEmpty()) "definition:${circuit.id.length}:${circuit.id}" else "instance:${navigationPathKey(path)}"
                val context = if (path.isEmpty()) message(Messages.SearchContextCircuit, "name" to circuit.name)
                    else message(Messages.SearchContextInstance, "path" to breadcrumb.joinToString(" / "), "name" to circuit.name)
                add(ProjectSearchResult(prefix, instanceLabel ?: circuit.name, context.resolve(language), ProjectSearchTarget.Project(location)), ProjectSearchScope.PROJECT)
                for (component in circuit.components) {
                    val title = component.label.ifBlank { component.kind.localizedLabel().resolve(language) }
                    add(ProjectSearchResult(
                        "$prefix/component:${component.id.length}:${component.id}", title,
                        message(Messages.SearchComponentContext, "context" to context, "kind" to component.kind.localizedLabel(),
                            "x" to component.position.x, "y" to component.position.y).resolve(language),
                        ProjectSearchTarget.Project(location.copy(componentId = component.id)),
                    ), ProjectSearchScope.PROJECT, listOf(component.kind.name, component.id, component.circuitName.orEmpty()))
                }
            }

            project.circuits.forEach { circuitEntries(it, emptyList()) }
            ProjectNavigation.rows(project).filter { it.kind == ProjectNavigationKind.INSTANCE }.forEach { row ->
                project.circuit(row.target.circuitName)?.let { circuitEntries(it, row.target.instancePath, row.label, row.breadcrumb) }
            }
            library.forEach { item ->
                add(ProjectSearchResult("library:${item.id}", item.title, item.library,
                    ProjectSearchTarget.Library(item.kind)), ProjectSearchScope.LIBRARY, item.keywords + item.kind.name)
            }
            commands.forEach { command ->
                val path = command.path ?: message(Messages.SearchCommand).resolve(language)
                val subtitle = listOfNotNull(path, command.shortcut).joinToString(" · ")
                add(ProjectSearchResult("command:${command.id}", command.title, subtitle,
                    ProjectSearchTarget.Command(command.id), command.enabled), ProjectSearchScope.COMMANDS, command.keywords)
            }
            return ProjectSearchIndex(entries)
        }
    }
}

internal fun normalizeSearchText(text: String): String = buildString {
    for (character in text.lowercase()) {
        when (character) {
            in '\u0300'..'\u036f' -> Unit
            'á', 'à', 'â', 'ä', 'ã', 'å', 'ā', 'ă', 'ą' -> append('a')
            'ç', 'ć', 'ĉ', 'ċ', 'č' -> append('c')
            'é', 'è', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> append('e')
            'í', 'ì', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı' -> append('i')
            'ñ', 'ń', 'ņ', 'ň' -> append('n')
            'ó', 'ò', 'ô', 'ö', 'õ', 'ø', 'ō', 'ŏ', 'ő' -> append('o')
            'ú', 'ù', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> append('u')
            'ý', 'ÿ', 'ŷ' -> append('y')
            'š', 'ś', 'ş', 'ŝ' -> append('s')
            'ž', 'ź', 'ż' -> append('z')
            'ł', 'ĺ', 'ļ', 'ľ' -> append('l')
            'ß' -> append("ss")
            'æ' -> append("ae")
            'œ' -> append("oe")
            else -> append(if (character.isWhitespace()) ' ' else character)
        }
    }
}.trim().split(Regex(" +")).joinToString(" ")
