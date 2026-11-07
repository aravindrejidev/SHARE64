package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTxtTest {
    private val device = LocalDevice("0123456789abcdef0123456789abcdef", "Aravind's Pixel", DeviceType.PHONE)

    private fun asBytes(m: Map<String, String>): Map<String, ByteArray?> =
        m.mapValues { it.value.toByteArray(Charsets.UTF_8) }

    @Test fun roundTrip() {
        val d = PeerTxt.decode(asBytes(PeerTxt.encode(device)))
        assertNotNull(d)
        assertEquals(device.id, d!!.id)
        assertEquals("Aravind's Pixel", d.name)
        assertEquals(DeviceType.PHONE, d.type)
        assertEquals(PeerTxt.PROTOCOL_VERSION, d.version)
    }

    @Test fun keysAreCaseInsensitive() {
        val upper = asBytes(PeerTxt.encode(device)).mapKeys { it.key.uppercase() }
        assertNotNull(PeerTxt.decode(upper))
    }

    @Test fun rejectsMissingOrMalformedFields() {
        assertNull(PeerTxt.decode(null))
        assertNull(PeerTxt.decode(emptyMap()))
        val good = PeerTxt.encode(device)
        assertNull(PeerTxt.decode(asBytes(good - "id")))
        assertNull(PeerTxt.decode(asBytes(good - "v")))
        assertNull(PeerTxt.decode(asBytes(good + ("id" to "short"))))
        assertNull(PeerTxt.decode(asBytes(good + ("id" to "../../etc/passwd/xxxxxxxx"))))
        assertNull(PeerTxt.decode(asBytes(good + ("v" to "abc"))))
    }

    @Test fun unknownTypeBecomesOther() {
        val d = PeerTxt.decode(asBytes(PeerTxt.encode(device) + ("t" to "zzz")))
        assertEquals(DeviceType.OTHER, d!!.type)
    }

    @Test fun namesAreSanitizedAndBounded() {
        assertEquals("Device", PeerTxt.sanitizeName("  \u0000\n\t "))
        assertEquals("ab", PeerTxt.sanitizeName("a\u0007b"))
        val long = PeerTxt.sanitizeName("x".repeat(500))
        assertEquals(PeerTxt.MAX_NAME_CHARS, long.length)
        val emoji = "\uD83D\uDE00".repeat(100) // 4 bytes each in UTF-8
        val encoded = PeerTxt.encode(device.copy(displayName = emoji))["n"]!!
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 96)
        assertTrue(encoded.isNotEmpty())
        var i = 0
        while (i < encoded.length) { // never cut mid-character
            assertEquals(0x1F600, encoded.codePointAt(i))
            i += Character.charCount(0x1F600)
        }
    }

    @Test fun truncateUtf8NeverSplitsCharacters() {
        assertEquals("a\u00e9", PeerTxt.truncateUtf8("a\u00e9\u00e9", 3)) // a(1) + é(2)
        assertEquals("", PeerTxt.truncateUtf8("\uD83D\uDE00", 3))
        assertEquals("\uD83D\uDE00", PeerTxt.truncateUtf8("\uD83D\uDE00x", 4))
    }
}
