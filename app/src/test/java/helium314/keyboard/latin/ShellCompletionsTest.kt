// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ShellCompletionsTest {
    private val completions = ShellCompletions(
        commands = listOf("gimp", "git", "gitk", "gio", "grep"),
        files = listOf("gradle/", "gradlew", "git-notes.txt", "src/"),
    )

    @Test fun theCommandCompletesFromCommandsAndArgumentsFromFiles() {
        assertEquals(listOf("gio", "git", "gimp", "gitk"), completions.completions("gi", ShellLine.Position.COMMAND, emptyList()))
        assertEquals(listOf("git-notes.txt"), completions.completions("gi", ShellLine.Position.ARGUMENT, emptyList()))
    }

    @Test fun anUnknownPositionMixesBoth() {
        assertEquals(listOf("gradle/", "gradlew", "grep"), completions.completions("gr", null, emptyList()).sorted())
    }

    @Test fun usedBeforeComeFirstThenTheShortestThenTheRestOfWhatWasLearned() {
        assertEquals(listOf("gitk", "gio", "git", "gimp", "gitignore"),
            completions.completions("gi", ShellLine.Position.COMMAND, listOf("gitk", "gitignore")))
    }

    @Test fun noDuplicatesAndAtMostTheLimit() {
        val result = completions.completions("g", null, listOf("git", "gradlew", "go", "gcc"))
        assertEquals(result.distinct(), result)
        assertEquals(TerminalVocabulary.MAX_SUGGESTIONS, result.size)
        assertEquals(listOf("git", "gradlew"), result.take(2))
    }

    @Test fun nothingTypedNothingCompleted() {
        assertEquals(emptyList(), completions.completions("", ShellLine.Position.COMMAND, emptyList()))
    }

    @Test fun aFolderGoesOnWithoutSpace() {
        assertTrue(ShellCompletions.continuesPath("src/"))
        assertFalse(ShellCompletions.continuesPath("gradlew"))
    }

    @Test fun readsTheListsOfAShellFieldOnly() {
        val shell = editorInfo("org.ferrao.homekey.context=shell:bash")
        assertEquals(listOf("git"), ShellCompletions.from(shell).completions("gi", ShellLine.Position.COMMAND, emptyList()))
        assertEquals(listOf("src/"), ShellCompletions.from(shell).completions("s", ShellLine.Position.ARGUMENT, emptyList()))
        val agent = editorInfo("org.ferrao.homekey.context=agent:claude")
        assertEquals(emptyList(), ShellCompletions.from(agent).completions("gi", ShellLine.Position.COMMAND, emptyList()))
    }

    @Test fun theLineFollowsTokensSeparatorsAndNewLines() {
        val line = ShellLine()
        assertEquals(ShellLine.Position.COMMAND, line.position)
        line.onTokenEnded()
        assertEquals(ShellLine.Position.ARGUMENT, line.position)
        line.onTokenEnded()
        assertEquals(ShellLine.Position.ARGUMENT, line.position)
        line.onCommandSeparator()
        assertEquals(ShellLine.Position.COMMAND, line.position)
        line.onUntrackedEdit()
        assertNull(line.position)
        line.onTokenEnded()
        line.onCommandSeparator()
        assertNull(line.position)
        line.onNewLine()
        assertEquals(ShellLine.Position.COMMAND, line.position)
        assertTrue(ShellLine.isCommandSeparator('|'.code))
        assertFalse(ShellLine.isCommandSeparator('-'.code))
    }

    private fun editorInfo(options: String) = EditorInfo().apply {
        privateImeOptions = options
        extras = Bundle().apply {
            putStringArray(ShellCompletions.COMMANDS_EXTRA, arrayOf("git"))
            putStringArray(ShellCompletions.FILES_EXTRA, arrayOf("src/"))
        }
    }
}
