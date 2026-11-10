// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.utils.Log

/** The agent of a Termarchy pane, as Termarchy tells it: Herdr's status, whether it is active, and since when. */
data class AgentState(val status: String, val active: Boolean, val since: Long)

/**
 * The agents of the Termarchy panes with a shortcut, by the id of the shortcut, as Termarchy tells them live in a
 * provider of its own (and not in the shortcuts: Android lets an app in the background update them only 10 times a
 * day, refusing the others without a word).
 */
object TermarchyAgents {
    /** what Termarchy serves to Keyarchy only, and tells its observers when it changes */
    val URI: Uri by lazy { Uri.parse("content://${TermarchyForwards.TERMARCHY_PACKAGE}.agents/agents") }

    /** the agents by shortcut id; empty without Termarchy or while it is not connected */
    fun read(context: Context): Map<String, AgentState> = try {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), AgentState(c.getString(1), c.getInt(2) == 1, c.getLong(3))) }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w("TermarchyAgents", "can't read the agents of Termarchy", e)
        emptyMap()
    }
}
