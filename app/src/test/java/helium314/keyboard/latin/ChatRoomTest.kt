// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatRoomTest {
    @Test
    fun roomOfTheMessageBox() {
        assertEquals("!abc:ferrao.org", ChatRoom.parse("org.ferrao.homekey.room=!abc:ferrao.org"))
        assertEquals("!abc:ferrao.org", ChatRoom.parse("nm, org.ferrao.homekey.room=!abc:ferrao.org ,other=1"))
    }

    @Test
    fun noRoom() {
        assertNull(ChatRoom.parse(null))
        assertNull(ChatRoom.parse(""))
        assertNull(ChatRoom.parse("org.ferrao.homekey.context=shell:bash"))
        assertNull(ChatRoom.parse("org.ferrao.homekey.room="))
    }
}
