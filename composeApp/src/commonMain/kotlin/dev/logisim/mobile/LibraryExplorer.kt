package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The explorer tabs; null in [LibraryExplorerState.section] shows the components, the most frequent use (C18). */
internal object ExplorerSection {
    const val PROJECT = "project"
    const val LIBRARY = "library"
    const val SEARCH = "search"
}

/** Collapsible groups of the Library tab, keyed by library descriptor (`#Gates`), not by the localized label. */
internal object LibraryGroups {
    const val RECENT = "recent"
    const val TTL = "#TTL"
    /** Only the groups used most start open; the long ones (85 TTL chips) stay one tap away. */
    val DefaultExpanded: Set<String> = setOf(RECENT, "#Wiring", "#Gates")

    fun toggle(expanded: Set<String>, group: String): Set<String> = if (group in expanded) expanded - group else expanded + group
}

/** The last components chosen from the library: session state only, never written to the .circ. */
internal object LibraryRecents {
    const val LIMIT = 6

    fun record(recent: List<ComponentKind>, kind: ComponentKind): List<ComponentKind> =
        (listOf(kind) + recent.filter { it != kind }).take(LIMIT)

    /** What the project still offers: a library unloaded since drops its components from the list. */
    fun visible(recent: List<ComponentKind>, groups: List<Pair<String, List<ComponentKind>>>): List<ComponentKind> {
        val available = groups.flatMapTo(HashSet()) { it.second }
        return recent.filter { it in available }
    }
}

/** One item of the Library tab's lazy list. */
internal sealed interface LibraryListEntry {
    val key: String

    data object Manage : LibraryListEntry { override val key get() = "manage" }

    data class Group(val group: String, val size: Int, val expanded: Boolean) : LibraryListEntry {
        override val key get() = "group:$group"
    }

    data class Component(val group: String, val kind: ComponentKind) : LibraryListEntry {
        override val key get() = "$group/${kind.name}"
    }
}

/** 'Manage libraries' first, then the recently used components and each library, whose rows show while expanded. */
internal fun libraryListEntries(
    groups: List<Pair<String, List<ComponentKind>>>,
    recent: List<ComponentKind>,
    expanded: Set<String>,
): List<LibraryListEntry> = buildList {
    add(LibraryListEntry.Manage)
    for ((group, kinds) in listOf(LibraryGroups.RECENT to LibraryRecents.visible(recent, groups)) + groups) {
        if (kinds.isEmpty()) continue
        val open = group in expanded
        add(LibraryListEntry.Group(group, kinds.size, open))
        if (open) kinds.forEach { add(LibraryListEntry.Component(group, it)) }
    }
}

/**
 * The explorer's tab, the Library list's scroll position, its expanded groups and the recently used components. The
 * editor keeps one for the session, so the tablet sidebar and the phone sheet reopen where they were left (C18).
 */
@Stable
internal class LibraryExplorerState(
    section: String? = null,
    expanded: Set<String> = LibraryGroups.DefaultExpanded,
    recent: List<ComponentKind> = emptyList(),
    val listState: LazyListState = LazyListState(),
) {
    var section by mutableStateOf(section)
    var expanded by mutableStateOf(expanded)
        private set
    var recent by mutableStateOf(recent)
        private set

    fun toggle(group: String) { expanded = LibraryGroups.toggle(expanded, group) }
    fun used(kind: ComponentKind) { recent = LibraryRecents.record(recent, kind) }

    companion object {
        val Saver: Saver<LibraryExplorerState, Any> = listSaver(
            save = {
                listOf(it.section.orEmpty(), it.expanded.joinToString("\n"), it.recent.joinToString("\n") { kind -> kind.name },
                    it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset)
            },
            restore = { saved ->
                fun lines(value: Any?) = (value as String).split('\n').filter(String::isNotEmpty)
                LibraryExplorerState((saved[0] as String).ifEmpty { null }, lines(saved[1]).toSet(),
                    lines(saved[2]).map(::ComponentKind), LazyListState(saved[3] as Int, saved[4] as Int))
            },
        )
    }
}

@Composable
internal fun rememberLibraryExplorerState(): LibraryExplorerState =
    rememberSaveable(saver = LibraryExplorerState.Saver) { LibraryExplorerState() }

