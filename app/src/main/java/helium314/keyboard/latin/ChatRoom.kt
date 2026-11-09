// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * The Matrix room of the chat open in MatriX, as it tells the keyboard in the privateImeOptions of its message box:
 * `org.ferrao.homekey.room=!id:server`, among other options separated by commas. With it the keyboard knows the chat
 * without reading the screen.
 */
object ChatRoom {
    const val PRIVATE_IME_OPTION = "org.ferrao.homekey.room"

    /** the room id in [privateImeOptions], or null for a field without one */
    @JvmStatic
    fun parse(privateImeOptions: String?): String? = privateImeOptions?.split(',')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$PRIVATE_IME_OPTION=") }
        ?.substringAfter('=')?.takeIf { it.isNotEmpty() }
}
