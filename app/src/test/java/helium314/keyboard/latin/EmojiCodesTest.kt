// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiCodesTest {
    private val byName = { prefix: String -> if (prefix == "zebra") listOf("🦓") else emptyList() }

    private fun pt(prefix: String) = EmojiCodes.complete(prefix, true, byName)
    private fun en(prefix: String) = EmojiCodes.complete(prefix, false, byName)

    @Test fun smileysFirst() {
        val bare = pt("")
        assertEquals(12, bare.size)
        assertEquals(listOf("🙂", "😁", "🙁"), bare.take(3))
        assertEquals(listOf("🙂"), pt(")"))
        assertEquals(listOf("😘"), pt("*"))
        assertEquals(listOf("😁", "🇩🇪", "🐶", "✅", "⬇️"), pt("D").take(5))
    }

    @Test fun loveBeforeLol() {
        assertEquals(listOf("❤️", "😍", "🥰", "😂", "🔒"), pt("lo"))
        assertEquals(listOf("😂"), pt("lol"))
    }

    @Test fun ownerFirstOnATie() {
        assertEquals(listOf("❤️", "😍", "🥰"), pt("amor"))
        assertEquals(listOf("❤️", "😍", "🥰"), pt("COR").take(3))
        assertEquals(listOf("💋", "😘"), en("kiss"))
    }

    @Test fun usersLanguage() {
        // the angel is "anjo" in Portuguese, as short as the heart's "amor", but "angel" in English
        assertEquals(listOf("😇", "❤️"), pt("a").take(2))
        assertEquals(listOf("❤️", "💧", "😠", "😇"), en("a").take(4))
        assertEquals(listOf("👍", "😎"), pt("fix"))
    }

    @Test fun otherNamesOnlyWhenTheTableHasNone() {
        assertEquals(listOf("🦓"), pt("zebra"))
        assertEquals(emptyList<String>(), pt("+zebra"))
        assertEquals(listOf("❤️", "😍", "🥰", "😂", "🔒"), pt("lo"))
    }
}