/** A one-circuit project for an index of library entries only. */
private val LibraryFilterProject = CircuitProject(listOf(CircuitDocument()))

@Composable
internal fun LibraryPanel(
    state: EditorState,
    searchActions: List<EditorSearchAction>,
    explorer: LibraryExplorerState,
    modifier: Modifier = Modifier,
    findActions: Boolean = false,
    /** False while another panel shows the same list (the find sheet over the tablet sidebar). */
    shareScroll: Boolean = true,
    onManageLibraries: () -> Unit,
    onExportCircuit: ((String) -> Unit)?,
    onChoose: () -> Unit = {},
    componentIconColor: Int? = null,
) {
    val strings = rememberLocalizer()
    // Find action opens Search with the query focused, then the remembered tab returns.
    var findingActions by remember { mutableStateOf(findActions) }
    val section = if (findingActions) ExplorerSection.SEARCH else explorer.section ?: ExplorerSection.LIBRARY
    val show = { next: String -> findingActions = false; explorer.section = next }
    val componentGroups = remember(state.componentRegistry, state.project.libraries) {
        registryComponentGroups(state.componentRegistry, state.project)
    }
    val libraryEntries = remember(strings.language, componentGroups) {
        componentGroups.flatMap { (library, kinds) ->
            val name = strings(ProjectBuiltinLibraries.displayName(library))
            kinds.map { SearchLibraryEntry(it, it.localizedLabel().resolve(strings.language), name) }
        }
    }
    val place = { kind: ComponentKind -> explorer.used(kind); state.place(kind); onChoose() }
    Column(modifier.background(ChromeColors.Panel).semantics { contentDescription = strings(Messages.A11yLibrary) }) {
        Row(Modifier.fillMaxWidth().background(ChromeColors.Heading)) {
            ExplorerTab(ExplorerSection.PROJECT, strings(Messages.ActionCircuits), strings(Messages.A11yShowTree),
                section == ExplorerSection.PROJECT) { show(ExplorerSection.PROJECT) }
            ExplorerTab(ExplorerSection.LIBRARY, strings(Messages.ActionLibrary), strings(Messages.A11yShowComponents),
                section == ExplorerSection.LIBRARY) { show(ExplorerSection.LIBRARY) }
            ExplorerTab(ExplorerSection.SEARCH, strings(Messages.ActionSearch), strings(Messages.A11ySearchAll),
                section == ExplorerSection.SEARCH) { show(ExplorerSection.SEARCH) }
        }
        HorizontalDivider(color = ChromeColors.Divider)
        when (section) {
            ExplorerSection.PROJECT -> ProjectNavigationPanel(state.project, state.activeTarget,
                onNavigate = { state.navigate(it); onChoose() },
                onCreate = { state.createCircuit(it) }, onRename = { id, name -> state.renameCircuit(id, name) },
                onMove = { id, direction -> state.moveCircuit(id, direction) }, onDelete = { state.removeCircuit(it) },
                onSetMain = { state.setMainCircuit(it) }, modifier = Modifier.weight(1f).fillMaxWidth(),
                onExport = onExportCircuit)
            ExplorerSection.SEARCH -> {
                val commands = searchActions.map { it.command }
                val project = state.project
                var searchIndex by remember { mutableStateOf<ProjectSearchIndex?>(null) }
                LaunchedEffect(project, libraryEntries, commands, strings.language) {
                    searchIndex = withContext(Dispatchers.Default) { ProjectSearchIndex.build(project, libraryEntries, commands, strings.language) }
                }
                searchIndex?.let { index ->
                    ProjectSearchPanel(index, onNavigate = { state.navigate(it); onChoose() },
                        onInsert = place, onCommand = { command ->
                            searchActions.firstOrNull { it.command.id == command && it.action.enabled }?.let {
                                onChoose()
                                it.action.onClick()
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        initialScope = if (findingActions) ProjectSearchScope.COMMANDS else ProjectSearchScope.ALL,
                        requestFocus = findingActions,
                    )
                } ?: Text(strings(Messages.SearchPreparing), modifier = Modifier.weight(1f).padding(12.dp))
            }
            else -> LibraryComponents(state, explorer, componentGroups, libraryEntries,
                listState = if (shareScroll) explorer.listState else rememberLazyListState(),
                iconColor = componentIconColor, onManageLibraries = onManageLibraries, onPlace = place)
        }
    }
}

/** The Library tab: an inline filter over a lazy list of collapsible library groups (C18). */
@Composable
private fun ColumnScope.LibraryComponents(
    state: EditorState,
    explorer: LibraryExplorerState,
    groups: List<Pair<String, List<ComponentKind>>>,
    libraryEntries: List<SearchLibraryEntry>,
    listState: LazyListState,
    iconColor: Int?,
    onManageLibraries: () -> Unit,
    onPlace: (ComponentKind) -> Unit,
) {
    val strings = rememberLocalizer()
    var filter by rememberSaveable { mutableStateOf("") }
    val groupOf = remember(groups) { groups.flatMap { (group, kinds) -> kinds.map { it to group } }.toMap() }
    val placing = state.placingKind.takeIf { state.mode == EditorMode.PLACE }
    @Composable
    fun row(kind: ComponentKind, tag: String, subtitle: String? = null) {
        val label = kind.localizedLabel().resolve(strings.language)
        LibraryRow(kind, label, selected = kind == placing, strings(Messages.A11yInsertComponent, "name" to label),
            iconColor, chip = groupOf[kind] == LibraryGroups.TTL, Modifier.testTag(tag), subtitle) { onPlace(kind) }
    }
    LibraryFilterField(filter, { filter = it })
    if (filter.isBlank()) {
        val entries = libraryListEntries(groups, explorer.recent, explorer.expanded)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-list"), state = listState) {
            items(entries, key = { it.key }, contentType = { it::class.simpleName }) { entry ->
                when (entry) {
                    LibraryListEntry.Manage -> TextButton(onClick = onManageLibraries,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-manage"),
                        contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text(strings("active_library_manage") + "…", fontSize = 13.sp, color = ChromeColors.Accent,
                            modifier = Modifier.fillMaxWidth())
                    }
                    is LibraryListEntry.Group -> LibraryGroupHeader(entry.group,
                        if (entry.group == LibraryGroups.RECENT) strings("library_recent")
                        else strings(ProjectBuiltinLibraries.displayName(entry.group)),
                        entry.size, entry.expanded) { explorer.toggle(entry.group) }
                    is LibraryListEntry.Component -> row(entry.kind,
                        if (entry.group == LibraryGroups.RECENT) "library-recent-${entry.kind.name}" else "library-item-${entry.kind.name}")
                }
            }
        }
    } else {
        val index = remember(libraryEntries) { ProjectSearchIndex.build(LibraryFilterProject, libraryEntries, language = strings.language) }
        val results = remember(index, filter) {
            index.search(filter, ProjectSearchScope.LIBRARY).mapNotNull { result ->
                (result.target as? ProjectSearchTarget.Library)?.let { it.kind to result.subtitle }
            }
        }
        if (results.isEmpty()) Text(strings("library_filter_empty"), fontSize = 13.sp, color = ChromeColors.Muted,
            modifier = Modifier.fillMaxWidth().padding(14.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-filter-results")) {
            items(results, key = { it.first.name }) { (kind, library) -> row(kind, "library-item-${kind.name}", library) }
        }
    }
}

@Composable
private fun LibraryFilterField(value: String, onValueChange: (String) -> Unit) {
    val strings = rememberLocalizer()
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValueChange, singleLine = true,
        label = { Text(strings("library_filter"), fontSize = 13.sp) },
        trailingIcon = if (value.isEmpty()) null else ({
            val clear = strings("library_filter_clear")
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.testTag("library-filter-clear")
                .semantics { contentDescription = clear }) {
                EditorIconGlyph(EditorIcon.CLOSE, Modifier.size(16.dp), tint = ChromeColors.Muted)
            }
        }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp).testTag("library-filter"))
}

