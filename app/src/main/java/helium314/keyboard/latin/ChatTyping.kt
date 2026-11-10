// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.utils.Log

/**
 * Who types in a MatriX room, as MatriX tells it live in a provider of its own (one row per room: its id and when
 * someone was last seen typing there; not in its shortcuts, as Android lets an app in the background update them only
 * 10 times a day). MatriX knows it only while it syncs and tells it again while it lasts, so a time older than
 * [LIMIT_MS] counts as stopped (a stop missed when the sync ends).
 */
object ChatTyping {
    /** what MatriX serves to Keyarchy only, and tells its observers when it changes */
    val URI: Uri by lazy { Uri.parse("content://org.ferrao.matrix.typing/typing") }
    const val LIMIT_MS = 35_000L
    /** the rooms someone types in, with when they were last seen typing; empty without MatriX */
    fun read(context: Context): List<Pair<String, Long>> = try {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w("ChatTyping", "can't read who types in MatriX", e)
        emptyList()
    }

    /** the id of a room without its server ("!abc" in "!abc:server") */
    private val ROOM_ID = Regex("""![^?/&:%]+""")

    /** the rooms (ids without their server) someone types in at [now], from room ids and their typing times */
    fun typingRooms(shortcuts: List<Pair<String, Long>>, now: Long): Set<String> =
        shortcuts.filter { (_, time) -> current(time, now) }.mapNotNull { (id, _) -> ROOM_ID.find(id)?.value }.toSet()

    /** when the first of them counts as stopped, null if nobody types */
    fun nextStop(shortcuts: List<Pair<String, Long>>, now: Long): Long? =
        shortcuts.map { it.second }.filter { current(it, now) }.minOrNull()?.plus(LIMIT_MS)

    private fun current(time: Long, now: Long) = time > 0 && now - time < LIMIT_MS
}
