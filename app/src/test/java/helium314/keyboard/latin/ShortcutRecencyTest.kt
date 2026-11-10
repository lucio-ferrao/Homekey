// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutRecencyTest {
    private val shown = "lira" to "idle"

    @Test
    fun aTermarchyPaneGivesItsOwnTimeEvenWhenFirstSeen() {
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
        val working = AppLauncherMode.Shortcut("org.ferrao.termarchy", "a", false, true, 100L, "working", active = true)
        val stopped = AppLauncherMode.Shortcut("org.ferrao.termarchy", "b", false, true, 100L, "idle")
        assertEquals(900L, working.changedTime(900L))
        assertEquals(100L, stopped.changedTime(900L))
    }

    @Test
    fun aDismissedEntryIsNotRecentUntilUsedOrChangedAgain() {
        assertEquals(500L, AppLauncherMode.recency(500L, null, dismissedAt = 0L, now = 900L))
        assertEquals(0L, AppLauncherMode.recency(500L, null, dismissedAt = 600L, now = 900L))
        assertEquals(700L, AppLauncherMode.recency(700L, null, dismissedAt = 600L, now = 900L))
        val stopped = AppLauncherMode.Shortcut("org.ferrao.termarchy", "b", false, true, 650L, "idle")
        assertEquals(650L, AppLauncherMode.recency(0L, stopped, dismissedAt = 600L, now = 900L))
        assertEquals(0L, AppLauncherMode.recency(0L, stopped, dismissedAt = 660L, now = 900L))
        // an agent that kept working since before the dismissal stays out; one that started after it is back
        val working = AppLauncherMode.Shortcut("org.ferrao.termarchy", "a", false, true, 100L, "working", active = true)
        assertEquals(0L, AppLauncherMode.recency(0L, working, dismissedAt = 600L, now = 900L))
        val restarted = AppLauncherMode.Shortcut("org.ferrao.termarchy", "a", false, true, 800L, "working", active = true)
        assertEquals(900L, AppLauncherMode.recency(0L, restarted, dismissedAt = 600L, now = 900L))
    }
}
