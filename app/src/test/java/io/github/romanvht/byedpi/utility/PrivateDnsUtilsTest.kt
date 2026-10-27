package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.PrivateDnsState
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDnsUtilsTest {
    @Test fun automaticPrivateDnsIsReportedAsActive() {
        assertEquals(
            PrivateDnsState.Configured("Автоматически"),
            PrivateDnsUtils.stateFor(true, null, "Автоматически"),
        )
    }

    @Test fun disabledPrivateDnsIsInactive() {
        assertEquals(PrivateDnsState.Inactive, PrivateDnsUtils.stateFor(false, null, "Автоматически"))
    }

    @Test fun namedPrivateDnsRetainsItsHostname() {
        assertEquals(
            PrivateDnsState.Configured("dns.example"),
            PrivateDnsUtils.stateFor(true, "dns.example", "Автоматически"),
        )
    }
}
