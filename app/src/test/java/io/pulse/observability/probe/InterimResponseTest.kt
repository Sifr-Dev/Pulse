package io.pulse.observability.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Interim 1xx responses against a real loopback socket.
 *
 * RFC 9110 allows any number of interim responses before the final status. A probe that treats
 * `103 Early Hints` or `100 Continue` as final records a successful "HTTP 103" measurement that
 * never happened, so these tests drive a real `ServerSocket` and assert that the final status is
 * the recorded one.
 */
class InterimResponseTest {
    @Test fun `103 early hints before 200 records the final status`() = runBlocking {
        val response = "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n" +
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        val result = measure(response)
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(200, measurement.responseCode)
        assertEquals(2L, measurement.bodyBytes)
        assertEquals(1, measurement.interimResponses)
        assertEquals("1.1", measurement.httpVersion)
    }

    @Test fun `100 continue before 204 records the final status`() = runBlocking {
        val result = measure("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n")
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(204, measurement.responseCode)
        assertEquals(0L, measurement.bodyBytes)
        assertEquals(1, measurement.interimResponses)
    }

    @Test fun `several interim responses are counted in order`() = runBlocking {
        val response = "HTTP/1.1 100 Continue\r\n\r\n" +
            "HTTP/1.1 103 Early Hints\r\n\r\n" +
            "HTTP/1.1 103 Early Hints\r\n\r\n" +
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        val measurement = (measure(response) as ProbeResult.Success).measurement
        assertEquals(404, measurement.responseCode)
        assertEquals(3, measurement.interimResponses)
    }

    @Test fun `no interim responses reports zero rather than null`() = runBlocking {
        val measurement = (measure("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") as ProbeResult.Success).measurement
        assertEquals(0, measurement.interimResponses)
        assertNull(measurement.redirectKind)
    }

    @Test fun `101 switching protocols is a typed protocol failure`() = runBlocking {
        val result = measure("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
        val failure = result as ProbeResult.Failure
        assertEquals(FailureKind.HTTP_PROTOCOL, failure.measurement.failureCode)
        assertEquals(StageName.HTTP_HEADERS, failure.measurement.failureStage)
        assertNull(failure.measurement.responseCode)
    }

    @Test fun `unbounded interim responses are rejected as a protocol failure`() = runBlocking {
        val interim = "HTTP/1.1 103 Early Hints\r\n\r\n".repeat(12)
        val result = measure(interim + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        val failure = result as ProbeResult.Failure
        assertEquals(FailureKind.HTTP_PROTOCOL, failure.measurement.failureCode)
        assertEquals(StageName.HTTP_HEADERS, failure.measurement.failureStage)
    }

    @Test fun `interim only then eof is an http failure not a success`() = runBlocking {
        // The server sends one interim response and closes before any final status. The probe
        // must not report a completed "HTTP 103" measurement.
        val result = measure("HTTP/1.1 103 Early Hints\r\n\r\n")
        assertEquals(FailureKind.HTTP_FAILURE, (result as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `header timing covers the whole interim exchange`() = runBlocking {
        val result = runBlocking {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 103 Early Hints\r\n\r\n".toByteArray())
                        output.flush()
                        Thread.sleep(150)
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true; start() }
            try {
                // The JVM unit-test stub for android.os.SystemClock returns 0 for every clock
                // call, so this one timing assertion injects a real monotonic clock instead of
                // asserting against a stubbed zero.
                probe(MonotonicClock { System.nanoTime() })
                    .measure(TargetParser.parse("http://127.0.0.1:${server.localPort}/"))
            } finally {
                server.close()
                thread.join(1_000)
            }
        }
        val measurement = (result as ProbeResult.Success).measurement
        val headers = measurement.duration(StageName.HTTP_HEADERS) ?: 0
        assertTrue("header stage must include the 150 ms interim wait, was $headers", headers >= 140_000_000)
    }

    private fun measure(response: String): ProbeResult = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readHeaders()
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            probe().measure(TargetParser.parse("http://127.0.0.1:${server.localPort}/"))
        } finally {
            server.close()
            thread.join(1_000)
        }
    }

    private fun probe(clock: MonotonicClock = MonotonicClock.SYSTEM) = NetworkProbe(
        HostResolver { listOf(InetAddress.getByName("127.0.0.1")) },
        ProbeConfig(maxInterimResponses = 8),
        clock,
    )

    private fun java.io.BufferedReader.readHeaders(): String = buildString {
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            append(line).append("\n")
        }
    }
}
