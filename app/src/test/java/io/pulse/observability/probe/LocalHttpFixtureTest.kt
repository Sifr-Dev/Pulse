package io.pulse.observability.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LocalHttpFixtureTest {
    @Test fun `real socket reports content length and monotonic stages`() = runBlocking {
        var request = ""
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            server.accept().use { socket ->
                request = socket.getInputStream().bufferedReader().readHeaders()
                socket.getOutputStream().bufferedWriter().use {
                    it.write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello")
                    it.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val result = measure(server.localPort, "/health?x=1")
        val success = (result as ProbeResult.Success).measurement
        assertEquals(200, success.responseCode)
        assertEquals(5L, success.bodyBytes)
        assertEquals("1.1", success.httpVersion)
        assertEquals("127.0.0.1", success.resolvedAddress)
        assertNotNull(success.firstResponseByteNanos)
        assertEquals(listOf(StageName.DNS, StageName.TCP, StageName.HTTP_HEADERS, StageName.HTTP_BODY), success.stages.map { it.stage })
        assertTrue(success.stages.all { it.durationNanos >= 0 })
        assertTrue(success.totalNanos >= success.stages.sumOf { it.durationNanos } - 100)
        assertTrue(request.contains("GET /health?x=1 HTTP/1.1"))
        assertTrue(request.contains("Host: 127.0.0.1"))
        assertTrue(request.contains("Accept-Encoding: identity"))
        server.close()
        thread.join(1_000)
    }

    @Test fun `chunked body is counted without persisting payload`() {
        val result = measure("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\ntest\r\n3\r\n123\r\n0\r\nX-Trailer: value\r\n\r\n")
        val measurement = (result as ProbeResult.Success).measurement
        assertEquals(7L, measurement.bodyBytes)
        assertNull(measurement.tlsProtocol)
    }

    @Test fun `declared oversized body fails before reading body`() {
        val result = measure("HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\n", maxBody = 100)
        val failure = result as ProbeResult.Failure
        assertEquals(FailureKind.BODY_TOO_LARGE, failure.measurement.failureCode)
        assertEquals(StageName.HTTP_BODY, failure.measurement.failureStage)
        assertEquals(200, failure.measurement.responseCode)
    }

    @Test fun `chunked body larger than limit fails`() {
        val result = measure("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n65\r\n${"x".repeat(101)}\r\n0\r\n\r\n", maxBody = 100)
        assertEquals(FailureKind.BODY_TOO_LARGE, (result as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `truncated body is a typed failure`() {
        val result = measure("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nab")
        assertEquals(FailureKind.BODY_FAILURE, (result as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `invalid protocol and ambiguous framing are rejected`() {
        listOf(
            "NOT HTTP\r\n\r\n",
            "HTTP/2 200\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: nope\r\n\r\n",
        ).forEach { response ->
            val result = measure(response)
            assertEquals(response, FailureKind.HTTP_PROTOCOL, (result as ProbeResult.Failure).measurement.failureCode)
        }
    }

    @Test fun `http error status is still a completed network measurement`() {
        val result = measure("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n")
        assertEquals(503, (result as ProbeResult.Success).measurement.responseCode)
    }

    @Test fun `dns error and empty response are typed`() = runBlocking {
        val dnsFailure = NetworkProbe(object : HostResolver { override suspend fun resolve(host: String): List<InetAddress> = throw IOException("resolver offline") }).measure(TargetParser.parse("http://example.com/"))
        assertEquals(FailureKind.DNS_FAILURE, (dnsFailure as ProbeResult.Failure).measurement.failureCode)
        assertEquals(FailureKind.HTTP_FAILURE, (measure(null) as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `dns timeout is typed`() = runBlocking {
        val result = NetworkProbe(object : HostResolver {
            override suspend fun resolve(host: String): List<InetAddress> {
                delay(10_000)
                return emptyList()
            }
        }, ProbeConfig(dnsTimeoutMillis = 25))
            .measure(TargetParser.parse("http://example.com/"))
        assertEquals(FailureKind.DNS_TIMEOUT, (result as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `connect refusal is a typed tcp failure`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val result = NetworkProbe(object : HostResolver { override suspend fun resolve(host: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) })
            .measure(TargetParser.parse("http://127.0.0.1:$port/"))
        assertEquals(FailureKind.TCP_FAILURE, (result as ProbeResult.Failure).measurement.failureCode)
    }

    @Test fun `cancelled blocked body is not converted to failure`() = runBlocking {
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
        val probe = NetworkProbe(object : HostResolver { override suspend fun resolve(host: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) })
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

    @Test fun `response header timeout is bounded`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { Thread.sleep(2_000) }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val result = runBlocking {
            NetworkProbe(object : HostResolver { override suspend fun resolve(host: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) },
                ProbeConfig(headersTimeoutMillis = 100, totalTimeoutMillis = 1_000))
                .measure(TargetParser.parse("http://127.0.0.1:${server.localPort}/"))
        }
        assertEquals(FailureKind.HTTP_TIMEOUT, (result as ProbeResult.Failure).measurement.failureCode)
        server.close()
        thread.join(1_000)
    }

    private fun measure(response: String?, maxBody: Int = 65_536): ProbeResult = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    if (response != null) {
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            measure(server.localPort, "/", maxBody)
        } finally {
            server.close()
            thread.join(1_000)
        }
    }

    private suspend fun measure(port: Int, path: String, maxBody: Int = 65_536): ProbeResult =
        NetworkProbe(object : HostResolver { override suspend fun resolve(host: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) }, ProbeConfig(maxBodyBytes = maxBody))
            .measure(TargetParser.parse("http://127.0.0.1:$port$path"))

    private fun java.io.BufferedReader.readHeaders(): String = buildString {
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            append(line).append("\n")
        }
    }
}
