package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer


import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ProjectSearchPanel(
    index: ProjectSearchIndex,
    onNavigate: (CircuitNavigationTarget) -> Unit,
    onInsert: (dev.logisim.mobile.core.ComponentKind) -> Unit,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialScope: ProjectSearchScope = ProjectSearchScope.ALL,
    requestFocus: Boolean = false,
) {
    val strings = rememberLocalizer()
    val searchFocus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(initialScope) }
    var results by remember { mutableStateOf(emptyList<ProjectSearchResult>()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(index, query, scope) {
        searching = true
        delay(120)
        results = withContext(Dispatchers.Default) { index.search(query, scope) }
        searching = false
    }
    LaunchedEffect(requestFocus) { if (requestFocus) searchFocus.requestFocus() }
    Column(modifier) {
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(strings(Messages.ActionSearch)) },
            modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("editor-search-query").focusRequester(searchFocus))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ProjectSearchScope.ALL to strings(Messages.SearchAll), ProjectSearchScope.PROJECT to strings(Messages.SearchProject),
                ProjectSearchScope.LIBRARY to strings(Messages.ActionLibrary), ProjectSearchScope.COMMANDS to strings(Messages.CommandsHeading)).forEach { (value, title) ->
                FilterChip(selected = scope == value, onClick = { scope = value }, label = { Text(title, fontSize = 11.sp) })
            }
        }
        if (searching) Text(strings(Messages.SearchRunning), modifier = Modifier.padding(8.dp), fontSize = 11.sp)
        else if (results.isEmpty()) Text(strings(Messages.SearchEmpty), modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.id }) { result ->
                val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (result.enabled) 1f else 0.38f)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor-search-result-${result.id}")
                        .clickable(enabled = result.enabled) {
                            when (val target = result.target) {
                                is ProjectSearchTarget.Project -> onNavigate(target.location)
                                is ProjectSearchTarget.Library -> onInsert(target.kind)
                                is ProjectSearchTarget.Command -> onCommand(target.id)
                            }
                        }.semantics {
                            contentDescription = strings(Messages.A11ySearchResult, "title" to result.title, "subtitle" to result.subtitle)
                        }.padding(12.dp),
                ) {
                    Text(result.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
                    Text(result.subtitle, fontSize = 11.sp, color = color)
                }
            }
        }
    }
}
