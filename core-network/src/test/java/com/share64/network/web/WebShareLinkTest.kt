package com.share64.network.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebShareLinkTest {
    @Test fun roundTrip() {
        val link = WebShareLink("192.168.1.23", 8080, "Ab3xK9pQrT2m")
        assertEquals("http://192.168.1.23:8080/s/Ab3xK9pQrT2m/", link.url)
        assertEquals(link, WebShareLink.parse(link.url))
    }

    @Test fun tolerantAboutWhatFollowsTheToken() {
        val link = WebShareLink("10.0.0.5", 8081, "Ab3xK9pQrT2m")
        for (t in listOf("http://10.0.0.5:8081/s/Ab3xK9pQrT2m", "http://10.0.0.5:8081/s/Ab3xK9pQrT2m/index.html", "http://10.0.0.5:8081/s/Ab3xK9pQrT2m/?x=1", "  http://10.0.0.5:8081/s/Ab3xK9pQrT2m/\n", "HTTP://10.0.0.5:8081/s/Ab3xK9pQrT2m/")) {
            assertEquals(t, link, WebShareLink.parse(t))
        }
    }

    @Test fun theAppLinkFromTheWebPageOpensTheSameShare() {
        val link = WebShareLink("172.20.10.1", 8080, "Zz9Zz9Zz9Zz9")
        assertEquals(link, WebShareLink.parse(link.appLink))
    }

    @Test fun onlyPrivateAddressesAndPlausibleTokensAreAccepted() {
        for (t in listOf(
            "http://8.8.8.8:8080/s/Ab3xK9pQrT2m/",          // public host
            "http://example.com/s/Ab3xK9pQrT2m/",           // a name
            "https://192.168.1.2:8080/s/Ab3xK9pQrT2m/",     // not http
            "http://192.168.1.2:8080/x/Ab3xK9pQrT2m/",      // wrong prefix
            "http://192.168.1.2:8080/s/short/",             // token too short
            "http://192.168.1.2:8080/s/has-dash-in-token/", // bad characters
            "http://192.168.1.2:99999/s/Ab3xK9pQrT2m/",     // bad port
            "http://192.168.1.2/",
            "share64://c?v=1&i=abcdef123456&p=1&a=10.0.0.1", // a pairing code, not a web share
            "share64://w?u=http%3A%2F%2F8.8.8.8%2Fs%2FAb3xK9pQrT2m%2F",
            "",
        )) assertNull(t, WebShareLink.parse(t))
    }

    @Test fun defaultPortIsEighty() {
        assertEquals(80, WebShareLink.parse("http://192.168.1.2/s/Ab3xK9pQrT2m/")?.port)
    }
}
