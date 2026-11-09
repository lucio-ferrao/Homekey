// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.latin.AppLauncherMode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SearchSettingsScreen

/** An app that offers shortcuts (a long press on its icon), with their names and long names when different. */
private class ShortcutApp(val packageName: String, val name: String, val icon: Drawable?, val shortcuts: List<Pair<String, String?>>)

/**
 * The apps that offer shortcuts, each with a switch to show its shortcuts in the launcher mode or not (all off at
 * first); the shortcuts pinned to Keyarchy are always shown. Android only gives the shortcuts to the home app.
 */
@Composable
fun AppShortcutsScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val title = stringResource(R.string.app_shortcuts)
    if (!ctx.getSystemService(LauncherApps::class.java).hasShortcutHostPermission()) {
        SearchSettingsScreen(onClickBack = onClickBack, title = title, settings = emptyList()) {
            Text(stringResource(R.string.app_shortcuts_not_home), Modifier.padding(16.dp))
        }
        return
    }
    val shortcutApps = remember { shortcutApps(ctx) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title) },
        filteredItems = { term -> shortcutApps.filter { it.name.contains(term, true) } },
        itemContent = { app -> ShortcutAppRow(app) },
    )
}

@Composable
private fun ShortcutAppRow(app: ShortcutApp) {
    val ctx = LocalContext.current
    var shown by remember {
        mutableStateOf(app.packageName in ctx.prefs().getStringSet(AppLauncherMode.PREF_SHORTCUT_APPS, emptySet())!!)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.icon?.let { Image(it.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(32.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = shown,
                onCheckedChange = {
                    shown = it
                    AppLauncherMode.setShortcutsShown(ctx, app.packageName, it)
                }
            )
        }
        val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
        app.shortcuts.forEach { (name, longName) ->
            // one line, the detail after the name, cut at the end when long
            Text(
                buildAnnotatedString {
                    append(name)
                    longName?.let { withStyle(SpanStyle(color = detailColor)) { append(" · $it") } }
                },
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 44.dp, top = 2.dp)
            )
        }
    }
}

/** the apps of the profiles that are not paused with the shortcuts they offer, by name */
private fun shortcutApps(context: Context): List<ShortcutApp> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val query = LauncherApps.ShortcutQuery().setQueryFlags(
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
    )
    val shortcuts = launcherApps.profiles.filter { !userManager.isQuietModeEnabled(it) }.flatMap { user ->
        try {
            launcherApps.getShortcuts(query, user).orEmpty()
        } catch (e: Exception) {
            Log.w("app-shortcuts", "can't read the shortcuts", e)
            emptyList()
        }
    }.filter { it.isEnabled }
    val packageManager = context.packageManager
    return shortcuts.groupBy { it.`package` }.map { (packageName, list) ->
        // the name and icon from the app's own profile (the private space or the work profile have apps the main
        // one lacks), badged there with the lock or the briefcase; the main profile when the app is in both
        val user = list.firstOrNull { it.userHandle == Process.myUserHandle() }?.userHandle ?: list.first().userHandle
        val info = try { launcherApps.getApplicationInfo(packageName, 0, user) } catch (e: Exception) { null }
        ShortcutApp(
            packageName,
            info?.loadLabel(packageManager)?.toString() ?: packageName,
            info?.loadIcon(packageManager)?.let { if (user == Process.myUserHandle()) it else packageManager.getUserBadgedIcon(it, user) },
            list.mapNotNull { shortcut ->
                val name = (shortcut.shortLabel ?: shortcut.longLabel)?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                name to shortcut.longLabel?.toString()?.takeIf { it.isNotBlank() && it != name }
            }.distinct()
        )
    }.sortedBy { it.name.lowercase() }
}
