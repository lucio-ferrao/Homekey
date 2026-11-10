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
}
