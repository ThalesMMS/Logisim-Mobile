package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.DemoCircuits
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals

class NewCircuitNameTest {
    @Test fun eachLanguageNamesTheNewCircuitLikeEvolution() {
        // Evolution d58a61e0, proj*.properties newCircuitName.
        val expected = mapOf("en" to "main", "pt" to "principal", "de" to "main", "es" to "principal", "fr" to "principal",
            "nl" to "hoofdcircuit", "pl" to "główny", "tr" to "ana", "el" to "κύριο", "ja" to "main")
        for ((tag, name) in expected) {
            assertEquals(name, message(Messages.NewCircuitName).resolve(AppLanguage.fromTag(tag)), tag)
        }
    }

    @Test fun newDocumentUsesTheGivenCircuitName() {
        val state = EditorState(CircCodec.write(DemoCircuits.andGate()))
        state.newDocument(circuitName = "principal")
        assertEquals("principal", state.project.mainCircuitName)
        state.newDocument(circuitName = " ")
        assertEquals("main", state.project.mainCircuitName)
    }
}
