package io.pulse.observability.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetParserTest {
    @Test fun `normalizes https defaults and preserves query`() {
        assertEquals("https://example.com/path?q=one%20two", TargetParser.parse(" HTTPS://example.com/path?q=one%20two ").normalized)
    }

    @Test fun `accepts explicit nondefault port and IDN`() {
        assertEquals("http://xn--bcher-kva.example:8080/", TargetParser.parse("http://bücher.example:8080").normalized)
    }

    @Test fun `accepts bracketed ipv6 and bracketed host header`() {
        val target = TargetParser.parse("https://[::1]:8443/a")
        assertEquals("::1", target.host)
        assertEquals("[::1]:8443", target.authority)
        assertEquals("https://[::1]:8443/a", target.normalized)
    }

    @Test fun `accepts ipv4 without resolver parsing`() {
        assertEquals("127.0.0.1", TargetParser.parse("http://127.0.0.1").host)
    }

    @Test fun `rejects credentials fragments whitespace and other schemes`() {
        listOf(
            "ftp://example.com", "example.com", "https://user:password@example.com/",
            "https://user@example.com/", "https://example.com/#fragment", "https://exa mple.com/",
            "https://example.com/a b", "", "http:///path", "http://:80/",
        ).forEach { input ->
            assertThrows(input, TargetValidationException::class.java) { TargetParser.parse(input) }
        }
    }

    @Test fun `rejects invalid hosts ports and ipv4`() {
        listOf(
            "http://example.com:0", "http://example.com:65536", "http://example.com:abc/",
            "http://-bad.example/", "http://bad-.example/", "http://bad_name.example/",
            "http://999.1.1.1/", "http://[::1/", "http://::1/", "http://[example.com]/",
        ).forEach { input ->
            assertThrows(input, TargetValidationException::class.java) { TargetParser.parse(input) }
        }
    }

    @Test fun `boundaries prevent oversized urls`() {
        assertThrows(TargetValidationException::class.java) { TargetParser.parse("http://example.com/${"a".repeat(2_100)}") }
    }
}
