package com.share64.network.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file offered by a session. [opener] returns a stream positioned at the given byte offset. */
class WebShareFile(
    val id: String,
    val name: String,
    /** Size in bytes, or -1 when the provider cannot tell (then there is no Content-Length and no resume). */
    val size: Long,
    val mime: String,
    private val opener: (offset: Long) -> InputStream,
) {
    fun open(offset: Long = 0): InputStream = opener(offset)
}

enum class ClientState { PENDING, APPROVED, DENIED }

/** A browser (or SHARE64 app) that asked for access, as the host sees it. */
data class WebClient(
    val id: String,
    val label: String,
    val ip: String,
    val state: ClientState,
    val bytesSent: Long,
    val activeDownloads: Int,
    val filesStarted: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

class WebShareConfig(
    /** Shown on the web page ("Aravind's Pixel wants to share 3 files"). */
    val senderName: String,
    /** Skip the host's "Allow?" prompt for new devices. */
    val autoAllow: Boolean = false,
    /** The session ends by itself after this long without any request or running download; 0 = never. */
    val idleTimeoutMs: Long = 30 * 60_000L,
    val maxClients: Int = 8,
    val maxClientsPerIp: Int = 3,
    val maxConnections: Int = 32,
    /** How often the idle timeout is checked (tests shorten it). */
    val watchdogMs: Long = 5_000L,
    /** Only devices passing this check are served at all (default: this device and private networks). */
    val allowedRemote: (InetAddress) -> Boolean = ::isLocalNetworkAddress,
)

/** Loopback, link-local, RFC 1918 and IPv6 unique-local addresses. */
fun isLocalNetworkAddress(a: InetAddress): Boolean {
    if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
    if (a is Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC
    return false
}

/**
 * "Share with anyone": a tiny HTTP server that lets any browser download the files of one session.
 *
 * - Everything lives under `/s/<token>/`; a wrong token looks exactly like a missing page.
 * - The page itself is public, but files and the list need a device that the host approved (or
 *   auto-allow). Approval is bound to a cookie / `c=` id handed out by `POST access`.
 * - Only the files of the session are reachable, by opaque id: there is no path to traverse.
 * - Downloads support Range (resume, video seeking); "all as ZIP" is streamed without temp files.
 * - Only private-network clients are served, and clients that keep guessing tokens are throttled.
 */
class WebShareServer(
    private val files: List<WebShareFile>,
    private val config: WebShareConfig,
) : Closeable {
    private val random = SecureRandom()

    val token: String = randomToken(12)

    @Volatile
    var port: Int = 0
        private set

    /** Called once when the session ends because of the idle timeout. */
    @Volatile
    var onExpired: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "share64-web").apply { isDaemon = true } }
    private val gate = Semaphore(config.maxConnections)
    private val openSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var closed = false

    private val lock = Any()
    private val clientMap = LinkedHashMap<String, MutableClient>()
    private val deniedIps = HashSet<String>()
    private val failures = ConcurrentHashMap<String, FailureCounter>()
    private val lastPublishMs = AtomicLong(0)

    @Volatile
    private var lastActivityMs = System.currentTimeMillis()

    private val _clients = MutableStateFlow<List<WebClient>>(emptyList())
    val clients: StateFlow<List<WebClient>> = _clients.asStateFlow()

    private val _autoAllow = MutableStateFlow(config.autoAllow)
    val autoAllow: StateFlow<Boolean> = _autoAllow.asStateFlow()

    private val pageBytes: ByteArray = WebSharePage.html().toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ lifecycle

    /** Starts listening on the first free port from [preferredPort] (or any free port) and returns it. */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        check(serverSocket == null) { "already started" }
        var socket: ServerSocket? = null
        if (preferredPort > 0) {
            for (p in preferredPort until preferredPort + 10) {
                try {
                    socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(p), 50)
                    }
                    break
                } catch (_: IOException) {
                    // taken: try the next one
                }
            }
        }
        if (socket == null) {
            socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), 50)
            }
        }
        serverSocket = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "share64-web-accept").apply { isDaemon = true }.start()
        Thread({ watchdog() }, "share64-web-watchdog").apply { isDaemon = true }.start()
        return port
    }

    fun url(host: String): String = WebShareLink.url(host, port, token)

    override fun close() {
        if (closed) return
        closed = true
        try { serverSocket?.close() } catch (_: IOException) {}
        for (s in openSockets.toList()) try { s.close() } catch (_: IOException) {}
        pool.shutdownNow()
        synchronized(lock) {
            clientMap.clear()
            _clients.value = emptyList()
        }
    }

    val isClosed: Boolean get() = closed

    // ------------------------------------------------------------------ host controls

    fun approve(clientId: String) = changeState(clientId, ClientState.APPROVED)

    fun deny(clientId: String) = changeState(clientId, ClientState.DENIED)

    /** Disconnects a device and forgets it; it has to ask again (unless it was denied). */
    fun remove(clientId: String) {
        synchronized(lock) {
            val c = clientMap.remove(clientId) ?: return
            c.closeSockets()
        }
        publish()
    }

    fun setAutoAllow(on: Boolean) {
        _autoAllow.value = on
        if (on) {
            synchronized(lock) { clientMap.values.filter { it.state == ClientState.PENDING }.forEach { it.state = ClientState.APPROVED } }
            publish()
        }
    }

    private fun changeState(clientId: String, state: ClientState) {
        synchronized(lock) {
            val c = clientMap[clientId] ?: return
            c.state = state
            if (state == ClientState.DENIED) {
                deniedIps += c.ip
                c.closeSockets()
            }
        }
        publish()
    }

    // ------------------------------------------------------------------ accept loop

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val s = try {
                socket.accept()
            } catch (_: IOException) {
                if (closed) return
                continue
            }
            if (!gate.tryAcquire()) {
                try { s.close() } catch (_: IOException) {}
                continue
            }
            openSockets += s
            try {
                pool.execute {
                    try {
                        handle(s)
                    } catch (_: Throwable) {
                        // a broken client must never take the server down
                    } finally {
                        openSockets -= s
                        try { s.close() } catch (_: IOException) {}
                        gate.release()
                    }
                }
            } catch (_: Exception) {
                openSockets -= s
                try { s.close() } catch (_: IOException) {}
                gate.release()
            }
        }
    }

    private fun watchdog() {
        while (!closed) {
            try {
                Thread.sleep(config.watchdogMs)
            } catch (_: InterruptedException) {
                return
            }
            val idle = config.idleTimeoutMs
            if (idle > 0 && System.currentTimeMillis() - lastActivityMs > idle && activeDownloads() == 0) {
                val cb = onExpired
                close()
                cb?.invoke()
                return
            }
        }
    }

    private fun activeDownloads(): Int = synchronized(lock) { clientMap.values.sumOf { it.active.get() } }

    // ------------------------------------------------------------------ request handling

    private fun handle(socket: Socket) {
        val remote = (socket.remoteSocketAddress as? InetSocketAddress)?.address ?: return
        if (!config.allowedRemote(remote)) return
        val ip = remote.hostAddress ?: return
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = HEADER_TIMEOUT_MS
        val input = socket.getInputStream().buffered(8192)
        val out = socket.getOutputStream()
        val req = try {
            HttpRequest.read(input)
        } catch (e: HttpFailure) {
            respondText(out, e.status, e.message ?: "Bad request")
            return
        } catch (_: IOException) {
            return
        } ?: return
        touch()
        if (isBlocked(ip)) {
            respondText(out, 429, "Too many requests", listOf("Retry-After" to "60"))
            return
        }
        route(req, ip, socket, out)
    }

    private fun route(req: HttpRequest, ip: String, socket: Socket, out: OutputStream) {
        val segments = req.path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "s" || !tokenMatches(segments[1])) {
            recordFailure(ip)
            respondText(out, 404, "Not found")
            return
        }
        val rest = segments.drop(2)
        val method = req.method
        if (method != "GET" && method != "HEAD" && method != "POST") {
            respondText(out, 405, "Method not allowed", listOf("Allow" to "GET, HEAD, POST"))
            return
        }
        when {
            rest.isEmpty() || (rest.size == 1 && rest[0] == "index.html") -> {
                if (rest.isEmpty() && !req.path.endsWith("/")) {
                    // the page's relative links need the trailing slash
                    respondText(out, 301, "Moved", listOf("Location" to "/s/$token/"))
                } else if (method == "POST") {
                    respondText(out, 405, "Method not allowed", listOf("Allow" to "GET, HEAD"))
                } else {
                    respondBytes(out, 200, "text/html; charset=utf-8", pageBytes, PAGE_HEADERS, head = method == "HEAD")
                }
            }
            rest.size == 1 && rest[0] == "access" -> access(req, ip, out)
            rest.size == 1 && rest[0] == "manifest.json" -> manifest(req, out)
            rest[0] == "f" && rest.size >= 2 -> download(req, rest[1], socket, out)
            rest[0] == "zip" -> zip(req, socket, out)
            else -> respondText(out, 404, "Not found")
        }
    }

    // ------------------------------------------------------------------ access / approval

    private sealed interface Resolved {
        data class Found(val client: MutableClient) : Resolved
        data object Full : Resolved
        data object Denied : Resolved
        data object Unknown : Resolved
    }

    private fun resolveClient(req: HttpRequest, ip: String, create: Boolean, label: String = "Browser"): Resolved {
        val id = req.cookie(COOKIE) ?: req.query["c"]
        synchronized(lock) {
            if (id != null) {
                val existing = clientMap[id]
                if (existing != null) {
                    existing.lastSeen = System.currentTimeMillis()
                    return Resolved.Found(existing)
                }
            }
            if (!create) return Resolved.Unknown
            if (ip in deniedIps) return Resolved.Denied
            if (clientMap.size >= config.maxClients || clientMap.values.count { it.ip == ip } >= config.maxClientsPerIp) return Resolved.Full
            val now = System.currentTimeMillis()
            val c = MutableClient(randomToken(16), label, ip, if (_autoAllow.value) ClientState.APPROVED else ClientState.PENDING, now)
            clientMap[c.id] = c
            publishLocked()
            return Resolved.Found(c)
        }
    }

    private fun access(req: HttpRequest, ip: String, out: OutputStream) {
        val post = req.method == "POST"
        val label = if (post) sanitizeLabel(req.formField("label")) else "Browser"
        val r = resolveClient(req, ip, create = post, label = label)
        val sender = jsonString(config.senderName)
        when (r) {
            is Resolved.Found -> {
                if (post) publish() // a new device (or a returning one) just knocked
                val c = r.client
                val state = c.state.name.lowercase()
                val headers = ArrayList<Pair<String, String>>(JSON_HEADERS)
                if (req.cookie(COOKIE) != c.id) {
                    headers += "Set-Cookie" to "$COOKIE=${c.id}; Path=/s/$token/; Max-Age=86400; HttpOnly; SameSite=Lax"
                }
                respondJson(out, 200, """{"app":"share64","v":1,"sender":$sender,"state":"$state","client":"${c.id}"}""", headers, head = req.method == "HEAD")
            }
            Resolved.Full -> respondJson(out, 200, """{"app":"share64","v":1,"sender":$sender,"state":"full"}""")
            Resolved.Denied -> respondJson(out, 200, """{"app":"share64","v":1,"sender":$sender,"state":"denied"}""")
            Resolved.Unknown -> respondJson(out, 200, """{"app":"share64","v":1,"sender":$sender,"state":"none"}""")
        }
    }

    private fun findClient(req: HttpRequest): MutableClient? {
        val id = req.cookie(COOKIE) ?: req.query["c"] ?: return null
        synchronized(lock) {
            val c = clientMap[id] ?: return null
            c.lastSeen = System.currentTimeMillis()
            return c
        }
    }

    /** The approved device behind this request, or null after sending the refusal. */
    private fun approvedClient(req: HttpRequest, out: OutputStream): MutableClient? {
        val c = findClient(req)
        if (c == null) {
            respondJson(out, 403, """{"error":"unknown_client"}""")
            return null
        }
        if (c.state != ClientState.APPROVED) {
            respondJson(out, 403, """{"error":"not_approved","state":"${c.state.name.lowercase()}"}""")
            return null
        }
        return c
    }

    private fun manifest(req: HttpRequest, out: OutputStream) {
        val c = approvedClient(req, out) ?: return
        c.lastSeen = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("""{"app":"share64","v":1,"sender":""").append(jsonString(config.senderName)).append(""","zip":true,"files":[""")
        files.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("""{"id":""").append(jsonString(f.id)).append(""","name":""").append(jsonString(f.name))
                .append(""","size":""").append(f.size).append(""","mime":""").append(jsonString(f.mime)).append('}')
        }
        sb.append("]}")
        respondJson(out, 200, sb.toString(), head = req.method == "HEAD")
    }

    // ------------------------------------------------------------------ downloads

    private fun download(req: HttpRequest, id: String, socket: Socket, out: OutputStream) {
        val c = approvedClient(req, out) ?: return
        val file = files.firstOrNull { it.id == id }
        if (file == null) {
            respondText(out, 404, "Not found")
            return
        }
        val size = file.size
        var status = 200
        var start = 0L
        var length = size
        if (size >= 0) {
            when (val r = parseRange(req.headers["range"], size)) {
                RangeResult.Whole -> Unit
                RangeResult.Unsatisfiable -> {
                    respondText(out, 416, "Range not satisfiable", listOf("Content-Range" to "bytes */$size"))
                    return
                }
                is RangeResult.Partial -> {
                    status = 206
                    start = r.start
                    length = r.end - r.start + 1
                }
            }
        }
        val inline = req.query["inline"] == "1" && isInlineSafe(file.mime)
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to (if (inline) file.mime else downloadMime(file.mime))
        headers += "Content-Disposition" to contentDisposition(file.name, inline)
        headers += "Cache-Control" to "no-store"
        headers += "X-Content-Type-Options" to "nosniff"
        if (inline) headers += "Content-Security-Policy" to "sandbox"
        if (size >= 0) {
            headers += "Accept-Ranges" to "bytes"
            headers += "Content-Length" to length.toString()
            if (status == 206) headers += "Content-Range" to "bytes $start-${start + length - 1}/$size"
        }
        writeHead(out, status, headers)
        if (req.method == "HEAD") {
            out.flush()
            return
        }
        if (start == 0L) c.filesStarted.incrementAndGet()
        withActive(c, socket) {
            file.open(start).use { input -> copy(input, out, length, c) }
        }
        out.flush()
    }

    private fun zip(req: HttpRequest, socket: Socket, out: OutputStream) {
        val c = approvedClient(req, out) ?: return
        val headers = listOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to contentDisposition("share64-files.zip", false),
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Transfer-Encoding" to "chunked",
        )
        writeHead(out, 200, headers)
        if (req.method == "HEAD") {
            out.flush()
            return
        }
        c.filesStarted.addAndGet(files.size)
        withActive(c, socket) {
            val chunked = ChunkedOutputStream(out)
            val zip = ZipOutputStream(BufferedOutputStream(chunked, 64 * 1024))
            zip.setLevel(Deflater.NO_COMPRESSION) // already-compressed media gains nothing; keep the CPU free
            val used = HashSet<String>()
            for (f in files) {
                zip.putNextEntry(ZipEntry(uniqueEntryName(f.name, used)))
                f.open(0).use { input -> copy(input, zip, -1, c) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            chunked.finish()
        }
        out.flush()
    }

    private inline fun withActive(c: MutableClient, socket: Socket, block: () -> Unit) {
        c.active.incrementAndGet()
        c.sockets += socket
        publishThrottled(force = true)
        try {
            block()
        } finally {
            c.sockets -= socket
            c.active.decrementAndGet()
            publishThrottled(force = true)
        }
    }

    /** Copies up to [length] bytes (all when negative), counting what leaves the device. */
    private fun copy(input: InputStream, out: OutputStream, length: Long, c: MutableClient) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining != 0L) {
            val want = if (remaining < 0) buf.size else minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            c.bytesSent.addAndGet(n.toLong())
            if (remaining > 0) remaining -= n
            touch()
            publishThrottled()
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun publishThrottled(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastPublishMs.get()
        if (force || now - last >= PUBLISH_EVERY_MS) {
            lastPublishMs.set(now)
            publish()
        }
    }

    private fun publish() = synchronized(lock) { publishLocked() }

    private fun publishLocked() {
        if (closed) return
        _clients.value = clientMap.values.map { it.snapshot() }
    }

    private fun tokenMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    private fun recordFailure(ip: String) {
        failures.computeIfAbsent(ip) { FailureCounter() }.add(System.currentTimeMillis())
    }

    private fun isBlocked(ip: String): Boolean = failures[ip]?.isBlocked(System.currentTimeMillis()) == true

    private fun randomToken(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private class FailureCounter {
        private var windowStart = 0L
        private var count = 0
        private var blockedUntil = 0L

        @Synchronized
        fun add(now: Long) {
            if (now - windowStart > 60_000) {
                windowStart = now
                count = 0
            }
            if (++count > MAX_FAILURES_PER_MINUTE) blockedUntil = now + 60_000
        }

        @Synchronized
        fun isBlocked(now: Long) = now < blockedUntil
    }

    private class MutableClient(val id: String, val label: String, val ip: String, @Volatile var state: ClientState, val firstSeen: Long) {
        val bytesSent = AtomicLong()
        val active = AtomicInteger()
        val filesStarted = AtomicInteger()

        @Volatile
        var lastSeen = firstSeen
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

        fun closeSockets() {
            for (s in sockets.toList()) try { s.close() } catch (_: IOException) {}
        }

        fun snapshot() = WebClient(id, label, ip, state, bytesSent.get(), active.get(), filesStarted.get(), firstSeen, lastSeen)
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val COOKIE = "s64c"
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val PUBLISH_EVERY_MS = 250L
        private const val MAX_FAILURES_PER_MINUTE = 30

        private val JSON_HEADERS = listOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
        private val PAGE_HEADERS = listOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'",
        )

        // ---- response writing -------------------------------------------------------------

        internal fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            sb.append("Server: SHARE64\r\nConnection: close\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        }

        internal fun respondBytes(out: OutputStream, status: Int, type: String, body: ByteArray, extra: List<Pair<String, String>> = emptyList(), head: Boolean = false) {
            val headers = ArrayList<Pair<String, String>>()
            headers += "Content-Type" to type
            headers += "Content-Length" to body.size.toString()
            headers += extra
            writeHead(out, status, headers)
            if (!head) out.write(body)
            out.flush()
        }

        internal fun respondJson(out: OutputStream, status: Int, json: String, extra: List<Pair<String, String>> = JSON_HEADERS, head: Boolean = false) =
            respondBytes(out, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), extra.filter { !it.first.equals("Content-Type", true) }, head)

        internal fun respondText(out: OutputStream, status: Int, text: String, extra: List<Pair<String, String>> = emptyList()) =
            respondBytes(out, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), listOf("Cache-Control" to "no-store") + extra)

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Status"
        }

        // ---- helpers shared with tests ----------------------------------------------------

        sealed interface RangeResult {
            data object Whole : RangeResult
            data object Unsatisfiable : RangeResult
            data class Partial(val start: Long, val end: Long) : RangeResult
        }

        /** Single byte ranges only; anything else (multi-range, junk) is ignored and the whole file is sent. */
        fun parseRange(header: String?, size: Long): RangeResult {
            if (header == null || size <= 0) return RangeResult.Whole
            val h = header.trim()
            if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Whole
            val spec = h.substring(6).trim()
            if (spec.contains(',')) return RangeResult.Whole
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeResult.Whole
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) { // suffix: the last n bytes
                val n = b.toLongOrNull() ?: return RangeResult.Whole
                if (n <= 0) return RangeResult.Unsatisfiable
                return RangeResult.Partial(maxOf(0L, size - n), size - 1)
            }
            val start = a.toLongOrNull() ?: return RangeResult.Whole
            val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return RangeResult.Whole)
            if (start < 0 || start >= size || end < start) return RangeResult.Unsatisfiable
            return RangeResult.Partial(start, minOf(end, size - 1))
        }

        /** ASCII fallback plus the RFC 5987 UTF-8 form, so Malayalam and emoji names survive. */
        fun contentDisposition(name: String, inline: Boolean): String {
            val clean = name.filter { !it.isISOControl() }.ifBlank { "file" }
            val ascii = clean.map { if (it.code in 32..126 && it != '"' && it != '\\' && it != '%' && it != '/') it else '_' }.joinToString("")
            val utf8 = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
            return "${if (inline) "inline" else "attachment"}; filename=\"$ascii\"; filename*=UTF-8''$utf8"
        }

        /** Types a browser may show in place; everything else is always a download (no script from shared files). */
        fun isInlineSafe(mime: String): Boolean =
            mime.startsWith("image/") && !mime.contains("svg") || mime.startsWith("video/") || mime.startsWith("audio/")

        /** Downloads keep their type (iOS uses it to pick an app), except types a browser could run. */
        fun downloadMime(mime: String): String {
            val m = mime.lowercase()
            return if (m.isBlank() || m.contains("html") || m.contains("xml") || m.contains("svg") || m.contains("javascript")) "application/octet-stream" else mime
        }

        fun uniqueEntryName(name: String, used: MutableSet<String>): String {
            val clean = name.filter { !it.isISOControl() && it != '/' && it != '\\' }.trim().let { if (it.isBlank() || it == "." || it == "..") "file" else it }
            var candidate = clean
            var n = 2
            val dot = clean.lastIndexOf('.')
            val stem = if (dot > 0) clean.substring(0, dot) else clean
            val ext = if (dot > 0) clean.substring(dot) else ""
            while (!used.add(candidate)) candidate = "$stem ($n)$ext".also { n++ }
            return candidate
        }

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (ch in s) {
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch == '\n' -> sb.append("\\n")
                    ch == '\r' -> sb.append("\\r")
                    ch == '\t' -> sb.append("\\t")
                    ch.code < 0x20 || ch == '\u2028' || ch == '\u2029' -> sb.append("\\u%04x".format(ch.code))
                    else -> sb.append(ch)
                }
            }
            return sb.append('"').toString()
        }

        fun sanitizeLabel(raw: String?): String {
            val s = (raw ?: "").filter { !it.isISOControl() }.trim().take(40)
            return s.ifBlank { "Browser" }
        }
    }
}

