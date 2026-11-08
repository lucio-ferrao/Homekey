// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

/**
 * A notification that arrives while the keyboard is open, shown in the suggestion strip with the icon of its app until
 * the next key or for [SHOWN_FOR] ms; a tap opens it, like in the list of the launcher mode. Not for the app being
 * typed in, nor on the home screen of Homekey (its list has the notifications), nor while the strip has a search.
 */
class NotificationStrip(private val latinIME: LatinIME) {
    private var shown: LauncherNotificationService.Entry? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { hide() }

    fun onPosted(entry: LauncherNotificationService.Entry, stripIsFree: Boolean) {
        val editor = latinIME.currentInputEditorInfo ?: return
        if (!latinIME.isInputViewShown || !stripIsFree || HomeActivity.isHomeField(editor) || entry.packageName == editor.packageName)
            return
        val strip = latinIME.suggestionStripView ?: return
        shown = entry
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SHOWN_FOR)
        val context = strip.context
        val res = context.resources
        val color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(8.dpToPx(res), 0, 8.dpToPx(res), 0)
        AppLauncherMode.iconOf(entry.packageName)?.let {
            val icon = ImageView(context)
            icon.setImageDrawable(it)
            row.addView(icon, LinearLayout.LayoutParams(28.dpToPx(res), 28.dpToPx(res)))
        }
        val label = TextView(context)
        label.text = entry.label
        label.setTextColor(color)
        label.textSize = 14f
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setPadding(6.dpToPx(res), 0, 0, 0)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        row.setOnClickListener {
            hide()
            // a chat: its message box is tapped once it is open, so the keyboard stays
            ScreenAgentService.focusMessageFieldSoon(entry.packageName)
            entry.open()
        }
        strip.setExternalSuggestionView(row, false)
    }

    /** gives the strip back to the suggestions */
    fun hide() {
        if (shown == null) return
        forget()
        latinIME.setNeutralSuggestionStrip()
    }

    /** forgets the notification without touching the strip, e.g. when the input finishes */
    fun forget() {
        shown = null
        handler.removeCallbacks(timeout)
    }

    companion object {
        private const val SHOWN_FOR = 10_000L
    }
}
