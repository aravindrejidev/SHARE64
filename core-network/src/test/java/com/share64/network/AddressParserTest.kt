package com.share64.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressParserTest {
    @Test fun parsesCommonForms() {
        assertEquals(AddressParser.Address("192.168.1.5", null), AddressParser.parse("192.168.1.5"))
        assertEquals(AddressParser.Address("192.168.1.5", 4000), AddressParser.parse(" 192.168.1.5:4000 "))
        assertEquals(AddressParser.Address("fe80::1", 52364), AddressParser.parse("[fe80::1]:52364"))
        assertEquals(AddressParser.Address("fe80::1", null), AddressParser.parse("[fe80::1]"))
        assertEquals(AddressParser.Address("fe80::1", null), AddressParser.parse("fe80::1"))
        assertEquals(AddressParser.Address("tv.local", 1), AddressParser.parse("tv.local:1"))
    }

    @Test fun rejectsGarbage() {
        assertNull(AddressParser.parse(""))
        assertNull(AddressParser.parse("   "))
        assertNull(AddressParser.parse("1.2.3.4:0"))
        assertNull(AddressParser.parse("1.2.3.4:70000"))
        assertNull(AddressParser.parse("1.2.3.4:abc"))
        assertNull(AddressParser.parse(":5000"))
        assertNull(AddressParser.parse("1.2 .3.4"))
        assertNull(AddressParser.parse("[fe80::1"))
        assertNull(AddressParser.parse("[fe80::1]x"))
    }
}
