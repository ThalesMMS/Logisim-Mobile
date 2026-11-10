package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalNoticeContentTest {
    @Test
    fun noticesIncludeProjectRightsAndOriginalExampleAttribution() {
        val notices = LegalNoticeContent.notices.associate { it.title to it.text }
        assertEquals(LegalNoticeContent.notices.size, notices.size)
        assertTrue(notices.getValue("Logisim Mobile").contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(notices.getValue("Logisim Mobile").contains("Version 3, 29 June 2007"))
        assertTrue(notices.getValue("SAP-1 example").contains("Copyright (c) 2024 Thales"))
        assertTrue(notices.getValue("SAP-1 example").contains("MIT License"))
    }

    @Test
    fun nativeDependenciesRetainTheirSeparateNotices() {
        val text = LegalNoticeContent.notices.single { it.title == "Skia and native dependencies" }.text
        assertTrue(text.contains("Independent JPEG Group"))
        assertTrue(text.contains("DNG SDK License Agreement"))
        assertTrue(text.contains("ICU"))
        assertTrue(text.contains("harfbuzz-LICENSE.txt"))
        assertTrue(text.contains("libjpeg-turbo-README.ijg"))
    }
}
