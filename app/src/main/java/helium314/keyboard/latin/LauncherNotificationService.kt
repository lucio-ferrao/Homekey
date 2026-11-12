// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import helium314.keyboard.latin.utils.Log

/**
 * Gives the launcher mode the notifications in the status bar, to find them by their text and jump to where they
 * point (a chat, an email). Enabled in the settings under "Notification access".
 */
class LauncherNotificationService : NotificationListenerService() {

    /** a notification as shown in the launcher mode */
    class Entry(val key: String, val packageName: String, val title: String, val text: String, val time: Long,
                /** a message in a group chat (not from one person only) */
                val isGroup: Boolean, private val notification: Notification) {
        /** "title: text", with the sender once (Element's title is "misty: misty", conversation: sender, in a one-to-one chat) */
        val label get() = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ").replace('\n', ' ')
            .split(": ").fold(listOf<String>()) { parts, part -> if (parts.lastOrNull() == part) parts else parts + part }
            .joinToString(": ")

        /** the shortcut it belongs to, as its app tells it (a session of Termarchy) */
        val shortcutId: String? get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.shortcutId else null

        /** opens what the notification points to, like a tap on it; removes it if it is meant to go away when opened */
        fun open(): Boolean {
            val intent = notification.contentIntent ?: return false
            return try {
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                else null
                intent.send(null, 0, null, null, null, null, options)
                if (notification.flags and Notification.FLAG_AUTO_CANCEL != 0) instance?.cancelNotification(key)
                true
            } catch (e: Exception) {
                Log.w(TAG, "can't open the notification $key", e)
                false
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // a message from a person moves their entry forward, as if just opened
        val entries = entries()
        AppLauncherMode.bump(this, entries)
        onChange?.invoke()
        entries.firstOrNull { it.key == sbn?.key }?.let { entry -> onPosted?.invoke(entry) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        onChange?.invoke()
    }

    companion object {
        private const val TAG = "launcher-notifications"

        private var instance: LauncherNotificationService? = null

        /** called (on the main thread) when a notification arrives, changes or goes away, set while the launcher mode is open */
        var onChange: (() -> Unit)? = null

        /** called (on the main thread) with a notification that arrives or changes, set while the keyboard service lives */
        var onPosted: ((Entry) -> Unit)? = null

        /** takes a notification away from the status bar, like a swipe there */
        fun dismiss(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (e: Exception) {
                Log.w(TAG, "can't dismiss the notification $key", e)
            }
        }

        /**
         * The notifications in the status bar, newest first, without ongoing ones, group summaries and silent ones (those
         * under "Silent" in the shade); empty if not allowed.
         */
        fun entries(): List<Entry> {
            val service = instance ?: return emptyList()
            val notifications = try { service.activeNotifications } catch (e: Exception) { null } ?: return emptyList()
            val ranking = try { service.currentRanking } catch (e: Exception) { null }
            return notifications.filter { isShown(it) && !isSilent(it, ranking) }.map { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString().orEmpty()
                Entry(sbn.key, sbn.packageName, title, text, sbn.postTime,
                    extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION), sbn.notification)
            }.filter { it.title.isNotBlank() || it.text.isNotBlank() }.sortedByDescending { it.time }
        }

        private fun isSilent(sbn: StatusBarNotification, ranking: RankingMap?): Boolean {
            val rank = Ranking()
            return ranking != null && ranking.getRanking(sbn.key, rank) && rank.importance < NotificationManager.IMPORTANCE_DEFAULT
        }

        private fun isShown(sbn: StatusBarNotification): Boolean {
            val flags = sbn.notification.flags
            return flags and Notification.FLAG_ONGOING_EVENT == 0 && flags and Notification.FLAG_FOREGROUND_SERVICE == 0 &&
                flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
    }
}
