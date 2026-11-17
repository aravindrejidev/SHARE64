package com.share64.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrConnectorTest {
    private fun reply(name: String = "Pixel") = HandshakeProbe.Result(name, 1)

    @Test fun theAddressThatAnswersWinsEvenIfItIsNotTheFirst() = runBlocking {
        val r = QrConnector.firstReachable(listOf("192.168.1.23", "192.168.43.1", "10.0.0.5")) { host ->
            if (host == "192.168.43.1") reply() else null
        }
        assertEquals("192.168.43.1", r?.host)
    }

    @Test fun noAnswerMeansNull() = runBlocking {
        assertNull(QrConnector.firstReachable(listOf("192.168.1.2", "192.168.1.3")) { null })
        assertNull(QrConnector.firstReachable(emptyList()) { reply() })
    }

    @Test fun aFastAnswerIsNotHeldUpBySlowDeadAddresses() = runBlocking {
        val start = System.nanoTime()
        val r = QrConnector.firstReachable(listOf("10.0.0.1", "10.0.0.2")) { host ->
            if (host == "10.0.0.1") { Thread.sleep(1500); null } else reply()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals("10.0.0.2", r?.host)
        assertTrue("took $ms ms", ms < 800)
    }

    @Test fun aProbeThatThrowsCountsAsNoAnswer() = runBlocking {
        val r = QrConnector.firstReachable(listOf("10.0.0.1", "10.0.0.2")) { host ->
            if (host == "10.0.0.1") throw IllegalStateException("boom") else reply("TV")
        }
        assertEquals("10.0.0.2", r?.host)
        assertEquals("TV", r?.reply?.deviceName)
    }
}
