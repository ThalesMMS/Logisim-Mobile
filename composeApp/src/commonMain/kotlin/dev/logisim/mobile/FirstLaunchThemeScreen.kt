package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun FirstLaunchThemeScreen(onContinue: (EditorThemeMode) -> PreferencesSaveResult) {
    var selectionName by rememberSaveable { mutableStateOf(EditorThemeMode.SYSTEM.name) }
    val selection = EditorThemeMode.valueOf(selectionName)
    var saveNotice by remember { mutableStateOf<LocalizedText?>(null) }
    val strings = rememberLocalizer()

    LogisimTheme(selection.resolveDark()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth().exportEditorTestTags().testTag("first-launch-theme")
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(strings(Messages.FirstLaunchThemeTitle), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall)
                    Text(strings(Messages.FirstLaunchThemeDescription), style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, label) in listOf(
                            EditorThemeMode.LIGHT to Messages.PreferencesThemeLight,
                            EditorThemeMode.DARK to Messages.PreferencesThemeDark,
                            EditorThemeMode.SYSTEM to Messages.FirstLaunchThemeSystem,
                        )) {
                            val selected = mode == selection
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MaterialTheme.shapes.medium)
                                    .background(if (selected) chromePalette.selected else MaterialTheme.colorScheme.surface)
                                    .testTag("first-launch-theme-${mode.name.lowercase()}")
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { selectionName = mode.name })
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(strings(label), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    saveNotice?.let { notice ->
                        Text(strings(notice), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { saveNotice = (onContinue(selection) as? PreferencesSaveResult.Failure)?.notice },
                        modifier = Modifier.fillMaxWidth().testTag("first-launch-theme-continue")) {
                        Text(strings(Messages.FirstLaunchThemeContinue))
                    }
                }
            }
        }
    }
}
