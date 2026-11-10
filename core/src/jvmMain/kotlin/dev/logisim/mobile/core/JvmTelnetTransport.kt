package dev.logisim.mobile.core

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/** Nonblocking companion-host transport. Loopback is the default; LAN binding is explicit. */
class JvmTelnetTransport : TelnetTransport {
    private var listener: ServerSocketChannel? = null
    private var client: SocketChannel? = null
    private val output = ArrayDeque<ByteBuffer>()
    private var configuration = TelnetListenConfiguration(0)
    private var link: TelnetLinkState = TelnetLinkState.Stopped

    override val localPort: Int? get() = (listener?.localAddress as? InetSocketAddress)?.port

    override fun listen(configuration: TelnetListenConfiguration): TelnetLinkState {
        close()
        this.configuration = configuration
        return try {
            val server = ServerSocketChannel.open()
            listener = server
            server.configureBlocking(false)
            server.bind(InetSocketAddress(if (configuration.allowLan) "0.0.0.0" else "127.0.0.1", configuration.port))
            TelnetLinkState.Listening.also { link = it }
        } catch (error: IOException) {
            fail(error)
        }
    }

    override fun poll(): TelnetTransportBatch {
        val received = mutableListOf<Int>()
        var peerChanged = false
        try {
            listener?.accept()?.let { accepted ->
                client?.close()
                output.clear()
                client = accepted
                peerChanged = true
                accepted.configureBlocking(false)
                link = TelnetLinkState.Connected
                if (configuration.telnetMode) enqueue(listOf(255, 251, 3, 255, 251, 1))
            }
        } catch (error: IOException) {
            fail(error)
            return TelnetTransportBatch(received, link, peerChanged)
        }
        val channel = client
        if (channel != null) {
            try {
                val buffer = ByteBuffer.allocate(8192)
                val read = channel.read(buffer)
                if (read < 0) {
                    dropClient(channel)
                } else {
                    buffer.flip()
                    while (buffer.hasRemaining()) received += buffer.get().toInt() and 255
                    flush(channel)
                }
            } catch (_: IOException) {
                // A reset or failed peer ends only that connection, as in Evolution's TelnetServer.
                dropClient(channel)
            }
        }
        return TelnetTransportBatch(received, link, peerChanged)
    }

    private fun dropClient(channel: SocketChannel) {
        try { channel.close() } catch (_: IOException) {}
        client = null
        output.clear()
        link = TelnetLinkState.Listening
    }

    override fun enqueue(bytes: List<Int>): Boolean {
        require(bytes.all { it in 0..255 })
        if (client == null || bytes.size + output.sumOf { it.remaining() } > configuration.maximumOutputBytes) return false
        output.addLast(ByteBuffer.wrap(bytes.map(Int::toByte).toByteArray()))
        return true
    }

    private fun flush(channel: SocketChannel) {
        while (output.isNotEmpty()) {
            val buffer = output.first()
            channel.write(buffer)
            if (buffer.hasRemaining()) break
            output.removeFirst()
        }
    }

    override fun close() {
        try { client?.close() } finally {
            client = null
            try { listener?.close() } finally {
                listener = null
                output.clear()
                link = TelnetLinkState.Stopped
            }
        }
    }

    private fun fail(error: IOException): TelnetLinkState {
        close()
        return TelnetLinkState.Failed(error.message ?: "Falha de conexão.").also { link = it }
    }
}
