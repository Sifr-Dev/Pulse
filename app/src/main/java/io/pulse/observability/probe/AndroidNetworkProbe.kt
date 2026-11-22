package io.pulse.observability.probe

fun networkProbe(config: ProbeConfig = ProbeConfig()): NetworkProbe =
    NetworkProbe(SystemResolver(), config)
