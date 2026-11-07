package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Fake SHARE64 receiver speaking just the Hello/HelloAck part of wire protocol v1. */
class HandshakeProbeTest {
    private fun ack(name: String, version: Int = 1, type: Int = 2, magic: Int = 0x34365348): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val body = ByteBuffer.allocate(6 + n.size).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putShort(n.size.toShort()).put(n).array()
        return ByteBuffer.allocate(16 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic).put(version.toByte()).put(type.toByte()).putShort(0).putInt(body.size).putInt(0).put(body).array()
    }

    private fun withServer(reply: ByteArray?, check: (Int, ByteArray) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var received = ByteArray(0)
        val t = thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val head = ByteArray(16).also { input.readFully(it) }
                val len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
                val body = ByteArray(len).also { input.readFully(it) }
                received = head + body
                if (reply != null) s.getOutputStream().apply { write(reply); flush() }
            }
        }
        try {
            check(server.localPort, ByteArray(0))
        } finally {
            t.join(3000)
            server.close()
        }
        // validate what the client sent
        val h = ByteBuffer.wrap(received).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x34365348, h.getInt(0))
        assertEquals(1, received[4].toInt())   // version
        assertEquals(1, received[5].toInt())   // Hello
        assertEquals(received.size - 16, h.getInt(8))
    }

    @Test fun readsDeviceNameFromHelloAck() {
        withServer(ack("Living Room TV")) { port, _ ->
            val r = HandshakeProbe.query("127.0.0.1", port, 2000, "Tester")
            assertNotNull(r)
            assertEquals("Living Room TV", r!!.deviceName)
            assertEquals(1, r.protocolVersion)
        }
    }

    @Test fun reportsPeerProtocolVersion() {
        withServer(ack("New", version = 7)) { port, _ ->
            assertEquals(7, HandshakeProbe.query("127.0.0.1", port, 2000)!!.protocolVersion)
        }
    }

    @Test fun rejectsForeignServices() {
        withServer(ack("x", magic = 0x41424344)) { port, _ -> assertNull(HandshakeProbe.query("127.0.0.1", port, 2000)) }
        withServer(ack("x", type = 9)) { port, _ -> assertNull(HandshakeProbe.query("127.0.0.1", port, 2000)) }
        withServer(null) { port, _ -> assertNull(HandshakeProbe.query("127.0.0.1", port, 800)) } // closes without reply
    }

    @Test fun reachabilityCheck() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        assertTrue(HandshakeProbe.isReachable("127.0.0.1", port, 1000))
        server.close()
        assertFalse(HandshakeProbe.isReachable("127.0.0.1", port, 1000))
        assertNull(HandshakeProbe.query("127.0.0.1", port, 500))
    }
}
