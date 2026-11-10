// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsScreensTest {
    private fun screen(id: String) = SettingsScreen.ALL.first { it.id == id }

    @Test
    fun foundInBothLanguagesWithoutAccents() {
        assertEquals(0, screen("wireless_debugging").rank("depuracao"))
        assertEquals(0, screen("wireless_debugging").rank("wireless"))
        assertEquals(0, screen("display").rank("ecr"))
        assertEquals(0, screen("location").rank("local"))
    }

    @Test
    fun foundBySynonymsAndWords() {
        assertEquals(0, screen("wireless_debugging").rank("adb"))
        assertEquals(1, screen("wireless_debugging").rank("debugging"))
        assertEquals(0, screen("bluetooth").rank("bt"))
        assertEquals(2, screen("developer").rank("gramador"))
    }

    @Test
    fun shortTextsInsideAWordDoNotCount() {
        assertNull(screen("bluetooth").rank("oo"))
        assertNull(screen("bluetooth").rank(""))
    }

    @Test
    fun wirelessDebuggingHighlightsItsPreference() {
        assertEquals("toggle_adb_wireless", screen("wireless_debugging").highlight)
    }
}
