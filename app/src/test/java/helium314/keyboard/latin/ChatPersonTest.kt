// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatPersonTest {
    private class Person(val names: List<String>, val tags: List<String>)

    private val paula = Person(listOf("paula", "paula costa"), listOf("misty", "familia"))
    private val silvia = Person(listOf("silvia"), listOf("familia"))
    private val people = listOf(paula, silvia)

    private fun personOf(title: String) =
        AppLauncherMode.personNamed(AppLauncherMode.titleName(title), people, { it.names }, { it.tags })

    @Test fun byName() {
        assertEquals(paula, personOf("Paula Costa"))
        assertEquals(paula, personOf("Paula Costa (2 mensagens)"))
        assertEquals(silvia, personOf("Sílvia"))
    }

    @Test fun byTagWithEmoji() {
        assertEquals("misty", AppLauncherMode.titleName("misty 😎"))
        assertEquals(paula, personOf("misty 😎"))
        assertEquals(paula, personOf("misty, 🟢"))
    }

    @Test fun groupOrUnknownIsNoOne() {
        assertNull(personOf("Família Ferrão"))
        // a tag of more than one person names no one
        assertNull(personOf("Família"))
        assertNull(personOf("😎"))
    }
}