/** Writes HTTP/1.1 chunked transfer encoding (for the ZIP, whose size is not known up front). */
internal class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        out.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(b, off, len)
        out.write(CRLF)
    }

    override fun flush() = out.flush()

    /** The terminating zero-length chunk. */
    fun finish() {
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private companion object {
        val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

internal class HttpFailure(val status: Int, message: String) : IOException(message)

/** One parsed request. Bodies are only read for small POSTs. */
internal class HttpRequest(
    val method: String,
    /** Percent-decoded path without the query. */
    val path: String,
    val query: Map<String, String>,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun cookie(name: String): String? {
        val raw = headers["cookie"] ?: return null
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
        }
        return null
    }

    fun formField(name: String): String? {
        if (body.isEmpty()) return null
        for (pair in String(body, Charsets.UTF_8).split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == name) return decode(pair.substring(eq + 1))
        }
        return null
    }

    companion object {
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 2048

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        /** Null when the peer closed without sending anything. */
        fun read(input: InputStream): HttpRequest? {
            val head = StringBuilder()
            var last4 = 0
            while (true) {
                val c = input.read()
                if (c < 0) return if (head.isEmpty()) null else throw HttpFailure(400, "Bad request")
                head.append(c.toChar())
                last4 = (last4 shl 8) or c
                if (last4 == 0x0D0A0D0A) break
                if (head.length > MAX_HEAD) throw HttpFailure(431, "Headers too large")
            }
            val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
            val first = lines.firstOrNull()?.split(' ') ?: throw HttpFailure(400, "Bad request")
            if (first.size < 3 || !first[2].startsWith("HTTP/")) throw HttpFailure(400, "Bad request")
            val method = first[0].uppercase()
            val target = first[1]
            val qIndex = target.indexOf('?')
            val rawPath = if (qIndex >= 0) target.substring(0, qIndex) else target
            val rawQuery = if (qIndex >= 0) target.substring(qIndex + 1) else ""
            if (!rawPath.startsWith("/")) throw HttpFailure(400, "Bad request")
            val path = decode(rawPath.replace("+", "%2B"))
            val query = HashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq > 0) query.putIfAbsent(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)))
                else query.putIfAbsent(decode(pair), "")
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) headers.putIfAbsent(line.substring(0, colon).trim().lowercase(), line.substring(colon + 1).trim())
            }
            var body = ByteArray(0)
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) throw HttpFailure(413, "Body too large")
            if (length > 0) {
                body = ByteArray(length)
                var off = 0
                while (off < length) {
                    val n = input.read(body, off, length - off)
                    if (n < 0) throw HttpFailure(400, "Truncated body")
                    off += n
                }
            }
            return HttpRequest(method, path, query, headers, body)
        }
    }
}
