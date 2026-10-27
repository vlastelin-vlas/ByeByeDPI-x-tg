package io.github.romanvht.byedpi.services

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DotDnsRelay(
    private val query: (ByteArray) -> ByteArray,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val udp = DatagramSocket(0, loopback)
    private val tcp = try {
        ServerSocket(udp.localPort, 50, loopback)
    } catch (error: Exception) {
        udp.close()
        throw error
    }
    val port: Int = udp.localPort
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val udpThread = Thread(::receiveUdp, "dot-dns-udp").apply { isDaemon = true; start() }
    private val tcpThread = Thread(::acceptTcp, "dot-dns-tcp").apply { isDaemon = true; start() }

    private fun receiveUdp() {
        while (!udp.isClosed) {
            val packet = DatagramPacket(ByteArray(65535), 65535)
            try {
                udp.receive(packet)
                val request = packet.data.copyOf(packet.length)
                val source = packet.socketAddress
                try {
                    workers.execute {
                        val response = resolve(request) ?: return@execute
                        try {
                            udp.send(DatagramPacket(response, response.size, source))
                        } catch (error: Exception) {
                            onError(error)
                        }
                    }
                } catch (error: RejectedExecutionException) {
                    onError(error)
                    servfail(request)?.let { udp.send(DatagramPacket(it, it.size, source)) }
                }
            } catch (error: SocketException) {
                if (!udp.isClosed) onError(error)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun acceptTcp() {
        while (!tcp.isClosed) {
            try {
                val client = tcp.accept()
                try {
                    workers.execute { serveTcp(client) }
                } catch (error: RejectedExecutionException) {
                    onError(error)
                    client.close()
                }
            } catch (error: SocketException) {
                if (!tcp.isClosed) onError(error)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun serveTcp(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 10000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                while (!socket.isClosed) {
                    val length = try { input.readUnsignedShort() } catch (_: EOFException) { break }
                    if (length == 0) break
                    val request = ByteArray(length)
                    input.readFully(request)
                    val response = resolve(request) ?: break
                    output.writeShort(response.size)
                    output.write(response)
                    output.flush()
                }
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun resolve(request: ByteArray): ByteArray? = try {
        query(request)
    } catch (error: Exception) {
        onError(error)
        servfail(request)
    }

    private fun servfail(request: ByteArray): ByteArray? {
        if (request.size < 12) return null
        return request.copyOf().apply {
            this[2] = ((this[2].toInt() and 0x79) or 0x80).toByte()
            this[3] = 0x02
            fill(0, 6, 10)
        }
    }

    override fun close() {
        udp.close()
        tcp.close()
        workers.shutdownNow()
    }
}
