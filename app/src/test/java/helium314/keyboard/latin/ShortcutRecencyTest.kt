// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutRecencyTest {
    private val shown = "lira" to "idle"
    private val termarchy = TermarchyForwards.TERMARCHY_PACKAGE

    @After
    fun forgetAgents() {
        AppLauncherMode.agentStates = emptyMap()
    }

    @Test
    fun shortcutsAreRecentWhenWhatTheyShowChanges() {
        assertEquals(0L, AppLauncherMode.shortcutChangedAt(null, shown, appWasShown = false, now = 900L))
        assertEquals(900L, AppLauncherMode.shortcutChangedAt(null, shown, appWasShown = true, now = 900L))
        assertEquals(900L, AppLauncherMode.shortcutChangedAt(("lira" to "src") to 50L, shown, true, 900L))
        assertEquals(50L, AppLauncherMode.shortcutChangedAt(shown to 50L, shown, true, 900L))
    }

    @Test
    fun aTermarchyPaneIsAsRecentAsWhatItsAgentDoes() {
        AppLauncherMode.agentStates = mapOf("a" to AgentState("working", true, 100L), "b" to AgentState("done", false, 700L))
        val working = AppLauncherMode.Shortcut(termarchy, "a", false, true, 50L, "lira")
        val done = AppLauncherMode.Shortcut(termarchy, "b", false, true, 50L, "lira")
        val unknown = AppLauncherMode.Shortcut(termarchy, "c", false, true, 50L, "lira")
        assertEquals(900L, working.changedTime(900L))
        // just done: as recent as the change, so it goes forward
        assertEquals(700L, done.changedTime(900L))
        assertEquals(50L, unknown.changedTime(900L))
        // another app's shortcut with the same id does not take the state
        assertEquals(50L, AppLauncherMode.Shortcut("other", "a", false, true, 50L, null).changedTime(900L))
    }

    @Test
    fun aDismissedEntryIsNotRecentUntilUsedOrChangedAgain() {
        assertEquals(500L, AppLauncherMode.recency(500L, null, dismissedAt = 0L, now = 900L))
        assertEquals(0L, AppLauncherMode.recency(500L, null, dismissedAt = 600L, now = 900L))
        assertEquals(700L, AppLauncherMode.recency(700L, null, dismissedAt = 600L, now = 900L))
        AppLauncherMode.agentStates = mapOf("b" to AgentState("idle", false, 650L), "a" to AgentState("working", true, 100L),
            "r" to AgentState("working", true, 800L))
        val stopped = AppLauncherMode.Shortcut(termarchy, "b", false, true, 0L, null)
        assertEquals(650L, AppLauncherMode.recency(0L, stopped, dismissedAt = 600L, now = 900L))
        assertEquals(0L, AppLauncherMode.recency(0L, stopped, dismissedAt = 660L, now = 900L))
        // an agent that kept working since before the dismissal stays out; one that started after it is back
        val working = AppLauncherMode.Shortcut(termarchy, "a", false, true, 0L, null)
        assertEquals(0L, AppLauncherMode.recency(0L, working, dismissedAt = 600L, now = 900L))
        val restarted = AppLauncherMode.Shortcut(termarchy, "r", false, true, 0L, null)
        assertEquals(900L, AppLauncherMode.recency(0L, restarted, dismissedAt = 600L, now = 900L))
    }
}
