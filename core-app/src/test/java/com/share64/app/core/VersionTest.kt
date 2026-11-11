package com.share64.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun parsing() {
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
        assertEquals(Version(1, 2, 3, listOf("beta", "1")), Version.parse("v1.2.3-beta.1"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3+build.7"))
        for (bad in listOf("", "abc", "1", "v", "1.2.3.4", "1..2", "99999999999.0.0", "1.2.3-", null)) {
            assertNull("should reject '$bad'", Version.parse(bad))
        }
    }

    @Test fun ordering() {
        assertTrue(Version.parse("1.0.1")!! > Version.parse("1.0.0")!!)
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("1.0.0-rc.1")!!)      // release beats pre-release
        assertTrue(Version.parse("1.0.0-rc.2")!! > Version.parse("1.0.0-rc.1")!!)
        assertTrue(Version.parse("1.0.0-rc.10")!! > Version.parse("1.0.0-rc.9")!!) // numeric, not lexical
        assertTrue(Version.parse("1.0.0-beta")!! > Version.parse("1.0.0-alpha")!!)
        assertTrue(Version.parse("1.0.0-alpha.1")!! > Version.parse("1.0.0-alpha")!!)
        assertEquals(0, Version.parse("v1.0.0")!!.compareTo(Version.parse("1.0.0")!!))
    }

    @Test fun isNewer() {
        assertTrue(Version.isNewer("v1.1.0", "1.0.0"))
        assertFalse(Version.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Version.isNewer("v0.9.0", "1.0.0"))
        assertFalse(Version.isNewer("garbage", "1.0.0"))
        assertTrue(Version.isNewer("v1.0.0", "unknown"))   // can't read our own version: offer it
        assertFalse(Version.isNewer("v1.0.0-rc.1", "1.0.0"))
    }

    @Test fun toStringRoundTrip() {
        assertEquals("1.2.3-beta.1", Version.parse("v1.2.3-beta.1").toString())
    }
}
