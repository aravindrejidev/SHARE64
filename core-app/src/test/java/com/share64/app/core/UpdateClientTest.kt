package com.share64.app.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest

/** Exercises the real HTTP code against a local server that mimics GitHub's layout. */
class UpdateClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val routes = HashMap<String, Pair<Int, ByteArray>>()
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private lateinit var tmp: File

    private fun reply(ex: HttpExchange, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        if (body.isEmpty()) ex.sendResponseHeaders(code, -1) else ex.sendResponseHeaders(code, body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    @Before fun start() {
        tmp = File.createTempFile("upd", "dir").also { it.delete(); it.mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            when {
                path == "/redirect/apk" -> reply(ex, 302, ByteArray(0), mapOf("Location" to "/dl/app.apk"))
                path == "/dl/app.apk" -> reply(ex, 200, apk)
                routes.containsKey(path) -> routes.getValue(path).let { reply(ex, it.first, it.second) }
                else -> reply(ex, 404, "nope".toByteArray())
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
        tmp.deleteRecursively()
    }

    private fun client(files: List<String> = listOf("Relece_notice.md", "Release_notice.md")) =
        UpdateClient("$base/api/latest", "$base/raw", files)

    private val releaseJson = """{"tag_name":"v1.2.0","name":"n","body":"b","html_url":"h","published_at":"2026-11-10T01:20:30Z","assets":[]}"""

    @Test fun fetchesLatestRelease() {
        routes["/api/latest"] = 200 to releaseJson.toByteArray()
        assertEquals("v1.2.0", client().fetchLatest().tag)
    }

    @Test fun reportsHttpErrorsWithTheirCode() {
        val e = try { client().fetchLatest(); null } catch (e: UpdateClient.HttpFailure) { e }
        assertEquals(404, e?.code)
        routes["/api/latest"] = 403 to "rate limit".toByteArray()
        assertEquals(403, (try { client().fetchLatest(); null } catch (e: UpdateClient.HttpFailure) { e })?.code)
        routes["/api/latest"] = 200 to "<html>captive portal</html>".toByteArray()
        assertTrue(try { client().fetchLatest(); false } catch (e: IOException) { true })
    }

    @Test fun notesPreferTheReleaseTagThenMainThenAlternateName() {
        routes["/raw/v1.2.0/Relece_notice.md"] = 200 to "  tag notes \n".toByteArray()
        routes["/raw/main/Relece_notice.md"] = 200 to "main notes".toByteArray()
        assertEquals("tag notes", client().fetchNotes("v1.2.0"))

        routes.clear()
        routes["/raw/main/Relece_notice.md"] = 200 to "main notes".toByteArray()
        assertEquals("main notes", client().fetchNotes("v1.2.0"))

        routes.clear()
        routes["/raw/v1.2.0/Release_notice.md"] = 200 to "corrected spelling".toByteArray()
        assertEquals("corrected spelling", client().fetchNotes("v1.2.0"))

        routes.clear()
        routes["/raw/v1.2.0/Relece_notice.md"] = 200 to "   \n".toByteArray() // empty file is skipped
        routes["/raw/main/Relece_notice.md"] = 200 to "fallback".toByteArray()
        assertEquals("fallback", client().fetchNotes("v1.2.0"))

        routes.clear()
        assertEquals("", client().fetchNotes("v1.2.0"))
    }

    @Test fun downloadsThroughARedirectWithProgress() {
        val dest = File(tmp, "updates/app.apk")
        val seen = ArrayList<Long>()
        var total = -1L
        client().download("$base/redirect/apk", dest) { done, t -> seen += done; total = t }
        assertArrayEquals(apk, dest.readBytes())
        assertEquals(apk.size.toLong(), total)
        assertEquals(apk.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.size > 1)
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun cancellingFromTheProgressCallbackLeavesNoFiles() {
        val dest = File(tmp, "app.apk")
        val threw = try {
            client().download("$base/dl/app.apk", dest) { done, _ -> if (done > 100_000) throw IllegalStateException("cancel") }
            false
        } catch (_: IllegalStateException) { true }
        assertTrue(threw)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun failedDownloadsLeaveNoFiles() {
        val dest = File(tmp, "missing.apk")
        val code = try { client().download("$base/dl/missing.apk", dest) { _, _ -> }; -1 } catch (e: UpdateClient.HttpFailure) { e.code }
        assertEquals(404, code)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun overwritesAnOlderDownload() {
        val dest = File(tmp, "app.apk")
        dest.writeText("old")
        client().download("$base/dl/app.apk", dest) { _, _ -> }
        assertEquals(apk.size.toLong(), dest.length())
    }

    @Test fun checksumVerificationEndToEnd() {
        val expected = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        routes["/dl/SHA256SUMS.txt"] = 200 to "$expected  ./SHARE64-phone-1.2.0-arm64-v8a-release.apk\n${"0".repeat(64)}  ./other.apk\n".toByteArray()
        val sums = client().fetchChecksums("$base/dl/SHA256SUMS.txt")
        val dest = File(tmp, "x.apk")
        client().download("$base/dl/app.apk", dest) { _, _ -> }
        assertEquals(sums["SHARE64-phone-1.2.0-arm64-v8a-release.apk"], client().sha256(dest))
        assertTrue(sums["other.apk"] != client().sha256(dest))
        dest.writeBytes(apk.copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertTrue(sums["SHARE64-phone-1.2.0-arm64-v8a-release.apk"] != client().sha256(dest)) // a flipped byte is caught
    }
}
