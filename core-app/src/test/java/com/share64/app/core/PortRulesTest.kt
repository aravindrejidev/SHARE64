package com.share64.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PortRulesTest {
    @Test fun parsesValidPorts() {
        assertEquals(52364, PortRules.parse("52364"))
        assertEquals(1024, PortRules.parse(" 1024 "))
        assertEquals(65535, PortRules.parse("65535"))
    }

    @Test fun rejectsInvalidPorts() {
        for (bad in listOf("", " ", "1023", "65536", "0", "-5", "abc", "80", "99999999999", "52 364", "1.5")) {
            assertNull("should reject '$bad'", PortRules.parse(bad))
            assertNotNull("should explain '$bad'", PortRules.error(bad))
        }
    }

    @Test fun validPortsHaveNoError() {
        assertNull(PortRules.error("52364"))
        assertNull(PortRules.error("1024"))
    }

    @Test fun messagesAreSpecific() {
        assertEquals("Enter a port number", PortRules.error("  "))
        assertEquals("Digits only", PortRules.error("12a4"))
        assertEquals("Use a number from 1024 to 65535", PortRules.error("80"))
        assertEquals("Use a number from 1024 to 65535", PortRules.error("123456789"))
    }

    @Test fun defaultIsValid() {
        assertEquals(PortRules.DEFAULT, PortRules.parse(PortRules.DEFAULT.toString()))
    }
}
