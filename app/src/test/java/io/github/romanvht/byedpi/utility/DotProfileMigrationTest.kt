package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DotProfileMigrationTest {
    @Test fun standardProfileUsesTestedVariantWhenDotIsEnabled() {
        assertEquals("-a1 -r-5+se", dotCompatibleArgsOrNull("-o1 -a1 -r-5+se", true, false))
    }

    @Test fun customOrAlreadyMigratedProfileIsPreserved() {
        assertNull(dotCompatibleArgsOrNull("-s1", true, false))
        assertNull(dotCompatibleArgsOrNull("-o1 -a1 -r-5+se", false, false))
        assertNull(dotCompatibleArgsOrNull("-o1 -a1 -r-5+se", true, true))
    }
}
