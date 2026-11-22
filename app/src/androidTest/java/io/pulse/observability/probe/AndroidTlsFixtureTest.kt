package io.pulse.observability.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Local TLS uses an ephemeral fixture key. The app does not install or persist that key. */
@RunWith(AndroidJUnit4::class)
class AndroidTlsFixtureTest {
    @Test fun realTlsCapturesServerNameAndUsesPlatformTrust() = runBlocking {
        val certificate = TestCertificate.create("localhost")
        val server = TestCertificate.server(certificate)
        var requestedName: String? = null
        val thread = Thread {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    val session = socket.session
                    socket.getInputStream().bufferedReader().let {
                        while (it.readLine()?.isNotEmpty() == true) { }
                    }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    socket.getOutputStream().flush()
                    requestedName = session.getValue("SNI_HOST_NAME") as? String
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val result = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })
            .measure(TargetParser.parse("https://localhost:${server.localPort}/"))
        val failure = result as ProbeResult.Failure
        assertEquals(FailureKind.TLS_CERTIFICATE, failure.measurement.failureCode)
        assertTrue(failure.measurement.totalNanos >= 0)
        // Trust fails before a certificate identity assertion can be relied on; no HTTP response is fabricated.
        assertNull(failure.measurement.responseCode)
        assertNull(failure.measurement.tlsProtocol)
        assertTrue(failure.measurement.stages.map { it.stage }.contains(StageName.TCP))
        assertTrue(failure.measurement.stages.map { it.stage }.none { it == StageName.HTTP_HEADERS })
        server.close()
        thread.join(1_000)
    }
}

internal object TestCertificate {
    data class Fixture(val key: java.security.PrivateKey, val certificate: X509Certificate)

    fun create(host: String): Fixture {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val content = org.bouncycastle.asn1.x500.X500Name("CN=$host")
        val serial = BigInteger(64, SecureRandom())
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            content, serial, Date(now - 60_000), Date(now + 3_600_000), content, keyPair.public,
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            org.bouncycastle.asn1.x509.GeneralNames(org.bouncycastle.asn1.x509.GeneralName(
                org.bouncycastle.asn1.x509.GeneralName.dNSName, host,
            )),
        )
        val holder = builder.build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
        return Fixture(keyPair.private, certificate)
    }

    fun server(fixture: Fixture): SSLServerSocket {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("fixture", fixture.key, CharArray(0), arrayOf<Certificate>(fixture.certificate))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    }
}
