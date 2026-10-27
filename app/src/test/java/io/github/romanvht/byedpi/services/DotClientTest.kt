package io.github.romanvht.byedpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

class DotClientTest {
    @Test fun framesRequestAndReadsSplitResponse() {
        val socket = FakeTlsSocket(byteArrayOf(0, 3, 1, 2, 3))
        val reply = DotClient { socket }.query(byteArrayOf(10, 11))
        assertArrayEquals(byteArrayOf(1, 2, 3), reply)
        assertArrayEquals(byteArrayOf(0, 2, 10, 11), socket.written.toByteArray())
        assertEquals("HTTPS", socket.parameters.endpointIdentificationAlgorithm)
        assertTrue(socket.closed)
    }

    @Test fun refusesEmptyAndOversizeRequestsBeforeConnecting() {
        var opens = 0
        val client = DotClient { opens++; FakeTlsSocket(byteArrayOf(0, 1, 1)) }
        expectFailure<IllegalArgumentException> { client.query(byteArrayOf()) }
        expectFailure<IllegalArgumentException> { client.query(ByteArray(65536)) }
        assertEquals(0, opens)
    }

    @Test fun tlsFailureHasNoFallback() {
        val socket = FakeTlsSocket(byteArrayOf(), failHandshake = true)
        expectFailure<SSLHandshakeException> { DotClient { socket }.query(byteArrayOf(1)) }
        assertEquals(0, socket.written.size())
        assertTrue(socket.closed)
    }

    @Test fun timeoutAndEmptyReplyFail() {
        val timeout = FakeTlsSocket(byteArrayOf(), timeoutOnRead = true)
        expectFailure<SocketTimeoutException> { DotClient { timeout }.query(byteArrayOf(1)) }
        expectFailure<IOException> { DotClient { FakeTlsSocket(byteArrayOf(0, 0)) }.query(byteArrayOf(1)) }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private class FakeTlsSocket(
        response: ByteArray,
        private val failHandshake: Boolean = false,
        private val timeoutOnRead: Boolean = false,
    ) : SSLSocket() {
        val written = ByteArrayOutputStream()
        var closed = false
        var parameters = SSLParameters()
        private val responseStream = object : ByteArrayInputStream(response) {
            override fun read(): Int {
                if (timeoutOnRead) throw SocketTimeoutException("timeout")
                return super.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (timeoutOnRead) throw SocketTimeoutException("timeout")
                return super.read(b, off, minOf(len, 1))
            }
        }

        override fun getInputStream(): InputStream = responseStream
        override fun getOutputStream() = written
        override fun close() { closed = true }
        override fun getSSLParameters(): SSLParameters = parameters
        override fun setSSLParameters(params: SSLParameters) { parameters = params }
        override fun startHandshake() { if (failHandshake) throw SSLHandshakeException("bad certificate") }
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<String>?) = Unit
        override fun getSession(): SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
    }
}
