package io.github.romanvht.byedpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class DotDnsRelayTest {
    private val request = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
    private val response = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun forwardsUdpDnsReply() {
        DotDnsRelay(query = { assertArrayEquals(request, it); response }).use { relay ->
            DatagramSocket().use { client ->
                client.soTimeout = 2000
                client.send(DatagramPacket(request, request.size, loopback, relay.port))
                val packet = DatagramPacket(ByteArray(512), 512)
                client.receive(packet)
                assertArrayEquals(response, packet.data.copyOf(packet.length))
            }
        }
    }

    @Test fun forwardsTcpDnsFrame() {
        DotDnsRelay(query = { assertArrayEquals(request, it); response }).use { relay ->
            Socket().use { client ->
                client.connect(InetSocketAddress(loopback, relay.port), 2000)
                client.soTimeout = 2000
                DataOutputStream(client.getOutputStream()).apply {
                    writeShort(request.size)
                    write(request)
                    flush()
                }
                val input = DataInputStream(client.getInputStream())
                val result = ByteArray(input.readUnsignedShort())
                input.readFully(result)
                assertArrayEquals(response, result)
            }
        }
    }

    @Test fun returnsServfailWithoutPlaintextFallback() {
        DotDnsRelay(query = { throw IOException("DoT unavailable") }).use { relay ->
            DatagramSocket().use { client ->
                client.soTimeout = 2000
                client.send(DatagramPacket(request, request.size, loopback, relay.port))
                val packet = DatagramPacket(ByteArray(512), 512)
                client.receive(packet)
                assertEquals(0x12, packet.data[0].toInt())
                assertEquals(0x34, packet.data[1].toInt())
                assertEquals(2, packet.data[3].toInt() and 0x0f)
            }
        }
    }
}
