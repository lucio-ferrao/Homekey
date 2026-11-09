// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * The launcher mode's ssh command, done by Ecto: "ssh olivais" connects to a saved server, "ssh [olivais] -L 3000",
 * "-R 5555", "-KL 3000" or "-KR 5555" forwards a port on it (or on the one connected). The forward syntax is ssh's,
 * shortened: a missing host is localhost and a missing host port the same port, so -L 5555 is -L 5555:localhost:5555.
 */
sealed interface SshCommand {
    data class Connect(val server: String) : SshCommand

    /** [arguments] go to Ecto as typed; [direction] 'L' or 'R'; [cancel] for -KL and -KR, which need only [port]. */
    data class Forward(
        val server: String?,
        val arguments: String,
        val direction: Char,
        val cancel: Boolean,
        val port: Int,
        val host: String,
        val hostPort: Int,
    ) : SshCommand {
        /** "L3000", as Ecto's key bar shows it */
        val label get() = "$direction$port"
    }

    companion object {
        private val SSH = Regex("""^ssh\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** the command in [text], or null when it is not one (or a forward Ecto would refuse) */
        fun parse(text: String): SshCommand? {
            val words = SSH.matchEntire(text.trim())?.groupValues?.get(1)?.split(Regex("\\s+")) ?: return null
            val server = words.first().takeUnless { it.startsWith("-") }
            val arguments = (if (server == null) words else words.drop(1)).joinToString(" ")
            if (arguments.isEmpty()) return server?.let { Connect(it) }
            return forward(server, arguments)
        }

        private fun forward(server: String?, arguments: String): Forward? {
            val words = arguments.split(" ")
            val flag = words[0]
            val cancel = flag.startsWith("-K")
            val letter = flag.getOrNull(if (cancel) 2 else 1)?.takeIf { it == 'L' || it == 'R' } ?: return null
            val spec = (listOf(flag.substring(if (cancel) 3 else 2)) + words.drop(1)).filter { it.isNotEmpty() }
                .singleOrNull() ?: return null
            val parts = spec.split(':')
            if (cancel) return port(parts.last())?.let { Forward(server, arguments, letter, true, it, "", it) }
            // [bind:]port[:host[:hostport]]: two parts are port:host when the first is a number, else bind:port
            val (portText, host, hostPortText) = when (parts.size) {
                1 -> Triple(parts[0], "localhost", parts[0])
                2 -> if (parts[0].all(Char::isDigit)) Triple(parts[0], parts[1], parts[0]) else Triple(parts[1], "localhost", parts[1])
                3 -> Triple(parts[0], parts[1], parts[2])
                4 -> Triple(parts[1], parts[2], parts[3])
                else -> return null
            }
            if (host.isEmpty() || parts.any { it.isEmpty() }) return null
            val port = port(portText) ?: return null
            val hostPort = port(hostPortText) ?: return null
            return Forward(server, arguments, letter, false, port, host, hostPort)
        }

        private fun port(text: String) = text.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
