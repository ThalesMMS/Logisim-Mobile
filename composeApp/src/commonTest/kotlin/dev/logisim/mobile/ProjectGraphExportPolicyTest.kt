package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectGraphExportPolicyTest {
    @Test
    fun preservedRawXmlCannotBecomeAnExportOfThePlaceholderGraph() {
        val policy = ProjectGraphExportPolicy(EditorProjectAccess.PRESERVED_RAW)
        var delivered = false
        var notice: LocalizedText? = null
        assertFalse(policy.export({ notice = it }) { delivered = true })
        assertFalse(delivered)
        assertEquals("project_graph_unavailable", notice?.key)
    }

    @Test
    fun aParsedReadOnlyGraphCanStillBeExported() {
        val policy = ProjectGraphExportPolicy(EditorProjectAccess.READ_ONLY)
        var delivered = false
        assertTrue(policy.export({ error("unexpected rejection") }) { delivered = true })
        assertTrue(delivered)
        assertFalse(policy.access.writable)
    }

    @Test
    fun aRequestCannotDeliverAfterItsOriginalDocumentChanged() {
        var identity = 7L
        val policy = ProjectGraphExportPolicy(EditorProjectAccess.EDITABLE) { identity == 7L }
        assertTrue(policy.available)
        identity = 8L
        var delivered = false
        var notice: LocalizedText? = null
        assertFalse(policy.export({ notice = it }) { delivered = true })
        assertFalse(delivered)
        assertEquals("project_export_obsolete", notice?.key)
    }

    @Test
    fun aClosedEditorCannotDeliverAnOtherwiseValidReadOnlyGraph() {
        var alive = true
        val policy = ProjectGraphExportPolicy(EditorProjectAccess.READ_ONLY) { alive }
        alive = false
        var delivered = false
        assertFalse(policy.export({}) { delivered = true })
        assertFalse(delivered)
    }
}
