// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshCommandTest {
    private fun forward(text: String) = SshCommand.parse(text) as SshCommand.Forward

    @Test
    fun aNameConnects() {
        assertEquals(SshCommand.Connect("olivais"), SshCommand.parse("ssh olivais"))
        assertEquals(SshCommand.Connect("olivais"), SshCommand.parse("SSH olivais "))
    }

    @Test
    fun aPortAloneIsTheSamePortOnLocalhost() {
        val local = forward("ssh -L 3000")
        assertEquals(listOf(null, "-L 3000", 'L', 3000, "localhost", 3000),
            listOf(local.server, local.arguments, local.direction, local.port, local.host, local.hostPort))
        assertEquals("R5555", forward("ssh olivais -R 5555").label)
        assertEquals("olivais", forward("ssh olivais -R 5555").server)
    }

    @Test
    fun theFullSyntaxAndItsShortForms() {
        assertEquals(listOf(8080, "web", 8080), forward("ssh -L 8080:web").let { listOf(it.port, it.host, it.hostPort) })
        assertEquals(listOf(5555, "localhost", 5555), forward("ssh -R 0.0.0.0:5555").let { listOf(it.port, it.host, it.hostPort) })
        assertEquals(listOf(3000, "localhost", 80), forward("ssh -L3000:localhost:80").let { listOf(it.port, it.host, it.hostPort) })
        assertEquals(listOf(5555, "127.0.0.1", 37099),
            forward("ssh -R 0.0.0.0:5555:127.0.0.1:37099").let { listOf(it.port, it.host, it.hostPort) })
    }

    @Test
    fun cancelTakesThePort() {
        val stop = forward("ssh -KL 3000")
        assertEquals(listOf(true, 'L', 3000), listOf(stop.cancel, stop.direction, stop.port))
    }

    @Test
    fun notACommandOrABadForward() {
        listOf("ssh", "sshd olivais", "s olivais", "ssh -X 3000", "ssh -L", "ssh -L abc", "ssh -L 70000", "ssh -L :80",
            "ssh -L 1:2:3:4:5", "ssh -L 3000 4000").forEach { assertNull(it, SshCommand.parse(it)) }
    }
}
