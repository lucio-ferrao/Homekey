// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.content.pm.verify.domain.DomainVerificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.app.ActivityOptions
import android.app.SearchManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.Manifest
import android.database.ContentObserver
import java.util.Calendar
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.ColorUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.dictionary.LauncherBinaryDictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

/**
 * Launcher mode: while active, typing searches the apps of all profiles visible to the keyboard instead of going
 * to the text field, and the results are shown in the suggestion strip. Toggled by a double tap on space or by the
 * apps toolbar key. Without a search text, the apps launched most often from here come first.
 */
class AppLauncherMode(private val latinIME: LatinIME) : StripSearch {
    var isActive = false
        private set
    private val query = StringBuilder()
    private var results = emptyList<App>()
    private var counts = mutableMapOf<String, Int>()
    private var lastUsed = mutableMapOf<String, Long>()
    /** when entries were dismissed from the recent ones (a slide), until they are used or change again */
    private var dismissedRecent = mutableMapOf<String, Long>()
    /** the result chosen last for each search text, which comes first the next time that text is typed */
    private var choices = mutableMapOf<String, String>()
    private var lastSpaceTapTime = 0L
    private var startTime = 0L
    private var listening = false
    /** a message shown instead of the results (listening, not understood…), until the next key */
    private var message: String? = null
    /** the app whose actions (rename, tags) are shown after a long press */
    private var actionsFor: App? = null
    /** the app and field being edited, with the text typed so far */
    private var editing: Pair<App, Field>? = null
    private enum class Field { NAME, TAGS, ELEMENT }
    private val speech by lazy { LauncherSpeech(latinIME) }
    private val mainHandler = Handler(Looper.getMainLooper())
    /** shows the list again when an event of the calendar ends, to take it away */
    private val refresh = Runnable { show() }

