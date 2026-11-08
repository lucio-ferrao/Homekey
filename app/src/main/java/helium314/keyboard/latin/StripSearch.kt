// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.event.Event

/**
 * A mode of the suggestion strip that takes the typing for a search of its own instead of the text field: the launcher
 * mode and the emoji mode. While [capturesInput], keys, gestures and text go to it and it shows its results.
 */
interface StripSearch {
    fun capturesInput(): Boolean
    /** a key; false if the mode does not use it */
    fun onEvent(event: Event): Boolean
    fun onTextInput(text: String)
    /** what goes before a gestured word: a space after a word already typed */
    fun gestureSeparator(): String
    fun show()
    /** a tap in the app or a move of its cursor, which leaves the mode */
    fun onTapOutside()
}
