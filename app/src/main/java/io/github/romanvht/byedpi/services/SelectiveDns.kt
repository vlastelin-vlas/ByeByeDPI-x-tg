package io.github.romanvht.byedpi.services

import android.content.pm.PackageManager

internal object SelectiveDns {
    fun eligiblePackages(vpnMode: String, vpnPackages: Set<String>, dnsPackages: Set<String>): Set<String> =
        when (vpnMode) {
            "disable" -> dnsPackages
            "whitelist" -> dnsPackages intersect vpnPackages
            "blacklist" -> dnsPackages - vpnPackages
            else -> emptySet()
        }

    fun resolveUids(packages: Set<String>, uidForPackage: (String) -> Int?): Set<Int> =
        packages.mapNotNull(uidForPackage).toSet()

    fun uidForPackages(packageManager: PackageManager, packages: Set<String>): Set<Int> =
        resolveUids(packages) { packageName ->
            try {
                packageManager.getApplicationInfo(packageName, 0).uid
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    fun relayPortFor(uid: Int, destinationPort: Int, selectedUids: Set<Int>, relayPort: Int): Int =
        if (uid >= 0 && destinationPort == 53 && uid in selectedUids) relayPort else 0

}
