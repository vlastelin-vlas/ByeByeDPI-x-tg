package io.github.romanvht.byedpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectiveDnsTest {
    @Test fun disabledVpnFilterKeepsDnsSelectionIndependent() {
        assertEquals(setOf("a", "c"), SelectiveDns.eligiblePackages("disable", setOf("b"), setOf("a", "c")))
    }

    @Test fun whitelistRequiresAppInVpn() {
        assertEquals(setOf("a"), SelectiveDns.eligiblePackages("whitelist", setOf("a", "b"), setOf("a", "c")))
    }

    @Test fun blacklistExcludesAppFromDns() {
        assertEquals(setOf("c"), SelectiveDns.eligiblePackages("blacklist", setOf("a"), setOf("a", "c")))
    }

    @Test fun unknownFilterNeverClaimsDnsProtection() {
        assertEquals(emptySet<String>(), SelectiveDns.eligiblePackages("unknown", emptySet(), setOf("a")))
    }

    @Test fun missingPackagesAndSharedUidsAreSafe() {
        val uids = SelectiveDns.resolveUids(setOf("installed", "removed", "same_uid")) {
            when (it) {
                "installed", "same_uid" -> 1234
                else -> null
            }
        }
        assertEquals(setOf(1234), uids)
    }

    @Test fun onlySelectedDnsFlowUsesLocalRelay() {
        val selected = setOf(1234)
        assertEquals(1053, SelectiveDns.relayPortFor(1234, 53, selected, 1053))
        assertEquals(0, SelectiveDns.relayPortFor(5678, 53, selected, 1053))
        assertEquals(0, SelectiveDns.relayPortFor(-1, 53, selected, 1053))
        assertEquals(0, SelectiveDns.relayPortFor(1234, 443, selected, 1053))
    }

}
