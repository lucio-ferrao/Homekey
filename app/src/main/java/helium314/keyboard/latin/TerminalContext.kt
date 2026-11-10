// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * What runs in a terminal field, as the terminal app (Termarchy) tells it in the privateImeOptions of the field:
 * `org.ferrao.homekey.context=<kind>:<program>`, e.g. `agent:claude`, `shell:bash`, `fullscreen:vim`, among other
 * options separated by commas. The keyboard follows it: prose for an agent, commands for a shell, plain keys for a
 * full-screen program. A password field wins over all of them.
 */
enum class TerminalContext {
    /** a coding agent like Claude: Portuguese prose, with suggestions, corrections, capitals and learning */
    AGENT,
    /** a shell: no corrections, capitals or automatic spaces; suggestions from the terminal vocabulary */
    SHELL,
    /** a full-screen program like vim: keys only, no suggestions */
    FULLSCREEN;

    companion object {
        const val PRIVATE_IME_OPTION = "org.ferrao.homekey.context"

        /** the context in [privateImeOptions], or null for a field without one (any other app) */
        @JvmStatic
        fun parse(privateImeOptions: String?): TerminalContext? {
            val value = privateImeOptions?.split(',')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$PRIVATE_IME_OPTION=") }
                ?.substringAfter('=')?.substringBefore(':') ?: return null
            return when (value) {
                "agent" -> AGENT
                "shell" -> SHELL
                "fullscreen" -> FULLSCREEN
                else -> null
            }
        }
    }
}
