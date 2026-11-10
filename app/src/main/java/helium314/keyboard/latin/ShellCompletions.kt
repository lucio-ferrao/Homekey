// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.view.inputmethod.EditorInfo

/**
 * The commands and the files of the current folder that the terminal app (Termarchy) sends for a shell field, as string
 * arrays in `EditorInfo.extras` (folders end in `/`). The token being typed completes from the commands at the start
 * of a command, from the files after it, and from both when the keyboard lost track of the line; then from what was
 * typed in shells before.
 */
class ShellCompletions(private val commands: List<String>, private val files: List<String>) {

    /**
     * Up to [TerminalVocabulary.MAX_SUGGESTIONS] completions of [typed]: the commands or files that start with it, those
     * used before first ([learned], most used first) then the shortest, followed by the rest of [learned].
     */
    fun completions(typed: String, position: ShellLine.Position?, learned: List<String>): List<String> {
        if (typed.isEmpty()) return emptyList()
        val candidates = when (position) {
            ShellLine.Position.COMMAND -> commands
            ShellLine.Position.ARGUMENT -> files
            null -> commands + files
        }
        val rank = learned.withIndex().associate { it.value to it.index }
        val matches = candidates.filter { it.length > typed.length && it.startsWith(typed) }
            .sortedWith(compareBy<String>({ rank[it] ?: Int.MAX_VALUE }, { it.length }, { it }))
        return (matches + learned).distinct().take(TerminalVocabulary.MAX_SUGGESTIONS)
    }

    companion object {
        const val COMMANDS_EXTRA = "org.ferrao.homekey.commands"
        const val FILES_EXTRA = "org.ferrao.homekey.files"

        @JvmField val EMPTY = ShellCompletions(emptyList(), emptyList())

        /** the lists of a shell field, or none for any other field */
        @JvmStatic
        fun from(editorInfo: EditorInfo?): ShellCompletions {
            val extras = editorInfo?.extras
            if (extras == null || TerminalContext.parse(editorInfo.privateImeOptions) != TerminalContext.SHELL) return EMPTY
            return ShellCompletions(
                extras.getStringArray(COMMANDS_EXTRA)?.toList().orEmpty(),
                extras.getStringArray(FILES_EXTRA)?.toList().orEmpty(),
            )
        }

        /** a picked folder is half a path: the path goes on without a space */
        @JvmStatic
        fun continuesPath(picked: String) = picked.endsWith('/')
    }
}

/**
 * Where the token being typed sits on a shell's command line, from what the keyboard sent since the last Enter: the
 * command, or an argument after it. Unknown (null) once the line was edited in a way the keyboard cannot follow, such
 * as moving the cursor or deleting back over a space, until the next Enter.
 */
class ShellLine {
    enum class Position { COMMAND, ARGUMENT }

    var position: Position? = Position.COMMAND
        private set

    /** Enter or Ctrl+C: a new command line */
    fun onNewLine() {
        position = Position.COMMAND
    }

    /** a space after a token: the next token is an argument */
    fun onTokenEnded() {
        if (position == Position.COMMAND) position = Position.ARGUMENT
    }

    /** `|`, `;` or `&`: a command follows */
    fun onCommandSeparator() {
        if (position != null) position = Position.COMMAND
    }

    fun onUntrackedEdit() {
        position = null
    }

    companion object {
        @JvmStatic
        fun isCommandSeparator(codePoint: Int) = codePoint == '|'.code || codePoint == ';'.code || codePoint == '&'.code
    }
}
