package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceProjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceDocumentLinksTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T) = action()
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }
    private val a = WorkspaceProjectId("A")
    private val b = WorkspaceProjectId("B")
    private fun link(id: WorkspaceProjectId) = WorkspaceExternalDocument(id,
        DocumentReference("opaque:🔐\nbookmark==$id", "Arquivo \"$id\": 📁.circ", true, "sha256:$id", DocumentOpenMode.EDIT_IN_PLACE),
        workspaceContentStamp("xml $id"))

    @Test fun opaqueUnicodeBookmarksAndWindowIdsRoundTripWithoutCrossAssociation() {
        val store = Store(); val links = StoredWorkspaceDocumentLinks(store)
        links.write(link(a)); links.write(link(b))
        links.selectWindowProject("cena 🪟 1", a); links.selectWindowProject("cena 🪟 2", b)
        val relaunched = StoredWorkspaceDocumentLinks(store)
        assertEquals(link(a), relaunched.read(a)); assertEquals(link(b), relaunched.read(b))
        assertEquals(a, relaunched.windowProject("cena 🪟 1")); assertEquals(b, relaunched.windowProject("cena 🪟 2"))
        relaunched.selectWindowProject("cena 🪟 1", null)
        assertNull(relaunched.windowProject("cena 🪟 1")); assertEquals(b, relaunched.windowProject("cena 🪟 2"))
    }

    @Test fun corruptOriginalIsPreservedBeforeExplicitRelinking() {
        val store = Store(); val links = StoredWorkspaceDocumentLinks(store)
        val name = "external-A.workspace-link"
        store.files[name] = "corrupt original bookmark"
        assertFails { links.read(a) }
        links.write(link(a))
        assertEquals(link(a), links.read(a))
        assertTrue(store.files.filterKeys { it.endsWith(".invalid") }.values.contains("corrupt original bookmark"))
    }

    @Test fun futureLinkVersionBlocksWritesAndWrongProjectHeaderIsRejected() {
        val store = Store(); val links = StoredWorkspaceDocumentLinks(store)
        links.write(link(a)); val name = "external-A.workspace-link"
        val valid = store.files.getValue(name)
        store.files[name] = valid.replace("LOGISIM-LINK\n1\n", "LOGISIM-LINK\n2\n")
        val future = store.files.toMap()
        assertFails { links.read(a) }; assertFails { links.write(link(a)) }
        assertEquals(future, store.files)
        store.files["external-B.workspace-link"] = valid
        assertFails { links.read(b) }
    }
}
