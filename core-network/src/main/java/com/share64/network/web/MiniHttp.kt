package com.share64.network.web

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A very small HTTP/1.1 client over a plain socket. It exists because Android blocks cleartext
 * HTTP for the platform's own HTTP classes, and a LAN share is always plain HTTP; a raw socket
 * keeps the app's strict cleartext policy intact everywhere else.
 */
object MiniHttp {
    class Response(
        val status: Int,
        /** Header names in lower case. */
        val headers: Map<String, String>,
        val body: InputStream,
        private val socket: Socket,
    ) : Closeable {
        val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: -1L
        override fun close() {
            try { socket.close() } catch (_: IOException) {}
        }

        fun readText(limit: Int = 1 shl 20): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = body.read(buf)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("response too large")
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    /** Sends one request and returns the response with the body still unread. The caller closes it. */
    fun request(
        method: String,
        host: String,
        port: Int,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 4_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(host).append(':').append(port).append("\r\n")
            head.append("Connection: close\r\n")
            for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()

            val input = socket.getInputStream().buffered(16 * 1024)
            val statusLine = readLine(input) ?: throw IOException("no response")
            val parts = statusLine.split(' ', limit = 3)
            val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
            val map = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("truncated headers")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val chunked = map["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            val stream = when {
                method == "HEAD" || status == 204 || status == 304 -> InputStream.nullInputStream()
                chunked -> ChunkedInput(input)
                map["content-length"] != null -> LimitedInput(input, map["content-length"]!!.toLongOrNull() ?: 0L)
                else -> input
            }
            return Response(status, map, stream, socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16 * 1024) throw IOException("header line too long")
            sb.append(c.toChar())
        }
    }

    private class LimitedInput(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val c = inner.read()
            if (c >= 0) left--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private class ChunkedInput(private val inner: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun nextChunk() {
            val line = readLine(inner) ?: throw IOException("truncated chunked body")
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0L) {
                done = true
                return
            }
            left = size
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0L) {
                nextChunk()
                if (done) return -1
            }
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated chunk")
            left -= n
            if (left == 0L) readLine(inner) // the CRLF after each chunk
            return n
        }
    }
}
