package com.share64.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {
    @Test fun abiLabels() {
        assertEquals("ARM64", AbiLabel.of("arm64-v8a"))
        assertEquals("ARM32", AbiLabel.of("armeabi-v7a"))
        assertEquals("ARM32", AbiLabel.of("armeabi"))
        assertEquals("x86_64", AbiLabel.of("x86_64"))
        assertEquals("x86", AbiLabel.of("x86"))
        assertEquals("Unknown", AbiLabel.of(null))
        assertEquals("Unknown", AbiLabel.of(""))
        assertEquals("RISCV64", AbiLabel.of("riscv64"))
    }

    @Test fun picksTheAbiMatchingProcessBitness() {
        val phone = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals("arm64-v8a", AbiLabel.pick(phone, true))
        assertEquals("armeabi-v7a", AbiLabel.pick(phone, false))
        assertEquals("armeabi-v7a", AbiLabel.pick(arrayOf("armeabi-v7a"), true)) // nothing 64-bit: fall back to first
        assertNull(AbiLabel.pick(emptyArray(), true))
    }

    @Test fun linksAreConsistent() {
        assertEquals("https://github.com/aravindrejidev", AppInfo.DEVELOPER_URL)
        assertTrue(AppInfo.REPO_URL.startsWith(AppInfo.DEVELOPER_URL + "/"))
        assertTrue(AppInfo.RELEASES_URL.endsWith("/releases"))
        assertTrue(AppInfo.ISSUES_URL.endsWith("/issues"))
        assertTrue(AppInfo.CONTRIBUTORS_API_URL.contains("/repos/aravindrejidev/${AppInfo.REPO_NAME}/contributors"))
    }
}
