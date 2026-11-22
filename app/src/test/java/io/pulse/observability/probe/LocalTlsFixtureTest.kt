package io.pulse.observability.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Local TLS fixture with a certificate absent from the platform trust store.
 *
 * A TCP proxy sits between the probe and a real JSSE TLS server. The proxy
 * records the client's first TLS record (the ClientHello) before forwarding
 * it, which keeps the TLS handshake authentic: a raw read from an SSLSocket
 * stream would itself perform the handshake and expose application data, not
 * handshake records.
 */
class LocalTlsFixtureTest {
    @Test fun `untrusted local certificate fails TLS without reading HTTP`() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=pulse.test")
        val now = System.currentTimeMillis()
        val certificateBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()), Date(now - 60_000), Date(now + 3_600_000), name, pair.public,
        ).addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            org.bouncycastle.asn1.x509.GeneralNames(org.bouncycastle.asn1.x509.GeneralName(
                org.bouncycastle.asn1.x509.GeneralName.dNSName, "pulse.test",
            )),
        )
        val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
            certificateBuilder.build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
        )
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("fixture", pair.private, CharArray(0), arrayOf<Certificate>(certificate))
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val tlsServer = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            .serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val proxyListener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        var clientHello: ByteArray? = null
        var serverError: Throwable? = null
        var handshakeRejected = false

        val serverThread = Thread {
            try {
                (tlsServer.accept() as SSLSocket).use { socket ->
                    try {
                        socket.startHandshake()
                    } catch (_: SSLHandshakeException) {
                        handshakeRejected = true
                    }
                }
            } catch (error: Throwable) {
                serverError = error
            }
        }.apply { isDaemon = true; start() }

        val proxyThread = Thread {
            try {
                proxyListener.accept().use { client ->
                    Socket("127.0.0.1", tlsServer.localPort).use { upstream ->
                        val downstream = client.getInputStream()
                        val forward = Thread {
                            try {
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = upstream.getInputStream().read(buffer)
                                    if (read < 0) break
                                    client.getOutputStream().write(buffer, 0, read)
                                    client.getOutputStream().flush()
                                }
                            } catch (_: Exception) { }
                        }
                        forward.isDaemon = true
                        forward.start()
                        // Capture exactly one TLS record from the client, then
                        // forward it verbatim so JSSE still sees a real hello.
                        val header = ByteArray(5)
                        var count = 0
                        while (count < header.size) {
                            val read = downstream.read(header, count, header.size - count)
                            if (read < 0) break
                            count += read
                        }
                        val bodyLength = (header[3].toInt() and 0xff) * 256 + (header[4].toInt() and 0xff)
                        val body = ByteArray(bodyLength)
                        var offset = 0
                        while (offset < bodyLength) {
                            val read = downstream.read(body, offset, bodyLength - offset)
                            if (read < 0) break
                            offset += read
                        }
                        clientHello = header + body
                        upstream.getOutputStream().write(header)
                        upstream.getOutputStream().write(body)
                        upstream.getOutputStream().flush()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = downstream.read(buffer)
                            if (read < 0) break
                            upstream.getOutputStream().write(buffer, 0, read)
                            upstream.getOutputStream().flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (serverError == null) serverError = error
            }
        }.apply { isDaemon = true; start() }

        val result = NetworkProbe(object : HostResolver {
            override suspend fun resolve(host: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }).measure(TargetParser.parse("https://pulse.test:${proxyListener.localPort}/"))

        val failure = result as ProbeResult.Failure
        assertEquals(FailureKind.TLS_CERTIFICATE, failure.measurement.failureCode)
        assertNull(failure.measurement.responseCode)
        assertTrue(failure.measurement.stages.map { it.stage }.contains(StageName.TCP))
        assertTrue(failure.measurement.stages.none { it.stage == StageName.HTTP_HEADERS })

        serverThread.join(5_000)
        proxyListener.close()
        tlsServer.close()
        proxyThread.join(5_000)

        assertEquals("server fixture failed: $serverError", null, serverError)
        assertTrue("server did not observe the client's certificate rejection", handshakeRejected)
        val hello = clientHello
        assertTrue("ClientHello was not captured", hello != null)
        if (hello != null) {
            assertEquals(22, hello[0].toInt())  // handshake record type
            // JSSE omits SNI for single-label hosts such as "localhost"; the
            // fixture therefore uses a dotted test name to prove SNI is sent.
            assertTrue("TLS ClientHello did not contain SNI pulse.test", hello.contains("pulse.test".toByteArray()))
        }
    }

    private fun ByteArray.contains(value: ByteArray): Boolean = indices.any { start ->
        start + value.size <= size && value.indices.all { index -> this[start + index] == value[index] }
    }
}
