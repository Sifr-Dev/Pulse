package io.pulse.observability.probe

import android.os.SystemClock

fun interface MonotonicClock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = MonotonicClock { SystemClock.elapsedRealtimeNanos() }
    }
}
