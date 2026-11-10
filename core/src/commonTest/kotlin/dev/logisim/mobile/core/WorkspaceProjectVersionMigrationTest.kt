package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceProjectVersionMigrationTest {
    @Test fun versionsOneTwoAndThreeKeepLegacyKeysAndReferencesWhenRewrittenAsFour() {
        val reference = WorkspaceAttachmentReference("original.zip", "application/zip", 4L,
            "2c8b18bbdba72a9d0f8e99107a42b35ca364567c0b0ef0e8d3c14a3cb8209616")
        val fixtures: List<Pair<String, Map<String, WorkspaceAttachmentReference>>> = listOf(
            VERSION_ONE to emptyMap(), VERSION_TWO to emptyMap(), VERSION_THREE to mapOf("portable-source" to reference),
        )
        for ((raw, attachments) in fixtures) {
            val document = assertNotNull(WorkspaceCatalogFormat.readDocument(raw))
            assertEquals(WorkspaceViewportKeyFormat.LEGACY, document.viewState.viewportKeyFormat)
            assertEquals(mapOf("4:leaf" to WorkspaceViewport(2f, -10f, 20f)), document.viewState.viewports)
            assertEquals(attachments, document.attachments)

            val renamed = document.copy(info = document.info.copy(name = "Renamed"))
            val rewritten = WorkspaceCatalogFormat.writeDocument(renamed)
            assertTrue(rewritten.startsWith("LOGISIM-PROJECT\n4\n"))
            val reopened = assertNotNull(WorkspaceCatalogFormat.readDocument(rewritten))
            assertEquals(renamed, reopened)
            assertEquals(WorkspaceViewportKeyFormat.LEGACY, reopened.viewState.viewportKeyFormat)
            assertEquals(attachments, reopened.attachments)

            val project = CircuitProject(listOf(CircuitDocument("Leaf", id = "leaf"), CircuitDocument("Literal", id = "4:leaf")))
            val migrated = reopened.copy(viewState = reopened.viewState.compatibleWith(project))
            val saved = assertNotNull(WorkspaceCatalogFormat.readDocument(WorkspaceCatalogFormat.writeDocument(migrated)))
            assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, saved.viewState.viewportKeyFormat)
            assertEquals(mapOf(workspaceNavigationKey("4:leaf", emptyList()) to WorkspaceViewport(2f, -10f, 20f)), saved.viewState.viewports)
            assertEquals(attachments, saved.attachments)
            assertEquals(document.xml, saved.xml)
        }
    }

    @Test fun versionFourRejectsAnUnknownViewportFormatInsteadOfAssumingLegacy() {
        val document = assertNotNull(WorkspaceCatalogFormat.readDocument(VERSION_THREE))
        val raw = WorkspaceCatalogFormat.writeDocument(document)
        val body = raw.substringAfter("\n\n").replaceFirst("6:LEGACY", "7:UNKNOWN")
        val unknown = "LOGISIM-PROJECT\n4\n${WorkspaceCatalogFormat.checksum(body)}\n\n$body"
        assertNull(WorkspaceCatalogFormat.readDocument(unknown))
    }

    companion object {
        private const val VERSION_ONE = "LOGISIM-PROJECT\n1\n70d76cbbf0640eff\n\n2:p11:A1:11:01:05:false1:01:088:<project><circuit name=\"Leaf\" id=\"leaf\"/><circuit name=\"Literal\" id=\"4:leaf\"/></project>6:4:leaf1:01:16:4:leaf3:2.05:-10.04:20.0"
        private const val VERSION_TWO = "LOGISIM-PROJECT\n2\n3621f5bc43191671\n\n2:p11:A1:11:01:05:false1:01:088:<project><circuit name=\"Leaf\" id=\"leaf\"/><circuit name=\"Literal\" id=\"4:leaf\"/></project>6:4:leaf1:01:16:4:leaf3:2.05:-10.04:20.01:01:0"
        private const val VERSION_THREE = "LOGISIM-PROJECT\n3\nef622e4482b73802\n\n2:p11:A1:11:01:05:false1:01:088:<project><circuit name=\"Leaf\" id=\"leaf\"/><circuit name=\"Literal\" id=\"4:leaf\"/></project>6:4:leaf1:01:16:4:leaf3:2.05:-10.04:20.01:01:01:115:portable-source12:original.zip15:application/zip1:464:2c8b18bbdba72a9d0f8e99107a42b35ca364567c0b0ef0e8d3c14a3cb8209616"
    }
}
