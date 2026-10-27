package io.github.romanvht.byedpi.core

object TProxyService {
    @Volatile
    var dnsRoute: ((Int, String, Int, String, Int) -> Int)? = null

    @Suppress("unused") // Called from hev-jni.c
    fun routeDns(protocol: Int, source: String, sourcePort: Int, destination: String, destinationPort: Int): Int =
        dnsRoute?.invoke(protocol, source, sourcePort, destination, destinationPort) ?: 0

    init {
        System.loadLibrary("byedpi")
    }

    external fun startTunnel(config: String, fd: Int): Int

    external fun stopTunnel()
}
