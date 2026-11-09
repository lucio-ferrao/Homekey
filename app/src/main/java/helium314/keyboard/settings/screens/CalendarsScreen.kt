// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.AppLauncherMode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen

/** A line of the list: an account, or one of its calendars ([id] as in the calendar provider, [color] as ARGB). */
private sealed interface CalendarLine {
    class Account(val name: String) : CalendarLine
    class Shown(val id: Long, val name: String, val account: String, val color: Int) : CalendarLine
}

/**
 * The calendars shown in the calendar app, by account, each with a switch to have its events in the list of the home
 * screen or not (all on at first). Without the permission to read the calendar the list is empty.
 */
@Composable
fun CalendarsScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val calendars = remember { shownCalendars(ctx) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.calendars)) },
        filteredItems = { term ->
            calendars.filter { it.name.contains(term, true) || it.account.contains(term, true) }
                .groupBy { it.account }.flatMap { (account, list) -> listOf(CalendarLine.Account(account)) + list }
        },
        itemContent = { line ->
            when (line) {
                is CalendarLine.Account -> Text(
                    line.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)
                )
                is CalendarLine.Shown -> CalendarRow(line)
            }
        },
    )
}

@Composable
private fun CalendarRow(calendar: CalendarLine.Shown) {
    val ctx = LocalContext.current
    var shown by remember {
        mutableStateOf(calendar.id.toString() !in ctx.prefs().getStringSet(AppLauncherMode.PREF_HIDDEN_CALENDARS, emptySet())!!)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Color(calendar.color), CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(calendar.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = shown,
            onCheckedChange = {
                shown = it
                val prefs = ctx.prefs()
                val hidden = prefs.getStringSet(AppLauncherMode.PREF_HIDDEN_CALENDARS, emptySet())!!
                val id = calendar.id.toString()
                prefs.edit { putStringSet(AppLauncherMode.PREF_HIDDEN_CALENDARS, if (it) hidden - id else hidden + id) }
            }
        )
    }
}

/** the calendars shown in the calendar app, by account and then by name */
private fun shownCalendars(context: Context): List<CalendarLine.Shown> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val calendars = mutableListOf<CalendarLine.Shown>()
    try {
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR), "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext())
                calendars.add(CalendarLine.Shown(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
        }
    } catch (e: SecurityException) {
        Log.w("calendars", "no permission to read the calendar", e)
    }
    return calendars.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
}
