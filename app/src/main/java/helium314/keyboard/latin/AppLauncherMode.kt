// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

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
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.Manifest
import android.database.ContentObserver
import java.util.Calendar
import java.util.TimeZone
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.ColorUtils
import android.view.Gravity
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
        LauncherNotificationService.onChange = { lastUsed = readLastUsed(latinIME); show() }
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
        lastUsed = readLastUsed(latinIME)
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
            // a single word opens the first app found, a sentence (or a word without apps) is a command
            codePoint == Constants.CODE_ENTER -> {
                val text = query.toString().trim()
                if (!text.contains(' ') && results.isNotEmpty()) launch(results.first())
                else if (text.isNotEmpty()) runSentence(text)
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
        if (bump(latinIME, LauncherNotificationService.entries())) lastUsed = readLastUsed(latinIME)
        val ranked = chosenFirst(withNotifications(alternativesFirst(filter(apps, query.toString(), counts, lastUsed)),
            normalize(query.toString().trim())))
        results = withEvents(listOfNotNull(urlEntry(), privateSpaceEntry()) + patternEntries() + withPlayers(ranked))
        // on the home screen of Homekey the search is its own text field and the results a vertical list above it; the
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
     * Takes a shortcut out of the list: one pinned to Homekey is unpinned; one the app offers is remembered as removed
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

    /** the keyboard is in the text field of the home screen of Homekey, where typing goes to the field and not to the mode */
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

    /** for the home screen: the text of its field is the search text */
    fun setHomeQuery(text: String) {
        if (!isActive || editing != null) return
        query.setLength(0)
        query.append(text)
        message = null
        actionsFor = null
        show()
    }

    /** for the home screen: Enter in its field opens the first result, or runs the sentence as a command */
    fun openFirst() {
        val text = query.toString().trim()
        if (!text.contains(' ') && results.isNotEmpty()) launch(results.first())
        else if (text.isNotEmpty()) runSentence(text)
    }

    /** for the list of the home screen: open the result */
    fun open(app: App) = launch(app)

    /** for the list of the home screen: the actions of a result (name, tags, WhatsApp or Element) in the strip */
    fun showActions(app: App) {
        if (app.notification != null) return
        actionsFor = app
        show()
    }

    /**
     * For the list of the home screen: dismisses a notification, or the message shown in the entry of a person, or an
     * event (from the list only, it stays in the calendar).
     */
    fun dismiss(app: App) {
        (app.notification ?: app.unread)?.let { LauncherNotificationService.dismiss(it.key) }
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

    /** the icon shown for a result (Element for a person contacted through it) */
    fun iconOf(app: App): Drawable? = app.element?.let { elementIcon } ?: app.icon

    private fun createRow(strip: SuggestionStripView): View {
        val context = strip.context
        val res = context.resources
        val color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val queryView = TextView(context)
        queryView.text = query
        queryView.setTextColor(color)
        queryView.textSize = 16f
        queryView.setPadding(4.dpToPx(res), 0, 12.dpToPx(res), 0)
        row.addView(queryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER_VERTICAL })
        queryView.gravity = Gravity.CENTER_VERTICAL

        // the typed text is shown on its own only when nothing matches it (the matches show it in their names)
        if (query.isNotBlank() && results.isNotEmpty()) queryView.visibility = View.GONE
        message?.let {
            queryView.text = it
            queryView.setTextColor(color)
            queryView.visibility = View.VISIBLE
        }
        for (app in if (message != null) emptyList() else results.take(MAX_SHOWN)) {
            val chip = LinearLayout(context)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPadding(8.dpToPx(res), 0, 8.dpToPx(res), 0)
            chip.setOnClickListener { launch(app) }
            val icon = ImageView(context)
            icon.setImageDrawable(iconOf(app))
            chip.addView(icon, LinearLayout.LayoutParams(28.dpToPx(res), 28.dpToPx(res)))
            val label = TextView(context)
            label.text = highlighted(app.label, color)
            app.unread?.let { label.append(": " + it.text.replace('\n', ' ').take(40)) }
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
            stop()
            it()
            return
        }
        app.intent?.let {
            // an SMS conversation: tap its message box once open, so the keyboard stays
            if (app.key.startsWith("sms|")) ScreenAgentService.focusMessageFieldSoon(app.component.packageName)
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
                ScreenAgentService.focusMessageFieldSoon(if (app.element != null) ELEMENT_PACKAGE else "com.whatsapp")
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
     * On the home screen, [list] with the events of today in the calendars shown in the calendar app that are not over
     * yet, as "10:00 title" (all-day ones by the title alone); with search text, those with it in the title. Each one
     * goes as many places from the front as there are whole hours until it begins (0 once begun; for an all-day one,
     * until the day ends), so it comes forward as it gets near. Opening one shows it in the calendar app. The list is
     * shown again when that changes, or when an event ends.
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
        // all-day events begin and end at midnight in UTC
        val todayUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val start = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 1)
        class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)
        val events = mutableListOf<Event>()
        try {
            CalendarContract.Instances.query(latinIME.contentResolver, arrayOf(CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.VISIBLE,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS), start, day.timeInMillis)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0 || c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val event = Event(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1)
                    val current = if (event.allDay) event.begin <= todayUtc && event.end > todayUtc else event.end > now
                    if (current && event.title.isNotBlank()) events.add(event)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to read the calendar", e)
        }
        val hour = 3_600_000L
        val endOfDay = day.timeInMillis
        // the time until which an event keeps its place: the next whole hour before it begins (or the end of the day)
        fun approach(event: Event) = if (event.allDay) endOfDay else event.begin
        val dismissed = dismissedEvents()
        val shown = events.filter { (q.isEmpty() || normalize(it.title).contains(q)) && "${it.id}|${it.begin}" !in dismissed }
        val changes = events.filter { !it.allDay }.map { it.end } +
            shown.map { approach(it) - now }.filter { it > 0 }.map { now + it % hour }
        changes.minOrNull()?.let { mainHandler.postDelayed(refresh, maxOf(it - now, 1000)) }
        val time = android.text.format.DateFormat.getTimeFormat(latinIME)
        val result = list.toMutableList()
        var last = -1
        for (event in shown.sortedWith(compareBy({ maxOf(approach(it) - now, 0) / hour }, { it.begin }))) {
            val label = if (event.allDay) event.title else time.format(event.begin) + " " + event.title
            val entry = entry("event", label, Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)) ?: continue
            entry.eventKey = "${event.id}|${event.begin}"
            // the reminder of the calendar app for the same event would say the same (its title wrapped in direction marks)
            fun plain(s: String) = s.replace(Regex("\\p{Cf}"), "").trim()
            result.removeAll { it.notification?.packageName == entry.component.packageName && plain(it.notification.title) == plain(event.title) }
            last = minOf(maxOf((maxOf(approach(event) - now, 0) / hour).toInt(), last + 1), result.size)
            result.add(last, entry)
        }
        return result
    }

    /** "s sílvia", "sms sílvia": the SMS conversation with the matching starred contacts, at their default number */
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
     * Without search text, what belongs to the app being typed in goes last: that app is already open, so the most
     * recent alternatives come first, like Alt-Tab.
     */
    private fun alternativesFirst(list: List<App>): List<App> {
        if (query.isNotBlank()) return list
        val current = latinIME.currentInputEditorInfo?.packageName ?: return list
        val (here, others) = list.partition { it.targetPackage == current }
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
        if (q.isEmpty()) return notifications + appResults
        val (typed, rest) = appResults.partition { app -> app.names.any { it.startsWith(q) } }
        return typed + notifications + rest
    }

    /**
     * "privado", "espaço", "private": locks or unlocks the private space (the system asks for the credentials). Only
     * possible as the default home app, which may see the private profile (ACCESS_HIDDEN_PROFILES).
     */
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
        latinIME.prefs().edit {
            putString(PREF_COUNTS, counts.entries.joinToString("\n") { "${it.key}=${it.value}" })
            putString(PREF_LAST_USED, lastUsed.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    /** an app with the name and tags given in the launcher mode (or in KISS), [kissId] identifying it as KISS does */
    /** a shortcut of an app: what starts or removes it; offered = by the app itself (not only pinned) */
    class Shortcut(val packageName: String, val id: String, val isPinned: Boolean, val isOffered: Boolean)

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
              /** for a shortcut of an app (a long press on its icon) or one pinned to Homekey */
              val shortcut: Shortcut? = null) {
        /** the file of the processed icon in the cache, to show the list at once after a restart of the keyboard */
        var iconFile: String? = null
        /** for a person: the newest message from them in the status bar, shown in their entry and opened by it */
        var unread: LauncherNotificationService.Entry? = null
        /** for an event of the calendar: its id and begin ("id|begin"), to dismiss it from the list */
        var eventKey: String? = null
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
            element != null -> ELEMENT_PACKAGE
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
        /** s or sms and a name: the SMS conversation with a starred contact */
        private val SMS = Regex("""^(?:s|sms)\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val KNOWN_BROWSERS = setOf("org.mozilla.firefox", "com.android.chrome", "com.duckduckgo.mobile.android")
        private const val PREF_LAST_USED = "app_search_last_launch"
        private const val PREF_CHOICES = "app_search_choices"
        /** the events dismissed from the list of the home screen, as "id|begin" (each day of a repeating event apart) */
        private const val PREF_DISMISSED_EVENTS = "app_search_dismissed_events"
        /** the shortcuts offered by the apps that were removed from the list (ids like shortcut://package/id) */
        private const val PREF_REMOVED_SHORTCUTS = "app_search_removed_shortcuts"
        private const val MAX_SHOWN = 30
        /** to change when the icon processing changes, so the cached icons are computed again */
        private const val ICON_VERSION = 2
        private const val TAGS_FILE = "etiquetas-kiss.json"
        private const val WHATSAPP_MIME_TYPE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        private const val ELEMENT_FILE = "element.json"
        private const val ELEMENT_PACKAGE = "io.element.android.x"
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
            return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(ELEMENT_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        /** the intermediate shade of the icons: the text color with this opacity */
        private const val MID_ALPHA = 0.45f
        private var apps = emptyList<App>()

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
                        o.getBoolean("pinned"), o.getBoolean("offered")) else null
                    list.add(App(o.getString("key"), kissId, o.getString("label"),
                        ComponentName(o.getString("package"), o.getString("class")), user, icon, names[kissId],
                        tags[kissId].orEmpty(), whatsApp, if (whatsApp != null) elementLinks[kissId] else null,
                        shortcut = shortcut).also { it.iconFile = iconFile })
                }
            } catch (e: Exception) {
                Log.w(TAG, "can't read the saved apps", e)
                return emptyList()
            }
            elementIcon = list.firstOrNull { it.component.packageName == ELEMENT_PACKAGE && it.shortcut == null }?.icon
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
            return (launchable + whatsAppChats(context, launchable, names, tags) + shortcuts).sortedBy { it.normalizedLabel }
        }

        /**
         * The shortcuts the apps offer (those of a long press on their icon) and the ones pinned to Homekey ("add to home
         * screen" in an app, see [PinShortcutActivity]), with their own icon, without the ones removed from the list.
         * Android only gives them to the home app, so there are none while Homekey is not the home app; none either for a
         * locked profile.
         */
        private fun appShortcuts(context: Context, launchable: List<App>, names: Map<String, String>,
                                 tags: Map<String, List<String>>, usedIconFiles: MutableSet<String>): List<App> {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val userManager = context.getSystemService(UserManager::class.java)
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val removed = context.prefs().getStringSet(PREF_REMOVED_SHORTCUTS, emptySet())!!
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
                    if (kissId in removed && !info.isPinned) return@mapNotNull null
                    val iconKey = "shortcut|${info.`package`}|${info.id}|$serial|${info.lastChangedTimestamp}"
                    val (icon, iconFile) = cachedIcon(context, iconKey, usedIconFiles) {
                        launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
                            ?.let { processedIcon(context, it, user, info.id) } ?: app.icon
                    }
                    App("shortcut|${info.`package`}|${info.id}|$serial", kissId, label, app.component, user, icon,
                        names[kissId], tags[kissId].orEmpty(), shortcut = Shortcut(info.`package`, info.id, info.isPinned,
                            info.isDynamic || info.isDeclaredInManifest)).also { it.iconFile = iconFile }
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
            elementIcon = launchable.firstOrNull { it.component.packageName == ELEMENT_PACKAGE }?.icon
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
            val lastUsed = readLastUsed(context)
            var moved = false
            for (notification in notifications) {
                val person = personOf(notification) ?: continue
                if ((lastUsed[person.key] ?: 0L) >= notification.time) continue
                lastUsed[person.key] = notification.time
                moved = true
            }
            if (moved) context.prefs().edit { putString(PREF_LAST_USED, lastUsed.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            return moved
        }

        /**
         * The person in the list (a starred contact) a notification of WhatsApp or Element X comes from, if it is a
         * one-to-one chat: by the Element room of their link, or by their name as the title ("Paula Costa",
         * "Paula Costa (2 mensagens)", Element's "misty: misty").
         */
        private fun personOf(notification: LauncherNotificationService.Entry): App? {
            if (notification.isGroup || notification.packageName != ELEMENT_PACKAGE && notification.packageName != "com.whatsapp")
                return null
            val people = apps.filter { it.whatsAppDataId != null }
            if (notification.packageName == ELEMENT_PACKAGE) people.firstOrNull { person ->
                val room = person.element?.let { ROOM_ID.find(it)?.value }
                room != null && notification.key.contains(room)
            }?.let { return it }
            val name = normalize(notification.title.substringBefore(": ").replace(MESSAGE_COUNT, ""))
                .replace(NOT_A_NAME, "").trim()
            return if (name.isEmpty()) null else people.firstOrNull { name in it.names }
        }
        /** the id of an Element room in a matrix.to link, without its server */
        private val ROOM_ID = Regex("""![^?/&:%]+""")
        private val MESSAGE_COUNT = Regex("""\s*\(\d+[^)]*\)\s*$""")
        private val NOT_A_NAME = Regex("""[^\p{L}\p{N} ]""")

        private fun readLastUsed(context: Context): MutableMap<String, Long> =
            context.prefs().getString(PREF_LAST_USED, "")!!.lines().mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i < 1) null else line.substring(0, i) to (line.substring(i + 1).toLongOrNull() ?: 0L)
            }.toMap(mutableMapOf())

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
        private fun filter(apps: List<App>, query: String, counts: Map<String, Int>, lastUsed: Map<String, Long> = emptyMap()): List<App> {
            val q = normalize(query.trim())
            val byUse = compareByDescending<App> { lastUsed[it.key] ?: 0L }.thenByDescending { counts[it.key] ?: 0 }
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
