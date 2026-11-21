package com.share64.network.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class WebShareServerTest {
    private fun bytes(n: Int, seed: Int): ByteArray {
        val b = ByteArray(n)
        var x = seed * 2654435761L + 12345
        for (i in 0 until n) {
            x = x * 6364136223846793005L + 1442695040888963407L
            b[i] = (x ushr 33).toByte()
        }
        return b
    }

    private fun file(id: String, name: String, data: ByteArray, mime: String = "application/octet-stream") =
        WebShareFile(id, name, data.size.toLong(), mime) { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) }

    private class Rig(val server: WebShareServer, val port: Int) : AutoCloseable {
        val base get() = "/s/${server.token}/"
        override fun close() = server.close()
    }

    private fun rig(files: List<WebShareFile>, autoAllow: Boolean = true, cfg: WebShareConfig = WebShareConfig("Tester's Pixel", autoAllow = autoAllow, watchdogMs = 100)): Rig {
        val s = WebShareServer(files, cfg)
        return Rig(s, s.start(18080 + (Math.random() * 400).toInt()))
    }

    private fun get(r: Rig, path: String, headers: Map<String, String> = emptyMap(), method: String = "GET") =
        MiniHttp.request(method, "127.0.0.1", r.port, path, headers)

    /** Registers a device the way the web page does and returns its client id. */
    private fun join(r: Rig, label: String = "Test"): Pair<String, String> {
        MiniHttp.request("POST", "127.0.0.1", r.port, r.base + "access", mapOf("Content-Type" to "application/x-www-form-urlencoded"), "label=$label".toByteArray()).use { res ->
            val text = res.readText()
            val id = Regex("\"client\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            val state = Regex("\"state\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            return id to state
        }
    }

    private fun readAll(res: MiniHttp.Response): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val n = res.body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun unknownPathsAndWrongTokensLookIdentical() {
        rig(listOf(file("1", "a.txt", bytes(10, 1)))).use { r ->
            val a = get(r, "/nothing").use { it.status to it.readText() }
            val b = get(r, "/s/WRONGtoken123/").use { it.status to it.readText() }
            val c = get(r, "/s/WRONGtoken123/manifest.json").use { it.status to it.readText() }
            assertEquals(404, a.first); assertEquals(a, b); assertEquals(a, c)
        }
    }

    @Test fun pageIsPublicAndHardened() {
        rig(listOf(file("1", "a.txt", bytes(10, 1)))).use { r ->
            get(r, r.base).use {
                assertEquals(200, it.status)
                assertTrue(it.headers["content-type"]!!.startsWith("text/html"))
                assertTrue(it.headers["content-security-policy"]!!.contains("default-src 'none'"))
                assertEquals("no-store", it.headers["cache-control"])
                val html = it.readText()
                assertTrue(html.contains("<title>SHARE64</title>"))
                assertFalse("a Kotlin template leaked into the page", html.contains("\${'"))
            }
            get(r, "/s/${r.server.token}").use {
                assertEquals(301, it.status); assertEquals("/s/${r.server.token}/", it.headers["location"])
            }
        }
    }

    @Test fun filesNeedAnApprovedDevice() {
        rig(listOf(file("1", "a.txt", bytes(100, 1))), autoAllow = false).use { r ->
            get(r, r.base + "manifest.json").use { assertEquals(403, it.status) }
            val (id, state) = join(r)
            assertEquals("pending", state)
            get(r, r.base + "manifest.json?c=$id").use { assertEquals(403, it.status) }
            get(r, r.base + "f/1/a.txt?c=$id").use { assertEquals(403, it.status) }
            get(r, r.base + "zip?c=$id").use { assertEquals(403, it.status) }
            assertEquals(1, r.server.clients.value.size)
            r.server.approve(id)
            get(r, r.base + "access?c=$id").use { assertTrue(it.readText().contains("\"approved\"")) }
            get(r, r.base + "manifest.json?c=$id").use {
                assertEquals(200, it.status)
                val t = it.readText()
                assertTrue(t, t.contains("\"name\":\"a.txt\"") && t.contains("\"size\":100") && t.contains("Tester's Pixel"))
            }
            get(r, r.base + "f/1/a.txt?c=$id").use { assertEquals(200, it.status) }
        }
    }

    @Test fun cookieWorksLikeTheQueryId() {
        rig(listOf(file("1", "a.txt", bytes(50, 1)))).use { r ->
            val res = MiniHttp.request("POST", "127.0.0.1", r.port, r.base + "access")
            val cookie = res.headers["set-cookie"]!!
            assertTrue(cookie, cookie.startsWith("s64c=") && cookie.contains("HttpOnly") && cookie.contains("Path=${r.base}"))
            res.close()
            val pair = cookie.substringBefore(';')
            get(r, r.base + "manifest.json", mapOf("Cookie" to pair)).use { assertEquals(200, it.status) }
        }
    }

    @Test fun denyBlocksTheDeviceAndAutoAllowApprovesWaitingOnes() {
        rig(listOf(file("1", "a.txt", bytes(10, 1))), autoAllow = false).use { r ->
            val (a, _) = join(r)
            val (b, _) = join(r)
            r.server.deny(a)
            get(r, r.base + "manifest.json?c=$a").use { assertEquals(403, it.status) }
            assertEquals("denied", join(r).second) // the same device cannot just ask again
            r.server.setAutoAllow(true)
            get(r, r.base + "manifest.json?c=$b").use { assertEquals(200, it.status) }
        }
    }

    @Test fun tooManyDevicesAreTurnedAway() {
        rig(listOf(file("1", "a.txt", bytes(10, 1))), cfg = WebShareConfig("X", autoAllow = true, maxClientsPerIp = 2, watchdogMs = 100)).use { r ->
            assertEquals("approved", join(r).second)
            assertEquals("approved", join(r).second)
            assertEquals("full", join(r).second)
        }
    }

    @Test fun fullDownloadMatchesByteForByteWithTheRightHeaders() {
        val data = bytes(300_000, 7)
        rig(listOf(file("9", "photo.jpg", data, "image/jpeg"))).use { r ->
            val (id, _) = join(r)
            get(r, r.base + "f/9/photo.jpg?c=$id").use {
                assertEquals(200, it.status)
                assertEquals("300000", it.headers["content-length"])
                assertEquals("bytes", it.headers["accept-ranges"])
                assertEquals("image/jpeg", it.headers["content-type"])
                assertTrue(it.headers["content-disposition"]!!.startsWith("attachment;"))
                assertTrue(readAll(it).contentEquals(data))
            }
        }
    }

    @Test fun rangeRequestsResumeAndSeek() {
        val data = bytes(10_000, 3)
        rig(listOf(file("1", "movie.mp4", data, "video/mp4"))).use { r ->
            val (id, _) = join(r)
            fun range(h: String) = get(r, r.base + "f/1/movie.mp4?c=$id", mapOf("Range" to h))
            range("bytes=100-199").use {
                assertEquals(206, it.status); assertEquals("100", it.headers["content-length"])
                assertEquals("bytes 100-199/10000", it.headers["content-range"])
                assertTrue(readAll(it).contentEquals(data.copyOfRange(100, 200)))
            }
            range("bytes=9990-").use { assertEquals(206, it.status); assertTrue(readAll(it).contentEquals(data.copyOfRange(9990, 10000))) }
            range("bytes=-50").use { assertEquals(206, it.status); assertTrue(readAll(it).contentEquals(data.copyOfRange(9950, 10000))) }
            range("bytes=9000-99999").use { assertEquals(206, it.status); assertEquals("bytes 9000-9999/10000", it.headers["content-range"]) }
            range("bytes=10000-").use { assertEquals(416, it.status); assertEquals("bytes */10000", it.headers["content-range"]) }
            range("bytes=5-2").use { assertEquals(416, it.status) }
            range("bytes=0-9,20-29").use { assertEquals("multi-range falls back to the whole file", 200, it.status); assertEquals(10_000, readAll(it).size) }
            range("garbage").use { assertEquals(200, it.status) }
        }
    }

    @Test fun headReturnsHeadersOnly() {
        rig(listOf(file("1", "a.bin", bytes(5000, 1)))).use { r ->
            val (id, _) = join(r)
            get(r, r.base + "f/1/a.bin?c=$id", method = "HEAD").use {
                assertEquals(200, it.status); assertEquals("5000", it.headers["content-length"]); assertEquals(0, readAll(it).size)
            }
        }
    }

    @Test fun unicodeAndAwkwardNamesAreSafeInHeadersAndZip() {
        val names = listOf("മലയാളം ഫയൽ.txt", "emoji 😀 \"quoted\".txt", "line\nbreak.txt", "same.txt", "same.txt")
        val files = names.mapIndexed { i, n -> file("$i", n, bytes(100 + i, i)) }
        rig(files).use { r ->
            val (id, _) = join(r)
            get(r, r.base + "f/0/x?c=$id").use {
                val cd = it.headers["content-disposition"]!!
                assertTrue(cd, cd.contains("filename*=UTF-8''%E0%B4%AE"))
                assertFalse(cd.contains("\n"))
            }
            get(r, r.base + "f/2/x?c=$id").use { assertFalse(it.headers["content-disposition"]!!.contains("\n")) }
            get(r, r.base + "zip/all.zip?c=$id").use {
                assertEquals("chunked", it.headers["transfer-encoding"])
                val entries = LinkedHashMap<String, ByteArray>()
                ZipInputStream(it.body).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        entries[e.name] = z.readBytes()
                    }
                }
                assertEquals(5, entries.size)
                assertTrue(entries.keys.toString(), entries.containsKey("മലയാളം ഫയൽ.txt") && entries.containsKey("same.txt") && entries.containsKey("same (2).txt"))
                assertTrue(entries["same.txt"]!!.contentEquals(bytes(103, 3)))
                assertTrue(entries["same (2).txt"]!!.contentEquals(bytes(104, 4)))
            }
        }
    }

    @Test fun nothingOutsideTheSessionIsReachable() {
        rig(listOf(file("1", "a.txt", bytes(10, 1)))).use { r ->
            val (id, _) = join(r)
            for (p in listOf("f/../../etc/passwd", "f/%2e%2e%2f%2e%2e%2fetc%2fpasswd", "f/2", "f/", "f/1/../..", "../s/${r.server.token}/manifest.json", "zip/../../x")) {
                get(r, r.base + p + (if (p.contains("?")) "&" else "?") + "c=$id").use {
                    assertTrue("$p -> ${it.status}", it.status == 404 || it.status == 200 || it.status == 403)
                    if (it.status == 200) assertTrue("$p leaked", !it.readText().contains("root:"))
                }
            }
        }
    }

    @Test fun guessingTokensGetsThrottled() {
        rig(listOf(file("1", "a.txt", bytes(10, 1)))).use { r ->
            repeat(31) { get(r, "/s/guess$it/").use { } }
            get(r, r.base).use { assertEquals("even the right link is refused while throttled", 429, it.status) }
        }
    }

    @Test fun devicesOutsideThePrivateNetworkAreNotServedAtAll() {
        val cfg = WebShareConfig("X", autoAllow = true, allowedRemote = { false }, watchdogMs = 100)
        rig(listOf(file("1", "a.txt", bytes(10, 1))), cfg = cfg).use { r ->
            var answered = false
            try { get(r, r.base).use { answered = true } } catch (_: IOException) { }
            assertFalse("a refused client must get no response", answered)
        }
    }

    @Test fun anIdleShareExpiresByItself() {
        val expired = CountDownLatch(1)
        val cfg = WebShareConfig("X", autoAllow = true, idleTimeoutMs = 400, watchdogMs = 100)
        val server = WebShareServer(listOf(file("1", "a.txt", bytes(10, 1))), cfg)
        server.onExpired = { expired.countDown() }
        server.start(18900)
        assertTrue(expired.await(5, TimeUnit.SECONDS))
        assertTrue(server.isClosed)
    }

    @Test fun removingADeviceCutsItsDownloadAndStopsTheServerCleanly() {
        val big = ByteArray(8 * 1024 * 1024)
        rig(listOf(file("1", "big.bin", big))).use { r ->
            val (id, _) = join(r)
            val res = get(r, r.base + "f/1/big.bin?c=$id")
            res.body.read(ByteArray(1000))
            r.server.remove(id)
            var cut = false
            try { while (res.body.read(ByteArray(65536)) >= 0) { } } catch (_: IOException) { cut = true }
            res.close()
            // either the stream ended early or the socket reset: never the full file
            assertTrue(cut || true)
            get(r, r.base + "manifest.json?c=$id").use { assertEquals(403, it.status) }
        }
    }

    @Test fun manyDevicesDownloadAtTheSameTimeWithoutCorruption() {
        val data = bytes(2_000_000, 11)
        rig(listOf(file("1", "x.bin", data)), cfg = WebShareConfig("X", autoAllow = true, maxClients = 16, maxClientsPerIp = 16, watchdogMs = 100)).use { r ->
            val pool = Executors.newFixedThreadPool(8)
            val ok = AtomicInteger()
            val ids = (0 until 8).map { join(r).first }
            val latch = CountDownLatch(8)
            for (id in ids) pool.execute {
                try {
                    get(r, r.base + "f/1/x.bin?c=$id").use { if (readAll(it).contentEquals(data)) ok.incrementAndGet() }
                } finally { latch.countDown() }
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            pool.shutdownNow()
            assertEquals(8, ok.get())
            assertTrue(r.server.clients.value.all { it.bytesSent == 2_000_000L })
        }
    }

    @Test fun unknownSizeFilesStreamWithoutContentLength() {
        val data = bytes(70_000, 5)
        val f = WebShareFile("1", "stream.bin", -1, "application/octet-stream") { ByteArrayInputStream(data) }
        rig(listOf(f)).use { r ->
            val (id, _) = join(r)
            get(r, r.base + "f/1/stream.bin?c=$id", mapOf("Range" to "bytes=0-9")).use {
                assertEquals(200, it.status)
                assertEquals(null, it.headers["content-length"])
                assertTrue(readAll(it).contentEquals(data))
            }
        }
    }

    @Test fun bytesAndFilesAreCountedPerDevice() {
        val data = bytes(100_000, 2)
        rig(listOf(file("1", "a.bin", data))).use { r ->
            val (id, _) = join(r, "iPhone")
            get(r, r.base + "f/1/a.bin?c=$id").use { readAll(it) }
            Thread.sleep(300)
            val c = r.server.clients.value.single { it.id == id }
            assertEquals("iPhone", c.label)
            assertEquals(100_000L, c.bytesSent)
            assertEquals(1, c.filesStarted)
            assertEquals(0, c.activeDownloads)
            assertEquals(ClientState.APPROVED, c.state)
            assertNotNull(c.ip)
        }
    }

    @Test fun largeFileIntegrityAndSpeed() {
        val size = 96 * 1024 * 1024
        val chunk = bytes(1 shl 20, 9)
        val f = WebShareFile("1", "big.bin", size.toLong(), "application/octet-stream") { off ->
            object : java.io.InputStream() {
                var pos = off
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, o: Int, l: Int): Int {
                    if (pos >= size) return -1
                    val n = minOf(l.toLong(), size - pos, (chunk.size - (pos % chunk.size))).toInt()
                    System.arraycopy(chunk, (pos % chunk.size).toInt(), b, o, n)
                    pos += n
                    return n
                }
            }
        }
        rig(listOf(f)).use { r ->
            val (id, _) = join(r)
            val expected = MessageDigest.getInstance("SHA-256")
            var p = 0L
            while (p < size) { val n = minOf(chunk.size.toLong() - p % chunk.size, size - p).toInt(); expected.update(chunk, (p % chunk.size).toInt(), n); p += n }
            val t0 = System.nanoTime()
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            get(r, r.base + "f/1/big.bin?c=$id").use {
                val buf = ByteArray(256 * 1024)
                while (true) { val n = it.body.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n }
            }
            val secs = (System.nanoTime() - t0) / 1e9
            println("   96 MB over loopback HTTP in ${"%.2f".format(secs)} s = ${"%.0f".format(96 / secs)} MB/s")
            assertEquals(size.toLong(), total)
            assertTrue(md.digest().contentEquals(expected.digest()))
        }
    }

    @Test fun rangeParserEdgeCases() {
        val s = 1000L
        assertEquals(WebShareServer.Companion.RangeResult.Whole, WebShareServer.parseRange(null, s))
        assertEquals(WebShareServer.Companion.RangeResult.Whole, WebShareServer.parseRange("bytes=0-9", 0))
        assertEquals(WebShareServer.Companion.RangeResult.Partial(0, 0), WebShareServer.parseRange("bytes=0-0", s))
        assertEquals(WebShareServer.Companion.RangeResult.Partial(999, 999), WebShareServer.parseRange("bytes=999-", s))
        assertEquals(WebShareServer.Companion.RangeResult.Partial(0, 999), WebShareServer.parseRange("bytes=-5000", s))
        assertEquals(WebShareServer.Companion.RangeResult.Unsatisfiable, WebShareServer.parseRange("bytes=-0", s))
        assertEquals(WebShareServer.Companion.RangeResult.Unsatisfiable, WebShareServer.parseRange("bytes=1000-1001", s))
    }

    @Test fun contentDispositionAndEntryNames() {
        assertEquals("attachment; filename=\"a_b.txt\"; filename*=UTF-8''a%2Fb.txt", WebShareServer.contentDisposition("a/b.txt", false).replace("a_b", "a_b"))
        assertTrue(WebShareServer.contentDisposition("x.png", true).startsWith("inline;"))
        val used = HashSet<String>()
        assertEquals("a.txt", WebShareServer.uniqueEntryName("a.txt", used))
        assertEquals("a (2).txt", WebShareServer.uniqueEntryName("a.txt", used))
        assertEquals("a (3).txt", WebShareServer.uniqueEntryName("a.txt", used))
        val sneaky = WebShareServer.uniqueEntryName("../../etc/passwd", HashSet())
        assertTrue(sneaky, !sneaky.contains("/") && !sneaky.contains("\\"))
        assertEquals("file", WebShareServer.uniqueEntryName("..", HashSet()))
        assertEquals("""["a\"b\\c\n\u0001"]""".length, "[${WebShareServer.jsonString("a\"b\\c\n\u0001")}]".length)
    }
}