/** A library group's heading, which folds or unfolds its rows and says which (C18, TalkBack). */
@Composable
private fun LibraryGroupHeader(group: String, title: String, size: Int, expanded: Boolean, onToggle: () -> Unit) {
    val strings = rememberLocalizer()
    val state = strings(if (expanded) "a11y_state_expanded" else "a11y_state_collapsed")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(ChromeColors.Heading)
        .clickable(role = Role.Button, onClick = onToggle)
        .testTag("library-group-${group.removePrefix("#")}")
        .semantics { heading(); stateDescription = state }
        .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorIconGlyph(EditorIcon.NEXT, Modifier.size(14.dp).rotate(if (expanded) 90f else 0f), tint = ChromeColors.Muted)
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChromeColors.Ink, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(size.toString(), fontSize = 11.sp, color = ChromeColors.Muted, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun RowScope.ExplorerTab(id: String, label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).heightIn(min = 48.dp).background(if (selected) ChromeColors.Panel else Color.Transparent)
        .clickable(role = Role.Tab, onClick = onClick)
        .testTag("explorer-tab-$id")
        .semantics { contentDescription = description; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) ChromeColors.Ink else ChromeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(if (selected) ChromeColors.Accent else Color.Transparent))
    }
}

/** A 48 dp component row; the armed placement tool's row is selected for TalkBack as well as tinted (C29, C152). */
@Composable
private fun LibraryRow(
    kind: ComponentKind,
    label: String,
    selected: Boolean,
    description: String,
    iconColor: Int?,
    chip: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        // The armed tool: the selected tint, a leading accent bar (3:1) and the selected state for TalkBack (C158).
        .leadingSelectionCue(selected)
        .selectable(selected = selected, role = Role.Button, onClick = onClick)
        .padding(start = 14.dp, end = 8.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(32.dp).height(24.dp), contentAlignment = Alignment.Center) {
            val icon = kind.editorIcon()
            val ink = iconColor.editorColorOr(ChromeColors.Ink)
            when {
                icon != null -> EditorIconGlyph(icon, Modifier.size(20.dp), tint = ink)
                chip -> ChipGlyph(ink, Modifier.width(22.dp).height(20.dp))
                else -> {
                    val symbol = kind.shortSymbol(label)
                    // A glyph, not text to read: it keeps its size within the box at any font scale.
                    val size = with(LocalDensity.current) {
                        when { symbol.length > 3 -> 8.5.dp; symbol.length == 3 -> 10.dp; else -> 12.dp }.toSp()
                    }
                    Text(symbol, color = iconColor.editorColorOr(Color(0xFF167624)), fontSize = size,
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = ChromeColors.Ink)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ChromeColors.Muted)
        }
    }
}

