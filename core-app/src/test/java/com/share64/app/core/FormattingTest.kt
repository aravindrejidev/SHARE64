package com.share64.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormattingTest {
    private val en = Locale.US

    @Test fun bytes() {
        assertEquals("0 B", Formatting.bytes(0, en))
        assertEquals("0 B", Formatting.bytes(-5, en))
        assertEquals("1023 B", Formatting.bytes(1023, en))
        assertEquals("1.00 KB", Formatting.bytes(1024, en))
        assertEquals("1.50 MB", Formatting.bytes(1536L * 1024, en))
        assertEquals("12.3 MB", Formatting.bytes((12.3 * 1024 * 1024).toLong(), en))
        assertEquals("123 MB", Formatting.bytes(123L * 1024 * 1024, en))
        assertEquals("1.00 GB", Formatting.bytes(1024L * 1024 * 1024, en))
        assertEquals("5.00 TB", Formatting.bytes(5L * 1024 * 1024 * 1024 * 1024, en))
        assertEquals("2048 TB", Formatting.bytes(2048L * 1024 * 1024 * 1024 * 1024, en))
    }

    @Test fun speedAndDuration() {
        assertEquals("1.00 MB/s", Formatting.speed(1024 * 1024, en))
        assertEquals("0 s", Formatting.duration(0))
        assertEquals("59 s", Formatting.duration(59))
        assertEquals("1 min 0 s", Formatting.duration(60))
        assertEquals("3 min 20 s", Formatting.duration(200))
        assertEquals("1 h 1 min", Formatting.duration(3660))
    }

    @Test fun eta() {
        assertNull(Formatting.eta(100, 0))
        assertNull(Formatting.eta(0, 100))
        assertEquals("2 s", Formatting.eta(150, 100)) // rounds up
        assertEquals("10 s", Formatting.eta(1000, 100))
    }
}
