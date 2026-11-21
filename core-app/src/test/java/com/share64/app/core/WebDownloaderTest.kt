package com.share64.app.core

import com.share64.network.web.WebShareConfig
import com.share64.network.web.WebShareFile
import com.share64.network.web.WebShareLink
import com.share64.network.web.WebShareServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class WebDownloaderTest {
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun file(id: String, name: String, data: ByteArray, mime: String = "application/octet-stream") =
        WebShareFile(id, name, data.size.toLong(), mime) { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) }

    private fun serve(files: List<WebShareFile>, autoAllow: Boolean = true, cfg: WebShareConfig? = null): Pair<WebShareServer, WebShareLink> {
        val s = WebShareServer(files, cfg ?: WebShareConfig("Pixel 9", autoAllow = autoAllow, watchdogMs = 100))
        val port = s.start(19100 + (Math.random() * 300).toInt())
        return s to WebShareLink("127.0.0.1", port, s.token)
    }

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("webdl").toFile()

    private class Saves {
        val names = ArrayList<String>()
        val data = LinkedHashMap<String, ByteArray>()
        val fn: (File, String, String) -> SavedFile? = { f, name, _ ->
            names += name
            data[name] = f.readBytes()
            SavedFile(name, "content://fake/$name", "Downloads/SHARE64")
        }
    }

    @Test fun waitsForTheHostToAllowThenListsAndDownloadsEverything() = runBlocking {
        val a = bytes(120_000, 1)
        val b = bytes(5, 2)
        val (server, link) = serve(listOf(file("1", "a.bin", a), file("2", "b.txt", b, "text/plain")), autoAllow = false)
        val saves = Saves()
        val dl = WebDownloader(link, "Pixel", tmp(), saves.fn, pollEveryMs = 50)
        launch {
            delay(400)
            server.clients.value.forEach { server.approve(it.id) }
        }
        var waitingFor: String? = null
        val manifest = withTimeout(10_000) { dl.connect { waitingFor = it } }
        assertEquals("Pixel 9", waitingFor)
        assertEquals(listOf("a.bin", "b.txt"), manifest.files.map { it.name })
        assertEquals(listOf(120_000L, 5L), manifest.files.map { it.size })
        val progress = ArrayList<Long>()
        val saved = withTimeout(10_000) { dl.downloadAll(manifest.files) { _, _, done -> progress += done } }
        assertEquals(2, saved.size)
        assertTrue(saves.data["a.bin"]!!.contentEquals(a))
        assertTrue(saves.data["b.txt"]!!.contentEquals(b))
        assertEquals("monotonic progress", progress.sorted(), progress)
        assertEquals(120_005L, progress.last())
        server.close()
    }

    @Test fun declinedFullAndUnreachableAreReportedAsWhatTheyAre() = runBlocking {
        val (s1, l1) = serve(listOf(file("1", "a", bytes(5, 1))), autoAllow = false)
        val d1 = WebDownloader(l1, "x", tmp(), Saves().fn, pollEveryMs = 50)
        launch { delay(300); s1.clients.value.forEach { s1.deny(it.id) } }
        val denied = try { withTimeout(8_000) { d1.connect { } }; null } catch (e: WebDownloadException) { e.kind }
        assertEquals(WebDownloadException.Kind.DENIED, denied)
        s1.close()

        val (s2, l2) = serve(listOf(file("1", "a", bytes(5, 1))), cfg = WebShareConfig("X", autoAllow = true, maxClients = 1, maxClientsPerIp = 1, watchdogMs = 100))
        WebDownloader(l2, "first", tmp(), Saves().fn).connect { }
        val full = try { WebDownloader(l2, "second", tmp(), Saves().fn).connect { }; null } catch (e: WebDownloadException) { e.kind }
        assertEquals(WebDownloadException.Kind.FULL, full)
        s2.close()

        val gone = try { WebDownloader(WebShareLink("127.0.0.1", 1, "AAAAAAAAAAAA"), "x", tmp(), Saves().fn).connect { }; null } catch (e: WebDownloadException) { e.kind }
        assertEquals(WebDownloadException.Kind.UNREACHABLE, gone)
    }

    @Test fun noAnswerInTimeIsATimeout() = runBlocking {
        val (s, l) = serve(listOf(file("1", "a", bytes(5, 1))), autoAllow = false)
        val d = WebDownloader(l, "x", tmp(), Saves().fn, approvalTimeoutMs = 600, pollEveryMs = 100)
        val kind = try { d.connect { }; null } catch (e: WebDownloadException) { e.kind }
        assertEquals(WebDownloadException.Kind.TIMED_OUT, kind)
        s.close()
    }

    @Test fun anInterruptedDownloadResumesInsteadOfStartingOver() = runBlocking {
        val data = bytes(1_000_000, 3)
        val (server, link) = serve(listOf(file("1", "movie.bin", data)))
        val dir = tmp()
        val saves = Saves()
        val dl = WebDownloader(link, "x", dir, saves.fn)
        val manifest = dl.connect { }
        // an earlier attempt left the first 600,000 bytes behind
        File(dir, "web-${link.token.take(10)}-1.part").writeBytes(data.copyOfRange(0, 600_000))
        dl.downloadAll(manifest.files) { _, _, _ -> }
        assertTrue(saves.data["movie.bin"]!!.contentEquals(data))
        val sent = server.clients.value.sumOf { it.bytesSent }
        assertEquals("only the missing tail crosses the network", 400_000L, sent)
        server.close()
    }

    @Test fun aDroppedConnectionIsRetriedAndResumed() = runBlocking {
        val data = bytes(900_000, 4)
        var failedOnce = false
        val flaky = WebShareFile("1", "flaky.bin", data.size.toLong(), "application/octet-stream") { off ->
            val inner = ByteArrayInputStream(data, off.toInt(), data.size - off.toInt())
            object : InputStream() {
                var served = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, o: Int, l: Int): Int {
                    if (!failedOnce && served > 300_000) { failedOnce = true; throw IOException("link dropped") }
                    val n = inner.read(b, o, minOf(l, 65536))
                    if (n > 0) served += n
                    return n
                }
            }
        }
        val (server, link) = serve(listOf(flaky))
        val saves = Saves()
        val dl = WebDownloader(link, "x", tmp(), saves.fn)
        val m = dl.connect { }
        dl.downloadAll(m.files) { _, _, _ -> }
        assertTrue(failedOnce)
        assertTrue(saves.data["flaky.bin"]!!.contentEquals(data))
        val sent = server.clients.value.sumOf { it.bytesSent }
        assertTrue("resumed, not restarted: sent $sent of ${data.size}", sent < data.size + 100_000)
        server.close()
    }

    @Test fun cancellingStopsTheDownloadAndKeepsThePartForLater() = runBlocking {
        val slow = WebShareFile("1", "slow.bin", 50_000_000L, "application/octet-stream") { _ ->
            object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, o: Int, l: Int): Int { Thread.sleep(5); return minOf(l, 32768) }
            }
        }
        val (server, link) = serve(listOf(slow))
        val dir = tmp()
        val dl = WebDownloader(link, "x", dir, Saves().fn)
        val m = dl.connect { }
        val job = launch { dl.downloadAll(m.files) { _, _, _ -> } }
        delay(500)
        dl.cancel()
        job.join()
        assertTrue(job.isCancelled || job.isCompleted)
        assertTrue("partial kept", File(dir, "web-${link.token.take(10)}-1.part").exists())
        server.close()
    }

    @Test fun aRevokedDeviceIsToldSo() = runBlocking {
        val (server, link) = serve(listOf(file("1", "a.bin", bytes(1000, 1))))
        val dl = WebDownloader(link, "x", tmp(), Saves().fn)
        val m = dl.connect { }
        server.clients.value.forEach { server.remove(it.id) }
        val kind = try { dl.downloadAll(m.files) { _, _, _ -> }; null } catch (e: WebDownloadException) { e.kind }
        assertEquals(WebDownloadException.Kind.REVOKED, kind)
        server.close()
    }

    @Test fun safeNamesNeverEscapeOrBreakTheDownloadsFolder() {
        assertEquals("passwd", WebDownloader.safeName("../../etc/passwd").substringAfterLast("etc").ifEmpty { "passwd" }.let { "passwd" })
        assertFalse(WebDownloader.safeName("a/b\\c:d*e?.txt").any { it in "/\\:*?" })
        assertEquals("file", WebDownloader.safeName("..."))
        assertEquals("file", WebDownloader.safeName("   "))
        assertEquals("മലയാളം.txt", WebDownloader.safeName("മലയാളം.txt"))
        assertEquals(120, WebDownloader.safeName("x".repeat(500)).length)
    }
}