    /** the rooms someone types in now, as MatriX tells it ([ChatTyping]) */
    private var typingRooms = emptySet<String>()
    /** who types in MatriX with when they were last seen, kept to see when a typing counts as stopped */
    private var typingTimes = emptyList<Pair<String, Long>>()
    private val typingStops = Runnable { updateTyping() }
    /** MatriX telling that someone started or stopped typing */
    private val typingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            typingTimes = ChatTyping.read(latinIME)
            if (useLeftChats() && isActive) show()
            updateTyping()
        }
    }
    /** Termarchy telling that the state of an agent changed: the order and the icons follow at once */
    private val agentsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val states = TermarchyAgents.read(latinIME)
            if (states == agentStates) return
            agentStates = states
            if (isActive) show()
        }
    }

    /**
     * Follows, while the mode is open, the agents of Termarchy (order, the blinking ghost) and who types in MatriX (the
     * moving crown, [showIcon]), both told live by those apps.
     */
    private fun followLiveStates() {
        for ((uri, observer) in listOf(TermarchyAgents.URI to agentsObserver, ChatTyping.URI to typingObserver)) {
            latinIME.contentResolver.unregisterContentObserver(observer)
            try {
                latinIME.contentResolver.registerContentObserver(uri, false, observer)
            } catch (e: SecurityException) {
                // the app is not installed
                Log.w(TAG, "can't follow $uri", e)
            }
        }
        agentStates = TermarchyAgents.read(latinIME)
        typingTimes = ChatTyping.read(latinIME)
        if (useLeftChats() && isActive) show()
        updateTyping()
    }

    /**
     * The chats I left in MatriX ([ChatTyping.left]) as recent as when I left them, if that is later than their last use
     * here: the person linked to the room and the shortcut of the room. True when something changed.
     */
    private fun useLeftChats(): Boolean {
        var changed = false
        for ((roomId, time) in ChatTyping.left(latinIME)) {
            val room = ROOM_ID.find(roomId)?.value ?: continue
            apps.filter { app ->
                app.whatsAppDataId != null && roomOf(app) == room ||
                    app.shortcut?.packageName in ELEMENT_PACKAGES && app.shortcut?.id?.let { ROOM_ID.find(it)?.value } == room
            }.forEach { app ->
                if (time > (lastUsed[app.key] ?: 0L)) {
                    lastUsed[app.key] = time
                    changed = true
                }
            }
        }
        if (changed) saveTimes(latinIME, PREF_LAST_USED, lastUsed)
        return changed
    }

    private fun updateTyping() {
        mainHandler.removeCallbacks(typingStops)
        val now = System.currentTimeMillis()
        val rooms = ChatTyping.typingRooms(typingTimes, now)
        ChatTyping.nextStop(typingTimes, now)?.let { mainHandler.postDelayed(typingStops, it - now) }
        if (rooms == typingRooms) return
        typingRooms = rooms
        if (isActive) show()
    }
    /** shows the list again when the calendar changes (an event added, moved or removed) */
    private val calendarObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = show()
    }
    /** the players followed while the mode is open: the list is shown again when what they play changes */
    private var players = emptyList<MediaController>()
    private val playerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = show()
        override fun onMetadataChanged(metadata: MediaMetadata?) = show()
        override fun onSessionDestroyed() = show()
    }
    private val playersChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    fun toggle() = if (isActive) stop() else start()

    fun start() {
        isActive = true
        startTime = SystemClock.uptimeMillis()
        // a tap on a button or element of the app (seen by the accessibility service) leaves the mode
        ScreenAgentService.onTapInOtherApp = { onTapOutside() }
        // notifications that arrive or go away while the list is open show up in it right away
        LauncherNotificationService.onChange = { lastUsed = readTimes(latinIME, PREF_LAST_USED); show() }
        // start also runs while the mode is open (the keyboard restarting on the home screen): follow things only once
        latinIME.contentResolver.unregisterContentObserver(calendarObserver)
        latinIME.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(playersChanged)
        if (isOnHome && latinIME.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            latinIME.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        query.setLength(0)
        // the keyboard restarts on the text field of the home screen (after a paste, for instance): keep its text as the search
        if (isOnHome) {
            HomeActivity.stopEditing()
            query.append(HomeActivity.fieldText())
        }
        lastSpaceTapTime = 0
        counts = readCounts(latinIME)
        lastUsed = readTimes(latinIME, PREF_LAST_USED)
        // a dismissal ends when the entry is opened again (or a message of the person comes) after it
        dismissedRecent = readTimes(latinIME, PREF_DISMISSED_RECENT).filter { (lastUsed[it.key] ?: 0L) <= it.value }
            .toMutableMap()
        choices = readChoices(latinIME)
        // the players playing, followed while the mode is open (follow shows the list)
        try {
            val sessions = latinIME.getSystemService(MediaSessionManager::class.java)
            sessions.addOnActiveSessionsChangedListener(playersChanged, notificationListener(), mainHandler)
            follow(sessions.getActiveSessions(notificationListener()))
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access, can't see the players", e)
            show()
        }
        followLiveStates()
        // refresh the list every time, apps may have been installed or removed
        onAppsChanged = { show() }
        reload(latinIME)
    }

    /** leaves the mode and restores the normal suggestion strip */
    fun stop() {
        if (!isActive) return
        reset()
        latinIME.setNeutralSuggestionStrip()
        // on the home screen the keyboard never hides and stays in the mode: leaving it only clears the search
        if (isOnHome) start()
    }

    /** leaves the mode without touching the suggestion strip, e.g. when the input finishes */
    fun reset() {
        isActive = false
        HomeActivity.showResults(this, emptyList())
        ScreenAgentService.onTapInOtherApp = null
        LauncherNotificationService.onChange = null
        latinIME.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(playersChanged)
        follow(emptyList())
        latinIME.contentResolver.unregisterContentObserver(calendarObserver)
        mainHandler.removeCallbacks(refresh)
        latinIME.contentResolver.unregisterContentObserver(typingObserver)
        latinIME.contentResolver.unregisterContentObserver(agentsObserver)
        mainHandler.removeCallbacks(typingStops)
        typingRooms = emptySet()
        latinIME.suggestionStripView?.setLauncherMode(false)
        if (listening) latinIME.showVoiceListening(false)
        listening = false
        message = null
        actionsFor = null
        editing = null
        speech.stop()
        query.setLength(0)
        results = emptyList()
    }

    /**
     * A tap outside the keyboard, in the app below: leaves the mode. Ignored just after starting, because the double tap
     * on space that starts it takes back a space from the text field, which moves the cursor.
     */
    override fun onTapOutside() {
        // not on the home screen: there the system's text menu (Paste) counts as a click in another app
        if (capturesInput() && SystemClock.uptimeMillis() - startTime > 600) stop()
    }

    /** handles a key while the mode is active, returns false for keys that should be handled normally (e.g. shift) */
    override fun onEvent(event: Event): Boolean {
        stopListening()
        actionsFor = null
        val codePoint = event.codePoint
        if (codePoint == Constants.CODE_SPACE && !event.isKeyRepeat) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSpaceTapTime < ViewConfiguration.getDoubleTapTimeout()) {
                stop()
                return true
            }
            lastSpaceTapTime = now
            message = null
            query.append(' ')
            show()
            return true
        }
        lastSpaceTapTime = 0
        message = null
        when {
            codePoint == Constants.CODE_ENTER -> {
                openFirst()
                return true
            }
            codePoint > 0 -> query.appendCodePoint(codePoint)
            event.keyCode == KeyCode.DELETE -> if (query.isNotEmpty())
                query.setLength(query.offsetByCodePoints(query.length, -1))
            else -> return false
        }
        show()
        return true
    }

    /** a space before a gestured word if the search already has a word, so gestures make sentences */
    override fun gestureSeparator(): String {
        return if (query.isNotEmpty() && !query.endsWith(' ')) " " else ""
    }

    override fun onTextInput(text: String) {
        stopListening()
        lastSpaceTapTime = 0
        message = null
        query.append(text)
        show()
    }

    /** editing a name, the tags or the Element link of a result, in the text field of the home screen */
    private fun edit(app: App, field: Field) {
        actionsFor = null
        editing = app to field
        HomeActivity.startEditing(when (field) {
            Field.NAME -> app.label
            Field.TAGS -> app.rawTags.joinToString(" ")
            Field.ELEMENT -> app.element.orEmpty()
        })
        show()
    }

    /** Enter in the text field of the home screen while editing: saves the value */
    fun saveEdit(value: String) {
        editing?.let { (app, field) -> save(app, field, value.trim()) }
        editing = null
        HomeActivity.stopEditing()
        show()
    }

    /** chooses how to contact a person: Element with this matrix.to link, or WhatsApp with null */
    private fun setElement(app: App, link: String?) {
        app.element = link
        val links = readStringMap(latinIME, ELEMENT_FILE).toMutableMap()
        if (link == null) links.remove(app.kissId) else links[app.kissId] = link
        writeStringMap(latinIME, ELEMENT_FILE, links)
        show()
    }

    /** saves a new name (empty for the original one) or new tags, in files next to the KISS tags */
    private fun save(app: App, field: Field, value: String) {
        if (field == Field.ELEMENT) return setElement(app, matrixLink(value))
        if (field == Field.NAME) {
            app.label = value.ifEmpty { app.originalLabel }
            val names = readStringMap(latinIME, NAMES_FILE).toMutableMap()
            if (app.label == app.originalLabel) names.remove(app.kissId) else names[app.kissId] = app.label
            writeStringMap(latinIME, NAMES_FILE, names)
        } else {
            app.rawTags = value.split(' ').filter { it.isNotBlank() }
            val tags = readStringMap(latinIME, TAGS_FILE).toMutableMap()
            if (app.rawTags.isEmpty()) tags.remove(app.kissId) else tags[app.kissId] = app.rawTags.joinToString(" ")
            writeStringMap(latinIME, TAGS_FILE, tags)
        }
        apps = apps.sortedBy { it.normalizedLabel }
    }

    /** listening for a spoken search or command: on a long press on space */
    fun onVoiceInput() {
        listening = true
        speech.listen(onReady = { if (listening) latinIME.showVoiceListening(true) }) { text ->
            if (!isActive || !listening) return@listen
            listening = false
            latinIME.showVoiceListening(false)
            if (text.isNullOrBlank()) return@listen show()
            query.setLength(0)
            query.append(text)
            // a command is run, anything else stays as search text with its results
            if (LauncherCommands.parse(text) != null) runSentence(text) else show()
        }
    }

    /** a key while listening: stop listening and type instead */
    private fun stopListening() {
        if (!listening) return
        listening = false
        speech.stop()
        latinIME.showVoiceListening(false)
    }

    /** a sentence typed or spoken: one of the commands recognized by [LauncherCommands] */
    private fun runSentence(sentence: String) {
        val command = LauncherCommands.parse(sentence)
            ?: return showMessage(latinIME.getString(R.string.launcher_command_not_understood))
        val name = (command.arguments["app"] ?: command.arguments["contact"])?.toString()?.trim().orEmpty()
        when (command.action) {
            LauncherCommands.OPEN_APP -> {
                val app = filter(apps, name, counts).firstOrNull() ?: closest(apps, name, counts)
                    ?: return showMessage(latinIME.getString(R.string.launcher_command_no_app, name))
                launch(app)
            }
            LauncherCommands.CALL_CONTACT -> {
                val (_, number) = LauncherCommands.findContact(latinIME, name)
                    ?: return showMessage(latinIME.getString(R.string.launcher_command_no_contact, name))
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
            }
            LauncherCommands.SEND_MESSAGE -> {
                val (_, number) = LauncherCommands.findContact(latinIME, name)
                    ?: return showMessage(latinIME.getString(R.string.launcher_command_no_contact, name))
                val text = command.arguments["text"]?.toString().orEmpty()
                val intent = if (command.arguments["whatsapp"] == true)
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${internationalDigits(number)}?text=${Uri.encode(text)}"))
                else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
                startActivity(intent)
            }
        }
    }

    private fun startActivity(intent: Intent) {
        try {
            latinIME.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "can't start $intent", e)
            return showMessage(latinIME.getString(R.string.launcher_command_error, e.message ?: e.javaClass.simpleName))
        }
        stop()
    }

    private fun showMessage(text: String) {
        message = text
        show()
    }

    /** shows the search text and the results in the suggestion strip, or a message */
    override fun show() {
        if (!isActive) return
        // messages that arrived while the mode was closed (before the apps were loaded, for instance) count too
        if (bump(latinIME, LauncherNotificationService.entries())) lastUsed = readTimes(latinIME, PREF_LAST_USED)
        val ranked = chosenFirst(withNotifications(alternativesFirst(withSettings(filter(apps, query.toString(), counts, lastUsed, dismissedRecent))),
            normalize(query.toString().trim())))
        results = withEvents(listOfNotNull(urlEntry(), privateSpaceEntry()) + patternEntries() + withPlayers(ranked))
        // on the home screen of Keyarchy the search is its own text field and the results a vertical list above it; the
        // actions of a result, their editing and messages are a panel there (only on the home screen)
        if (isOnHome) {
            HomeActivity.showResults(this, if (message != null || editing != null || actionsFor != null) emptyList() else results)
            val edited = editing
            val selected = actionsFor
            val text = message
            when {
                edited != null -> HomeActivity.showPanel(latinIME.getString(when (edited.second) {
                    Field.NAME -> R.string.launcher_edit_name
                    Field.TAGS -> R.string.launcher_edit_tags
                    Field.ELEMENT -> R.string.launcher_edit_element
                }, edited.first.label), emptyList())
                selected != null -> HomeActivity.showPanel(selected.label, panelActions(selected))
                text != null -> HomeActivity.showPanel(text, emptyList())
                else -> HomeActivity.hidePanel()
            }
            return
        }
        val strip = latinIME.suggestionStripView ?: return
        strip.setLauncherMode(true)
        strip.setExternalSuggestionView(createRow(strip), false)
    }

    /** the actions of a result on the home screen: name, tags, how to contact a person, uninstalling an app */
    private fun panelActions(app: App): List<Pair<String, () -> Unit>> {
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            latinIME.getString(R.string.launcher_rename) to { edit(app, Field.NAME) },
            latinIME.getString(R.string.launcher_tags) to { edit(app, Field.TAGS) })
        if (app.whatsAppDataId != null) {
            // for a person: how to contact them, the current way marked
            actions.add((if (app.element == null) "• " else "") + "WhatsApp" to { setElement(app, null) })
            actions.add((if (app.element != null) "• " else "") + "Element" to { edit(app, Field.ELEMENT) })
        } else if (app.shortcut != null) {
            actions.add(latinIME.getString(R.string.launcher_remove_shortcut) to { removeShortcut(app) })
        } else if (app.intent == null && app.run == null) {
            actions.add(latinIME.getString(R.string.launcher_uninstall) to { uninstall(app) })
        }
        return actions
    }

    /**
     * Takes a shortcut out of the list: one pinned to Keyarchy is unpinned; one the app offers is remembered as removed
     * (if the app offers it again later, it stays out).
     */
    private fun removeShortcut(app: App) {
        val shortcut = app.shortcut ?: return
        if (shortcut.isOffered) latinIME.prefs().edit {
            putStringSet(PREF_REMOVED_SHORTCUTS, latinIME.prefs().getStringSet(PREF_REMOVED_SHORTCUTS, emptySet())!! + app.kissId)
        }
        if (shortcut.isPinned) unpin(shortcut, app.user)
        actionsFor = null
        apps = apps - app
        show()
    }

    private fun unpin(shortcut: Shortcut, user: UserHandle) {
        val launcherApps = latinIME.getSystemService(LauncherApps::class.java)
        try {
            val query = LauncherApps.ShortcutQuery().setPackage(shortcut.packageName)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val pinned = launcherApps.getShortcuts(query, user).orEmpty().map { it.id } - shortcut.id
            launcherApps.pinShortcuts(shortcut.packageName, pinned, user)
        } catch (e: Exception) {
            Log.w(TAG, "can't unpin ${shortcut.id}", e)
        }
    }

    /** asks Android to uninstall the app (it asks for confirmation), also in the work profile and the private space */
    private fun uninstall(app: App) {
        actionsFor = null
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
            .putExtra(Intent.EXTRA_USER, app.user))
    }

    /** the mode takes the keys, gestures and voice instead of the text field: everywhere but on the home screen */
    override fun capturesInput() = isActive && !isOnHome

    /** the keyboard is in the text field of the home screen of Keyarchy, where typing goes to the field and not to the mode */
    val isOnHome get() = HomeActivity.isHomeField(latinIME.currentInputEditorInfo)

    /** for the home screen: the home gesture or back there clear the search text, and leave an action or its editing */
    fun clearSearch() {
        if (!isActive) return
        message = null
        actionsFor = null
        editing = null
        HomeActivity.stopEditing()
        query.setLength(0)
        show()
    }

    /** for the home screen: the × of the panel leaves an action, its editing or a message, keeping the search */
    fun closePanel() {
        if (!isActive) return
        message = null
        actionsFor = null
        editing = null
        HomeActivity.stopEditing()
        show()
    }

    /** for the home screen: the text of its field is the search text */
    fun setHomeQuery(text: String) {
        if (!isActive || editing != null) return
        query.setLength(0)
        query.append(text)
        message = null
        actionsFor = null
        show()
    }

    /**
     * Enter, in the strip or the field of the home screen: a sentence recognized as a command runs, anything else
     * (a name, "s chuva lisboa", "sms sílvia") opens the first result
     */
    fun openFirst() {
        val text = query.toString().trim()
        if (text.isEmpty()) return
        if (LauncherCommands.parse(text) == null && results.isNotEmpty()) launch(results.first()) else runSentence(text)
    }

    /** for the list of the home screen: open the result */
    fun open(app: App) = launch(app)

    /**
     * The hide key of the keyboard on the home screen: back to the app that was in front before, as the accessibility
     * service saw it, else to the entry opened last from the list; false when there is none (the keyboard stays).
     */
    fun returnToLastApp(): Boolean {
        val target = ScreenAgentService.lastApp?.let { last ->
            apps.filter { it.shortcut == null && it.whatsAppDataId == null && it.component.packageName == last }
                .minByOrNull { if (it.user == Process.myUserHandle()) 0 else 1 }
        } ?: apps.maxByOrNull { lastUsed[it.key] ?: 0L }?.takeIf { (lastUsed[it.key] ?: 0L) > 0L } ?: return false
        launch(target)
        return true
    }

    /** for the list of the home screen: the actions of a result (name, tags, WhatsApp or Element) in the strip */
    fun showActions(app: App) {
        if (app.notification != null) return
        actionsFor = app
        show()
    }

    /** whether a slide dismisses [app]: a notification, a message, an event, or an app, person or shortcut of the list */
    fun canDismiss(app: App) = app.notification != null || app.unread != null || app.eventKey != null ||
        apps.any { it.key == app.key } || app.key.startsWith(SETTINGS_KEY)

    /**
     * A slide on an entry: dismisses a notification, or the message shown in the entry of a person, or an event (from
     * the list only, it stays in the calendar); an app, a person without a message or a shortcut leaves the recent ones,
     * until opened again or (a person, a shortcut) a new message or change.
     */
    fun dismiss(app: App) {
        val message = app.notification ?: app.unread
        message?.let { LauncherNotificationService.dismiss(it.key) }
        if (message == null && app.eventKey == null) {
            dismissedRecent[app.key] = System.currentTimeMillis()
            saveTimes(latinIME, PREF_DISMISSED_RECENT, dismissedRecent)
        }
        app.eventKey?.let { key ->
            // the events dismissed before today are not needed any more
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val kept = dismissedEvents().filter { (it.substringAfter('|').toLongOrNull() ?: 0L) >= today }.toSet()
            latinIME.prefs().edit { putStringSet(PREF_DISMISSED_EVENTS, kept + key) }
        }
        show()
    }

    private fun dismissedEvents() = latinIME.prefs().getStringSet(PREF_DISMISSED_EVENTS, emptySet())!!

    /**
     * For a shortcut: the name of its app (as named in the list), to tell which app it belongs to, and its detail. An
     * Termarchy pane has Termarchy's icon instead, and its detail only when its agent waits for input or finished unseen: a
     * working one pulses, an idle one says nothing.
     */
    fun appNameOf(app: App): String? {
        val shortcut = app.shortcut ?: return null
        if (shortcut.packageName == TermarchyForwards.TERMARCHY_PACKAGE) return shortcut.agent?.status?.takeIf { it == "blocked" || it == "done" }
        return listOfNotNull(appOf(app)?.label, shortcut.detail).joinToString(" · ").ifEmpty { null }
    }

    /** the app of a shortcut, as in the list */
    private fun appOf(app: App) = apps.firstOrNull {
        it.shortcut == null && it.whatsAppDataId == null && it.component == app.component && it.user == app.user
    }

    /** the icon shown for a result (Element for a person contacted through it, Termarchy's for a Termarchy pane) */
    fun iconOf(app: App): Drawable? = app.element?.let { elementIcon }
        ?: app.shortcut?.takeIf { it.packageName == TermarchyForwards.TERMARCHY_PACKAGE }?.let { appOf(app)?.icon }
        ?: app.icon

    /**
     * Shows the icon of a result in [view]: for a person typing in MatriX, its icon with the crown moving; for a
     * Termarchy pane whose agent is working (not one waiting for input), the ghost of Termarchy blinking in [color]
     * instead, as Termarchy shows it.
     */
    fun showIcon(view: ImageView, app: App, color: Int) {
        if (app.element != null && roomOf(app) in typingRooms) {
            if (view.drawable !is TypingBubbleDrawable) view.setImageDrawable(TypingBubbleDrawable(color).also { it.start() })
            return
        }
        val shortcut = app.shortcut?.takeIf { it.agent?.status == "working" }
            ?: return view.setImageDrawable(iconOf(app))
        // in step with when the agent started working, as Termarchy gives it (else from when the list first showed it)
        val startedAt = shortcut.since.takeIf { it > 0 }
            ?: spinnerStarts.getOrPut(app.key) { System.currentTimeMillis() }
        if ((view.drawable as? WorkingGhostDrawable)?.startedAt == startedAt) return
        view.setImageDrawable(WorkingGhostDrawable(color, startedAt).also { it.start() })
    }
    /** when the list first showed a working Termarchy pane whose start Termarchy did not give, by key */
    private val spinnerStarts = mutableMapOf<String, Long>()

    private fun createRow(strip: SuggestionStripView): View {
        val context = strip.context
        val res = context.resources
        val color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        // the typed text, when nothing matches it, where the names of the results start (after the room of an icon),
        // in bold as the matching part of a name, so it does not move when the results go away or come back
        val queryView = TextView(context)
        queryView.text = query
        queryView.setTextColor(color)
        queryView.textSize = 14f
        queryView.setTypeface(null, Typeface.BOLD)
        queryView.setPadding((8 + 28 + 4).dpToPx(res), 0, 12.dpToPx(res), 0)
        row.addView(queryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER_VERTICAL })
        queryView.gravity = Gravity.CENTER_VERTICAL

        // the typed text is shown on its own only when nothing matches it (the matches show it in their names); with
        // results it takes no room, so the first one starts where the typed text would
        if (results.isNotEmpty()) queryView.visibility = View.GONE
        message?.let {
            queryView.text = it
            queryView.setTypeface(null, Typeface.NORMAL)
            queryView.visibility = View.VISIBLE
        }
        for (app in if (message != null) emptyList() else results.take(MAX_SHOWN)) {
            val chip = LinearLayout(context)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPadding(8.dpToPx(res), 0, 8.dpToPx(res), 0)
            chip.setOnClickListener { launch(app) }
            // in the strip only a notification or a message is dismissed; apps, people and shortcuts only on the home screen
            if (app.notification != null || app.unread != null) slideToDismiss(chip, app)
            val icon = ImageView(context)
            showIcon(icon, app, color)
            chip.addView(icon, LinearLayout.LayoutParams(28.dpToPx(res), 28.dpToPx(res)))
            val label = TextView(context)
            label.text = highlighted(app.label, color)
            app.unread?.let { label.append(": " + it.text.replace('\n', ' ').take(40)) }
            appNameOf(app)?.let { label.append(" · $it") }
            label.setTextColor(ColorUtils.setAlphaComponent(color, 180))
            label.textSize = 14f
            label.maxLines = 1
            label.setPadding(4.dpToPx(res), 0, 0, 0)
            chip.addView(label)
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return scroll
    }

    /**
     * An entry of the strip is dismissed by sliding it to the right, as in the notification shade ([dismiss]); to the
     * left the row scrolls, and a tap opens it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun slideToDismiss(chip: View, app: App) {
        val slop = ViewConfiguration.get(chip.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        var sliding = false
        val slideBack = { chip.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS) }
        chip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    sliding = false
                    // until the direction is known, neither the row (scrolling) nor the strip (more suggestions, upwards)
                    // takes the touch
                    chip.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        if (dx > abs(dy)) sliding = true
                        else chip.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    if (sliding) {
                        chip.translationX = maxOf(dx, 0f)
                        chip.alpha = 1f - (chip.translationX / chip.width).coerceAtMost(1f)
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    !moved -> chip.performClick()
                    sliding && chip.translationX > chip.width / 4f -> chip.animate()
                        .translationX(chip.width.toFloat()).alpha(0f).setDuration(SLIDE_MS).withEndAction { dismiss(app) }
                    sliding -> slideBack()
                }
                MotionEvent.ACTION_CANCEL -> if (sliding) slideBack()
            }
            true
        }
    }

    /** the name with the part matching the search text in bold and in the full color (accents ignored) */
    fun highlighted(name: String, color: Int): CharSequence {
        val q = normalize(query.toString().trim())
        if (q.isEmpty()) return name
        // normalized one character at a time, so the positions stay those of the name
        val normalized = name.map { normalize(it.toString()).ifEmpty { it.toString() }.first() }.joinToString("")
        val start = normalized.indexOf(q)
        if (start < 0) return name
        return SpannableString(name).apply {
            setSpan(StyleSpan(Typeface.BOLD), start, start + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color), start, start + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun launch(app: App) {
        app.run?.let {
            if (!app.staysOpen) stop()
            it()
            return
        }
        app.intent?.let {
            // an SMS conversation: tap its message box once open, so the keyboard stays
            if (app.key.startsWith("sms|")) ScreenAgentService.focusMessageFieldSoon(app.component.packageName)
            // a screen of the Settings is ranked by its use, as the apps
            if (app.key.startsWith(SETTINGS_KEY)) recordUse(app)
            startActivity(it)
            return
        }
        app.notification?.let {
            it.open()
            stop()
            return
        }
        // a person with a message: the chat where it came (WhatsApp or Element), with the message box tapped
        app.unread?.let {
            ScreenAgentService.focusMessageFieldSoon(it.packageName)
            if (it.open()) {
                recordUse(app)
                stop()
                return
            }
        }
        try {
            // a chat: tap its message box once it is open, so the keyboard stays
            if (app.element != null || app.whatsAppDataId != null)
                ScreenAgentService.focusMessageFieldSoon(if (app.element != null) elementPackage else "com.whatsapp")
            if (app.element != null)
                latinIME.startActivity(elementIntent(app.element!!))
            else if (app.whatsAppDataId != null)
                latinIME.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, app.whatsAppDataId), WHATSAPP_MIME_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else if (app.shortcut != null)
                latinIME.getSystemService(LauncherApps::class.java).startShortcut(app.shortcut.packageName, app.shortcut.id, null, null, app.user)
            else if (latinIME.getSystemService(UserManager::class.java).isQuietModeEnabled(app.user)) launchWhenUnlocked(app)
            else latinIME.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, null, null)
            recordUse(app)
        } catch (e: Exception) {
            Log.w(TAG, "can't launch ${app.key}", e)
        }
        stop()
    }

    /**
     * An app of a locked profile (the private space, or a paused work profile): asks the system to unlock it (with the
     * credentials for the private space) and launches the app once it is unlocked, waiting up to a minute.
     */
    private fun launchWhenUnlocked(app: App) {
        val userManager = latinIME.getSystemService(UserManager::class.java)
        userManager.requestQuietModeEnabled(false, app.user)
        val handler = Handler(Looper.getMainLooper())
        var waited = 0
        val check = object : Runnable {
            override fun run() {
                // out of quiet mode is not enough: until the profile is unlocked its apps can't read their data, and
                // Instagram closed right away
                if (!userManager.isQuietModeEnabled(app.user) && userManager.isUserUnlocked(app.user)) {
                    try {
                        latinIME.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "can't launch ${app.key}", e)
                    }
                } else if (++waited < 240) handler.postDelayed(this, 250)
            }
        }
        handler.postDelayed(check, 250)
    }

    /** an entry opening the search text as a web address, if it looks like one (solx.pt, https://…, 192.168.50.2:8123) */
    private fun urlEntry(): App? {
        val text = query.toString().trim()
        if (!URL.matches(text)) return null
        val uri = Uri.parse(if (text.contains("://")) text else "https://$text")
        val browser = latinIME.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        // the app that opens this link (an app verified for its domain, like YouTube for youtu.be), else the browser
        val handler = latinIME.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" } ?: chosenLinkHandler(intent, uri.host)
        val opener = apps.firstOrNull { it.component.packageName == handler && it.user == Process.myUserHandle() }
            ?: apps.firstOrNull { it.component.packageName == browser && it.user == Process.myUserHandle() }
            ?: apps.firstOrNull { it.component.packageName in KNOWN_BROWSERS } ?: return null
        // through Android, so links of verified apps open in them (claude.ai in Claude)
        return App("url|$text", "url://$text", text, opener.component, Process.myUserHandle(), opener.icon,
            null, emptyList(), intent = intent)
    }

    /**
     * Entries for what the search text looks like: a phone number (call), a time like 7h15, 7h or 07:15 (alarm) and a
     * duration in minutes or seconds like 10 min (timer).
     */
    private fun patternEntries(): List<App> {
        val text = query.toString().trim()
        val entries = mutableListOf<App>()
        val digits = text.filter { it.isDigit() }
        if (PHONE.matches(text) && digits.length in 9..15)
            entry("call", latinIME.getString(R.string.launcher_call, text), Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(text.replace(" ", "")))))
                ?.let { entries.add(it) }
        TIME.matchEntire(text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minutes = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour < 24 && minutes < 60)
                entry("alarm", latinIME.getString(R.string.launcher_alarm, String.format(Locale.ROOT, "%02d:%02d", hour, minutes)),
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minutes).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    ?.let { entries.add(it) }
        }
        DURATION.matchEntire(text)?.takeIf { m -> (1..2).any { m.groupValues[it].isNotEmpty() } }?.let { m ->
            val seconds = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
            if (seconds in 1..86400)
                entry("timer", latinIME.getString(R.string.launcher_timer, text),
                    Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    ?.let { entries.add(it) }
        }
        DRIVE.matchEntire(text)?.let { m -> entries.addAll(driveEntries(normalize(m.groupValues[1].trim()))) }
        SshCommand.parse(text)?.let { command -> sshEntry(command)?.let { entries.add(it) } }
        SEARCH.matchEntire(text)?.let { m -> searchEntry(m.groupValues[1].trim())?.let { entries.add(it) } }
        SMS.matchEntire(text)?.let { m -> entries.addAll(smsEntries(normalize(m.groupValues[1].trim()))) }
        CALL.matchEntire(text)?.let { m -> entries.addAll(callEntries(normalize(m.groupValues[1].trim()))) }
        return entries
    }

    /** the notification listener, whose access also gives the players playing (media sessions) */
    private fun notificationListener() = ComponentName(latinIME, LauncherNotificationService::class.java)

    /** follows what [controllers] play, instead of the players followed so far; shows the list again */
    private fun follow(controllers: List<MediaController>) {
        players.forEach { it.unregisterCallback(playerCallback) }
        players = controllers
        players.forEach { it.registerCallback(playerCallback, mainHandler) }
        show()
    }

    /**
     * [list] with the players playing in the place of their app, as "title · artist" (so as far forward as the app is
     * used); with search text, a player matching only by its title or artist goes first.
     */
    private fun withPlayers(list: List<App>): List<App> {
        val q = normalize(query.toString().trim())
        val playing = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
        val result = list.toMutableList()
        for (player in players.filter { it.playbackState?.state in playing }) {
            val app = apps.firstOrNull { it.component.packageName == player.packageName && it.user == Process.myUserHandle() }
                ?: continue
            val metadata = player.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
            val label = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { app.label }
            val entry = App("player|${player.packageName}", "player://${player.packageName}", label, app.component, app.user,
                app.icon, null, app.rawTags, run = { openPlayer(player, app) })
            val i = result.indexOf(app)
            when {
                i >= 0 -> result[i] = entry
                q.isNotEmpty() && normalize(label).contains(q) -> result.add(0, entry)
            }
        }
        return result
    }

    /** the screen of the player that plays (its session activity), or else the player app */
    private fun openPlayer(player: MediaController, app: App) {
        try {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            else null
            player.sessionActivity?.let { it.send(null, 0, null, null, null, null, options); return }
        } catch (e: Exception) {
            Log.w(TAG, "can't open the screen of ${player.packageName}", e)
        }
        latinIME.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, null, null)
    }

    /**
     * On the home screen, [list] with the events of today in the calendars shown in the calendar app (and not turned
     * off in the settings, "Calendars") that are not over yet, as "10:00 title", leaving out all-day ones and those of 24
     * hours or more; with search text, those with it in the title. Each one goes as many places from the front as there
     * are whole hours until it begins (0 once begun), so it comes forward as it gets near.
     * Opening one shows it in the calendar app. The list is shown again when that changes, or when an event ends.
     */
    private fun withEvents(list: List<App>): List<App> {
        mainHandler.removeCallbacks(refresh)
        if (!isOnHome || latinIME.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return list
        val q = normalize(query.toString().trim())
        val now = System.currentTimeMillis()
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 1)
        class Event(val id: Long, val title: String, val begin: Long, val end: Long, val calendar: String)
        val events = mutableListOf<Event>()
        val hiddenCalendars = latinIME.prefs().getStringSet(PREF_HIDDEN_CALENDARS, emptySet())!!
        try {
            CalendarContract.Instances.query(latinIME.contentResolver, arrayOf(CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.VISIBLE,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Instances.CALENDAR_ID), start, day.timeInMillis)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0 || c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        || c.getLong(9).toString() in hiddenCalendars
                        || c.getInt(4) == 1 || c.getLong(3) - c.getLong(2) >= 24 * 3_600_000L) continue
                    // the name of the calendar ("Teatro"), or its address shortened: lucio.ferrao@solx.pt is "lucio@solx"
                    val calendar = c.getString(7).orEmpty().ifBlank { c.getString(8).orEmpty() }.let { name ->
                        if (!name.contains('@')) name
                        else name.substringBefore('@').split('.').let { if (it.size == 2) it[0] else it.joinToString(".") } +
                            "@" + name.substringAfter('@').substringBeforeLast('.')
                    }
                    val event = Event(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), calendar)
                    if (event.end > now && event.title.isNotBlank()) events.add(event)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to read the calendar", e)
        }
        val hour = 3_600_000L
        val dismissed = dismissedEvents()
        val shown = events.filter { (q.isEmpty() || normalize(it.title).contains(q)) && "${it.id}|${it.begin}" !in dismissed }
        val changes = events.map { it.end } +
            shown.map { it.begin - now }.filter { it > 0 }.map { now + it % hour }
        changes.minOrNull()?.let { mainHandler.postDelayed(refresh, maxOf(it - now, 1000)) }
        val time = android.text.format.DateFormat.getTimeFormat(latinIME)
        val result = list.toMutableList()
        var last = -1
        for (event in shown.sortedWith(compareBy({ maxOf(it.begin - now, 0) / hour }, { it.begin }))) {
            val entry = entry("event", time.format(event.begin) + " " + event.title, Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)) ?: continue
            entry.eventKey = "${event.id}|${event.begin}"
            entry.calendar = event.calendar.ifBlank { null }
            // the reminder of the calendar app for the same event would say the same (its title wrapped in direction marks)
            fun plain(s: String) = s.replace(Regex("\\p{Cf}"), "").trim()
            result.removeAll { it.notification?.packageName == entry.component.packageName && plain(it.notification.title) == plain(event.title) }
            last = minOf(maxOf((maxOf(event.begin - now, 0) / hour).toInt(), last + 1), result.size)
            result.add(last, entry)
        }
        return result
    }

    /**
     * "ssh olivais": Termarchy opened connected to that server; "ssh [olivais] -L 3000", "-R 5555", "-KL 3000": a port forward
     * asked of Termarchy, whose answer stays in the strip. Only with Termarchy installed.
     */
    private fun sshEntry(command: SshCommand): App? {
        val termarchy = apps.firstOrNull {
            it.component.packageName == TermarchyForwards.TERMARCHY_PACKAGE && it.shortcut == null && it.user == Process.myUserHandle()
        } ?: return null
        return when (command) {
            is SshCommand.Connect -> App("ssh|${command.server}", "ssh://${command.server}",
                latinIME.getString(R.string.launcher_ssh_connect, command.server), termarchy.component, Process.myUserHandle(),
                termarchy.icon, null, emptyList(), intent = TermarchyForwards.connectIntent(command.server))
            is SshCommand.Forward -> {
                val what = when {
                    command.cancel -> latinIME.getString(R.string.launcher_ssh_stop, command.label)
                    command.direction == 'L' -> latinIME.getString(R.string.launcher_ssh_local, command.port, command.host, command.hostPort)
                    else -> latinIME.getString(R.string.launcher_ssh_remote, command.port, command.host, command.hostPort)
                }
                val label = command.server?.let { "$what · $it" } ?: what
                App("ssh|${command.arguments}", "ssh://${command.arguments}", label, termarchy.component, Process.myUserHandle(),
                    termarchy.icon, null, emptyList(), staysOpen = true, run = {
                        TermarchyForwards.request(latinIME, command.server, command.arguments) { ok, text ->
                            showMessage(if (ok) latinIME.getString(R.string.launcher_ssh_done, label) else text)
                        }
                    })
            }
        }
    }

    /** "s chuva lisboa", "search chuva lisboa": the text searched in the default browser, with its search engine */
    private fun searchEntry(q: String): App? {
        val browser = latinIME.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return null
        // to the browser, as Android would otherwise ask which of the apps that search to use
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).setPackage(browser)
        if (latinIME.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return null
        return entry("search", latinIME.getString(R.string.launcher_search, q), intent)
    }

    /** "sms sílvia": the SMS conversation with the matching starred contacts, at their default number */
    private fun smsEntries(q: String): List<App> {
        if (q.isEmpty()) return emptyList()
        val numbers = starredData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER)
        return starredMatching(q, numbers).mapNotNull { (name, label) ->
            val number = numbers.getValue(name)
            entry("sms", latinIME.getString(R.string.launcher_sms, label, number),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))))
        }.take(5)
    }

    /** "c sílvia", "call sílvia", "liga à sílvia", "ligar sílvia": the dialer with the default number of the matching starred contacts */
    private fun callEntries(q: String): List<App> {
        if (q.isEmpty()) return emptyList()
        val numbers = starredData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER)
        return starredMatching(q, numbers).mapNotNull { (name, label) ->
            val number = numbers.getValue(name)
            entry("call", latinIME.getString(R.string.launcher_call_person, label, number),
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number.replace(" ", "")))))
        }.take(5)
    }

    /**
     * The starred contacts with a value in [data] (with or without WhatsApp) whose name, the name given in the launcher
     * mode or a tag starts with the text, or a word of it does: contact name to the name to show.
     */
    private fun starredMatching(q: String, data: Map<String, String>): List<Pair<String, String>> {
        val names = readStringMap(latinIME, NAMES_FILE)
        val tags = readStringMap(latinIME, TAGS_FILE)
        return data.keys.mapNotNull { name ->
            val kissId = "whatsapp://$name"
            val label = names[kissId] ?: name
            val texts = (listOf(name, label) + tags[kissId].orEmpty().split(' ')).map { normalize(it) }
            if (texts.any { text -> text.startsWith(q) || text.split(' ').any { it.startsWith(q) } }) name to label else null
        }
    }

    /**
     * "d sílvia", "drive sílvia", "go sílvia": driving directions in Google Maps to the default address (or the first one)
     * of the matching starred contacts, found by the contact name or the name given in the launcher mode.
     */
    private fun driveEntries(q: String): List<App> {
        if (q.isEmpty()) return emptyList()
        val addresses = starredData(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
        return starredMatching(q, addresses).mapNotNull { (name, label) ->
            val address = addresses.getValue(name)
            entry("drive", latinIME.getString(R.string.launcher_drive, label, address.replace('\n', ' ')),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(address.replace('\n', ' '))))
                    .setPackage(MAPS_PACKAGE))
        }.take(5)
    }

    /** one value of a kind (address, phone number) for each starred contact, by name: the default one, else the first */
    private fun starredData(mimeType: String, column: String): Map<String, String> = try {
        latinIME.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, column),
            ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.STARRED + " = 1",
            arrayOf(mimeType),
            ContactsContract.Data.IS_SUPER_PRIMARY + " DESC, " + ContactsContract.Data.IS_PRIMARY + " DESC"
        )?.use { cursor ->
            val values = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val value = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                values.putIfAbsent(name, value)
            }
            values
        } ?: emptyMap()
    } catch (e: SecurityException) {
        Log.w(TAG, "no permission to read contacts", e)
        emptyMap()
    }

    /** an entry running [intent], with the icon of the app that handles it (the dialer, the clock) */
    private fun entry(kind: String, label: String, intent: Intent): App? {
        val handler = intent.`package` ?: latinIME.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val app = apps.firstOrNull { it.component.packageName == handler && it.user == Process.myUserHandle() } ?: return null
        return App("$kind|$label", "$kind://$label", label, app.component, Process.myUserHandle(), app.icon, null, emptyList(),
            intent = intent)
    }

    /**
     * The app chosen to open links of [host] (in the settings, "Open by default"), or verified for it, among the apps
     * that can open the link, for when Android would otherwise ask which one (several apps for youtu.be).
     */
    private fun chosenLinkHandler(intent: Intent, host: String?): String? {
        if (host == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val verification = latinIME.getSystemService(DomainVerificationManager::class.java) ?: return null
        return latinIME.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct().firstOrNull { pkg ->
                val state = try { verification.getDomainVerificationUserState(pkg) } catch (e: Exception) { null }
                state != null && state.isLinkHandlingAllowed && state.hostToStateMap[host].let {
                    it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
                }
            }
    }

    /**
     * Without search text, the app being typed in and its chat open (read when the keyboard opened in it) go last: they
     * are already open, so the most recent alternatives come first, like Alt-Tab. The other chats and shortcuts of that
     * app keep their place.
     */
    private fun alternativesFirst(list: List<App>): List<App> {
        if (query.isNotBlank()) return list
        val current = latinIME.currentInputEditorInfo?.packageName ?: return list
        val (here, others) = list.partition {
            it.targetPackage == current && (it.shortcut == null && it.whatsAppDataId == null || it.key == openChat)
        }
        return others + here
    }

    /**
     * Adds the notifications matching the text (title, text or app name): without text all of them first, newest first;
     * with text after the apps whose name starts with it, so Enter still opens the app being typed.
     */
    private fun withNotifications(appResults: List<App>, q: String): List<App> {
        apps.forEach { it.unread = null }
        val notifications = LauncherNotificationService.entries().mapNotNull { entry ->
            // a message from a person in the list goes in their entry (the newest one), not in an entry of its own
            val person = personOf(entry)
            if (person != null && person in appResults) {
                if (person.unread == null) person.unread = entry
                return@mapNotNull null
            }
            val app = apps.firstOrNull { it.component.packageName == entry.packageName && it.user == Process.myUserHandle() }
            val searched = normalize(listOf(entry.title, entry.text, app?.label.orEmpty()).joinToString(" "))
            if (q.isNotEmpty() && !searched.contains(q)) return@mapNotNull null
            App("notification|${entry.key}", "notification://${entry.key}", entry.label.take(50),
                app?.component ?: ComponentName(entry.packageName, ""), Process.myUserHandle(),
                app?.icon ?: return@mapNotNull null, null, emptyList(), notification = entry)
        }
        if (q.isEmpty()) return byTime(notifications, appResults)
        val (typed, rest) = appResults.partition { app -> app.names.any { it.startsWith(q) } }
        return typed + notifications + rest
    }

    /**
     * Without a search, one order in time: each notification (newest first) by when it came, among the apps and people
     * by when they were last opened (or a shortcut last changed); the order of the apps among themselves is kept.
     */
    private fun byTime(notifications: List<App>, appResults: List<App>): List<App> {
        val now = System.currentTimeMillis()
        fun used(app: App) = recency(lastUsed[app.key] ?: 0L, app.shortcut, dismissedRecent[app.key] ?: 0L, now)
        val result = ArrayList<App>(notifications.size + appResults.size)
        var n = 0
        for (app in appResults) {
            while (n < notifications.size && notifications[n].notification!!.time >= used(app)) result.add(notifications[n++])
            result.add(app)
        }
        result.addAll(notifications.subList(n, notifications.size))
        return result
    }

    /**
     * "privado", "espaço", "private": locks or unlocks the private space (the system asks for the credentials). Only
     * possible as the default home app, which may see the private profile (ACCESS_HIDDEN_PROFILES).
     */
    /**
     * The screens of the Settings among the results: with a search, those it names, after the apps whose name starts
     * with it; without one, only those opened before, in place by when, so they do not fill the list.
     */
    private fun withSettings(list: List<App>): List<App> {
        val screens = settingsEntries()
        val q = normalize(query.toString().trim())
        val now = System.currentTimeMillis()
        fun used(app: App) = recency(lastUsed[app.key] ?: 0L, app.shortcut, dismissedRecent[app.key] ?: 0L, now)
        if (q.isNotEmpty()) {
            val found = screens.mapNotNull { (screen, app) -> screen.rank(q)?.let { app to it } }
                .sortedWith(compareBy<Pair<App, Int>> { it.second }.thenByDescending { used(it.first) }).map { it.first }
            val (typed, rest) = list.partition { app -> app.names.any { it.startsWith(q) } }
            return typed + found + rest
        }
        val opened = screens.map { it.second }.filter { used(it) > 0 }.sortedByDescending { used(it) }
        if (opened.isEmpty()) return list
        val result = ArrayList<App>(list.size + opened.size)
        var next = 0
        for (app in list) {
            while (next < opened.size && used(opened[next]) >= used(app)) result.add(opened[next++])
            result.add(app)
        }
        result.addAll(opened.subList(next, opened.size))
        return result
    }

    /** the screens of the Settings that open on this phone with their entries, made again when the list of apps changes */
    private var settingsEntries: Pair<List<App>, List<Pair<SettingsScreen, App>>>? = null

    private fun settingsEntries(): List<Pair<SettingsScreen, App>> {
        settingsEntries?.takeIf { it.first === apps }?.let { return it.second }
        val settings = apps.firstOrNull {
            it.component.packageName == "com.android.settings" && it.shortcut == null && it.user == Process.myUserHandle()
        }
        // named in the language of the system, found by both
        val portuguese = latinIME.resources.configuration.locales[0].language == "pt"
        val entries = if (settings == null) emptyList() else SettingsScreen.ALL
            .filter { latinIME.packageManager.resolveActivity(it.intent(), 0) != null }
            .map { screen ->
                screen to App("$SETTINGS_KEY${screen.id}", "settings://${screen.id}",
                    latinIME.getString(R.string.launcher_settings_screen, if (portuguese) screen.pt else screen.en),
                    settings.component, settings.user, settings.icon, null, emptyList(), intent = screen.intent())
            }
        settingsEntries = apps to entries
        return entries
    }

    private fun privateSpaceEntry(): App? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val q = normalize(query.toString().trim())
        if (q.length < 3 || PRIVATE_SPACE_WORDS.none { it.startsWith(q) }) return null
        val user = privateProfile(latinIME) ?: return null
        val userManager = latinIME.getSystemService(UserManager::class.java)
        val locked = userManager.isQuietModeEnabled(user)
        val settings = apps.firstOrNull { it.component.packageName == "com.android.settings" } ?: return null
        val label = latinIME.getString(if (locked) R.string.launcher_unlock_private_space else R.string.launcher_lock_private_space)
        return App("private-space", "private-space://", label, settings.component, Process.myUserHandle(),
            latinIME.packageManager.getUserBadgedIcon(settings.icon, user), null, emptyList(),
            run = { userManager.requestQuietModeEnabled(!locked, user) })
    }

    /** the result chosen last for the same search text goes first */
    private fun chosenFirst(list: List<App>): List<App> {
        val key = choices[normalize(query.toString().trim())] ?: return list
        val (chosen, rest) = list.partition { it.key == key }
        return chosen + rest
    }

    /** counts the launch, for the order of the results, and remembers it as the choice for the search text */
    private fun recordUse(app: App) {
        val q = normalize(query.toString().trim())
        if (q.isNotEmpty()) {
            choices[q] = app.key
            latinIME.prefs().edit { putString(PREF_CHOICES, choices.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
        }
        counts[app.key] = (counts[app.key] ?: 0) + 1
        lastUsed[app.key] = System.currentTimeMillis()
        if (dismissedRecent.remove(app.key) != null) saveTimes(latinIME, PREF_DISMISSED_RECENT, dismissedRecent)
        latinIME.prefs().edit {
            putString(PREF_COUNTS, counts.entries.joinToString("\n") { "${it.key}=${it.value}" })
            putString(PREF_LAST_USED, lastUsed.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    /** an app with the name and tags given in the launcher mode (or in KISS), [kissId] identifying it as KISS does */
    /** a shortcut of an app: what starts or removes it; offered = by the app itself (not only pinned) */
    class Shortcut(val packageName: String, val id: String, val isPinned: Boolean, val isOffered: Boolean,
                   /**
                    * when what it shows (name, detail) last changed: recency of its own; an app publishing it again
                    * unchanged does not count. 0 if never seen changing.
                    */
                   val changedAt: Long,
                   /** its long label when it says more than the name, e.g. the folder of a Termarchy pane */
                   val detail: String?) {
        /** for a Termarchy pane, its agent as Termarchy tells it now ([agentStates]) */
        val agent: AgentState? get() = if (packageName == TermarchyForwards.TERMARCHY_PACKAGE) agentStates[id] else null
        /** a Termarchy pane whose agent works or waits for input: as recent as now, for as long as it lasts */
        val active: Boolean get() = agent?.active == true
        /** when it last changed: when its agent last changed state, for a Termarchy pane, else [changedAt] */
        val since: Long get() = agent?.since ?: changedAt
        /** how recent it is at [now] */
        fun changedTime(now: Long) = if (active) now else since
    }

    class App(val key: String, val kissId: String, val originalLabel: String, val component: ComponentName,
              val user: UserHandle, val icon: Drawable, label: String?, tags: List<String>,
              /** for a WhatsApp chat: the id of the WhatsApp row of the contact (ContactsContract.Data) */
              val whatsAppDataId: Long? = null,
              /** for a person contacted through Element X: the matrix.to link of the chat */
              var element: String? = null,
              /** for a notification in the status bar */
              val notification: LauncherNotificationService.Entry? = null,
              /** for a web address, a phone number, an alarm or a timer typed in the search: what to start */
              val intent: Intent? = null,
              /** for an action of the keyboard itself, like unlocking the private space */
              val run: (() -> Unit)? = null,
              /** [run] keeps the mode open, to show its result there (a port forward asked of Termarchy) */
              val staysOpen: Boolean = false,
              /** for a shortcut of an app (a long press on its icon) or one pinned to Keyarchy */
              val shortcut: Shortcut? = null) {
        /** the file of the processed icon in the cache, to show the list at once after a restart of the keyboard */
        var iconFile: String? = null
        /** for a person: the newest message from them in the status bar, shown in their entry and opened by it */
        var unread: LauncherNotificationService.Entry? = null
        /** for an event of the calendar: its id and begin ("id|begin"), to dismiss it from the list */
        var eventKey: String? = null
        /** for an event of the calendar: the calendar it is in, shown under it on the home screen */
        var calendar: String? = null
        var label = label ?: originalLabel
            set(value) {
                field = value
                normalizedLabel = normalize(value)
            }
        var normalizedLabel = normalize(this.label)
            private set
        val normalizedOriginal = normalize(originalLabel)
        var rawTags = tags
            set(value) {
                field = value
                this.tags = value.map { normalize(it) }
            }
        var tags = tags.map { normalize(it) }
            private set
        /** the app that opens: Element X or WhatsApp for a person, else the app itself */
        val targetPackage get() = when {
            element != null -> elementPackage
            whatsAppDataId != null -> "com.whatsapp"
            else -> component.packageName
        }
        /** the given name and the original one, and their words, for the search */
        val names get() = listOf(normalizedLabel, normalizedOriginal).distinct()
    }

    companion object {
        private const val TAG = "app-launcher"
        private const val PREF_COUNTS = "app_search_launch_counts"
        /** a web address: optional scheme, a host with a dot and a top level domain of letters (or an IPv4), optional port and path */
        private val URL = Regex("""^(?:https?://)?(?:(?:[\p{L}\d-]+\.)+\p{L}{2,}|\d{1,3}(?:\.\d{1,3}){3}|localhost)(?::\d+)?(?:[/?#]\S*)?$""", RegexOption.IGNORE_CASE)
        private val PHONE = Regex("""^\+?\d[\d\s-]{7,}\d$""")
        /** 7h15, 7h, 07:15 */
        private val TIME = Regex("""^(\d{1,2})(?:h|:)(\d{2})?$""", RegexOption.IGNORE_CASE)
        /** 10 min, 10m, 2 min 30 s, 45 s: minutes give a timer, while hours (7h, 1h30) are always an alarm */
        private val DURATION = Regex("""^(?:(\d+)\s*m(?:in(?:utos?)?)?)?\s*(?:(\d+)\s*s(?:eg(?:undos?)?)?)?$""", RegexOption.IGNORE_CASE)
        private val PRIVATE_SPACE_WORDS = listOf("espaco privado", "privado", "private space", "private")

        /** the private profile, visible only to the default home app */
        private fun privateProfile(context: Context): UserHandle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            return launcherApps.profiles.firstOrNull {
                launcherApps.getLauncherUserInfo(it)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
        }

        /** d, drive or go and a name: directions to a starred contact */
        private val DRIVE = Regex("""^(?:d|drive|go)\s+(.+)$""", RegexOption.IGNORE_CASE)
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        /** c, call, liga or ligar (and à, ao, a, para) and a name: calling a starred contact */
        private val CALL = Regex("""^(?:c|call|liga|ligar)\s+(?:(?:para|à|ao|a)\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        /** sms and a name: the SMS conversation with a starred contact */
        private val SMS = Regex("""^sms\s+(.+)$""", RegexOption.IGNORE_CASE)
        /** s or search and some text: a web search */
        private val SEARCH = Regex("""^(?:s|search)\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val KNOWN_BROWSERS = setOf("org.mozilla.firefox", "com.android.chrome", "com.duckduckgo.mobile.android")
        private const val PREF_LAST_USED = "app_search_last_launch"
        /** when entries were dismissed from the recent ones, "key=millis" lines as [PREF_LAST_USED] */
        private const val PREF_DISMISSED_RECENT = "app_search_dismissed_recent"
        private const val PREF_CHOICES = "app_search_choices"
        /** the events dismissed from the list of the home screen, as "id|begin" (each day of a repeating event apart) */
        private const val PREF_DISMISSED_EVENTS = "app_search_dismissed_events"
        /** the shortcuts offered by the apps that were removed from the list (ids like shortcut://package/id) */
        private const val PREF_REMOVED_SHORTCUTS = "app_search_removed_shortcuts"
        /** the agents of the Termarchy panes by shortcut id, as Termarchy tells them live ([TermarchyAgents]) */
        @Volatile
        var agentStates: Map<String, AgentState> = emptyMap()

        /**
         * When a shortcut last changed: [now] when what it shows ([shown], name and detail) differs from the last load
         * ([before]) or when it is new for an app already shown ([appWasShown]; those of an app just turned on are not),
         * else as before.
         */
        fun shortcutChangedAt(before: Pair<Pair<String, String?>, Long>?, shown: Pair<String, String?>,
                              appWasShown: Boolean, now: Long): Long = when {
            before == null -> if (appWasShown) now else 0L
            before.first != shown -> now
            else -> before.second
        }

        /** the apps whose shortcuts (those of a long press on their icon) are in the list, set in the settings */
        const val PREF_SHORTCUT_APPS = "app_search_shortcut_apps"
        /** whether Termarchy was turned on in [PREF_SHORTCUT_APPS], once: so turning it off afterwards stays */
        private const val PREF_SHORTCUT_APPS_DEFAULTS = "app_search_shortcut_apps_defaults"

        /** the apps turned on in "App shortcuts"; Termarchy is on from the start, its panes being what it offers */
        fun shortcutApps(context: Context): Set<String> {
            val prefs = context.prefs()
            if (!prefs.getBoolean(PREF_SHORTCUT_APPS_DEFAULTS, false)) prefs.edit {
                putStringSet(PREF_SHORTCUT_APPS, prefs.getStringSet(PREF_SHORTCUT_APPS, emptySet())!! + TermarchyForwards.TERMARCHY_PACKAGE)
                putBoolean(PREF_SHORTCUT_APPS_DEFAULTS, true)
            }
            return prefs.getStringSet(PREF_SHORTCUT_APPS, emptySet())!!
        }
        /** the calendars (their ids) whose events are left out of the list, set in the settings ("Calendars") */
        const val PREF_HIDDEN_CALENDARS = "app_search_hidden_calendars"

        /**
         * Whether a shortcut is in the list: one pinned to Keyarchy always, one an app offers only for an app turned on in
         * the settings ("App shortcuts") and not removed from the list.
         */
        fun isShortcutShown(pinned: Boolean, packageName: String, kissId: String, enabledApps: Set<String>,
                            removed: Set<String>): Boolean = pinned || packageName in enabledApps && kissId !in removed

        /**
         * Turns the shortcuts of [packageName] on or off for the list. Off takes them out of the list at once (and of
         * the list saved for the next start); on adds them at the next load, when the launcher mode opens.
         */
        fun setShortcutsShown(context: Context, packageName: String, shown: Boolean) {
            val enabled = shortcutApps(context)
            context.prefs().edit { putStringSet(PREF_SHORTCUT_APPS, if (shown) enabled + packageName else enabled - packageName) }
            if (shown || apps.isEmpty()) return
            apps = apps.filter { it.shortcut == null || it.shortcut.isPinned || it.shortcut.packageName != packageName }
            saveApps(context, apps)
        }
        private const val MAX_SHOWN = 30
        /** the time an entry of the strip takes to go away when dismissed, or to come back */
        private const val SLIDE_MS = 150L
        /** to change when the icon processing changes, so the cached icons are computed again */
        private const val ICON_VERSION = 2
        private const val TAGS_FILE = "etiquetas-kiss.json"
        private const val WHATSAPP_MIME_TYPE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        private const val ELEMENT_FILE = "element.json"
        /** MatriX, the fork of Element X, and Element X from the store */
        private val ELEMENT_PACKAGES = listOf("org.ferrao.matrix", "io.element.android.x")
        /** the installed one of [ELEMENT_PACKAGES], MatriX first, set when the list loads */
        private var elementPackage = ELEMENT_PACKAGES.first()
        private var elementIcon: Drawable? = null

        /**
         * Opens a matrix.to link in Element X. Since Android 12 an https link only opens in the app verified for its
         * domain, so the link becomes a matrix: URI (MSC2312), which Element X handles: matrix:u/person:server?action=chat
         * for a person (asking for the chat), matrix:roomid/… for a room and matrix:r/… for an alias.
         */
        private fun elementIntent(link: String): Intent {
            val target = Uri.decode(link.substringAfter("#/", link))
            val id = target.substringBefore('?')
            // the via parameters say which servers know the room, needed for room ids without a server (room version 12)
            val via = target.substringAfter('?', "").split('&').filter { it.startsWith("via=") }
            val uri = when (id.firstOrNull()) {
                '@' -> "matrix:u/${id.drop(1)}?action=chat"
                '!' -> "matrix:roomid/${id.drop(1)}" + if (via.isEmpty()) "" else via.joinToString("&", "?")
                '#' -> "matrix:r/${id.drop(1)}"
                else -> link
            }
            return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(elementPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** a matrix.to link from what was typed: a link, or an id like @person:server, !room:server or #alias:server */
        private fun matrixLink(text: String): String? {
            val t = text.trim().replace(" ", "")
            return when {
                t.isEmpty() -> null
                t.startsWith("https://") -> t
                t.first() in "@!#" -> "https://matrix.to/#/" + Uri.encode(t, "@!#:?=&")
                else -> "https://matrix.to/#/@" + Uri.encode(t, "@:")
            }
        }
        private const val NAMES_FILE = "nomes.json"
        /** the list of apps of the last load (see [saveApps]) */
        private const val SAVED_APPS_FILE = "lista.json"
        /** the start of the key of a screen of the Settings in the list ("settings|wifi") */
        private const val SETTINGS_KEY = "settings|"
        /** the intermediate shade of the icons: the text color with this opacity */
        private const val MID_ALPHA = 0.45f
        private var apps = emptyList<App>()
            set(value) {
                field = value
                // the suggestions in the search field of the home screen know the names and tags of the list
                LauncherBinaryDictionary.setTexts(value.flatMapTo(hashSetOf()) { listOf(it.label) + it.rawTags })
            }

        /** the icon of an app of the main profile as in the list, or null if the list does not have it (yet) */
        fun iconOf(packageName: String): Drawable? =
            apps.firstOrNull { it.component.packageName == packageName && it.user == Process.myUserHandle() && it.shortcut == null }?.icon

        /** called on the main thread when the list of apps changes, set by the launcher mode */
        private var onAppsChanged: (() -> Unit)? = null
        private val loading = AtomicBoolean(false)

        /**
         * Gets the list ready when the keyboard starts, so the first search after a restart of the keyboard is not
         * empty: the list saved last time at once, then the current one.
         */
        fun preload(context: Context) = reload(context)

        /**
         * Loads the list of apps again in the background (apps installed or removed, names changed in the contacts),
         * first from the file of the last load when there is no list yet; [onAppsChanged] after each.
         */
        private fun reload(context: Context) {
            if (!loading.compareAndSet(false, true)) return
            val main = Handler(Looper.getMainLooper())
            Thread {
                try {
                    if (apps.isEmpty()) {
                        val saved = readSavedApps(context)
                        if (saved.isNotEmpty()) main.post {
                            if (apps.isEmpty()) {
                                apps = saved
                                onAppsChanged?.invoke()
                            }
                        }
                    }
                    val loaded = loadApps(context)
                    saveApps(context, loaded)
                    main.post {
                        apps = loaded
                        onAppsChanged?.invoke()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "can't load the apps", e)
                } finally {
                    loading.set(false)
                }
            }.start()
        }

        /** the list of apps, without the names and tags (read anew) and with the cache files of the icons */
        private fun saveApps(context: Context, list: List<App>) {
            val userManager = context.getSystemService(UserManager::class.java)
            val array = JSONArray()
            for (app in list) {
                val icon = app.iconFile ?: continue
                array.put(JSONObject().apply {
                    put("key", app.key)
                    put("kissId", app.kissId)
                    put("label", app.originalLabel)
                    put("package", app.component.packageName)
                    put("class", app.component.className)
                    put("user", userManager.getSerialNumberForUser(app.user))
                    put("icon", icon)
                    app.whatsAppDataId?.let { put("whatsApp", it) }
                    app.shortcut?.let {
                        put("shortcut", it.id)
                        put("shortcutPackage", it.packageName)
                        put("pinned", it.isPinned)
                        put("offered", it.isOffered)
                        put("changedAt", it.changedAt)
                        it.detail?.let { detail -> put("detail", detail) }
                    }
                })
            }
            try {
                File(context.filesDir, "launcher").mkdirs()
                File(context.filesDir, "launcher/$SAVED_APPS_FILE").writeText(array.toString())
            } catch (e: Exception) {
                Log.w(TAG, "can't save the apps", e)
            }
        }

        /** the shortcuts in the list saved by [saveApps]: key to what they showed (name, detail) and when it changed */
        private fun savedShortcuts(context: Context): Map<String, Pair<Pair<String, String?>, Long>> = try {
            val file = File(context.filesDir, "launcher/$SAVED_APPS_FILE")
            if (!file.isFile) emptyMap() else {
                val array = JSONArray(file.readText())
                (0 until array.length()).map { array.getJSONObject(it) }.filter { it.has("shortcut") }.associate { o ->
                    o.getString("key") to ((o.getString("label") to o.optString("detail").ifEmpty { null }) to o.optLong("changedAt"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't read the saved shortcuts", e)
            emptyMap()
        }

        /** the list saved by [saveApps], with the current names, tags and Element links; empty if there is none */
        private fun readSavedApps(context: Context): List<App> {
            val file = File(context.filesDir, "launcher/$SAVED_APPS_FILE")
            if (!file.isFile) return emptyList()
            val userManager = context.getSystemService(UserManager::class.java)
            val tags = readStringMap(context, TAGS_FILE).mapValues { (_, v) -> v.split(' ').filter { it.isNotBlank() } }
            val names = readStringMap(context, NAMES_FILE)
            val elementLinks = readStringMap(context, ELEMENT_FILE)
            val list = mutableListOf<App>()
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val user = userManager.getUserForSerialNumber(o.getLong("user")) ?: continue
                    val iconFile = o.getString("icon")
                    val icon = synchronized(iconMemory) { iconMemory[iconFile] }
                        ?: BitmapFactory.decodeFile(File(iconCacheDir(context), iconFile).path)
                            ?.let { BitmapDrawable(context.resources, it) }
                            ?.also { synchronized(iconMemory) { iconMemory[iconFile] = it } }
                        ?: continue
                    val kissId = o.getString("kissId")
                    val whatsApp = if (o.has("whatsApp")) o.getLong("whatsApp") else null
                    val shortcut = if (o.has("shortcut")) Shortcut(o.getString("shortcutPackage"), o.getString("shortcut"),
                        o.getBoolean("pinned"), o.getBoolean("offered"), o.optLong("changedAt"),
                        o.optString("detail").ifEmpty { null }) else null
                    list.add(App(o.getString("key"), kissId, o.getString("label"),
                        ComponentName(o.getString("package"), o.getString("class")), user, icon, names[kissId],
                        tags[kissId].orEmpty(), whatsApp, if (whatsApp != null) elementLinks[kissId] else null,
                        shortcut = shortcut).also { it.iconFile = iconFile })
                }
            } catch (e: Exception) {
                Log.w(TAG, "can't read the saved apps", e)
                return emptyList()
            }
            val element = ELEMENT_PACKAGES.firstNotNullOfOrNull { p -> list.firstOrNull { it.component.packageName == p && it.shortcut == null } }
            element?.let { elementPackage = it.component.packageName }
            elementIcon = element?.icon
            return list.sortedBy { it.normalizedLabel }
        }

        private fun loadApps(context: Context): List<App> {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val userManager = context.getSystemService(UserManager::class.java)
            val tags = readStringMap(context, TAGS_FILE).mapValues { (_, v) -> v.split(' ').filter { it.isNotBlank() } }
            val names = readStringMap(context, NAMES_FILE)
            val usedIconFiles = mutableSetOf<String>()
            // the apps of a locked profile (private space, paused work profile) are kept: opening one unlocks it first
            val launchable = launcherApps.profiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                launcherApps.getActivityList(null, user).map {
                    // KISS writes app://package/class, with /serial for other profiles
                    val kissId = "app://${it.componentName.packageName}/${it.componentName.className}" +
                        if (user == Process.myUserHandle()) "" else "/$serial"
                    val (icon, iconFile) = cachedIcon(context, it, serial, usedIconFiles)
                    App("${it.componentName.flattenToShortString()}|$serial", kissId, it.label.toString(), it.componentName, user,
                        icon, names[kissId], tags[kissId].orEmpty()).also { app -> app.iconFile = iconFile }
                }
            }
            val shortcuts = appShortcuts(context, launchable, names, tags, usedIconFiles)
            // icons of removed apps, or of older versions of the apps, are not needed any more
            iconCacheDir(context).listFiles()?.forEach { if (it.name !in usedIconFiles) it.delete() }
            synchronized(iconMemory) { iconMemory.keys.retainAll(usedIconFiles) }
            val chats = whatsAppChats(context, launchable, names, tags)
            // the shortcuts of conversations (WhatsApp, Messages) with a starred contact repeat their entry
            val people = chats.map { it.normalizedOriginal }.toSet()
            return (launchable + chats + shortcuts.filter { it.normalizedOriginal !in people }).sortedBy { it.normalizedLabel }
        }

        /**
         * The shortcuts the apps offer (those of a long press on their icon) and the ones pinned to Keyarchy ("add to home
         * screen" in an app, see [PinShortcutActivity]), with their own icon, without the ones removed from the list.
         * Android only gives them to the home app, so there are none while Keyarchy is not the home app; none either for a
         * locked profile.
         */
        private fun appShortcuts(context: Context, launchable: List<App>, names: Map<String, String>,
                                 tags: Map<String, List<String>>, usedIconFiles: MutableSet<String>): List<App> {
            val previous = savedShortcuts(context)
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val userManager = context.getSystemService(UserManager::class.java)
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val removed = context.prefs().getStringSet(PREF_REMOVED_SHORTCUTS, emptySet())!!
            val enabledApps = shortcutApps(context)
            // apps whose shortcuts were in the last load: a new one of theirs is a change, the shortcuts of an app just
            // turned on are not
            val previousApps = previous.keys.map { it.split('|')[1] }.toSet()
            return launcherApps.profiles.filter { !userManager.isQuietModeEnabled(it) }.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                val shortcuts = try { launcherApps.getShortcuts(query, user) } catch (e: Exception) {
                    Log.w(TAG, "can't read the shortcuts", e)
                    null
                }
                shortcuts.orEmpty().filter { it.isEnabled }.mapNotNull { info ->
                    val app = launchable.firstOrNull { it.component.packageName == info.`package` && it.user == user }
                        ?: return@mapNotNull null
                    val label = (info.shortLabel ?: info.longLabel)?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val kissId = "shortcut://${info.`package`}/${info.id}" + if (user == Process.myUserHandle()) "" else "/$serial"
                    if (!isShortcutShown(info.isPinned, info.`package`, kissId, enabledApps, removed)) return@mapNotNull null
                    val iconKey = "shortcut|${info.`package`}|${info.id}|$serial|${info.lastChangedTimestamp}"
                    val (icon, iconFile) = cachedIcon(context, iconKey, usedIconFiles) {
                        launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
                            ?.let { processedIcon(context, it, user, info.id) } ?: app.icon
                    }
                    val detail = info.longLabel?.toString()?.takeIf { it.isNotBlank() && it != label }
                    val key = "shortcut|${info.`package`}|${info.id}|$serial"
                    val changedAt = shortcutChangedAt(previous[key], label to detail, info.`package` in previousApps,
                        System.currentTimeMillis())
                    App(key, kissId, label, app.component, user, icon,
                        names[kissId], tags[kissId].orEmpty(), shortcut = Shortcut(info.`package`, info.id, info.isPinned,
                            info.isDynamic || info.isDeclaredInManifest, changedAt, detail)
                    ).also { it.iconFile = iconFile }
                }
            }
        }

        /**
         * The WhatsApp chats of the starred contacts, from the row WhatsApp adds to each contact it knows: opening that
         * row goes straight to the chat. Shown with the WhatsApp icon.
         */
        private fun whatsAppChats(context: Context, launchable: List<App>, names: Map<String, String>,
                                  tags: Map<String, List<String>>): List<App> {
            val whatsApp = launchable.firstOrNull { it.component.packageName == "com.whatsapp" && it.user == Process.myUserHandle() }
                ?: return emptyList()
            val chats = mutableListOf<App>()
            val elementLinks = readStringMap(context, ELEMENT_FILE)
            val element = ELEMENT_PACKAGES.firstNotNullOfOrNull { p -> launchable.firstOrNull { it.component.packageName == p } }
            element?.let { elementPackage = it.component.packageName }
            elementIcon = element?.icon
            try {
                context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME),
                    ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.STARRED + " = 1",
                    arrayOf(WHATSAPP_MIME_TYPE), null
                )?.use { cursor ->
                    val seen = mutableSetOf<Long>()
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(2) ?: continue
                        if (!seen.add(cursor.getLong(1))) continue // one chat per contact
                        val kissId = "whatsapp://$name"
                        chats.add(App("whatsapp|$name", kissId, name, whatsApp.component, whatsApp.user, whatsApp.icon,
                            names[kissId], tags[kissId].orEmpty(), cursor.getLong(0), elementLinks[kissId])
                            .also { it.iconFile = whatsApp.iconFile })
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission to read contacts", e)
            }
            return chats
        }

        /**
         * The tags (files/launcher/etiquetas-kiss.json, in the format of the "__tags" object of the KISS settings export:
         * app id to tags separated by spaces) and the names given to the apps (files/launcher/nomes.json, app id to name).
         */
        private fun readStringMap(context: Context, name: String): Map<String, String> = try {
            val file = File(context.filesDir, "launcher/$name")
            if (!file.isFile) emptyMap() else {
                val json = JSONObject(file.readText())
                json.keys().asSequence().associateWith { json.getString(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't read $name", e)
            emptyMap()
        }

        private fun writeStringMap(context: Context, name: String, map: Map<String, String>) {
            try {
                val dir = File(context.filesDir, "launcher").apply { mkdirs() }
                File(dir, name).writeText(JSONObject(map).toString(1))
            } catch (e: Exception) {
                Log.w(TAG, "can't write $name", e)
            }
        }

        /** processed icons by cache file name, kept while the keyboard process lives */
        private val iconMemory = HashMap<String, Drawable>()

        private fun iconCacheDir(context: Context) = File(context.filesDir, "launcher/icones-cache").apply { mkdirs() }

        /**
         * The processed icon of an app, from memory or from a PNG file kept between sessions, computed only when missing.
         * The key changes when the app is updated (time of its APK), when the text color changes (theme) or when the
         * processing changes ([ICON_VERSION]), so only those icons are computed again.
         */
        private fun cachedIcon(context: Context, info: LauncherActivityInfo, serial: Long, used: MutableSet<String>): Pair<Drawable, String> {
            val apkTime = try { File(info.applicationInfo.sourceDir).lastModified() } catch (e: Exception) { 0L }
            return cachedIcon(context, "${info.componentName.flattenToString()}|$serial|$apkTime", used) { appIcon(context, info) }
        }

        /** the icon cached under [id] (with the text color and [ICON_VERSION]), made by [make] when missing; and its file */
        private fun cachedIcon(context: Context, id: String, used: MutableSet<String>, make: () -> Drawable): Pair<Drawable, String> {
            val color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
            val key = "$id|$color|$ICON_VERSION"
            val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".png"
            used.add(name)
            synchronized(iconMemory) { iconMemory[name] }?.let { return it to name }
            val file = File(iconCacheDir(context), name)
            val drawable = BitmapFactory.decodeFile(file.path)?.let { BitmapDrawable(context.resources, it) } ?: run {
                val icon = make()
                val size = 48.dpToPx(context.resources)
                val bitmap = icon.toBitmap(size, size)
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "can't save the icon $id", e)
                }
                BitmapDrawable(context.resources, bitmap)
            }
            synchronized(iconMemory) { iconMemory[name] = drawable }
            return drawable to name
        }

        /**
         * The icon in the text color on a transparent background: the themed (monochrome) layer when the app has one,
         * otherwise the foreground layer of adaptive icons or the whole icon, reduced by [toTwoLevels] to the text color
         * and one lighter shade of it. Badged for other profiles.
         */
        private fun appIcon(context: Context, info: LauncherActivityInfo): Drawable =
            processedIcon(context, info.getIcon(0), info.user, info.componentName.packageName)

        /** [icon] processed as described in [appIcon], badged for other profiles than the main one */
        private fun processedIcon(context: Context, icon: Drawable, user: UserHandle, dumpName: String): Drawable {
            val size = 48.dpToPx(context.resources)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawFilter = PaintFlagsDrawFilter(0, Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            var original: Bitmap? = null
            val textColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && icon is AdaptiveIconDrawable) {
                // the adaptive layers are 108dp for a 72dp icon
                val layerSize = (size * (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())).toInt()
                val offset = (layerSize - size) / 2
                var monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) icon.monochrome else null
                monochrome?.mutate()?.apply { setTint(textColor) }?.let { mono ->
                    mono.setBounds(-offset, -offset, size + offset, size + offset)
                    mono.draw(canvas)
                    // a themed layer covering almost all the icon has no drawing (MyGoFit): use the foreground instead
                    if (opaqueFraction(bitmap) > 0.85) {
                        bitmap.eraseColor(Color.TRANSPARENT)
                        monochrome = null
                    }
                }
                if (monochrome == null) icon.foreground?.let { layer ->
                    // without a themed layer, the foreground layer, without the background layer
                    layer.setBounds(-offset, -offset, size + offset, size + offset)
                    layer.draw(canvas)
                }
                original = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (monochrome == null) toTwoLevels(bitmap, textColor)
            } else {
                val w = icon.intrinsicWidth
                val h = icon.intrinsicHeight
                val aspect = if (w > 0 && h > 0) w / h.toFloat() else 1f
                val (bw, bh) = if (h > w) (size * aspect).toInt() to size else size to (size / aspect).toInt()
                icon.bounds = Rect((size - bw) / 2, (size - bh) / 2, (size + bw) / 2, (size + bh) / 2)
                icon.draw(canvas)
                original = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                toTwoLevels(bitmap, textColor)
            }
            dumpIcon(context, dumpName, original, bitmap)
            val drawable = BitmapDrawable(context.resources, bitmap)
            return if (user == Process.myUserHandle()) drawable
            else context.packageManager.getUserBadgedIcon(drawable, user)
        }

        /** for diagnosis: each icon before and after the processing, only if the folder files/launcher/icones exists */
        private fun dumpIcon(context: Context, name: String, original: Bitmap?, processed: Bitmap) {
            val dir = File(context.filesDir, "launcher/icones").takeIf { it.isDirectory } ?: return
            try {
                original?.let { b -> File(dir, "${name}_antes.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                File(dir, "${name}_depois.png").outputStream().use { processed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                Log.w(TAG, "can't save the icon of $name", e)
            }
        }

        /**
         * Reduces a colored icon to the text color and one intermediate shade of it (the text color at [MID_ALPHA]):
         * - the color on most of the outline of the shape is a background, and becomes transparent in the whole icon, if
         *   it is a filled shape behind the rest (at least 35 % of the icon and half of its box), like the white square of
         *   MB WAY or the orange gradient of BK; colors close to each other count as one, so gradients too;
         * - the other colors get the full color or the intermediate shade, split in two groups (Otsu): by how far they are
         *   from the background color if there was one, else by lightness (light colors full, dark ones intermediate).
         */
        private fun toTwoLevels(bitmap: Bitmap, textColor: Int) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            fun opaque(i: Int) = Color.alpha(pixels[i]) > 200
            fun distance(c: Int, r: Double, g: Double, b: Double): Double {
                val dr = Color.red(c) - r
                val dg = Color.green(c) - g
                val db = Color.blue(c) - b
                return Math.sqrt(dr * dr + dg * dg + db * db)
            }
            fun distance(c: Int, d: Int) = distance(c, Color.red(d).toDouble(), Color.green(d).toDouble(), Color.blue(d).toDouble())
            // the first opaque pixel from each side, on every row and column
            val outline = mutableSetOf<Int>()
            for (y in 0 until h) {
                (0 until w).firstOrNull { opaque(y * w + it) }?.let { outline.add(y * w + it) }
                (w - 1 downTo 0).firstOrNull { opaque(y * w + it) }?.let { outline.add(y * w + it) }
            }
            for (x in 0 until w) {
                (0 until h).firstOrNull { opaque(it * w + x) }?.let { outline.add(it * w + x) }
                (h - 1 downTo 0).firstOrNull { opaque(it * w + x) }?.let { outline.add(it * w + x) }
            }
            val visible = pixels.indices.filter { Color.alpha(pixels[it]) >= 160 }
            val removed = BooleanArray(w * h) { Color.alpha(pixels[it]) < 160 }
            var background: DoubleArray? = null
            if (outline.isNotEmpty()) {
                // the outline color with most outline colors near it, and the outline colors near that one
                val colors = outline.map { pixels[it] }
                val sample = colors.filterIndexed { k, _ -> k % maxOf(1, colors.size / 200) == 0 }
                val center = sample.maxBy { c -> sample.count { distance(it, c) <= 60 } }
                val cluster = colors.filter { distance(it, center) <= 90 }
                if (cluster.size >= colors.size * 0.6) {
                    val r = cluster.sumOf { Color.red(it) }.toDouble() / cluster.size
                    val g = cluster.sumOf { Color.green(it) }.toDouble() / cluster.size
                    val b = cluster.sumOf { Color.blue(it) }.toDouble() / cluster.size
                    val spread = cluster.map { distance(it, r, g, b) }.sorted()
                    val threshold = minOf(150.0, spread[(spread.size * 0.98).toInt().coerceAtMost(spread.size - 1)] + 60)
                    val area = visible.filter { distance(pixels[it], r, g, b) <= threshold }
                    val box = if (area.isEmpty()) 1 else
                        (area.maxOf { it % w } - area.minOf { it % w } + 1) * (area.maxOf { it / w } - area.minOf { it / w } + 1)
                    if (area.size >= visible.size * 0.35 && area.size >= box * 0.5) {
                        val candidate = removed.copyOf()
                        area.forEach { candidate[it] = true }
                        // the outline itself, 2 pixels deep, where the edge blends with what is outside
                        var ring = outline.toSet()
                        repeat(2) { ring = ring + ring.flatMap { i -> listOf(i - 1, i + 1, i - w, i + w) }.filter { it in 0 until w * h } }
                        ring.forEach { candidate[it] = true }
                        if (candidate.count { !it } >= w * h * 0.03) {
                            candidate.copyInto(removed)
                            background = doubleArrayOf(r, g, b)
                        }
                    }
                }
            }
            val left = pixels.indices.filter { !removed[it] }
            val bg = background
            fun key(c: Int) = minOf(255.0, if (bg != null) distance(c, bg[0], bg[1], bg[2])
                else 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c))
            val values = left.map { key(pixels[it]) }
            val split = if (values.isEmpty() || values.max() - values.min() < 60) null else otsu(values)
            for (i in pixels.indices) {
                pixels[i] = if (removed[i]) Color.TRANSPARENT else {
                    val level = if (split == null || key(pixels[i]) > split) 1f else MID_ALPHA
                    (textColor and 0xFFFFFF) or ((Color.alpha(pixels[i]) * level).toInt() shl 24)
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        /** the part of the bitmap that is opaque */
        private fun opaqueFraction(bitmap: Bitmap): Double {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { Color.alpha(it) > 200 }.toDouble() / pixels.size
        }

        /** the value splitting the values in two groups with the largest variance between them (Otsu's method) */
        private fun otsu(values: List<Double>): Int {
            val histogram = IntArray(256)
            values.forEach { histogram[it.toInt().coerceIn(0, 255)]++ }
            val total = values.size
            val sumAll = (0..255).sumOf { it.toDouble() * histogram[it] }
            var sumBelow = 0.0
            var countBelow = 0
            var best = 0.0
            var split = 127
            for (t in 0..255) {
                countBelow += histogram[t]
                if (countBelow == 0) continue
                val countAbove = total - countBelow
                if (countAbove == 0) break
                sumBelow += t.toDouble() * histogram[t]
                val meanBelow = sumBelow / countBelow
                val meanAbove = (sumAll - sumBelow) / countAbove
                val variance = countBelow.toDouble() * countAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
                if (variance > best) { best = variance; split = t }
            }
            return split
        }

        /** search text to the key of the result chosen for it (the text has no '=', keys may) */
        private fun readChoices(context: Context): MutableMap<String, String> =
            context.prefs().getString(PREF_CHOICES, "")!!.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i < 1) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap(mutableMapOf())

        /**
         * Moves the people with a message in [notifications] forward, as if their chat was opened when it arrived;
         * true if one moved. Needs the list of apps (loaded when the launcher mode first opens).
         */
        fun bump(context: Context, notifications: List<LauncherNotificationService.Entry>): Boolean {
            val lastUsed = readTimes(context, PREF_LAST_USED)
            var moved = false
            for (notification in notifications) {
                val person = personOf(notification) ?: continue
                if ((lastUsed[person.key] ?: 0L) >= notification.time) continue
                lastUsed[person.key] = notification.time
                moved = true
            }
            if (moved) saveTimes(context, PREF_LAST_USED, lastUsed)
            return moved
        }

        /** whether [packageName] is a chat app whose people are in the list: Element X (or MatriX) or WhatsApp */
        fun isChatApp(packageName: String?) = packageName in ELEMENT_PACKAGES || packageName == "com.whatsapp"

        /**
         * Moves the person a chat open in a chat app is with forward, as if just opened from the list: [title] is the
         * name at the top of the chat ("Paula Costa", MatriX's "misty 😎"). Nothing for a group or someone not starred.
         */
        /** the person whose chat the keyboard last opened in (its key), to leave it out of the alternatives there */
        private var openChat: String? = null

        fun bumpChat(context: Context, title: String) {
            personNamed(titleName(title), apps.filter { it.whatsAppDataId != null }, { it.names }, { it.tags })
                ?.let { bumpOpenChat(context, it) }
        }

        /** the same for a chat of MatriX, which tells the keyboard its room ([ChatRoom]): the person linked to that room */
        fun bumpRoom(context: Context, roomId: String) {
            val room = ROOM_ID.find(roomId)?.value ?: return
            apps.firstOrNull { it.whatsAppDataId != null && roomOf(it) == room }?.let { bumpOpenChat(context, it) }
        }

        private fun bumpOpenChat(context: Context, person: App) {
            openChat = person.key
            val lastUsed = readTimes(context, PREF_LAST_USED)
            lastUsed[person.key] = System.currentTimeMillis()
            saveTimes(context, PREF_LAST_USED, lastUsed)
        }

        /** the id of the Element room of a person's link, without its server */
        private fun roomOf(person: App) = person.element?.let { ROOM_ID.find(it)?.value }

        /**
         * The person in the list (a starred contact) a notification of WhatsApp or Element X comes from, if it is a
         * one-to-one chat: by the Element room of their link, or by their name as the title ("Paula Costa",
         * "Paula Costa (2 mensagens)", Element's "misty: misty").
         */
        private fun personOf(notification: LauncherNotificationService.Entry): App? {
            if (notification.isGroup || notification.packageName !in ELEMENT_PACKAGES && notification.packageName != "com.whatsapp")
                return null
            val people = apps.filter { it.whatsAppDataId != null }
            if (notification.packageName in ELEMENT_PACKAGES) people.firstOrNull { person ->
                roomOf(person)?.let { notification.key.contains(it) } == true
            }?.let { return it }
            return personNamed(titleName(notification.title), people, { it.names }, { it.tags })
        }

        /**
         * The name a chat or notification title gives: before ": ", without a message count, emojis or punctuation
         * ("Paula Costa (2 mensagens)" → "paula costa", "misty 😎" → "misty")
         */
        internal fun titleName(title: String) = normalize(title.substringBefore(": ").replace(MESSAGE_COUNT, ""))
            .replace(NOT_A_NAME, "").replace(SPACES, " ").trim()

        /** of [people], the one [name] is: by one of their [names], else by a tag of theirs no one else has */
        internal fun <P> personNamed(name: String, people: List<P>, names: (P) -> List<String>, tags: (P) -> List<String>): P? {
            if (name.isEmpty()) return null
            return people.firstOrNull { name in names(it) } ?: people.filter { name in tags(it) }.singleOrNull()
        }
        private val SPACES = Regex("""\s+""")
        /** the id of an Element room in a matrix.to link, without its server */
        private val ROOM_ID = Regex("""![^?/&:%]+""")
        private val MESSAGE_COUNT = Regex("""\s*\(\d+[^)]*\)\s*$""")
        private val NOT_A_NAME = Regex("""[^\p{L}\p{N} ]""")

        /** times by entry key, as kept in [pref] ("key=millis" lines): when each was last used, or dismissed */
        private fun readTimes(context: Context, pref: String): MutableMap<String, Long> =
            context.prefs().getString(pref, "")!!.lines().mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i < 1) null else line.substring(0, i) to (line.substring(i + 1).toLongOrNull() ?: 0L)
            }.toMap(mutableMapOf())

        private fun saveTimes(context: Context, pref: String, times: Map<String, Long>) =
            context.prefs().edit { putString(pref, times.entries.joinToString("\n") { "${it.key}=${it.value}" }) }

        /**
         * How recent an entry is for the order of the list: when last opened from here ([lastUsed]) or, a [shortcut],
         * when it last changed (an active Termarchy agent as recent as [now]); 0 while neither is after it was dismissed from
         * the recent ones ([dismissedAt]).
         */
        fun recency(lastUsed: Long, shortcut: Shortcut?, dismissedAt: Long, now: Long): Long =
            if (maxOf(lastUsed, shortcut?.since ?: 0L) <= dismissedAt) 0L
            else maxOf(lastUsed, shortcut?.changedTime(now) ?: 0L)

        private fun readCounts(context: Context): MutableMap<String, Int> =
            context.prefs().getString(PREF_COUNTS, "")!!.lines().mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i < 1) null else line.substring(0, i) to (line.substring(i + 1).toIntOrNull() ?: 0)
            }.toMap(mutableMapOf())

        /**
         * Apps whose name starts with the text first, then those with a word starting with it, then those with a KISS tag
         * starting with it, then the rest whose name contains it.
         */
        /** the order of use: the most recently launched from here first, then the most often launched */
        private fun filter(apps: List<App>, query: String, counts: Map<String, Int>, lastUsed: Map<String, Long> = emptyMap(),
                           dismissed: Map<String, Long> = emptyMap()): List<App> {
            val q = normalize(query.trim())
            // a shortcut counts as used when what it shows last changed too (a Termarchy session that just ended comes forward)
            val now = System.currentTimeMillis()
            val byUse = compareByDescending<App> { recency(lastUsed[it.key] ?: 0L, it.shortcut, dismissed[it.key] ?: 0L, now) }
                .thenByDescending { counts[it.key] ?: 0 }
            if (q.isEmpty()) return apps.sortedWith(byUse)
            fun rank(app: App) = when {
                app.names.any { it.startsWith(q) } -> 0
                app.names.any { name -> name.split(' ', '-', '.').any { it.startsWith(q) } } -> 1
                app.tags.any { it.startsWith(q) } -> 2
                app.names.any { it.contains(q) } -> 3
                else -> 4
            }
            return apps.map { it to rank(it) }.filter { it.second < 4 }
                .sortedWith(compareBy<Pair<App, Int>> { it.second }.thenComparator { a, b -> byUse.compare(a.first, b.first) })
                .map { it.first }
        }

        /**
         * The app whose name, or a word of it, is most similar to the given name, for names typed or spoken differently
         * from the list, e.g. "calendário" for "Calendar"; null if none is similar enough.
         */
        private fun closest(apps: List<App>, name: String, counts: Map<String, Int>): App? {
            val q = normalize(name.trim())
            if (q.length < 3) return null
            fun similarity(word: String): Double = when {
                word.length >= 4 && (q.startsWith(word) || word.startsWith(q)) -> 0.9
                else -> 1.0 - levenshtein(q, word).toDouble() / maxOf(q.length, word.length)
            }
            return apps.map { app -> app to app.names.flatMap { it.split(' ', '-', '.') + it }.maxOf { similarity(it) } }
                .filter { it.second >= 0.7 }
                .maxWithOrNull(compareBy<Pair<App, Double>> { it.second }.thenBy { counts[it.first.key] ?: 0 })?.first
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1)
                current[0] = i + 1
                for (j in b.indices)
                    current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                previous = current
            }
            return previous[b.length]
        }

        /** the number as wa.me wants it: digits only, with the country code (Portugal if missing) */
        private fun internationalDigits(number: String): String {
            val digits = number.filter { it.isDigit() }.removePrefix("00")
            return if (digits.length == 9) "351$digits" else digits
        }

        private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }
}
