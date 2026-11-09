// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutRecencyTest {
    private val shown = "lira" to "idle"

    @Test
    fun anEctoPaneGivesItsOwnTimeEvenWhenFirstSeen() {
        assertEquals(100L, AppLauncherMode.shortcutChangedAt(100L, null, shown, appWasShown = true, now = 900L))
        assertEquals(100L, AppLauncherMode.shortcutChangedAt(100L, ("old" to null) to 50L, shown, true, 900L))
    }

    @Test
    fun otherShortcutsAreRecentWhenWhatTheyShowChanges() {
        assertEquals(0L, AppLauncherMode.shortcutChangedAt(null, null, shown, appWasShown = false, now = 900L))
        assertEquals(900L, AppLauncherMode.shortcutChangedAt(null, null, shown, appWasShown = true, now = 900L))
        assertEquals(900L, AppLauncherMode.shortcutChangedAt(null, ("lira" to "working") to 50L, shown, true, 900L))
        assertEquals(50L, AppLauncherMode.shortcutChangedAt(null, shown to 50L, shown, true, 900L))
    }

    @Test
    fun anActiveShortcutIsAsRecentAsNow() {
        val working = AppLauncherMode.Shortcut("com.mrndtvndv.term", "a", false, true, 100L, "working", active = true)
        val stopped = AppLauncherMode.Shortcut("com.mrndtvndv.term", "b", false, true, 100L, "idle")
        assertEquals(900L, working.changedTime(900L))
        assertEquals(100L, stopped.changedTime(900L))
    }
}
