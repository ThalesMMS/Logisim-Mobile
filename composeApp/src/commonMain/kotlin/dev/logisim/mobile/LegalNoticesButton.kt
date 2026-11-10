package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun LegalNoticesButton() {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)
        .semantics { contentDescription = strings(Messages.LegalButtonDescription) }) {
        Text(strings(Messages.LegalButton), fontSize = 12.sp)
    }
    if (open) LegalNoticesDialog { open = false }
}

@Composable
internal fun LegalNoticesDialog(onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    var selected by remember { mutableStateOf<Int?>(null) }
    val uri = LocalUriHandler.current
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selected?.let { LegalNoticeContent.notices[it].title } ?: strings(Messages.LegalTitle)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (selected == null) {
                    Text(strings(Messages.LegalIntroduction))
                    AppIdentity.sourceReleaseUrl?.let { sourceUrl ->
                        TextButton(onClick = { uri.openUri(sourceUrl) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(strings(Messages.LegalSourceAction))
                        }
                    }
                    LegalNoticeContent.notices.forEachIndexed { index, notice ->
                        TextButton(onClick = { selected = index }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(notice.title)
                        }
                    }
                } else {
                    Text(LegalNoticeContent.notices[selected!!].text, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } },
        dismissButton = {
            if (selected != null) TextButton(onClick = { selected = null }) { Text(strings(Messages.LegalBackAction)) }
        },
    )
}