/** A dual in-line package, the glyph of every TTL chip; the row's label names the chip. */
@Composable
private fun ChipGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = 1.4.dp.toPx()
        val pin = size.width * 0.16f
        val body = Size(size.width - pin * 2, size.height)
        drawRect(color, Offset(pin, 0f), body, style = Stroke(stroke))
        drawArc(color, 180f, -180f, false, Offset(size.width / 2 - 3.dp.toPx(), -3.dp.toPx()),
            Size(6.dp.toPx(), 6.dp.toPx()), style = Stroke(stroke))
        for (index in 1..3) {
            val y = size.height * index / 4f
            drawLine(color, Offset(0f, y), Offset(pin, y), stroke)
            drawLine(color, Offset(size.width - pin, y), Offset(size.width, y), stroke)
        }
    }
}

/**
 * A short, presentation-only symbol for a library row whose component has no drawn glyph ([editorIcon]), so rows can
 * be told apart at a glance; the serialized kind names stay untouched (C26). Components the table does not know
 * (extensions, JAR libraries) take the initials of their [label].
 */
internal fun ComponentKind.shortSymbol(label: String = ""): String = when (this) {
    ComponentKind.INPUT -> "01"
    ComponentKind.OUTPUT -> "Y"
    ComponentKind.CLOCK -> "CLK"
    ComponentKind.D_FLIP_FLOP -> "D"
    ComponentKind.AND, ComponentKind.NAND -> "&"
    ComponentKind.OR, ComponentKind.NOR -> "≥1"
    ComponentKind.XOR, ComponentKind.XNOR -> "=1"
    ComponentKind.NOT -> "¬"
    ComponentKind.BUFFER -> "▷"
    else -> LibrarySymbols[name] ?: labelInitials(label)
}

private fun labelInitials(label: String): String {
    val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.single().take(3)
        else -> words.take(3).joinToString("") { it.take(1) }
    }.uppercase()
}

