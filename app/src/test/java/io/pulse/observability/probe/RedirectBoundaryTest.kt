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
 * Redirects are deliberately not followed. These tests cover the boundary that *is* recorded:
 * where an unfollowed 3xx pointed, and whether that crossing is safe. Everything runs against a
 * real loopback `ServerSocket`; there is no network or external service dependency.
 */
class RedirectBoundaryTest {
    @Test fun `same origin redirect is classified without following it`() = runBlocking {
        withServer("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n") { port, result ->
            val measurement = (result as ProbeResult.Success).measurement
            assertEquals(302, measurement.responseCode)
            assertEquals(RedirectKind.SAME_ORIGIN, measurement.redirectKind)
            assertEquals("http://127.0.0.1:$port", measurement.redirectTarget)
            // Not followed: no second request reached the server, and the body was not chased.
            assertEquals(0L, measurement.bodyBytes)
            assertEquals(listOf(StageName.DNS, StageName.TCP, StageName.HTTP_HEADERS, StageName.HTTP_BODY),
                measurement.stages.map { it.stage })
        }
    }

    @Test fun `cross origin redirect records only the origin`() = runBlocking {
        withServer(
            "HTTP/1.1 301 Moved Permanently\r\nLocation: https://other.example/landing?token=secret#frag\r\nContent-Length: 0\r\n\r\n",
        ) { port, result ->
            val measurement = (result as ProbeResult.Success).measurement
            assertEquals(RedirectKind.UPGRADE, measurement.redirectKind)
            assertEquals("https://other.example", measurement.redirectTarget)
            // Path, query, fragment, and any token must not survive into the measurement.
            val target = measurement.redirectTarget!!
            assertTrue("target was $target", target.removePrefix("https://").none { it == '/' || it == '?' || it == '#' })
            assertTrue("token leaked into $target", !target.contains("secret"))
        }
    }

    @Test fun `https to http is a downgrade`() {
        val origin = TargetParser.parse("https://secure.example/")
        val redirect = RedirectClassifier.classify(origin, "http://secure.example/login")
        assertEquals(RedirectKind.DOWNGRADE, redirect.kind)
        assertEquals("http://secure.example", redirect.target)
    }

    @Test fun `https to https on another host is cross origin not downgrade`() {
        val origin = TargetParser.parse("https://secure.example/")
        val redirect = RedirectClassifier.classify(origin, "https://cdn.example/asset")
        assertEquals(RedirectKind.CROSS_ORIGIN, redirect.kind)
        assertEquals("https://cdn.example", redirect.target)
    }

    @Test fun `userinfo in a location is rejected rather than persisted`() {
        val origin = TargetParser.parse("http://127.0.0.1/")
        listOf(
            "http://user:pass@evil.example/",
            "http://token@evil.example/",
            "//user:pass@evil.example/",
        ).forEach { location ->
            val redirect = RedirectClassifier.classify(origin, location)
            assertEquals(location, RedirectKind.UNRESOLVED, redirect.kind)
            assertNull(location, redirect.target)
        }
    }

    @Test fun `relative location resolves against the measured origin`() {
        val origin = TargetParser.parse("http://example.com/a/b?c=d")
        val redirect = RedirectClassifier.classify(origin, "/moved")
        assertEquals(RedirectKind.SAME_ORIGIN, redirect.kind)
        assertEquals("http://example.com", redirect.target)
    }

    @Test fun `non default ports are retained in the recorded origin`() {
        val origin = TargetParser.parse("http://example.com/")
        val redirect = RedirectClassifier.classify(origin, "https://example.com:8443/x")
        assertEquals(RedirectKind.UPGRADE, redirect.kind)
        assertEquals("https://example.com:8443", redirect.target)
    }

    @Test fun `unparsable and non http locations are unresolved`() {
        val origin = TargetParser.parse("http://example.com/")
        listOf(
            "",
            "   ",
            "javascript:alert(1)",
            "ftp://example.com/file",
            "http://exa mple.com/",
            "http://example.com/\r\nX-Injected: 1",
            "https://",
        ).forEach { location ->
            val redirect = RedirectClassifier.classify(origin, location)
            assertEquals("location <$location>", RedirectKind.UNRESOLVED, redirect.kind)
            assertNull("location <$location>", redirect.target)
        }
    }

