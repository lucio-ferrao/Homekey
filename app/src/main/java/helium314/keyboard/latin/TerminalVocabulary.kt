// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * The words typed in shell fields of a terminal ([TerminalContext.SHELL]): commands, options, paths, names, kept apart
 * from the Portuguese typing history, in files/terminal/vocabulario.tsv (token, count, last use). Shell fields get
 * their completions from here instead of corrections.
 */
class TerminalVocabulary(private val context: Context) {
    private class Entry(var count: Int, var lastUsed: Long)

    private val tokens: MutableMap<String, Entry> by lazy { load() }

    /** remembers a token confirmed in a shell field (typed and followed by space or Enter, or picked) */
    fun learn(token: String) {
        if (token.length < MIN_LENGTH) return
        val now = System.currentTimeMillis()
        val entry = tokens.getOrPut(token) { Entry(0, now) }
        entry.count++
        entry.lastUsed = now
        if (tokens.size > MAX_TOKENS)
            tokens.entries.sortedBy { it.value.lastUsed }.take(tokens.size - MAX_TOKENS).forEach { tokens.remove(it.key) }
        save()
    }

    /** the tokens that complete [typed], most used first */
    fun completions(typed: String): List<String> = if (typed.isEmpty()) emptyList() else tokens.entries
        .filter { it.key.length > typed.length && it.key.startsWith(typed) }
        .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.count }.thenByDescending { it.value.lastUsed })
        .map { it.key }.take(MAX_SUGGESTIONS)

    /** [typed] and its completions as suggestions: picking one replaces the token being typed */
    fun toSuggestedWords(typed: String, completions: List<String>, inputStyle: InputStyle, sequenceNumber: Int): SuggestedWords {
        val typedInfo = SuggestedWordInfo(typed, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        val infos = arrayListOf(typedInfo)
        completions.forEachIndexed { i, token ->
            infos.add(SuggestedWordInfo(token, "", Int.MAX_VALUE - 1 - i, SuggestedWordInfo.KIND_COMPLETION,
                Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        }
        return SuggestedWords(infos, null, typedInfo, false, false, false, inputStyle, sequenceNumber)
    }

    private fun file() = File(context.filesDir, "terminal/vocabulario.tsv")

    private fun load(): MutableMap<String, Entry> {
        val map = HashMap<String, Entry>()
        try {
            val file = file()
            if (file.isFile) file.readLines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 3) map[parts[0]] = Entry(parts[1].toIntOrNull() ?: 1, parts[2].toLongOrNull() ?: 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't read the terminal vocabulary", e)
        }
        return map
    }

    private fun save() {
        try {
            val file = file()
            file.parentFile?.mkdirs()
            file.writeText(tokens.entries.joinToString("\n") { "${it.key}\t${it.value.count}\t${it.value.lastUsed}" })
        } catch (e: Exception) {
            Log.w(TAG, "can't save the terminal vocabulary", e)
        }
    }

    companion object {
        private const val TAG = "TerminalVocabulary"
        private const val MAX_TOKENS = 3000
        private const val MAX_SUGGESTIONS = 5
        private const val MIN_LENGTH = 2
    }
}
