// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTypingTest {
    private val paula = "!8u9XzTj_KEQU37A1:ferrao.org"
    private val silvia = "!wthFNU4CW8ekLi9s:ferrao.org"

    @Test
    fun aRecentTimeIsTypingInThatRoomWithoutItsServer() {
        assertEquals(setOf("!8u9XzTj_KEQU37A1"), ChatTyping.typingRooms(listOf(paula to 1_000L, silvia to 0L), 2_000L))
    }

    @Test
    fun anOldTimeCountsAsStopped() {
        assertEquals(emptySet(), ChatTyping.typingRooms(listOf(paula to 1_000L), 1_000L + ChatTyping.LIMIT_MS))
    }

    @Test
    fun theNextStopIsTheEarliestTimeAndTheLimit() {
        assertEquals(1_000L + ChatTyping.LIMIT_MS, ChatTyping.nextStop(listOf(paula to 1_000L, silvia to 5_000L), 6_000L))
        assertNull(ChatTyping.nextStop(listOf(paula to 0L), 6_000L))
    }
}
