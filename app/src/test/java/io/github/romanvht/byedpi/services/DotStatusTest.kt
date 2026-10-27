package io.github.romanvht.byedpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class DotStatusTest {
    @Test fun reportsConfigurationAndRuntimeStates() {
        assertEquals(DotStatus.OFF, DotStatus.resolve(false, true, 1, 1, false, false, false))
        assertEquals(DotStatus.UNSUPPORTED, DotStatus.resolve(true, false, 1, 1, false, false, false))
        assertEquals(DotStatus.EMPTY, DotStatus.resolve(true, true, 0, 0, false, false, false))
        assertEquals(DotStatus.OUTSIDE_VPN, DotStatus.resolve(true, true, 1, 0, false, false, false))
        assertEquals(DotStatus.PRIVATE_DNS, DotStatus.resolve(true, true, 1, 1, true, false, false))
        assertEquals(DotStatus.READY, DotStatus.resolve(true, true, 1, 1, false, false, false))
        assertEquals(DotStatus.ACTIVE, DotStatus.resolve(true, true, 1, 1, false, true, false))
        assertEquals(DotStatus.TLS_ERROR, DotStatus.resolve(true, true, 1, 1, false, true, true))
    }
}
