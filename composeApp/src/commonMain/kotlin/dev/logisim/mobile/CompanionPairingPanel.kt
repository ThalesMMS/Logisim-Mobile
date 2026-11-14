package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CompanionCapabilities
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionConnection
import dev.logisim.mobile.core.CompanionOperationCapability
import dev.logisim.mobile.core.CompanionEndpoint
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun companionPairingEditorTool(
    onConnected: (CompanionClient, CompanionCapabilities) -> Unit,
    onDisconnected: () -> Unit,
): EditorTool = EditorTool("companion-pairing", "Companion", titleText = message(Messages.CompanionUiTitle)) {
    CompanionPairingPanel(onConnected, onDisconnected)
}

private const val COMPANION_RECENT_HOSTS = "companion.recentHosts"
private const val COMPANION_DEFAULT_HOST = "http://127.0.0.1:8042"

/** The last verified companion addresses, newest first. Only endpoints are kept; the token is never stored. */
internal val EditorPreferences.companionRecentHosts: List<String>
    get() = extraValues[COMPANION_RECENT_HOSTS].orEmpty().split(' ').filter { it.isNotBlank() }.take(3)

/** Remembers [endpoint] as the most recent verified host; up to three are kept. */
internal fun EditorPreferences.withCompanionRecentHost(endpoint: String): EditorPreferences =
    copy(extraValues = extraValues + (COMPANION_RECENT_HOSTS to
        (listOf(endpoint) + companionRecentHosts.filter { it != endpoint }).take(3).joinToString(" ")))

/** Pairing requests run only after the user presses Verify host; this panel submits no jobs. */
@Composable
fun CompanionPairingPanel(
    onConnected: (CompanionClient, CompanionCapabilities) -> Unit,
    onDisconnected: () -> Unit,
    modifier: Modifier = Modifier,
    clientFactory: (CompanionConnection) -> CompanionClient = { CompanionClient(it) },
) {
    val strings = rememberLocalizer()
    val preferences = LocalEditorPreferences.current
    val recentHosts = preferences?.preferences?.companionRecentHosts.orEmpty()
    // Pairing starts from the last verified host instead of the phone's own loopback address.
    var address by remember { mutableStateOf(recentHosts.firstOrNull() ?: COMPANION_DEFAULT_HOST) }
    var token by remember { mutableStateOf("") }
    var capabilities by remember { mutableStateOf<CompanionCapabilities?>(null) }
    var notice by remember { mutableStateOf<ToolNotice?>(null) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { pending?.cancel() } }
    fun invalidate() {
        generation++
        pending?.cancel(); pending = null
        if (capabilities != null) onDisconnected()
        capabilities = null; notice = null
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings(Messages.CompanionUiTitle), style = MaterialTheme.typography.titleLarge)
        Text(strings(Messages.CompanionUiHelp))
        OutlinedTextField(address, { invalidate(); address = it }, singleLine = true,
            label = { Text(strings(Messages.CompanionUiAddress)) }, keyboardOptions = FieldKeyboards.Uri, modifier = Modifier.fillMaxWidth())
        if (recentHosts.isNotEmpty()) {
            Column(Modifier.selectableGroup()) {
                Text(strings("companion_recent_hosts"), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() })
                recentHosts.forEach { host ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("companion-recent-host")
                        .selectable(address == host, role = Role.RadioButton) { if (address != host) { invalidate(); address = host } },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(address == host, onClick = null)
                        Text(host, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        OutlinedTextField(token, { invalidate(); token = it }, singleLine = true,
            label = { Text(strings(Messages.CompanionUiToken)) }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = strings(Messages.CompanionUiTokenA11y) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pending?.isActive != true && token.isNotBlank(), onClick = {
                notice = null
                val connection = try {
                    CompanionConnection(CompanionEndpoint.parse(address), CompanionToken.parse(token))
                } catch (_: IllegalArgumentException) {
                    notice = message(Messages.CompanionUiConnectionInvalid).asError()
                    return@Button
                }
                val client = clientFactory(connection)
                val revision = ++generation
                pending = scope.launch {
                    try {
                        val result = client.capabilities()
                        if (revision == generation) {
                            capabilities = result
                            preferences?.let { it.onChange(it.preferences.withCompanionRecentHost(connection.endpoint.url)) }
                            onConnected(client, result)
                            token = ""
                            notice = message(Messages.CompanionUiVerified).asResult()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: CompanionProtocolException) { if (revision == generation) notice = companionPairingFailure(error).asError() }
                    catch (error: CompanionTransportException) { if (revision == generation) notice = companionPairingFailure(error).asError() }
                    finally { if (revision == generation) pending = null }
                }
            }) { Text(if (pending?.isActive == true) strings(Messages.CompanionUiVerifyPending) else strings(Messages.CompanionUiVerify)) }
            TextButton(onClick = { val wasConnected = capabilities != null; invalidate(); token = ""; if (!wasConnected) onDisconnected() }) { Text(strings(Messages.CompanionUiDisconnect)) }
        }
        ToolNoticeText(notice)
        capabilities?.let { result ->
            Text(strings.plural(Messages.CompanionUiCapabilities, result.limits.inputBytes, "version" to result.protocolVersion))
            result.limits.requestReceipts?.let { Text(strings.plural(Messages.CompanionUiReceipts, it)) }
            Text(if (result.limits.osSandbox) strings(Messages.CompanionUiOsSandbox) else strings(Messages.CompanionUiNoOsSandbox))
            result.operations.forEach { operation ->
                Text(strings(Messages.CompanionUiOperationStatus, "name" to companionOperationLabel(operation),
                    "status" to companionOperationStatus(operation)))
            }
        }
    }
}

internal fun companionPairingFailure(error: CompanionProtocolException): LocalizedText =
    message(Messages.CompanionUiProtocolError, "code" to error.code, "detail" to error.message)

internal fun companionPairingFailure(error: CompanionTransportException): LocalizedText =
    message(Messages.CompanionUiConnectionFailed, "code" to error.code)

internal fun companionOperationStatus(operation: CompanionOperationCapability): LocalizedText = message(when {
    !operation.allowed -> Messages.CompanionUiOperationDisabled
    !operation.available -> Messages.CompanionUiOperationUnavailable
    operation.requiresConfirmation -> Messages.CompanionUiOperationConfirmation
    else -> Messages.CompanionUiOperationAvailable
})
