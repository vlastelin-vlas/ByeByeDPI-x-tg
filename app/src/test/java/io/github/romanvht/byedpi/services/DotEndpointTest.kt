package io.github.romanvht.byedpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DotEndpointTest {
    @Test fun parsesHostAndOptionalPort() {
        assertEquals(DotEndpoint("dns.example", 853), DotEndpoint.parse(" dns.example "))
        assertEquals(DotEndpoint("dns.example", 8853), DotEndpoint.parse("dns.example:8853"))
        assertEquals(DotEndpoint("1.1.1.1", 853), DotEndpoint.parse("1.1.1.1"))
    }

    @Test fun rejectsInputsThatAreNotDotEndpoints() {
        listOf("", "https://dns.example", "dns.example/path", "dns.example:0",
            "dns.example:65536", "dns.example:abc", "user@dns.example")
            .forEach { assertNull(it, DotEndpoint.parse(it)) }
    }
}
