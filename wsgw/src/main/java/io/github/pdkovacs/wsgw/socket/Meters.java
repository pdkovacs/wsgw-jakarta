package io.github.pdkovacs.wsgw.socket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;

record Meters(
        Counter registrationWaits,
        Counter registrationTimeoutFlagged,
        AtomicInteger registrationTimeoutAbandoned,
        Timer pushSendLockWait,
        Counter pushSendLockTimeouts
) {
    static Meters create(MeterRegistry registry) {
        // PUSH
        // -- waiting for session registration
        Counter registrationWaits = registry.counter("wsgw.registration.waits", "leg", "push");
        Counter registrationTimeoutFlagged = registry.counter("wsgw.registration.timeout.flagged", "leg", "push");
        AtomicInteger registrationTimeoutAbandoned = new AtomicInteger(0);
        Gauge.builder("wsgw.registration.timeout.abandoned", registrationTimeoutAbandoned, AtomicInteger::get)
                .tag("leg", "push")
                .register(registry);
        // -- waiting for send_lock
        Timer pushSendLockWait = registry.timer("wsgw.send_lock.wait", "leg", "push");
        Counter pushSendLockTimeouts = registry.counter("wsgw.send_lock.timeouts", "leg", "push");

        return new Meters(registrationWaits, registrationTimeoutFlagged, registrationTimeoutAbandoned,
                pushSendLockWait, pushSendLockTimeouts);
    }
}
