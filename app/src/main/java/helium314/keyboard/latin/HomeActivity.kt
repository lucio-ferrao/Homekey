// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView

/**
 * Home screen of the phone when Keyarchy is chosen as the home app: on black, the results of the launcher mode as a
 * vertical list, and a text field for the search (recognized by [isHomeField]) where typing, pasting and editing work
 * as in any field. The keyboard never hides there: back and the home gesture only clear the search.
 */
class HomeActivity : Activity() {
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val dp = resources.displayMetrics.density
        // the search: a normal text field (cursor, selection, paste), with the arrow of the apps
        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            privateImeOptions = PRIVATE_IME_OPTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            isSingleLine = true
            setTextColor(Color.WHITE)
            textSize = 18f
            useKeyboardFont()
            setBackgroundColor(Color.argb(170, 20, 20, 20))
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            // the arrow that opens the apps from the suggestion strip; the icons are loaded here too, as the home
            // screen can start before the keyboard (after an update, for instance)
            KeyboardIconsSet.instance.loadIcons(this@HomeActivity)
            setCompoundDrawablesRelativeWithIntrinsicBounds(KeyboardIconsSet.instance
                .getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, this@HomeActivity)?.mutate()?.apply { setTint(Color.WHITE) },
                null, null, null)
            compoundDrawablePadding = (12 * dp).toInt()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!editing) mode?.setHomeQuery(s?.toString().orEmpty())
                }
            })
            // Enter: saves an editing, or opens the first result (or runs a command)
            setOnEditorActionListener { _, _, _ ->
                if (editing) mode?.saveEdit(text.toString()) else mode?.openFirst()
                true
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // the results of the launcher mode, from the bottom (next to the keyboard) upwards, like KISS
        // all of them, drawing only the visible rows; reversed, so the first result is at the bottom
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, LinearLayoutManager.VERTICAL, true)
            adapter = this@HomeActivity.adapter
            isVerticalScrollBarEnabled = false
            itemAnimator = null
        }
        // swiping an entry aside dismisses it (AppLauncherMode.dismiss): a notification, a message, an event, or a recent one
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                val app = results.getOrNull(holder.bindingAdapterPosition) ?: return 0
                return if (mode?.canDismiss(app) == true) super.getSwipeDirs(recyclerView, holder) else 0
            }
            override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            // a quarter of the width is enough to dismiss, as in the strip
            override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.25f
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val app = results.getOrNull(holder.bindingAdapterPosition) ?: return
                mode?.dismiss(app)
            }
        }).attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // the actions of a result (long press), the title of an editing and messages, just above the text field
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(Color.argb(200, 20, 20, 20))
            visibility = android.view.View.GONE
        }
        panelTitle = TextView(this).apply { setTextColor(Color.argb(180, 255, 255, 255)); textSize = 14f; useKeyboardFont() }
        panelActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(panelTitle)
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(panelActions)
        })
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // above the keyboard and below the status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            // the keyboard never hides here, but Android hides it itself on back (predictive back): show it again, with
            // the search cleared, as back does
            val imeShown = insets.isVisible(WindowInsets.Type.ime())
            if (keyboardWasShown && !imeShown && hasWindowFocus()) view.post {
                if (!hasWindowFocus()) return@post
                field.text.clear()
                openKeyboard()
            }
            keyboardWasShown = imeShown
            insets
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the events of today in the list
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // starting the launcher mode again shows the events and follows the changes of the calendar
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) mode?.let { if (it.isActive) it.start() }
    }

    private lateinit var list: RecyclerView
    /** the text field holds a name, tags or a link being edited, not the search */
    private var editing = false
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelActions: LinearLayout

    /** the font of the keyboard (Appearance, custom font), when one is set */
    private fun TextView.useKeyboardFont() {
        KeyboardTypeface.customTypeface()?.let { typeface = it }
    }

    private fun renderPanel(title: CharSequence?, actions: List<Pair<String, () -> Unit>>) {
        if (title == null) {
            panel.visibility = android.view.View.GONE
            return
        }
        val dp = resources.displayMetrics.density
        panelTitle.text = title
        panelActions.removeAllViews()
        for ((label, onClick) in actions) panelActions.addView(TextView(this).apply {
            this.text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            useKeyboardFont()
            setPadding(0, (10 * dp).toInt(), (24 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { onClick() }
        })
        panel.visibility = android.view.View.VISIBLE
    }
    private var mode: AppLauncherMode? = null
    private var results = emptyList<AppLauncherMode.App>()

    /** the search text the list was last shown for, null to show the next list from the bottom */
    private var shownQuery: String? = null

    private fun render(mode: AppLauncherMode, results: List<AppLauncherMode.App>) {
        this.mode = mode
        this.results = results
        adapter.notifyDataSetChanged()
        // back to the bottom (the first result) for a new search; a dismissed entry or a new notification keeps the place
        val query = field.text.toString()
        if (query != shownQuery) list.scrollToPosition(0)
        shownQuery = query
    }

    private class RowHolder(val row: LinearLayout, val icon: ImageView, val name: TextView, val tags: TextView) :
        RecyclerView.ViewHolder(row)

    private val adapter = object : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val dp = resources.displayMetrics.density
            val row = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(this@HomeActivity)
            row.addView(icon, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
            // the name, and the tags below it
            val texts = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), 0, 0, 0)
            }
            val name = TextView(this@HomeActivity).apply {
                setTextColor(Color.argb(210, 255, 255, 255))
                textSize = 17f
                useKeyboardFont()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
            }
            val tags = TextView(this@HomeActivity).apply {
                setTextColor(Color.argb(150, 255, 255, 255))
                textSize = 13f
                useKeyboardFont()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
            }
            // the whole width left of the row, not the width of the first text shown in it (rows are reused)
            texts.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            texts.addView(tags, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return RowHolder(row, icon, name, tags)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val mode = mode ?: return
            val app = results[position]
            holder.icon.setImageDrawable(mode.iconOf(app))
            mode.pulseIfWorking(holder.icon, app)
            holder.name.text = mode.highlighted(app.label, Color.WHITE)
            // under a person, their newest message instead of the tags
            val unread = app.unread?.text?.replace('\n', ' ')
            // for a shortcut, the name of its app before the tags; for an event, its calendar
            val under = listOfNotNull(mode.appNameOf(app), app.calendar, app.rawTags.joinToString(" ").ifEmpty { null }).joinToString(" · ")
            holder.tags.text = unread ?: mode.highlighted(under, Color.WHITE)
            holder.tags.visibility = if (unread == null && under.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            holder.row.setOnClickListener { mode.open(app) }
            holder.row.setOnLongClickListener { mode.showActions(app); true }
        }
    }

    /** back stays on the home screen */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    /** whether the keyboard was shown at the last change of the insets, to show it again when it hides */
    private var keyboardWasShown = false

    /** coming back from an app: an empty search */
    override fun onResume() {
        super.onResume()
        instance = this
        shownQuery = null
        stopEditing()
        field.text.clear()
        openKeyboard()
    }

    override fun onPause() {
        super.onPause()
        if (instance === this) instance = null
    }

    /** the home gesture while already here: clears the search (and any action being edited) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mode?.clearSearch()
        field.text.clear()
        openKeyboard()
    }

    /** asked as soon as the window has the focus, the earliest a text field can get the keyboard */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) openKeyboard()
    }

    /**
     * Opens the keyboard on the text field, keeping what is typed. From Android 11 the home screen takes control of the
     * keyboard animation and finishes it at once, so the keyboard is in place right away instead of sliding up.
     */
    private fun openKeyboard() {
        field.requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.decorView.hasWindowFocus()) {
            window.insetsController?.controlWindowInsetsAnimation(WindowInsets.Type.ime(), 0, LinearInterpolator(), null,
                object : WindowInsetsAnimationControlListener {
                    override fun onReady(controller: WindowInsetsAnimationController, types: Int) = controller.finish(true)
                    override fun onFinished(controller: WindowInsetsAnimationController) {}
                    override fun onCancelled(controller: WindowInsetsAnimationController?) {}
                })
        }
        getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    companion object {
        private const val PRIVATE_IME_OPTIONS = "helium314.keyboard.home"
        private var instance: HomeActivity? = null

        /** the text in the text field of the home screen, the search */
        fun fieldText(): String = instance?.field?.text?.toString().orEmpty()

        /** the text field edits [value] (a name, tags, a link) instead of searching, until [stopEditing] */
        fun startEditing(value: String) {
            val activity = instance ?: return
            activity.editing = true
            activity.field.setText(value)
            activity.field.setSelection(value.length)
        }

        /** back to searching, with an empty text field */
        fun stopEditing() {
            val activity = instance ?: return
            if (!activity.editing) return
            activity.editing = false
            activity.field.text.clear()
        }

        /** shows the actions of a result, the title of an editing or a message, above the text field */
        fun showPanel(title: CharSequence, actions: List<Pair<String, () -> Unit>>) {
            instance?.renderPanel(title, actions)
        }

        fun hidePanel() {
            instance?.renderPanel(null, emptyList())
        }

        /** shows the results of the launcher mode in the list of the home screen, if it is open */
        fun showResults(mode: AppLauncherMode, results: List<AppLauncherMode.App>) {
            instance?.render(mode, results)
        }

        /** whether the keyboard is in the field of the home screen, where it opens in the launcher mode */
        fun isHomeField(editorInfo: EditorInfo?) = editorInfo?.privateImeOptions == PRIVATE_IME_OPTIONS
    }
}
