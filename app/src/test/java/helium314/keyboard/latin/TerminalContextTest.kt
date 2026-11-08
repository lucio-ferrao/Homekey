// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.settings.SpacingAndPunctuations
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class TerminalContextTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test fun parsesTheContextAmongOtherOptions() {
        assertEquals(TerminalContext.AGENT, TerminalContext.parse("org.ferrao.homekey.context=agent:claude"))
        assertEquals(TerminalContext.SHELL, TerminalContext.parse("nm, org.ferrao.homekey.context=shell:bash ,other"))
        assertEquals(TerminalContext.FULLSCREEN, TerminalContext.parse("org.ferrao.homekey.context=fullscreen:vim"))
        assertEquals(TerminalContext.SHELL, TerminalContext.parse("org.ferrao.homekey.context=shell"))
    }

    @Test fun noContextOutsideATerminal() {
        assertNull(TerminalContext.parse(null))
        assertNull(TerminalContext.parse("helium314.keyboard.home"))
        assertNull(TerminalContext.parse("org.ferrao.homekey.context=browser:firefox"))
    }

    @Test fun shellTokensKeepTheirCharacters() {
        val shell = SpacingAndPunctuations(context.resources, true, Locale.ROOT, true)
        for (c in "-./_~=") {
            assertTrue(shell.isWordConnector(c.code), "connector $c")
            assertFalse(shell.isWordSeparator(c.code), "separator $c")
        }
        assertTrue(shell.isWordSeparator(' '.code))
        val prose = SpacingAndPunctuations(context.resources, true, Locale.ROOT)
        assertTrue(prose.isWordSeparator('.'.code))
        assertFalse(prose.isWordConnector('/'.code))
    }

    @Test fun vocabularyCompletesWhatWasTyped() {
        val vocabulary = TerminalVocabulary(context)
        listOf("--no-daemon", "--no-daemon", "--no-verify", "herdr", "git").forEach { vocabulary.learn(it) }
        assertEquals(listOf("--no-daemon", "--no-verify"), vocabulary.completions("--no-"))
        assertEquals(listOf("herdr"), vocabulary.completions("he"))
        assertTrue(vocabulary.completions("git").isEmpty())
        assertTrue(vocabulary.completions("").isEmpty())
        // a new instance reads what was saved
        assertEquals(listOf("herdr"), TerminalVocabulary(context).completions("her"))
    }
}
