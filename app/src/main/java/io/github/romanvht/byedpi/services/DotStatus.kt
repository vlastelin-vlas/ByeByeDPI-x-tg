package io.github.romanvht.byedpi.services

internal enum class DotStatus {
    OFF, UNSUPPORTED, EMPTY, OUTSIDE_VPN, PRIVATE_DNS, READY, ACTIVE, TLS_ERROR;

    companion object {
        fun resolve(
            enabled: Boolean,
            supported: Boolean,
            selectedCount: Int,
            eligibleCount: Int,
            privateDns: Boolean,
            vpnRunning: Boolean,
            tlsError: Boolean,
        ): DotStatus = when {
            !enabled -> OFF
            !supported -> UNSUPPORTED
            selectedCount == 0 -> EMPTY
            eligibleCount == 0 -> OUTSIDE_VPN
            privateDns -> PRIVATE_DNS
            tlsError && vpnRunning -> TLS_ERROR
            vpnRunning -> ACTIVE
            else -> READY
        }
    }
}
