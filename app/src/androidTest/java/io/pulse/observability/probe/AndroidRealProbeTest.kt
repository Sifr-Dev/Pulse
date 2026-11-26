package io.pulse.observability.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class AndroidRealProbeTest {
    @Test fun numericLoopbackNeedsNoDnsTransaction() = runBlocking {
        // Android's DnsResolver is a network resolver: it does not read
        // /etc/hosts, so "localhost" is NXDOMAIN on an emulator. A numeric
        // address is resolved locally and must round-trip exactly.
        val addresses = SystemResolver().resolve("127.0.0.1")
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), addresses)
    }

    @Test fun unknownHostnameFailsTypedNotHung() = runBlocking {
        // .invalid is guaranteed not to exist (RFC 2606). The probe must
        // surface the resolver error instead of fabricating an address.
        var failed = false
        try {
            SystemResolver().resolve("pulse-does-not-exist.invalid")
        } catch (_: java.net.UnknownHostException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test fun localSocketHasNoFabricatedTlsForHttp() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val result = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })
            .measure(TargetParser.parse("http://127.0.0.1:${server.localPort}/"))
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(204, measurement.responseCode)
        assertEquals(0L, measurement.bodyBytes)
        assertTrue(measurement.stages.none { it.stage == StageName.TLS })
        server.close()
        thread.join(1_000)
    }

    @Test fun interim103OnDeviceIsNotReportedAsFinalStatus() = runBlocking {
        // Android's runtime must reach the same conclusion as the JVM suite: the final status
        // wins and the interim count is recorded. This runs on the device stack, not the JVM.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    output.flush()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val result = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })
            .measure(TargetParser.parse("http://127.0.0.1:" + server.localPort + "/"))
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(200, measurement.responseCode)
        assertEquals(1, measurement.interimResponses)
        assertEquals(2L, measurement.bodyBytes)
        server.close()
        thread.join(1_000)
    }

    @Test fun crossOriginRedirectIsRecordedWithoutBeingFollowed() = runBlocking {
        // Plain HTTP keeps this a real end-to-end measurement on device; an HTTPS origin would
        // stop at TLS_CERTIFICATE because the loopback fixture is not platform-trusted.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    socket.getOutputStream().write(
                        ("HTTP/1.1 302 Found\r\nLocation: https://secure.example/next?token=x\r\n" +
                            "Content-Length: 0\r\n\r\n").toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val result = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })
            .measure(TargetParser.parse("http://127.0.0.1:" + server.localPort + "/start"))
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(302, measurement.responseCode)
        assertEquals(RedirectKind.UPGRADE, measurement.redirectKind)
        // Only the origin is stored: no path, no query token.
        assertEquals("https://secure.example", measurement.redirectTarget)
        server.close()
        thread.join(1_000)
    }

    @Test fun cancellationClosesBlockedSocketWithoutMeasurement() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".toByteArray())
                    output.flush()
                    Thread.sleep(4_000)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val probe = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })
        val job = async {
            probe.measure(TargetParser.parse("http://127.0.0.1:${server.localPort}/"))
        }
        delay(250)
        job.cancel()
        try {
            withTimeout(2_000) { job.await() }
            throw AssertionError("Expected CancellationException")
        } catch (_: CancellationException) { }
        server.close()
        thread.join(1_000)
    }
}
