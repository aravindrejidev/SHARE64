package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    private val info = ConnectInfo(
        id = "3f9a1c2b7d4e5a60",
        name = "Aravind's Pixel 7",
        type = DeviceType.PHONE,
        port = 52364,
        addresses = listOf("192.168.1.23", "192.168.43.1"),
    )

    private fun parseOk(text: String): ConnectInfo = (QrPayload.parse(text) as QrParse.Ok).info
    private fun reason(text: String): QrParse.Reason = (QrPayload.parse(text) as QrParse.Invalid).reason

    @Test fun roundTripKeepsEverything() {
        assertEquals(info, parseOk(QrPayload.encode(info)))
    }

    @Test fun payloadStaysSmallEnoughForABigEasyCode() {
        val text = QrPayload.encode(info.copy(addresses = listOf("192.168.1.23", "192.168.43.1", "10.0.0.7", "172.20.10.2")))
        assertTrue("payload is ${text.length} chars", text.length <= 130)
        assertTrue("version ${QrCode.encode(text).version}", QrCode.encode(text).version <= 8)
    }

    @Test fun namesWithSpacesAndUnicodeSurvive() {
        val n = info.copy(name = "सोഫ Phone ✓ 日本")
        assertEquals(n.name, parseOk(QrPayload.encode(n)).name)
    }

    @Test fun longNamesAreCutAndControlCharactersRemoved() {
        val n = parseOk(QrPayload.encode(info.copy(name = "x".repeat(100) + "\n")))
        assertEquals(QrPayload.MAX_NAME, n.name.length)
        val clean = parseOk(QrPayload.encode(info.copy(name = "Bad\u0000Name\n")))
        assertEquals("BadName", clean.name)
    }

    @Test fun onlyPrivateAddressesAreKept() {
        val text = "share64://c?v=1&i=abcdef123456&n=X&t=p&p=52364&a=8.8.8.8,192.168.1.5,127.0.0.1,172.32.0.1,172.16.0.9,example.com"
        assertEquals(listOf("192.168.1.5", "172.16.0.9"), parseOk(text).addresses)
    }

    @Test fun aCodeWithNoUsableAddressIsRejected() {
        assertEquals(QrParse.Reason.BAD_ADDRESS, reason("share64://c?v=1&i=abcdef123456&n=X&t=p&p=52364&a=8.8.8.8,1.1.1.1"))
        assertEquals(QrParse.Reason.MISSING_FIELD, reason("share64://c?v=1&i=abcdef123456&n=X&t=p&p=52364"))
    }

    @Test fun badPortsAreRejected() {
        for (p in listOf("0", "70000", "-1")) {
            assertEquals(p, QrParse.Reason.BAD_PORT, reason("share64://c?v=1&i=abcdef123456&n=X&t=p&p=$p&a=192.168.1.5"))
        }
        assertEquals(QrParse.Reason.MISSING_FIELD, reason("share64://c?v=1&i=abcdef123456&n=X&t=p&p=abc&a=192.168.1.5"))
    }

    @Test fun badIdsAreRejected() {
        assertEquals(QrParse.Reason.BAD_ID, reason("share64://c?v=1&i=short&n=X&t=p&p=52364&a=192.168.1.5"))
        assertEquals(QrParse.Reason.BAD_ID, reason("share64://c?v=1&i=has%20space123&n=X&t=p&p=52364&a=192.168.1.5"))
    }

    @Test fun otherQrCodesAreNotOurs() {
        for (t in listOf("https://example.com", "WIFI:S:home;T:WPA;P:secret;;", "", "share64://other?x=1", "hello")) {
            assertEquals(t, QrParse.Reason.NOT_SHARE64, reason(t))
        }
    }

    @Test fun aNewerFormatAsksForAnUpdateInsteadOfFailingSilently() {
        assertEquals(QrParse.Reason.NEWER_VERSION, reason("share64://c?v=2&i=abcdef123456&n=X&t=p&p=52364&a=192.168.1.5"))
    }

    @Test fun unknownFieldsAreIgnoredSoTheFormatCanGrow() {
        val ok = parseOk("share64://c?v=1&i=abcdef123456&n=X&t=t&p=1234&a=10.1.2.3&future=yes&z")
        assertEquals(DeviceType.TV, ok.type)
        assertEquals(1234, ok.port)
    }

    @Test fun whitespaceAroundTheTextIsTolerated() {
        assertEquals(info, parseOk("  \n" + QrPayload.encode(info) + "\r\n"))
    }

    @Test fun privateAddressCheck() {
        for (ip in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.1", "192.168.0.1", "192.168.43.1")) assertTrue(ip, QrPayload.isPrivateIpv4(ip))
        for (ip in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "127.0.0.1", "169.254.1.1", "256.1.1.1", "1.2.3", "a.b.c.d", "", "010.0.0.1x", "192.168.1.1.1"))
            assertTrue(ip, !QrPayload.isPrivateIpv4(ip))
    }
}
