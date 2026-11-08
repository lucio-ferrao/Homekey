// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.emoji.RecentEmojis
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import java.text.Normalizer

/**
 * The emojis in the suggestion strip, with the keyboard still shown (the emoji key, or emoji in the alternatives of
 * Enter): the recent ones first, then all by category; typing filters them by their names and keywords in English and
 * Portuguese (assets/emoji/nomes.txt, from the CLDR annotations, see tools/emoji-nomes.py). A tap inserts the emoji in
 * the app and leaves the mode, as do a double tap on space, the emoji icon, or a tap in the app.
 */
class EmojiStripMode(private val latinIME: LatinIME) : StripSearch {
    var isActive = false
        private set
    private val query = StringBuilder()
    private var lastSpaceTapTime = 0L
    private var startTime = 0L

    override fun capturesInput() = isActive

    fun start() {
        isActive = true
        startTime = SystemClock.uptimeMillis()
        query.setLength(0)
        lastSpaceTapTime = 0
        ScreenAgentService.onTapInOtherApp = { onTapOutside() }
        show()
    }

    /** leaves the mode and restores the normal suggestion strip */
    fun stop() {
        if (!isActive) return
        reset()
        latinIME.setNeutralSuggestionStrip()
    }

    /** leaves the mode without touching the suggestion strip, e.g. when the input finishes */
    fun reset() {
        if (!isActive) return
        isActive = false
        query.setLength(0)
        ScreenAgentService.onTapInOtherApp = null
        latinIME.suggestionStripView?.setEmojiMode(false)
    }

    override fun onTapOutside() {
        if (isActive && SystemClock.uptimeMillis() - startTime > 600) stop()
    }

    override fun onEvent(event: Event): Boolean {
        val codePoint = event.codePoint
        if (codePoint == Constants.CODE_SPACE && !event.isKeyRepeat) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSpaceTapTime < ViewConfiguration.getDoubleTapTimeout()) {
                stop()
                return true
            }
            lastSpaceTapTime = now
            query.append(' ')
            show()
            return true
        }
        lastSpaceTapTime = 0
        when {
            codePoint == Constants.CODE_ENTER -> {
                results().firstOrNull()?.let { insert(it) }
                return true
            }
            codePoint > 0 -> query.appendCodePoint(codePoint)
            // backspace with nothing typed leaves the mode
            event.keyCode == KeyCode.DELETE -> if (query.isNotEmpty())
                query.setLength(query.offsetByCodePoints(query.length, -1))
            else {
                stop()
                return true
            }
            else -> return false
        }
        show()
        return true
    }

    override fun onTextInput(text: String) {
        lastSpaceTapTime = 0
        query.append(text)
        show()
    }

    override fun gestureSeparator() = if (query.isNotEmpty() && !query.endsWith(' ')) " " else ""

    /** the emojis for the search text: every word typed starts a word of the names of the emoji */
    private fun results(): List<String> {
        val all = emojis(latinIME)
        val recent = RecentEmojis.get().filter { !SupportedEmojis.isUnsupported(it) }
        val ordered = (recent + all.map { it.first }).distinct()
        val words = normalize(query.toString()).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ordered
        val names = all.toMap()
        return ordered.filter { emoji ->
            val emojiWords = names[emoji.replace("️", "")] ?: return@filter false
            words.all { word -> emojiWords.any { it.startsWith(word) } }
        }
    }

    private fun insert(emoji: String) {
        stop()
        RecentEmojis.add(emoji)
        latinIME.onTextInput(emoji)
    }

    override fun show() {
        if (!isActive) return
        val strip = latinIME.suggestionStripView ?: return
        strip.setEmojiMode(true)
        strip.setExternalSuggestionView(createRow(strip.context, results()), false)
    }

    private fun createRow(context: Context, emojis: List<String>): View {
        val res = context.resources
        val color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // the emoji icon leaves the mode
        val icon = ImageView(context)
        icon.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.EMOJI.name, context)?.mutate()?.apply { setTint(color) })
        icon.setPadding(8.dpToPx(res), 0, 4.dpToPx(res), 0)
        icon.setOnClickListener { stop() }
        row.addView(icon, LinearLayout.LayoutParams(32.dpToPx(res), ViewGroup.LayoutParams.MATCH_PARENT))
        val queryView = TextView(context)
        queryView.text = query
        queryView.setTextColor(ColorUtils.setAlphaComponent(color, 180))
        queryView.textSize = 16f
        queryView.gravity = Gravity.CENTER_VERTICAL
        queryView.setPadding(0, 0, 8.dpToPx(res), 0)
        queryView.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        row.addView(queryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // all of them, drawing only the visible ones
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        list.isHorizontalScrollBarEnabled = false
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = emojis.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TextView(context).apply {
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(6.dpToPx(res), 0, 6.dpToPx(res), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val emoji = emojis[position]
                (holder.itemView as TextView).text = emoji
                holder.itemView.setOnClickListener { insert(emoji) }
            }
        }
        row.addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return row
    }

    companion object {
        private const val TAG = "emoji-strip"
        private val CATEGORIES = listOf("SMILEYS_AND_EMOTION", "PEOPLE_AND_BODY", "ANIMALS_AND_NATURE", "FOOD_AND_DRINK",
            "TRAVEL_AND_PLACES", "ACTIVITIES", "OBJECTS", "SYMBOLS", "FLAGS")
        private var emojis: List<Pair<String, List<String>>>? = null

        /** the emojis the phone can show, by category, each with the normalized words of its names (none if unknown) */
        private fun emojis(context: Context): List<Pair<String, List<String>>> = emojis ?: try {
            val names = context.assets.open("emoji/nomes.txt").bufferedReader().readLines().associate { line ->
                val (emoji, text) = line.split('\t', limit = 2)
                emoji to normalize(text).split('|', ' ', ':', ',').filter { it.isNotEmpty() }.distinct()
            }
            CATEGORIES.flatMap { category ->
                context.assets.open("emoji/$category.txt").bufferedReader().readLines()
                    .mapNotNull { it.split(' ').firstOrNull()?.takeIf { e -> e.isNotEmpty() && !SupportedEmojis.isUnsupported(e) } }
            }.map { it to names[it.replace("️", "")].orEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "can't read the emojis", e)
            emptyList()
        }.also { emojis = it }

        private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }
}
