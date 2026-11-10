package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages

class MobileExtensionsTest {
    private val kind = ComponentKind("ExampleInvert")
    private val manifest = MobileExtensionManifest("example.inverter", "1.0.0", "Inverter", "Example Author",
        "GPL-3.0-only", "Copyright Example Author\nSPDX-License-Identifier: GPL-3.0-only",
        capabilities = setOf(ExtensionCapability.PURE_LOGIC),
        components = listOf(ExtensionComponentSpec(kind.name, "Inverter", "#example.inverter")),
        legacyJarClasses = setOf("org.example.InverterLibrary"))
    private val definition = ComponentDefinition(kind, "Inverter", "#example.inverter",
        layout = { component, _ -> ComponentLayout(listOf(
            ComponentTerminal("in", component.position.copy(x = component.position.x - 20), PortDirection.INPUT),
            ComponentTerminal("out", component.position, PortDirection.OUTPUT)), ComponentBounds(-20, -10, 0, 10)) },
        behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(
                mapOf("out" to context.inputs.getValue("in").inverted()))
        }, isCombinational = true)
    private fun approval(m: MobileExtensionManifest = manifest) = ExtensionApproval(m.fingerprint, m.capabilities, m.licenseId)
    private fun provider(m: MobileExtensionManifest = manifest) = StaticMobileExtension(m, listOf(definition))

    @Test
    fun sha256MatchesPublishedVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ExtensionDigest.sha256(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ExtensionDigest.sha256("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ExtensionDigest.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
    }

    @Test
    fun manifestRoundTripPreservesLicenseAndApi() {
        val xml = MobileExtensionManifestCodec.write(manifest)
        assertEquals(manifest, MobileExtensionManifestCodec.read(xml))
        assertEquals(manifest.fingerprint, MobileExtensionManifestCodec.read(xml.replace(">\n", ">\r\n")).fingerprint)
        assertNotEquals(manifest.fingerprint, manifest.copy(licenseNotice = "Changed notice").fingerprint)
    }

    @Test
    fun manifestRejectsUnknownCapabilitiesAndUndeclaredContent() {
        val xml = MobileExtensionManifestCodec.write(manifest)
        assertFailsWith<IllegalArgumentException> { MobileExtensionManifestCodec.read(xml.replace("pure-logic", "download-and-execute")) }
        assertFailsWith<IllegalArgumentException> { MobileExtensionManifestCodec.read(xml.replace("<notice>", "<notice arbitrary=\"yes\">")) }
        assertFailsWith<IllegalArgumentException> { MobileExtensionManifestCodec.read(xml.replace("</mobile-extension>", "<script>exec x</script></mobile-extension>")) }
        assertFailsWith<IllegalArgumentException> { MobileExtensionManifestCodec.read("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><mobile-extension/>") }
    }

    @Test
    fun approvedStaticExtensionRegistersAndEvaluatesWithoutMutatingOtherCatalogs() {
        val original = MobileExtensionCatalog(ComponentRegistry(emptyList()))
        val installed = assertIs<ExtensionRegistration.Installed>(original.install(provider(), approval())).catalog
        assertNull(original.registry.definition(kind))
        val component = installed.registry.create(kind, "inv", GridPoint(30, 20))
        val registered = installed.registry.requireDefinition(kind)
        val result = registered.behavior!!.evaluate(ComponentEvaluationContext(component, registered.schema.parse(emptyMap()),
            mapOf("in" to LogicVector.high(1)), setOf("in")))
        assertEquals(0uL, result.outputs.getValue("out").toULongOrNull())
        assertEquals(1L, result.delay)
        assertIs<ExtensionRegistration.Rejected>(installed.install(provider(), approval()))
    }

    @Test
    fun changedFingerprintApiLicenseAndCapabilitiesAreRejectedAtomically() {
        val original = MobileExtensionCatalog(ComponentRegistry(emptyList()))
        assertIs<ExtensionRegistration.Rejected>(original.install(provider(manifest.copy(version = "1.0.1")), approval()))
        val future = manifest.copy(apiMajor = 2)
        assertIs<ExtensionRegistration.Rejected>(original.install(provider(future), approval(future)))
        val customLicense = manifest.copy(licenseId = "LicenseRef-Unreviewed")
        assertIs<ExtensionRegistration.Rejected>(original.install(provider(customLicense), approval(customLicense)))
        val remote = manifest.copy(capabilities = setOf(ExtensionCapability.COMPANION_JAR))
        assertIs<ExtensionRegistration.Rejected>(original.install(provider(remote), approval(remote)))
        assertTrue(original.registry.definitions.isEmpty())
    }

    @Test
    fun installedMetadataAndPolicyDoNotRetainMutableCallerCollections() {
        val allowed = mutableSetOf(ExtensionCapability.PURE_LOGIC)
        val capabilities = mutableSetOf(ExtensionCapability.PURE_LOGIC)
        val definitions = mutableListOf(definition)
        val mutableManifest = manifest.copy(capabilities = capabilities)
        val original = MobileExtensionCatalog(ComponentRegistry(emptyList()), ExtensionPolicy(allowedCapabilities = allowed))
        val installed = assertIs<ExtensionRegistration.Installed>(original.install(
            StaticMobileExtension(mutableManifest, definitions), approval(mutableManifest))).catalog
        allowed.clear()
        capabilities.clear()
        definitions.clear()
        assertEquals(setOf(ExtensionCapability.PURE_LOGIC), installed.policy.allowedCapabilities)
        assertEquals(setOf(ExtensionCapability.PURE_LOGIC), installed.installed.single().manifest.capabilities)
        assertEquals(kind, installed.registry.definitions.single().kind)
    }

    @Test
    fun rejectionKeepsItsCanonicalIdentityAndResolvesInBothLanguages() {
        val future = manifest.copy(apiMajor = 2)
        val rejected = assertIs<ExtensionRegistration.Rejected>(MobileExtensionCatalog(ComponentRegistry(emptyList()))
            .install(provider(future), approval(future)))
        assertEquals(Messages.ExtensionsErrorApi, rejected.notice.key)
        assertEquals("Extension API 2.0 is unavailable.", rejected.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("A API de extensão 2.0 está indisponível.", rejected.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun providerMustMatchDeclaredComponentsAndStatefulCapability() {
        val catalog = MobileExtensionCatalog(ComponentRegistry(emptyList()))
        assertIs<ExtensionRegistration.Rejected>(catalog.install(StaticMobileExtension(manifest, emptyList()), approval()))
        assertIs<ExtensionRegistration.Rejected>(catalog.install(StaticMobileExtension(manifest,
            listOf(definition.copy(isCombinational = false))), approval()))
        val occupied = MobileExtensionCatalog(ComponentRegistry(listOf(definition)))
        assertIs<ExtensionRegistration.Rejected>(occupied.install(provider(), approval()))
        val shadowed = MobileExtensionCatalog(ComponentRegistry(listOf(definition.copy(kind = ComponentKind("OtherKind")))))
        assertIs<ExtensionRegistration.Rejected>(shadowed.install(provider(), approval()))
    }

    @Test
    fun jarAndUnknownLibraryInspectionPreservesOriginalWithoutExecution() {
        val xml = """<?xml version="1.0"?>
            <project><lib desc="jar#lib/a#b.jar#org.example.InverterLibrary" name="9"><tool name="Inverter"/></lib>
            <lib name="10" desc="#Uninstalled"/><main name="main"/><circuit name="main">
            <comp lib="9" name="Inverter" loc="(20,20)"><a name="label" val=" Keep me "/></comp>
            </circuit></project>""".trimIndent()
        val envelope = ExtensionReferenceScanner.inspect(xml)
        assertEquals(xml, envelope.exportOriginal())
        assertEquals("lib/a#b.jar", envelope.jarLibraries.single().path)
        assertEquals("org.example.InverterLibrary", envelope.jarLibraries.single().className)
        assertEquals(listOf("#Uninstalled"), envelope.unknownLibraries)
    }

    @Test
    fun jarMigrationRequiresInstalledMatchingProviderAndRetainsDescriptor() {
        val reference = JarLibraryReference("9", "jar#inverter.jar#org.example.InverterLibrary", "inverter.jar", "org.example.InverterLibrary", "<lib/>")
        val empty = MobileExtensionCatalog(ComponentRegistry(emptyList()))
        assertFailsWith<IllegalArgumentException> { empty.bindLegacyLibrary(reference, manifest.id) }
        val installed = assertIs<ExtensionRegistration.Installed>(empty.install(provider(), approval())).catalog
        val migrated = installed.bindLegacyLibrary(reference, manifest.id)
        val resolved = migrated.resolve(reference.descriptor, "Inverter", emptyMap())!!
        assertEquals(reference.descriptor, resolved.library)
        assertEquals("ExampleInvert@9", resolved.kind.name)
        assertFailsWith<IllegalArgumentException> { installed.bindLegacyLibrary(reference.copy(className = "unknown.Class"), manifest.id) }
    }

    @Test
    fun sessionBindingsAreIdempotentAndSurviveAdditionalRegistrations() {
        val reference = JarLibraryReference("9", "jar#inverter.jar#org.example.InverterLibrary", "inverter.jar", "org.example.InverterLibrary", "<lib/>")
        val original = assertIs<ExtensionRegistration.Installed>(MobileExtensionCatalog(ComponentRegistry(emptyList()))
            .install(provider(), approval())).catalog
        val migrated = original.withLegacyLibrary(reference, manifest.id)
        assertNull(original.registry.resolve(reference.descriptor, "Inverter", emptyMap()))
        assertEquals(migrated.registry.definitions, migrated.withLegacyLibrary(reference, manifest.id).registry.definitions)
        val otherPath = reference.copy(descriptor = "jar#other/inverter.jar#org.example.InverterLibrary", path = "other/inverter.jar")
        val both = migrated.withLegacyLibrary(otherPath, manifest.id)
        assertNotEquals(both.registry.resolve(reference.descriptor, "Inverter", emptyMap())!!.kind,
            both.registry.resolve(otherPath.descriptor, "Inverter", emptyMap())!!.kind)
        val gray = GrayCodeExtension.provider
        val extended = assertIs<ExtensionRegistration.Installed>(both.install(gray,
            ExtensionApproval(gray.manifest.fingerprint, gray.manifest.capabilities, gray.manifest.licenseId))).catalog
        assertEquals(both.registry.resolve(reference.descriptor, "Inverter", emptyMap()),
            extended.registry.resolve(reference.descriptor, "Inverter", emptyMap()))
    }
}