/** Keyed by [ComponentKind.name]; unique within each library (`EditorBuiltinLibrariesTest`). */
private val LibrarySymbols: Map<String, String> = mapOf(
    // Wiring
    "TUNNEL" to "◀", "SPLITTER" to "⊣", "PULL_RESISTOR" to "Ω", "POWER_ON_RESET" to "POR", "POWER" to "VCC",
    "GROUND" to "GND", "NO_CONNECT" to "NC", "TRANSISTOR" to "FET", "TRANSMISSION_GATE" to "TG", "BIT_EXTENDER" to "EXT",
    // Gates
    "ODD_PARITY" to "2k+1", "EVEN_PARITY" to "2k", "CONTROLLED_BUFFER" to "EN▷", "CONTROLLED_INVERTER" to "EN¬",
    "PLA" to "PLA",
    // Arithmetic
    "ADDER" to "+", "SUBTRACTOR" to "−", "MULTIPLIER" to "×", "DIVIDER" to "÷", "NEGATOR" to "−x",
    "EXPONENTIATOR" to "x^y", "SQUARE_ROOT" to "√", "ABSOLUTE" to "|x|", "COMPARATOR" to "<=>", "MIN_MAX" to "max",
    "SHIFTER" to "<<", "BIT_ADDER" to "Σ1", "BIT_FINDER" to "1?",
    // Floating-point arithmetic
    "FPConstant" to "1.0", "FPAdder" to "f+", "FPSubtractor" to "f−", "FPMultiplier" to "f×", "FPDivider" to "f÷",
    "FPNegator" to "−f", "FPExponentiator" to "f^y", "FPLogarithm" to "log", "FPSquareRoot" to "√f", "FPAbsolute" to "|f|",
    "FPComparator" to "f<>", "FPMinMax" to "fmax", "FPRound" to "f≈", "FPTrigonometry" to "sin", "FPClassificator" to "NaN",
    "FPToFP" to "f→f", "FPToInt" to "f→i", "IntToFP" to "i→f",
    // Plexers
    "MULTIPLEXER" to "MUX", "DEMULTIPLEXER" to "DMX", "DECODER" to "DEC", "PRIORITY_ENCODER" to "PE", "BIT_SELECTOR" to "[i]",
    // Memory
    "ROM" to "ROM", "RAM" to "RAM", "DUAL_RAM" to "RAM2", "T_FLIP_FLOP" to "T", "JK_FLIP_FLOP" to "JK",
    "SR_FLIP_FLOP" to "SR", "REGISTER" to "REG", "COUNTER" to "+1", "SHIFT_REGISTER" to "SHR", "RANDOM" to "RND",
    // Input/output
    "BUTTON" to "BTN", "DIP_SWITCH" to "DIP", "JOYSTICK" to "JOY", "KEYBOARD" to "KBD", "LED_BAR" to "BAR",
    "RGB_LED" to "RGB", "SEVEN_SEGMENT_DISPLAY" to "7SEG", "HEX_DISPLAY" to "HEX", "DOT_MATRIX" to "MTX", "TTY" to "TTY",
    "MATRIX_KEYPAD" to "KPD", "PORT_IO" to "PIO", "RGB_VIDEO" to "VID", "REAL_TIME_CLOCK" to "RTC", "TELNET" to "TEL",
    "REPTAR_LOCAL_BUS" to "LB",
    // BFH mega functions
    "BIN_TO_BCD" to "BCD", "BCD_TO_SEVEN_SEGMENT" to "B→7",
    // Input/output extra
    "SWITCH" to "SW", "TWO_WAY_SWITCH" to "SW2", "SLIDER" to "SLD", "TWO_PIN_LED" to "LED2", "PLA_ROM" to "PLA",
    "DIGITAL_OSCILLOSCOPE" to "OSC", "NE555" to "555", "BUZZER" to "BZ",
    // Annotations
    "IMAGE" to "IMG",
    // System on a chip
    "SOC_BUS" to "BUS", "SOC_MEMORY" to "MEM", "SOC_PIO" to "PIO", "SOC_VGA" to "VGA", "SOC_DMA" to "DMA",
    "SOC_JTAG_UART" to "UART", "SOC_RV32IM" to "RV32", "SOC_NIOS2" to "NIOS",
    // TCL
    "TCL_CONSOLE_REDS" to "Tcl>", "TCL_GENERIC" to "TCL",
    // HDL-IP
    "VHDL_ENTITY" to "VHDL", "BLIF_CIRCUIT" to "BLIF",
)
