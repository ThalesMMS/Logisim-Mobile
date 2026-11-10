package dev.logisim.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.localizedString

/** The host supplies action results and diagnostics here, never clock ticks or signal samples. */
@Composable
fun AccessibleEditorNotice(notice: LocalizedText?, modifier: Modifier = Modifier) {
    if (notice != null) Text(
        localizedString(notice),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
