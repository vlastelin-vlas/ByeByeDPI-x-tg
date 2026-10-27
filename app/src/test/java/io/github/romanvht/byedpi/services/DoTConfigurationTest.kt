package io.github.romanvht.byedpi.services

import io.github.romanvht.byedpi.data.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoTConfigurationTest {
    @Test fun oldCallersKeepDotDisabled() {
        val config = Configuration(listOf("-a"), "127.0.0.1", 1080)
        assertFalse(config.dotEnabled)
        assertEquals(emptyList<String>(), config.dotPackages)
        assertEquals("dns.zapret.moe", config.dotServer)
    }

    @Test fun selectedPackagesSurviveConfigurationCopy() {
        val config = Configuration(listOf("-a"), "127.0.0.1", 1080,
            dotEnabled = true, dotPackages = listOf("example.a", "example.b"))
        val copied = config.copy(dns = "1.1.1.1")
        assertTrue(copied.dotEnabled)
        assertEquals(listOf("example.a", "example.b"), copied.dotPackages)
        assertEquals("dns.zapret.moe", copied.dotServer)
    }
}
