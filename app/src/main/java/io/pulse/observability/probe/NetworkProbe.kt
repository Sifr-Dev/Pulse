package io.pulse.observability.probe

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/**
 * One-shot, user-initiated HTTP(S) measurement. Deliberately not OkHttp: stage boundaries are
 * explicit and TLS uses the platform's trust manager, SNI and hostname verifier.
 *
 * This implementation supports HTTP/1.0 and HTTP/1.1 and does not offer ALPN protocols. It never
 * follows redirects. It discards response bytes and keeps only timing, status and bounded counts.
 */
class NetworkProbe(
    private val resolver: HostResolver,
    private val config: ProbeConfig = ProbeConfig(),
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
) {
    suspend fun measure(target: Target): ProbeResult {
        return withContext<ProbeResult>(Dispatchers.IO) {
        val started = clock.nowNanos()
        val wallClock = System.currentTimeMillis()
        val timings = mutableListOf<StageTiming>()
        var address: InetAddress? = null
        var response: HttpResponse? = null
        var tlsProtocol: String? = null
        var tlsCipher: String? = null
        var firstByteAt: Long? = null
        var currentStage = StageName.DNS
        val activeSocket = java.util.concurrent.atomic.AtomicReference<Socket?>(null)
        fun closeActiveSocket() {
            activeSocket.getAndSet(null)?.let { socket ->
                try { socket.close() } catch (_: IOException) { }
            }
        }
        // Each coroutine owns its socket. A stale cancel from another attempt cannot close it.
        val cancellationRegistration = coroutineContext.job.invokeOnCompletion { cause: Throwable? ->
            if (cause is CancellationException) closeActiveSocket()
        }
        try {
            val result = withTimeout<ProbeResult>(config.totalTimeoutMillis) {
                coroutineContext.ensureActive()
                address = stage(timings, StageName.DNS, FailureKind.DNS_FAILURE, FailureKind.DNS_TIMEOUT) {
                    withTimeout(config.dnsTimeoutMillis) { resolver.resolve(target.host) }
                        .firstOrNull() ?: throw UnknownHostException("No addresses returned")
                }
                currentStage = StageName.TCP
                val plainSocket = Socket()
                activeSocket.set(plainSocket)
                try {
                    stage(timings, StageName.TCP, FailureKind.TCP_FAILURE, FailureKind.TCP_TIMEOUT) {
                        plainSocket.connect(InetSocketAddress(address, target.port), remaining(config.connectTimeoutMillis, started))
                    }
                    plainSocket.tcpNoDelay = true
                    currentStage = StageName.TLS
                    val socket = if (target.isTls) {
                        val tls = createTlsSocket(plainSocket, target)
                        activeSocket.set(tls)
                        // The legacy HttpsURLConnection verifier keeps Android's platform hostname
                        // verification policy (CN fallback and wildcard behavior), not a custom trust-all verifier.
                        tls.useClientMode = true
                        stage(timings, StageName.TLS, FailureKind.TLS_FAILURE, FailureKind.TLS_TIMEOUT) {
                            tls.soTimeout = remaining(config.tlsTimeoutMillis, started)
                            tls.startHandshake()
                            val session = tls.session
                            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, session)) {
                                throw SSLPeerUnverifiedException("Certificate does not match ${target.host}")
                            }
                            tlsProtocol = session.protocol
                            tlsCipher = session.cipherSuite
                        }
                        tls
                    } else plainSocket
                    currentStage = StageName.HTTP_HEADERS
                    socket.soTimeout = remaining(config.headersTimeoutMillis, started)
                    val output = BufferedOutputStream(socket.getOutputStream())
                    writeRequest(output, target)
                    val input = BufferedInputStream(socket.getInputStream())
                    response = stage(timings, StageName.HTTP_HEADERS, FailureKind.HTTP_FAILURE, FailureKind.HTTP_TIMEOUT) {
                        readResponseHead(input, config) { firstByteAt = clock.nowNanos() - started }
                    }
                    val readResponse = response!!
                    currentStage = StageName.HTTP_BODY
                    val bytes = stage(timings, StageName.HTTP_BODY, FailureKind.BODY_FAILURE, FailureKind.BODY_TIMEOUT) {
                        socket.soTimeout = remaining(config.bodyTimeoutMillis, started)
                        readBody(input, readResponse, config.maxBodyBytes)
                    }
                    complete(
                        target, wallClock, started, timings, address, readResponse,
                        tlsProtocol, tlsCipher, bytes, firstByteAt,
                    )
                } finally {
                    closeActiveSocket()
                }
            }
            cancellationRegistration.dispose()
            result
        } catch (error: TimeoutCancellationException) {
            cancellationRegistration.dispose()
            failed(target, wallClock, started, timings, address, response, tlsProtocol, tlsCipher, firstByteAt,
                StageFailure(FailureKind.TOTAL_TIMEOUT, currentStage, clock.nowNanos() - started, error))
        } catch (error: CancellationException) {
            cancellationRegistration.dispose()
            throw error
        } catch (error: StageFailure) {
            cancellationRegistration.dispose()
            failed(target, wallClock, started, timings, address, response, tlsProtocol, tlsCipher, firstByteAt, error)
        } catch (error: Exception) {
            cancellationRegistration.dispose()
            failed(target, wallClock, started, timings, address, response, tlsProtocol, tlsCipher, firstByteAt,
                StageFailure(FailureKind.HTTP_FAILURE, StageName.HTTP_HEADERS, clock.nowNanos() - started, error))
        }
        }
    }

    private fun createTlsSocket(plain: Socket, target: Target): SSLSocket {
        // Passing the hostname enables JSSE SNI. A wrapping socket preserves the measured TCP connection.
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        return factory.createSocket(plain, target.host, target.port, true) as SSLSocket
    }

    @Throws(StageFailure::class)
    private suspend fun <T> stage(
        timings: MutableList<StageTiming>,
        name: StageName,
        failure: FailureKind,
        timeout: FailureKind,
        block: suspend () -> T,
    ): T {
        val start = clock.nowNanos()
        try {
            val value = block()
            timings += StageTiming(name, (clock.nowNanos() - start).coerceAtLeast(0))
            return value
        } catch (error: TimeoutCancellationException) {
            throw StageFailure(timeout, name, (clock.nowNanos() - start).coerceAtLeast(0), error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StageFailure) {
            throw error
        } catch (error: Exception) {
            val elapsed = (clock.nowNanos() - start).coerceAtLeast(0)
            val code = when {
                error is SocketTimeoutException || error is kotlinx.coroutines.TimeoutCancellationException -> timeout
                name == StageName.TLS && (error is SSLHandshakeException || error is SSLPeerUnverifiedException || error is CertificateException) ->
                    FailureKind.TLS_CERTIFICATE
                error is HttpProtocolException -> FailureKind.HTTP_PROTOCOL
                error is BodyLimitException -> FailureKind.BODY_TOO_LARGE
                else -> failure
            }
            throw StageFailure(code, name, elapsed, error)
        }
    }

    private fun complete(
        target: Target,
        wallClock: Long,
        started: Long,
        timings: List<StageTiming>,
        address: InetAddress?,
        response: HttpResponse,
        protocol: String?,
        cipher: String?,
        bodyBytes: Long,
        firstByte: Long?,
    ): ProbeResult.Success = ProbeResult.Success(Measurement(
        target = target.normalized,
        startedAtEpochMillis = wallClock,
        outcome = Outcome.SUCCESS,
        totalNanos = (clock.nowNanos() - started).coerceAtLeast(0),
        stages = timings,
        resolvedAddress = address?.hostAddress,
        responseCode = response.code,
        httpVersion = response.version,
        tlsProtocol = protocol,
        tlsCipherSuite = cipher,
        bodyBytes = bodyBytes,
        firstResponseByteNanos = firstByte,
    ))

    private fun failed(
        target: Target,
        wallClock: Long,
        started: Long,
        timings: List<StageTiming>,
        address: InetAddress?,
        response: HttpResponse?,
        protocol: String?,
        cipher: String?,
        firstByte: Long?,
        failure: StageFailure,
    ): ProbeResult.Failure = ProbeResult.Failure(Measurement(
        target = target.normalized,
        startedAtEpochMillis = wallClock,
        outcome = Outcome.FAILURE,
        totalNanos = (clock.nowNanos() - started).coerceAtLeast(0),
        stages = timings,
        resolvedAddress = address?.hostAddress,
        responseCode = response?.code,
        httpVersion = response?.version,
        tlsProtocol = protocol,
        tlsCipherSuite = cipher,
        firstResponseByteNanos = firstByte,
        failureStage = failure.stage,
        failureCode = failure.kind,
        failureMessage = failure.kind.userMessage(),
        failedStageDurationNanos = failure.durationNanos,
    ))

    private fun remaining(stageLimit: Long, started: Long): Int {
        val elapsedMillis = (clock.nowNanos() - started).coerceAtLeast(0) / 1_000_000
        return (stageLimit - elapsedMillis).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun writeRequest(output: OutputStream, target: Target) {
        val request = buildString {
            append("GET ").append(target.pathAndQuery).append(" HTTP/1.1\r\n")
            append("Host: ").append(target.authority).append("\r\n")
            append("User-Agent: Pulse/0.1 Android/").append(Build.VERSION.RELEASE).append("\r\n")
            append("Accept: */*\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun readResponseHead(
        input: InputStream,
        config: ProbeConfig,
        onFirstByte: () -> Unit,
    ): HttpResponse {
        val head = readHead(input, config.maxHeaderBytes, onFirstByte)
        val lines = head.split("\r\n")
        val status = lines.firstOrNull().orEmpty()
        val match = Regex("HTTP/(1\\.[01]) ([0-9]{3})(?: [^\r\n]*)?").matchEntire(status)
            ?: throw HttpProtocolException("Invalid HTTP status line")
        val response = HttpResponse(match.groupValues[1], match.groupValues[2].toInt())
        val contentLengths = mutableSetOf<Long>()
        var chunked = false
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0 || line.first().isWhitespace()) throw HttpProtocolException("Invalid header")
            val name = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            when {
                name.equals("content-length", true) -> {
                    contentLengths += value.toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw HttpProtocolException("Invalid Content-Length")
                }
                name.equals("transfer-encoding", true) -> {
                    val codings = value.split(',').map { it.trim().lowercase() }
                    if (codings != listOf("chunked")) throw HttpProtocolException("Unsupported Transfer-Encoding")
                    chunked = true
                }
            }
        }
        if (chunked && contentLengths.isNotEmpty()) throw HttpProtocolException("Ambiguous message framing")
        if (contentLengths.size > 1) throw HttpProtocolException("Conflicting Content-Length")
        return response.copy(
            contentLength = contentLengths.singleOrNull(),
            chunked = chunked,
            hasBody = response.code !in 100..199 && response.code != 204 && response.code != 304,
        )
    }

    private fun readHead(input: InputStream, limit: Int, onFirstByte: () -> Unit): String {
        val output = java.io.ByteArrayOutputStream()
        var previous = -1
        while (output.size() < limit) {
            val next = input.read()
            if (previous == -1 && next >= 0) onFirstByte()
            if (next < 0) throw EOFException("Connection closed before complete response headers")
            output.write(next)
            val size = output.size()
            if (size >= 4) {
                val bytes = output.toByteArray()
                if (bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()
                ) return String(bytes, 0, size - 2, StandardCharsets.ISO_8859_1)
            }
            previous = next
        }
        throw HttpProtocolException("HTTP response headers exceed limit")
    }

    private fun readBody(input: InputStream, response: HttpResponse, limit: Int): Long {
        val declared = response.contentLength
        if (declared != null && response.hasBody && declared > limit) throw BodyLimitException(declared)
        if (!response.hasBody) return 0
        return when {
            response.chunked -> readChunks(input, limit)
            declared != null -> readExactly(input, declared, limit)
            else -> readUntilEof(input, limit)
        }
    }

    private fun readExactly(input: InputStream, length: Long, limit: Int): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        var total = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Response body shorter than Content-Length")
            total += read
            remaining -= read
            if (total > limit) throw BodyLimitException(null)
        }
        return total
    }

    private fun readUntilEof(input: InputStream, limit: Int): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) throw BodyLimitException(null)
        }
    }

    private fun readChunks(input: InputStream, limit: Int): Long {
        var total = 0L
        while (true) {
            val line = readAsciiLine(input)
            val length = line.substringBefore(';').trim().toLongOrNull(16)
                ?: throw HttpProtocolException("Invalid chunk length")
            if (length < 0) throw HttpProtocolException("Negative chunk length")
            if (length == 0L) {
                // Consume optional bounded trailers, including their terminating empty line.
                while (readAsciiLine(input).isNotEmpty()) { }
                return total
            }
            total += readExactly(input, length, limit - total.coerceAtMost(limit.toLong()).toInt())
            if (input.read() != '\r'.code || input.read() != '\n'.code) {
                throw HttpProtocolException("Missing chunk terminator")
            }
            if (total > limit) throw BodyLimitException(null)
        }
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8_192) {
            val value = input.read()
            if (value < 0) throw EOFException("Connection closed inside chunked body")
            bytes.write(value)
            if (value == '\n'.code) {
                val array = bytes.toByteArray()
                if (array.size < 2 || array[array.size - 2] != '\r'.code.toByte()) {
                    throw HttpProtocolException("Invalid line ending")
                }
                return String(array, 0, array.size - 2, StandardCharsets.US_ASCII)
            }
        }
        throw HttpProtocolException("Chunk metadata exceeds limit")
    }
}

internal data class HttpResponse(
    val version: String,
    val code: Int,
    val contentLength: Long? = null,
    val chunked: Boolean = false,
    val hasBody: Boolean = true,
)
