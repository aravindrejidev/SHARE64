package com.share64.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SubnetScannerTest {
    @Test fun slash24SkipsSelfNetworkAndBroadcast() {
        val c = SubnetScanner.candidates("192.168.43.1", 24)
        assertEquals(253, c.size)
        assertEquals("192.168.43.2", c.first())
        assertEquals("192.168.43.254", c.last())
        assertFalse("192.168.43.1" in c)
        assertFalse("192.168.43.0" in c)
        assertFalse("192.168.43.255" in c)
    }

    @Test fun biggerSubnetsAreCappedToTheSlash24AroundUs() {
        val c = SubnetScanner.candidates("10.20.30.40", 8)
        assertEquals(253, c.size)
        assertTrue(c.all { it.startsWith("10.20.30.") })
    }

    @Test fun smallSubnetsStayAsSmallAsTheyAre() {
        val c = SubnetScanner.candidates("192.168.1.18", 28) // 192.168.1.16/28: .17 - .30
        assertEquals(13, c.size)
        assertEquals("192.168.1.17", c.first())
        assertEquals("192.168.1.30", c.last())
    }

    @Test fun pointToPointAndGarbageGiveNothing() {
        assertTrue(SubnetScanner.candidates("192.168.1.1", 32).isEmpty())
        assertTrue(SubnetScanner.candidates("192.168.1.1", 31).isEmpty())
        assertTrue(SubnetScanner.candidates("not-an-ip", 24).isEmpty())
        assertTrue(SubnetScanner.candidates("300.1.1.1", 24).isEmpty())
    }

    @Test fun scanReturnsOnlyTheHostsThatAnswerAndHonoursParallelism() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val hosts = SubnetScanner.candidates("192.168.43.1", 24)
        val hits = SubnetScanner.scan(hosts, 52364, parallelism = 8) { host ->
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            Thread.sleep(2)
            running.decrementAndGet()
            when (host) {
                "192.168.43.5" -> HandshakeProbe.Result("Pixel", 1)
                "192.168.43.77" -> HandshakeProbe.Result("Living Room TV", 1)
                else -> null
            }
        }
        assertEquals(setOf("192.168.43.5", "192.168.43.77"), hits.map { it.host }.toSet())
        assertEquals("Living Room TV", hits.first { it.host == "192.168.43.77" }.name)
        assertEquals(52364, hits.first().port)
        assertTrue("peak concurrency ${peak.get()}", peak.get() <= 8)
    }
}
