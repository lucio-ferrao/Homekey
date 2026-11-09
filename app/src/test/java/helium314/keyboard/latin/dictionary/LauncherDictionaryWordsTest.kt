// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherDictionaryWordsTest {
    @Test fun splitsAtSpaces() {
        assertEquals(listOf("Home", "Assistant"), launcherDictionaryWords("Home  Assistant"))
    }

    @Test fun dropsPunctuationAround() {
        assertEquals(listOf("Visita", "Técnica", "Reunião", "Bloco"), launcherDictionaryWords("Visita Técnica (Reunião Bloco)"))
        assertEquals(listOf("Lúcio", "Adriana"), launcherDictionaryWords("Lúcio / Adriana"))
    }

    @Test fun keepsPunctuationInside() {
        assertEquals(listOf("olivais.ferrao.org", "e-mail"), launcherDictionaryWords("olivais.ferrao.org e-mail"))
    }

    @Test fun dropsSingleLetters() {
        assertEquals(listOf("Ferrao"), launcherDictionaryWords("A Ferrao ·"))
        assertEquals(emptyList(), launcherDictionaryWords(""))
    }
}
