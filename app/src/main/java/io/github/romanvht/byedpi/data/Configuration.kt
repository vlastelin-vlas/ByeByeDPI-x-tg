package io.github.romanvht.byedpi.data

data class Configuration(
    val args: List<String>,
    val host: String,
    val port: Int,
    val dns: String = "",
    val ipv6: Boolean = false,
    val appListType: String = "disable",
    val apps: List<String> = emptyList(),
    val dotEnabled: Boolean = false,
    val dotPackages: List<String> = emptyList(),
    val dotServer: String = "dns.zapret.moe",
)
