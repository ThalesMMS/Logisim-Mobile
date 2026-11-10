package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/** Pairing requests run only after the user presses Verify host; this panel submits no jobs. */
@Composable
fun CompanionPairingPanel(
    onConnected: (CompanionClient, CompanionCapabilities) -> Unit,
    onDisconnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberLocalizer()
    var address by remember { mutableStateOf("http://127.0.0.1:8042") }
    var token by remember { mutableStateOf("") }
    var capabilities by remember { mutableStateOf<CompanionCapabilities?>(null) }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
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
            label = { Text(strings(Messages.CompanionUiAddress)) }, modifier = Modifier.fillMaxWidth())
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
                    notice = message(Messages.CompanionUiConnectionInvalid)
                    return@Button
                }
                val client = CompanionClient(connection)
                val revision = ++generation
                pending = scope.launch {
                    try {
                        val result = client.capabilities()
                        if (revision == generation) {
                            capabilities = result
                            onConnected(client, result)
                            token = ""
                            notice = message(Messages.CompanionUiVerified)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: CompanionProtocolException) { if (revision == generation) notice = companionPairingFailure(error) }
                    catch (error: CompanionTransportException) { if (revision == generation) notice = companionPairingFailure(error) }
                    finally { if (revision == generation) pending = null }
                }
            }) { Text(if (pending?.isActive == true) strings(Messages.CompanionUiVerifyPending) else strings(Messages.CompanionUiVerify)) }
            TextButton(onClick = { val wasConnected = capabilities != null; invalidate(); token = ""; if (!wasConnected) onDisconnected() }) { Text(strings(Messages.CompanionUiDisconnect)) }
        }
        notice?.let { Text(strings(it)) }
        capabilities?.let { result ->
            Text(strings.plural(Messages.CompanionUiCapabilities, result.limits.inputBytes, "version" to result.protocolVersion))
            result.limits.requestReceipts?.let { Text(strings.plural(Messages.CompanionUiReceipts, it)) }
            Text(if (result.limits.osSandbox) strings(Messages.CompanionUiOsSandbox) else strings(Messages.CompanionUiNoOsSandbox))
            result.operations.forEach { operation ->
                Text(strings(Messages.CompanionUiOperationStatus, "name" to operation.name,
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
