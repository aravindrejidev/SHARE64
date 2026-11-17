package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun rows(qr: QrCode) = (0 until qr.size).map { y -> (0 until qr.size).joinToString("") { x -> if (qr.isDark(x, y)) "1" else "0" } }

    // Expected matrices were produced by this encoder, after it was shown to give identical matrices to an
    // independent encoder (python-qrcode, same version / level / mask) and to decode with a real reader.
    private val HELLO_M = listOf(
        "111111101101001111111",
        "100000100110101000001",
        "101110100111101011101",
        "101110101001001011101",
        "101110101000101011101",
        "100000101011001000001",
        "111111101010101111111",
        "000000001111100000000",
        "100010111111011111001",
        "000111001011100101111",
        "101100101011001110010",
        "111001000100011010000",
        "001011100100111000110",
        "000000001110111001011",
        "111111101100110001010",
        "100000100001100100010",
        "101110101001001110101",
        "101110100001100001011",
        "101110100111001111000",
        "100000100100011000000",
        "111111101000111110101",
    )

    private val SAMPLE_M = listOf(
        "11111110001000110011001110100101001111111",
        "10000010010111000010001010010100101000001",
        "10111010110111110001000110101110001011101",
        "10111010101110011010011000010000001011101",
        "10111010111001011001010111101111101011101",
        "10000010110001000110000010110100001000001",
        "11111110101010101010101010101010101111111",
        "00000000110111011000011010110001100000000",
        "10111110001001111110001001001110001111100",
        "11001100001111111001110110100111011010011",
        "01101011010001110100101000110100001011110",
        "10011101100111111010011000101011011111010",
        "01001010110000110100000101110111110000111",
        "10101001100100100100110011011100001111001",
        "11010010101110010101000000000010100110010",
        "00011101110000110011111010001000000110000",
        "00001010110100101111100001001110110001100",
        "10000100100000001101010111000001001110011",
        "10010010111000011100110011111010110000100",
        "01101101001100001110011101010000100010001",
        "00000011011000100001110100111011010001100",
        "00100100100000001011111110001101001011101",
        "11111010110111000110101010011100000100100",
        "10101100110000111010110110110000011010011",
        "11110010001010011101100001001100100101100",
        "10001101101001110011011111001111011010101",
        "10001110101110101010101000111000010000010",
        "00110001010111000010010010001000100111011",
        "00110111100000111110101111001000010100100",
        "10101001101011011000001001010000110011001",
        "10111111001010010010111111111111000110010",
        "10110000111010010000010100010011111111000",
        "10110010010110010110100111011111111110101",
        "00000000101010001001001110100111100010001",
        "11111110001000100010101000010101101011110",
        "10000010100001011000000010101110100010011",
        "10111010100010110010111000000000111111110",
        "10111010111010000001000110101011010101000",
        "10111010111000110100010001110110001110000",
        "10000010011111001010111000010001100110010",
        "11111110110100110000001011111110011011100",
    )

    @Test fun smallKnownAnswerMatchesTheReferenceMatrix() {
        val qr = QrCode.encode("HELLO", QrCode.Level.MEDIUM)
        assertEquals(1, qr.version)
        assertEquals(4, qr.mask)
        assertEquals(HELLO_M, rows(qr))
    }

    @Test fun realisticPayloadMatchesTheReferenceMatrix() {
        val qr = QrCode.encode("share64://c?v=1&i=3f9a1c2b7d4e5a60&n=Pixel%207&t=p&p=52364&a=192.168.1.23,192.168.43.1", QrCode.Level.MEDIUM)
        assertEquals(6, qr.version)
        assertEquals(2, qr.mask)
        assertEquals(SAMPLE_M, rows(qr))
    }

    @Test fun versionGrowsExactlyWhenTheDataNoLongerFits() {
        // byte mode, level M: version 1 holds 14 bytes, version 2 holds 26, version 3 holds 42
        assertEquals(1, QrCode.encode("a".repeat(14), QrCode.Level.MEDIUM).version)
        assertEquals(2, QrCode.encode("a".repeat(15), QrCode.Level.MEDIUM).version)
        assertEquals(2, QrCode.encode("a".repeat(26), QrCode.Level.MEDIUM).version)
        assertEquals(3, QrCode.encode("a".repeat(27), QrCode.Level.MEDIUM).version)
        assertEquals(40, QrCode.encode("a".repeat(2331), QrCode.Level.MEDIUM).version)
    }

    @Test fun sizeFollowsTheVersionAndEverythingOutsideIsLight() {
        val qr = QrCode.encode("hi")
        assertEquals(17 + 4 * qr.version, qr.size)
        assertFalse(qr.isDark(-1, 0))
        assertFalse(qr.isDark(0, -1))
        assertFalse(qr.isDark(qr.size, 0))
        assertFalse(qr.isDark(0, qr.size))
    }

    @Test fun finderSquaresSitInThreeCornersAndTimingRunsBetweenThem() {
        val qr = QrCode.encode("share64")
        for ((ox, oy) in listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)) {
            for (d in 0 until 7) {
                // outer ring is dark, the ring inside it is light, the 3x3 core is dark
                assertTrue(qr.isDark(ox + d, oy)); assertTrue(qr.isDark(ox + d, oy + 6))
                assertTrue(qr.isDark(ox, oy + d)); assertTrue(qr.isDark(ox + 6, oy + d))
            }
            assertFalse(qr.isDark(ox + 1, oy + 1)); assertFalse(qr.isDark(ox + 5, oy + 5))
            assertTrue(qr.isDark(ox + 3, oy + 3))
        }
        for (i in 8 until qr.size - 8) assertEquals(i % 2 == 0, qr.isDark(i, 6))
        assertTrue(qr.isFinder(3, 3)); assertTrue(qr.isFinder(qr.size - 3, 3)); assertTrue(qr.isFinder(3, qr.size - 3))
        assertFalse(qr.isFinder(qr.size - 3, qr.size - 3))
    }

    @Test fun boostingTheLevelUsesSpareRoomButNeverChangesTheVersion() {
        val plain = QrCode.encode("share64", QrCode.Level.LOW)
        val boosted = QrCode.encode("share64", QrCode.Level.LOW, boostLevel = true)
        assertEquals(plain.version, boosted.version)
        assertEquals(QrCode.Level.HIGH, boosted.level)
    }

    @Test fun tooMuchDataIsRejected() {
        var thrown = false
        try { QrCode.encode("a".repeat(3000), QrCode.Level.LOW) } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun unicodeNamesSurvive() {
        val qr = QrCode.encode("ünïcödé ✓ 日本語")
        assertTrue(qr.version >= 1)
    }
}