    @Test fun `non redirect statuses carry no boundary`() = runBlocking {
        listOf("200 OK", "404 Not Found").forEach { status ->
            withServer("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n") { _, result ->
                val measurement = (result as ProbeResult.Success).measurement
                assertNull(status, measurement.redirectKind)
                assertNull(status, measurement.redirectTarget)
            }
        }
    }

    @Test fun `a redirect status without a location header is not classified`() = runBlocking {
        withServer("HTTP/1.1 307 Temporary Redirect\r\nContent-Length: 0\r\n\r\n") { _, result ->
            val measurement = (result as ProbeResult.Success).measurement
            assertEquals(307, measurement.responseCode)
            assertNull(measurement.redirectTarget)
            assertNull(measurement.redirectKind)
        }
    }

    @Test fun `redirect is recorded even when the body read fails`() = runBlocking {
        // A 3xx whose body promises more bytes than the server sends. The boundary is known at
        // header time, so it must survive the body failure instead of being lost.
        withServer("HTTP/1.1 301 Moved Permanently\r\nLocation: https://elsewhere.example/x\r\nContent-Length: 100\r\n\r\nshort") { _, result ->
            val failure = result as ProbeResult.Failure
            assertEquals(FailureKind.BODY_FAILURE, failure.measurement.failureCode)
            assertEquals(RedirectKind.UPGRADE, failure.measurement.redirectKind)
            assertEquals("https://elsewhere.example", failure.measurement.redirectTarget)
            assertEquals(301, failure.measurement.responseCode)
        }
    }

    @Test fun `a Location header on a non redirect status is ignored`() = runBlocking {
        // Only 3xx statuses describe a redirect. A 200 with a Location header is not a boundary
        // and must not be recorded as one.
        withServer("HTTP/1.1 200 OK\r\nLocation: https://elsewhere.example/x\r\nContent-Length: 0\r\n\r\n") { _, result ->
            val measurement = (result as ProbeResult.Success).measurement
            assertEquals(200, measurement.responseCode)
            assertNull(measurement.redirectKind)
            assertNull(measurement.redirectTarget)
        }
    }

    @Test fun `redirect status codes are exactly the located ones`() {
        listOf(300, 301, 302, 303, 305, 307, 308).forEach { code ->
            assertTrue("status $code", RedirectClassifier.isRedirect(code))
        }
        listOf(200, 204, 304, 400, 500).forEach { code ->
            assertTrue("status $code", !RedirectClassifier.isRedirect(code))
        }
    }

    @Test fun `repeated location headers do not rewrite the boundary`() = runBlocking {
        withServer(
            "HTTP/1.1 302 Found\r\nLocation: https://first.example/\r\nLocation: http://second.example/\r\nContent-Length: 0\r\n\r\n",
        ) { _, result ->
            val measurement = (result as ProbeResult.Success).measurement
            assertEquals(RedirectKind.UPGRADE, measurement.redirectKind)
            assertEquals("https://first.example", measurement.redirectTarget)
        }
    }

    /**
     * Serves exactly one loopback HTTP response, measures it, and hands the measurement to the
     * assertion block together with the ephemeral port the server actually bound.
     */
    private suspend fun withServer(response: String, assertions: (Int, ProbeResult) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readHeaders()
                    socket.getOutputStream().write(response.replace("PORT", port.toString()).toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            val result = probe().measure(TargetParser.parse("http://127.0.0.1:$port/"))
            assertions(port, result)
        } finally {
            server.close()
            thread.join(1_000)
        }
    }

    private fun probe() = NetworkProbe(HostResolver { listOf(InetAddress.getByName("127.0.0.1")) })

    private fun java.io.BufferedReader.readHeaders(): String = buildString {
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            append(line).append("\n")
        }
    }
}
