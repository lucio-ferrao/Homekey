// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShortcutsFilterTest {
    private val enabled = setOf("org.ferrao.termarchy")
    private val removed = setOf("shortcut://org.ferrao.termarchy/herdr:1:term_x")

    @Test fun onlyTheAppsTurnedOn() {
        assertTrue(AppLauncherMode.isShortcutShown(false, "org.ferrao.termarchy", "shortcut://org.ferrao.termarchy/a", enabled, removed))
        assertFalse(AppLauncherMode.isShortcutShown(false, "com.google.android.gm", "shortcut://com.google.android.gm/a", enabled, removed))
    }

    @Test fun pinnedAlwaysShown() {
        assertTrue(AppLauncherMode.isShortcutShown(true, "com.google.android.gm", "shortcut://com.google.android.gm/a", emptySet(), emptySet()))
        assertTrue(AppLauncherMode.isShortcutShown(true, "org.ferrao.termarchy", "shortcut://org.ferrao.termarchy/herdr:1:term_x", enabled, removed))
    }

    @Test fun removedStaysOut() {
        assertFalse(AppLauncherMode.isShortcutShown(false, "org.ferrao.termarchy", "shortcut://org.ferrao.termarchy/herdr:1:term_x", enabled, removed))
    }

    @Test fun whatsAppOnlyChatsOfTheLastWeek() {
        val now = 1_794_700_000_000L
        val day = 24 * 3600 * 1000L
        assertTrue(AppLauncherMode.isWhatsAppChatShown("120363412985613421@g.us", now - 6 * day, now))
        assertTrue(AppLauncherMode.isWhatsAppChatShown("51230420238452@lid", now, now))
        assertFalse(AppLauncherMode.isWhatsAppChatShown("351930695246@s.whatsapp.net", now - 8 * day, now))
        assertFalse(AppLauncherMode.isWhatsAppChatShown("meta_ai_chat", now, now))
        assertFalse(AppLauncherMode.isWhatsAppChatShown("open_camera", now, now))
    }
}
