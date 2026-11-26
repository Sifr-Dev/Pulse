package io.pulse.observability.probe

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Classifies the redirect boundary a measurement stopped at. Redirects are never followed;
 * this only answers "where did the server point, and is that boundary safe?".
 *
 * Only `scheme://authority` of the location is retained. Path, query, fragment, and userinfo
 * are dropped: redirect URLs routinely carry single-use tokens, capability secrets, and
 * session identifiers in the query string, and Pulse never persists request payloads. A
 * location that cannot be reduced to a safe absolute HTTP(S) origin is recorded as
 * [RedirectKind.UNRESOLVED] with no target, never as raw untrusted text.
 */
object RedirectClassifier {
    /** Status codes that carry a redirect location (RFC 9110 §15.4 plus 300/305 legacy). */
    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 305, 307, 308)

    fun isRedirect(code: Int): Boolean = code in REDIRECT_CODES

    /**
     * @param origin the measured request's origin, already validated by [TargetParser].
     * @param location the raw `Location` header value, or null when the header is absent.
     */
    fun classify(origin: Target, location: String?): Redirect {
        if (location.isNullOrBlank()) return Redirect(target = null, kind = RedirectKind.UNRESOLVED)
        val reference = try {
            // Reject control characters and whitespace before parsing: a Location header must
            // not smuggle CR/LF or spaces into a persisted field.
            if (location.any { it.isISOControl() || it.isWhitespace() }) {
                return Redirect(target = null, kind = RedirectKind.UNRESOLVED)
            }
            URI(location)
        } catch (_: URISyntaxException) {
            return Redirect(target = null, kind = RedirectKind.UNRESOLVED)
        }
        val resolved = try {
            originUri(origin).resolve(reference)
        } catch (_: IllegalArgumentException) {
            return Redirect(target = null, kind = RedirectKind.UNRESOLVED)
        }
        val scheme = resolved.scheme?.lowercase(Locale.ROOT) ?: return Redirect(null, RedirectKind.UNRESOLVED)
        if (scheme != "http" && scheme != "https") return Redirect(null, RedirectKind.UNRESOLVED)
        // A relative reference inherits the origin's userinfo only if the origin had none,
        // which TargetParser already guarantees. An absolute reference with userinfo is
        // rejected rather than stripped: it is a credential boundary, not a measurement.
        if (resolved.rawUserInfo != null) return Redirect(null, RedirectKind.UNRESOLVED)
        val host = resolved.host?.lowercase(Locale.ROOT) ?: return Redirect(null, RedirectKind.UNRESOLVED)
        val port = if (resolved.port == -1) Target.defaultPort(scheme) else resolved.port
        if (port !in 1..65_535) return Redirect(null, RedirectKind.UNRESOLVED)
        if (host.isEmpty() || host.any { it.isWhitespace() || it.isISOControl() }) {
            return Redirect(null, RedirectKind.UNRESOLVED)
        }
        val authority = authority(host, port, scheme)
        val kind = when {
            scheme == "http" && origin.scheme == "https" -> RedirectKind.DOWNGRADE
            scheme == "https" && origin.scheme == "http" -> RedirectKind.UPGRADE
            scheme == origin.scheme && host == origin.host && port == origin.port -> RedirectKind.SAME_ORIGIN
            else -> RedirectKind.CROSS_ORIGIN
        }
        return Redirect(target = "$scheme://$authority", kind = kind)
    }

    private fun originUri(origin: Target): URI = URI(origin.normalized)

    private fun authority(host: String, port: Int, scheme: String): String = buildString {
        if (':' in host) append('[').append(host).append(']') else append(host)
        if (port != Target.defaultPort(scheme)) append(':').append(port)
    }
}
