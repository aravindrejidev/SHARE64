package com.share64.network

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny client for the SHARE64 wire protocol v1 handshake (Hello -> HelloAck). Used to verify that a
 * manually typed address really is a SHARE64 device and to read its name. Pure JVM sockets.
 */
object HandshakeProbe {
    data class Result(val deviceName: String, val protocolVersion: Int)

    private const val MAGIC = 0x34365348
    private const val MSG_HELLO = 1
    private const val MSG_HELLO_ACK = 2
    private const val HEADER = 16
    private const val MAX_CONTROL = 2048

    /** Plain TCP connect, used for liveness checks. */
    fun isReachable(host: String, port: Int, timeoutMs: Int = 1_200): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Returns null when nothing answers or the peer does not speak SHARE64. */
    fun query(host: String, port: Int, timeoutMs: Int = 2_500, ownName: String = "SHARE64"): Result? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val name = PeerTxt.sanitizeName(ownName).toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(4 + 2 + name.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putShort(name.size.toShort()).put(name).array()
            val hello = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).put(PeerTxt.PROTOCOL_VERSION.toByte()).put(MSG_HELLO.toByte()).putShort(0)
                .putInt(payload.size).putInt(0).put(payload).array()
            s.getOutputStream().apply { write(hello); flush() }

            val input = DataInputStream(s.getInputStream())
            val head = ByteArray(HEADER).also { input.readFully(it) }
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (h.getInt() != MAGIC) return null
            val version = h.get().toInt() and 0xFF
            val type = h.get().toInt() and 0xFF
            h.getShort() // flags
            val length = h.getInt()
            if (type != MSG_HELLO_ACK || length < 6 || length > MAX_CONTROL) return null
            val body = ByteArray(length).also { input.readFully(it) }
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            b.getInt() // capabilities
            val n = b.getShort().toInt() and 0xFFFF
            if (n > b.remaining()) return null
            val raw = ByteArray(n).also { b.get(it) }
            Result(PeerTxt.sanitizeName(String(raw, Charsets.UTF_8)), version)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
