package io.pulse.observability.probe

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A validated, single HTTP(S) endpoint. No credentials or redirects are accepted. */
data class Target(
    val scheme: String,
    val host: String,
    val port: Int,
    val pathAndQuery: String,
) {
    val isTls: Boolean get() = scheme == "https"
    val normalized: String
        get() = buildString {
            append(scheme).append("://").append(authority).append(pathAndQuery)
        }

    val authority: String
        get() = buildString {
            if (':' in host) append('[').append(host).append(']') else append(host)
            if (port != defaultPort(scheme)) append(':').append(port)
        }

    companion object {
        fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
    }
}

class TargetValidationException(message: String) : IllegalArgumentException(message)

object TargetParser {
    private const val MAX_URL_LENGTH = 2_048
    private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun parse(input: String): Target {
        val value = input.trim()
        if (value.isEmpty()) fail("Enter an HTTP or HTTPS URL.")
        if (value.length > MAX_URL_LENGTH) fail("URLs are limited to $MAX_URL_LENGTH characters.")
        if (value.any { it.isWhitespace() || it.isISOControl() }) {
            fail("The URL cannot contain spaces or control characters.")
        }
        val uri = try {
            URI(value)
        } catch (error: URISyntaxException) {
            fail("Enter a complete, valid URL, for example https://example.com/.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") fail("Only http:// and https:// URLs are supported.")
        if (!uri.isAbsolute || uri.rawAuthority.isNullOrEmpty()) {
            fail("Include a hostname after $scheme://.")
        }
        if (uri.rawUserInfo != null || uri.rawAuthority.contains('@')) {
            fail("URLs containing usernames, passwords, or credentials are not supported.")
        }
        if (uri.fragment != null) fail("Remove the URL fragment (#...); it is not sent to a server.")

        val host = parseHost(uri)
        val portText = when {
            uri.rawAuthority.startsWith('[') -> uri.rawAuthority.substringAfter(']', "").removePrefix(":")
            else -> uri.rawAuthority.substringAfterLast(':', "").takeIf { it.all(Char::isDigit) }
        }
        val port = if (portText.isNullOrEmpty()) Target.defaultPort(scheme) else portText.toIntOrNull()
            ?: fail("The URL port is invalid.")
        if (port !in 1..65_535) fail("The port must be between 1 and 65535.")
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (!path.startsWith('/')) fail("The URL path is invalid.")
        val pathAndQuery = path + uri.rawQuery?.let { "?$it" }.orEmpty()
        return Target(scheme, host, port, pathAndQuery)
    }

    private fun parseHost(uri: URI): String {
        val authority = uri.rawAuthority
        val rawHost = when {
            authority.startsWith('[') -> {
                val close = authority.indexOf(']')
                if (close < 0 || !authority.substring(close + 1).matches(Regex("(?::[0-9]+)?"))) {
                    fail("The bracketed IP address or port is invalid.")
                }
                authority.substring(1, close)
            }
            uri.host != null -> uri.host
            else -> {
                // URI rejects non-ASCII DNS names; validate the authority explicitly before IDN mapping.
                val suffix = Regex(":[0-9]+$").find(authority)
                val candidate = suffix?.let { authority.dropLast(it.value.length) } ?: authority
                if (candidate.isEmpty() || candidate.contains(':')) fail("The hostname or port is invalid.")
                candidate
            }
        }
        if (rawHost.isEmpty()) fail("The hostname cannot be empty.")
        if (rawHost.contains(':')) {
            if (uri.host == null && !authority.startsWith('[')) fail("IPv6 addresses must be enclosed in brackets.")
            if (!IPV6.matches(rawHost)) fail("The bracketed IPv6 address is invalid.")
            return rawHost.lowercase(Locale.ROOT)
        }
        val ascii = try {
            IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            fail("The hostname is not a valid DNS name or IP address.")
        }
        if (ascii.length > 253 || ascii.endsWith('.') || ascii.split('.').any { !label.matches(it) }) {
            fail("The hostname is not a valid DNS name or IP address.")
        }
        if (IPV4_LIKE.matches(ascii)) {
            val parts = ascii.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) {
                fail("The IPv4 address is invalid.")
            }
        }
        return ascii
    }

    private val IPV4_LIKE = Regex("[0-9.]+")
    private val IPV6 = Regex("[0-9a-fA-F:.]+")

    private fun fail(message: String): Nothing = throw TargetValidationException(message)
}
