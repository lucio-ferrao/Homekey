// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Who types in a MatriX room, as MatriX tells it in the extras of its conversation shortcuts (ids "<session>-<room>"):
 * [EXTRA], when someone was last seen typing (0 when nobody types). MatriX knows it only while it syncs and sends it
 * again while it lasts, so a time older than [LIMIT_MS] counts as stopped (a stop missed when the sync ends).
 */
object ChatTyping {
    const val EXTRA = "org.ferrao.homekey.typing"
    const val LIMIT_MS = 35_000L
    /** the id of a room without its server, in a shortcut id */
    private val ROOM_ID = Regex("""![^?/&:%]+""")

    /** the rooms (ids without their server) someone types in at [now], from shortcut ids and their typing times */
    fun typingRooms(shortcuts: List<Pair<String, Long>>, now: Long): Set<String> =
        shortcuts.filter { (_, time) -> current(time, now) }.mapNotNull { (id, _) -> ROOM_ID.find(id)?.value }.toSet()

    /** when the first of them counts as stopped, null if nobody types */
    fun nextStop(shortcuts: List<Pair<String, Long>>, now: Long): Long? =
        shortcuts.map { it.second }.filter { current(it, now) }.minOrNull()?.plus(LIMIT_MS)

    private fun current(time: Long, now: Long) = time > 0 && now - time < LIMIT_MS
}
