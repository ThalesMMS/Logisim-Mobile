package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val grayLegacyProjectXml = """
    <project source="2.7.1" version="1.0">
      <lib desc="#Wiring" name="0"/>
      <lib desc="jar#gray-1.0.jar#com.cburch.gray.Components" name="1"/>
      <main name="main"/><circuit name="main">
        <comp lib="0" loc="(0,0)" name="Pin"><a name="width" val="4"/></comp>
        <comp lib="1" loc="(30,0)" name="Gray Code Incrementer"/>
        <comp lib="0" loc="(30,0)" name="Pin"><a name="output" val="true"/><a name="width" val="4"/></comp>
      </circuit>
    </project>
""".trimIndent()

internal fun installedGrayCatalog(): MobileExtensionCatalog {
    val provider = GrayCodeExtension.provider
    return assertIs<ExtensionRegistration.Installed>(MobileExtensionCatalog(ComponentCatalog.registry).install(provider,
        ExtensionApproval(provider.manifest.fingerprint, provider.manifest.capabilities, provider.manifest.licenseId))).catalog
}

class GrayCodeEditorTest {
    @Test
    fun preservedProjectBecomesEditableOnlyAfterExplicitLegacyBinding() {
        val state = EditorState(grayLegacyProjectXml)
        assertFalse(state.documentWritable)
        assertEquals(grayLegacyProjectXml, state.exportXml())
        assertNotNull(state.importError)
        val catalog = installedGrayCatalog()
        state.updateExtensionRegistry(catalog.registry)
        assertFalse(state.documentWritable)
        assertEquals(grayLegacyProjectXml, state.exportExtensionSourceXml())
        val reference = ExtensionReferenceScanner.inspect(state.exportExtensionSourceXml()).jarLibraries.single()
        state.updateExtensionRegistry(catalog.withLegacyLibrary(reference, GrayCodeExtension.provider.manifest.id).registry)
        assertTrue(state.documentWritable)
        assertEquals(EditorProjectAccess.EDITABLE, state.projectAccess)
        verifyCircuit(state)
        assertTrue(state.exportXml().contains(reference.descriptor))
    }

    @Test
    fun failedImportPreservesCurrentDocumentAndKeepsCandidateForExplicitMigration() {
        val state = EditorState(null)
        state.insertComponent(ComponentKind.INPUT, GridPoint(400, 400))
        val original = state.exportXml()
        assertFalse(state.importXml(grayLegacyProjectXml))
        assertTrue(state.jarImportRecoveryAvailable)
        assertEquals(original, state.exportXml())
        state.dismissImportError()
        assertFalse(state.jarImportRecoveryAvailable)
        assertEquals(grayLegacyProjectXml, state.exportExtensionSourceXml())
        val catalog = installedGrayCatalog()
        state.updateExtensionRegistry(catalog.registry)
        assertEquals(original, state.exportXml())
        assertTrue(state.canUndo)
        val reference = ExtensionReferenceScanner.inspect(state.exportExtensionSourceXml()).jarLibraries.single()
        state.updateExtensionRegistry(catalog.withLegacyLibrary(reference, GrayCodeExtension.provider.manifest.id).registry)
        verifyCircuit(state)
        state.undo()
        assertEquals(original, state.exportXml())
    }

    private fun verifyCircuit(state: EditorState) {
        val input = state.document.components.single { it.kind == ComponentKind.INPUT }
        val output = state.document.components.single { it.kind == ComponentKind.OUTPUT }
        state.setInputValue(input.id, 0uL)
        assertEquals(1uL, state.rootSimulation.vectorValues.getValue(output.id).toULongOrNull())
        state.setInputValue(input.id, 8uL)
        assertEquals(0uL, state.rootSimulation.vectorValues.getValue(output.id).toULongOrNull())
        val incrementer = state.document.components.single { it.kind.name.startsWith("GrayCodeIncrementer") }
        state.selectComponent(incrementer.id)
        assertTrue(PropertyEditorModel.fields(state.componentRegistry, listOf(incrementer)).any { it.name == "width" && it.value == "4" })
    }
}
