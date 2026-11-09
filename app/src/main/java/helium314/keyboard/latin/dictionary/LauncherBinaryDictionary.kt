// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.NgramContext
import java.util.Locale

/**
 * The words of the list of the launcher mode (the names of its apps, people and shortcuts, and their tags), used only
 * in the search field of the home screen of Homekey, so a name typed with a wrong letter gets its suggestion
 * ("spotifu" gives "Spotify").
 */
class LauncherBinaryDictionary private constructor(context: Context, locale: Locale) :
    ExpandableBinaryDictionary(context, getDictName(NAME, locale, null), locale, TYPE_LAUNCHER, null) {

    init {
        reloadDictionaryIfRequired()
    }

    override fun loadInitialContentsLocked() {
        for (text in texts) {
            var ngramContext = NgramContext.getEmptyPrevWordsContext(BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM)
            for (word in launcherDictionaryWords(text)) {
                if (word.codePointCount(0, word.length) > MAX_WORD_LENGTH) continue
                runGCIfRequiredLocked(true)
                addUnigramLocked(word, FREQUENCY, null, 0, false, false, BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                if (ngramContext.isValid) {
                    runGCIfRequiredLocked(true)
                    addNgramEntryLocked(ngramContext, word, FREQUENCY_BIGRAM, BinaryDictionary.NOT_A_VALID_TIMESTAMP)
                }
                ngramContext = ngramContext.getNextNgramContext(NgramContext.WordInfo(word))
            }
        }
    }

    companion object {
        const val NAME = "launcher"
        // like the names of apps and contacts in their dictionaries
        private const val FREQUENCY = 100
        private const val FREQUENCY_BIGRAM = 200

        /** the names and tags of the list, as set by the launcher mode */
        @Volatile
        private var texts = emptySet<String>()

        @JvmStatic
        fun getDictionary(context: Context, locale: Locale) = LauncherBinaryDictionary(context, locale)

        /** when the list changes: the dictionary is made again at its next use if its names or tags changed */
        fun setTexts(newTexts: Set<String>) {
            if (newTexts == texts) return
            texts = newTexts
            ExpandableDictionaryUtils.setNeedsUpdate(NAME)
        }
    }
}

/** the words of a name or a tag: split at spaces, without punctuation around them ("(Reunião" gives "Reunião"), of 2 letters or more */
fun launcherDictionaryWords(text: String): List<String> = text.split(Regex("\\s+"))
    .map { word -> word.trim { !it.isLetterOrDigit() } }
    .filter { it.codePointCount(0, it.length) >= 2 }
