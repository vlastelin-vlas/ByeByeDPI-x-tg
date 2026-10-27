package io.github.romanvht.byedpi.services

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class DotClient(
    private val handshakeOnQuery: Boolean = true,
    private val openTlsSocket: () -> SSLSocket,
) {
    fun query(request: ByteArray): ByteArray {
        require(request.size in 1..65535) { "Invalid DNS request length" }
        return openTlsSocket().use { tls ->
            if (handshakeOnQuery) {
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
            }
            DataOutputStream(tls.outputStream).apply {
                writeShort(request.size)
                write(request)
                flush()
            }
            val input = DataInputStream(tls.inputStream)
            val length = input.readUnsignedShort()
            if (length == 0) throw IOException("Empty DoT response")
            ByteArray(length).also(input::readFully)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000

        fun forVpn(service: VpnService, connectivity: ConnectivityManager, endpoint: DotEndpoint): DotClient = DotClient(handshakeOnQuery = false) {
            val networks = connectivity.allNetworks
                .mapNotNull { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) return@mapNotNull null
                    network to capabilities
                }
                .sortedWith(compareByDescending<Pair<android.net.Network, NetworkCapabilities>> { (_, capabilities) ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }.thenByDescending { (_, capabilities) ->
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                })
            val network = networks.firstOrNull()?.first ?: throw IOException("No underlying network for DoT")

            var lastError: IOException? = null
            for (address in network.getAllByName(endpoint.host)) {
                val raw = Socket()
                try {
                    raw.bind(null)
                    network.bindSocket(raw)
                    if (!service.protect(raw)) throw IOException("Could not protect DoT socket")
                    raw.connect(InetSocketAddress(address, endpoint.port), TIMEOUT_MS)
                    raw.soTimeout = TIMEOUT_MS
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
                    tls.soTimeout = TIMEOUT_MS
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    return@DotClient tls
                } catch (error: IOException) {
                    raw.close()
                    lastError = error
                }
            }
            throw lastError ?: IOException("No address for DoT server")
        }
    }
}
