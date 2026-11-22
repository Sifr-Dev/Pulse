package io.pulse.observability.probe

import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.DnsResolver.DnsException
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface HostResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/** Uses Android's resolver (including its cache), not a hard-coded DNS-over-HTTPS service. */
class SystemResolver(
    private val executor: Executor = Executor { command -> Thread(command, "pulse-dns").start() },
) : HostResolver {
    override suspend fun resolve(host: String): List<InetAddress> {
        // Numeric addresses do not require a DNS transaction.
        if (host.contains(':') || IPV4.matches(host)) return listOf(InetAddress.getByName(host))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return withContext(Dispatchers.IO) {
                // On API 26-28 InetAddress delegates to the platform netd resolver. Cancellation is
                // cooperative because the legacy blocking API exposes no CancellationSignal.
                InetAddress.getAllByName(host).distinctBy { it.hostAddress }
            }
        }
        return resolveModern(host)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveModern(host: String): List<InetAddress> {
        val resolver = DnsResolver.getInstance()
        val errors = mutableListOf<Throwable>()
        val answers = mutableListOf<InetAddress>()
        for (type in listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)) {
            try {
                val addresses = suspendCancellableCoroutine<List<InetAddress>> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    resolver.query(
                        null,
                        host,
                        type,
                        DnsResolver.FLAG_NO_RETRY,
                        executor,
                        signal,
                        ContinuationCallback(continuation),
                    )
                }
                if (addresses.isEmpty()) {
                    errors += java.net.UnknownHostException("No addresses for query type $type")
                } else {
                    answers.addAll(addresses)
                }
            } catch (error: DnsException) {
                errors += error
            } catch (error: java.net.UnknownHostException) {
                errors += error
            }
        }
        if (answers.isNotEmpty()) return answers.distinctBy { it.hostAddress }
        throw errors.lastOrNull() ?: java.net.UnknownHostException(host)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class ContinuationCallback(
        private val continuation: CancellableContinuation<List<InetAddress>>,
    ) : Callback<List<InetAddress>> {
        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
            if (rcode == 0) continuation.resume(answer)
            else continuation.resumeWithException(java.net.UnknownHostException("Resolver rcode $rcode"))
        }

        override fun onError(error: DnsException) {
            continuation.resumeWithException(error)
        }
    }

    private companion object {
        val IPV4 = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
    }
}
