package dev.logisim.mobile.core

import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmTelnetTransportTest {
    @Test
    fun explicitLoopbackSessionReceivesSendsAndClosesItsSocket() {
        val transport = JvmTelnetTransport()
        assertEquals(null, transport.localPort)
        assertEquals(TelnetLinkState.Listening, transport.listen(TelnetListenConfiguration(0)))
        val port = transport.localPort!!
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 2000
            val accepted = transport.poll()
            assertEquals(TelnetLinkState.Connected, accepted.link)
            assertEquals(true, accepted.peerChanged)
            assertEquals(false, transport.poll().peerChanged)
            client.getOutputStream().write(byteArrayOf(65, 66, 67))
            val received = mutableListOf<Int>()
            val deadline = System.nanoTime() + 2_000_000_000
            while (received.size < 3 && System.nanoTime() < deadline) {
                received += transport.poll().bytes
                if (received.size < 3) Thread.sleep(1)
            }
            assertEquals(listOf(65, 66, 67), received)
            transport.enqueue(listOf(90, 255))
            transport.poll()
            assertEquals(90, client.getInputStream().read())
            assertEquals(255, client.getInputStream().read())
            transport.close()
            assertEquals(-1, client.getInputStream().read())
        }
        assertEquals(null, transport.localPort)
        assertEquals(TelnetLinkState.Stopped, transport.poll().link)
    }

    @Test
    fun peerResetDropsOnlyThatPeerAndKeepsListening() {
        val transport = JvmTelnetTransport()
        try {
            transport.listen(TelnetListenConfiguration(0))
            val port = transport.localPort!!
            Socket("127.0.0.1", port).use { peer ->
                assertEquals(TelnetLinkState.Connected, transport.poll().link)
                // SO_LINGER 0 closes with a TCP reset instead of FIN.
                peer.setSoLinger(true, 0)
            }
            val deadline = System.nanoTime() + 2_000_000_000
            var link = transport.poll().link
            while (link != TelnetLinkState.Listening && System.nanoTime() < deadline) {
                Thread.sleep(1)
                link = transport.poll().link
            }
            assertEquals(TelnetLinkState.Listening, link)
            assertEquals(port, transport.localPort)
            Socket("127.0.0.1", port).use { next ->
                next.soTimeout = 2000
                val accepted = transport.poll()
                assertEquals(TelnetLinkState.Connected, accepted.link)
                assertEquals(true, accepted.peerChanged)
                transport.enqueue(listOf(7))
                transport.poll()
                assertEquals(7, next.getInputStream().read())
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun connectionFailureIsReportedAndDoesNotKeepAPartialListener() {
        val first = JvmTelnetTransport()
        val second = JvmTelnetTransport()
        try {
            first.listen(TelnetListenConfiguration(0))
            val result = second.listen(TelnetListenConfiguration(first.localPort!!))
            assertTrue(result is TelnetLinkState.Failed)
            assertEquals(null, second.localPort)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun outputQueueAppliesBackpressureUntilThePeerReceivesData() {
        val transport = JvmTelnetTransport()
        try {
            transport.listen(TelnetListenConfiguration(0, maximumOutputBytes = 2))
            Socket("127.0.0.1", transport.localPort!!).use { peer ->
                peer.soTimeout = 2000
                transport.poll()
                assertEquals(true, transport.enqueue(listOf(1, 2)))
                assertEquals(false, transport.enqueue(listOf(3)))
                transport.poll()
                assertEquals(1, peer.getInputStream().read())
                assertEquals(2, peer.getInputStream().read())
                assertEquals(true, transport.enqueue(listOf(3)))
                transport.poll()
                assertEquals(3, peer.getInputStream().read())
            }
        } finally {
            transport.close()
        }
    }
}
