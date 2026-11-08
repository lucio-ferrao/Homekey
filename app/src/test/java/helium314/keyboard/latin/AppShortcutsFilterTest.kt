// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShortcutsFilterTest {
    private val enabled = setOf("com.mrndtvndv.term")
    private val removed = setOf("shortcut://com.mrndtvndv.term/herdr:1:term_x")

    @Test fun onlyTheAppsTurnedOn() {
        assertTrue(AppLauncherMode.isShortcutShown(false, "com.mrndtvndv.term", "shortcut://com.mrndtvndv.term/a", enabled, removed))
        assertFalse(AppLauncherMode.isShortcutShown(false, "com.google.android.gm", "shortcut://com.google.android.gm/a", enabled, removed))
    }

    @Test fun pinnedAlwaysShown() {
        assertTrue(AppLauncherMode.isShortcutShown(true, "com.google.android.gm", "shortcut://com.google.android.gm/a", emptySet(), emptySet()))
        assertTrue(AppLauncherMode.isShortcutShown(true, "com.mrndtvndv.term", "shortcut://com.mrndtvndv.term/herdr:1:term_x", enabled, removed))
    }

    @Test fun removedStaysOut() {
        assertFalse(AppLauncherMode.isShortcutShown(false, "com.mrndtvndv.term", "shortcut://com.mrndtvndv.term/herdr:1:term_x", enabled, removed))
    }
}
