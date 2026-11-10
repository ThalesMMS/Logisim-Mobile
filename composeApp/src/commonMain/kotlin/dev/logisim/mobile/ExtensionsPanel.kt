package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.ExtensionApproval
import dev.logisim.mobile.core.ExtensionReferenceScanner
import dev.logisim.mobile.core.ExtensionRegistration
import dev.logisim.mobile.core.MobileExtensionCatalog
import dev.logisim.mobile.core.StaticMobileExtension
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The application supplies compiled providers. Pasted manifests cannot create executable code. */
fun extensionsEditorTool(
    initialCatalog: MobileExtensionCatalog,
    providers: List<StaticMobileExtension> = emptyList(),
    onCatalogChanged: (MobileExtensionCatalog) -> Unit = {},
): EditorTool = EditorTool("extensions", Messages.ExtensionsTitle, titleText = message(Messages.ExtensionsTitle)) { context ->
    val strings = rememberLocalizer()
    var catalog by remember(initialCatalog) { mutableStateOf(initialCatalog) }
    var report by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }
    val projectXml = context.extensionDocumentXml?.invoke() ?: CircCodec.writeProject(context.project, catalog.registry)
    val envelope = remember(projectXml) { runCatching { ExtensionReferenceScanner.inspect(projectXml) }.getOrNull() }
    var showOriginal by remember(projectXml) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings(Messages.ExtensionsApiHeading))
        Text(strings(Messages.ExtensionsPolicy))
        Button(onClick = {
            report = runCatching {
                val result = ExtensionReferenceScanner.inspect(projectXml)
                buildList {
                    if (result.jarLibraries.isEmpty() && result.unknownLibraries.isEmpty()) add(message(Messages.ExtensionsNoneExternal))
                    result.jarLibraries.forEach { add(message(Messages.ExtensionsJarReference, "path" to it.path, "class" to it.className)) }
                    result.unknownLibraries.forEach { add(message(Messages.ExtensionsUnknownLibrary, "library" to it)) }
                }
            }.getOrElse { listOf(it.localizedDescription(message(Messages.ExtensionsErrorInspect))) }
        }) { Text(strings(Messages.ExtensionsInspect)) }
        if (report.isNotEmpty()) Text(report.joinToString("\n") { strings(it) })
        if (envelope?.jarLibraries?.isNotEmpty() == true) {
            Button(onClick = { showOriginal = !showOriginal }) { Text(strings("extensions_show_original")) }
            if (showOriginal) OutlinedTextField(projectXml, {}, readOnly = true, minLines = 3, maxLines = 8,
                label = { Text(strings("extensions_original_xml")) }, modifier = Modifier.fillMaxWidth())
        }
        if (providers.isEmpty()) Text(strings(Messages.ExtensionsNoProviders))
        providers.forEach { provider ->
            HorizontalDivider()
            ExtensionReview(provider, provider.manifest.id in catalog.installed.map { it.manifest.id }) { approval ->
                when (val result = catalog.install(provider, approval)) {
                    is ExtensionRegistration.Installed -> {
                        catalog = result.catalog
                        onCatalogChanged(result.catalog)
                        report = listOf(message(Messages.ExtensionsRegistered, "name" to provider.manifest.displayName))
                    }
                    is ExtensionRegistration.Rejected -> report = listOf(result.notice)
                }
                context.showNotice(report.first())
            }
            if (catalog.installed.any { it.manifest.id == provider.manifest.id }) {
                envelope?.jarLibraries?.filter { it.className in provider.manifest.legacyJarClasses }?.forEach { reference ->
                    val bound = provider.definitions.all { catalog.registry.resolve(reference.descriptor, it.fileName, emptyMap()) != null }
                    Text(strings(Messages.ExtensionsJarReference, "path" to reference.path, "class" to reference.className))
                    Button(enabled = !bound, onClick = {
                        runCatching {
                            val migrated = catalog.withLegacyLibrary(reference, provider.manifest.id)
                            onCatalogChanged(migrated)
                            catalog = migrated
                            message("extensions_bound", "path" to reference.path)
                        }.onSuccess { context.showNotice(it) }
                            .onFailure { context.showNotice(it.localizedDescription(message("extensions_error_bind"))) }
                    }) { Text(strings(if (bound) "extensions_bound_action" else "extensions_bind_action")) }
                }
            }
        }
    }
}

@Composable
private fun ExtensionReview(provider: StaticMobileExtension, installed: Boolean, install: (ExtensionApproval) -> Unit) {
    val manifest = provider.manifest
    val strings = rememberLocalizer()
    var accepted by remember(manifest.fingerprint) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Messages.ExtensionsProvider, "name" to manifest.displayName, "version" to manifest.version, "publisher" to manifest.publisher))
        Text(strings(Messages.ExtensionsApiLicense, "major" to manifest.apiMajor, "minor" to manifest.apiMinor, "license" to manifest.licenseId))
        Text(strings(Messages.ExtensionsCapabilities, "capabilities" to manifest.capabilities.joinToString { it.serialized }))
        Text(strings(Messages.ExtensionsComponents, "components" to manifest.components.joinToString { it.fileName }))
        Text(strings(Messages.ExtensionsFingerprint, "fingerprint" to manifest.fingerprint))
        OutlinedTextField(manifest.licenseNotice, {}, label = { Text(strings(Messages.ExtensionsLicenseNotice)) }, readOnly = true, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        if (installed) Text(strings(Messages.ExtensionsInstalled)) else {
            Text(strings(Messages.ExtensionsReview))
            Checkbox(checked = accepted, onCheckedChange = { accepted = it },
                modifier = Modifier.semantics { contentDescription = strings(Messages.ExtensionsAcceptA11y) })
            Button(enabled = accepted, onClick = {
                install(ExtensionApproval(manifest.fingerprint, manifest.capabilities, manifest.licenseId))
            }) { Text(strings(Messages.ExtensionsAccept)) }
        }
    }
}
