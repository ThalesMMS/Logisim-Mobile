package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TraceSignalOrderDialog(
    signals: List<TraceSignal>,
    enabled: Boolean,
    nameOf: (TraceSignal) -> TraceSignalName,
    onClose: () -> Unit,
    onMove: (TraceSignalReference, Int) -> Unit,
) {
    val localize = rememberLocalizer()
    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(localize(Messages.TraceOrderSignals)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(signals, key = { _, signal -> signal.reference.key }) { index, signal ->
                    // The name and instance path, not the internal key, tell same-named signals apart (C64, C160).
                    val name = nameOf(signal)
                    val moveUp = localize(Messages.TraceMoveSignalUp, "signal" to name.label, "reference" to name.instance)
                    val moveDown = localize(Messages.TraceMoveSignalDown, "signal" to name.label, "reference" to name.instance)
                    Column {
                        Text("${index + 1}. ${name.label}")
                        Text(name.instance, fontSize = 10.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onMove(signal.reference, -1) }, enabled = enabled && index > 0,
                                modifier = Modifier.semantics { contentDescription = moveUp }) {
                                Text(localize(Messages.ActionMoveUp))
                            }
                            TextButton(onClick = { onMove(signal.reference, 1) }, enabled = enabled && index < signals.lastIndex,
                                modifier = Modifier.semantics { contentDescription = moveDown }) {
                                Text(localize(Messages.ActionMoveDown))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(localize(Messages.TraceDone)) } },
    )
}
