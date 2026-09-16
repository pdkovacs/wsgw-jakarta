package io.github.pdkovacs.wsgw.unit.support;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.socket.Timeouts;
import io.github.pdkovacs.wsgw.socket.WsConnections;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

import java.time.Duration;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the WsConnections unit tests. Those are split by the backpressure flow they
 * exercise: ConnectTest covers flow=connect / site=registration, PushTest covers flow=push /
 * site=gw_to_client. The contract for both is docs/backpressure.md.
 */
public final class WsConnectionsFixture {

    public static final Duration WAIT_FOR_REGISTRATION = Duration.ofSeconds(2);
    public static final Duration WAIT_FOR_SENDMESSAGE_DESATURATION = Duration.ofSeconds(1);

    private WsConnectionsFixture() {
    }

    public static Session newMockedSession() {
        var session = mock(Session.class);
        var basicRemote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(basicRemote);
        return session;
    }

    public record ConnectionsUnderTest(WsConnections connections, MeterRegistry registry) {

        // --- flow=connect, site=registration (asserted on by ConnectTest) ---

        // Eagerly registered in the WsConnections ctor, so this read succeeds (returning 0) even in
        // arms where no push ever raced -- the count, not a MeterNotFoundException, is the signal.
        public int registrationWaits() {
            return (int) registry.get("wsgw.registration.waits").tag("flow", "connect").tag("site", "registration").counter().count();
        }

        public int registrationTimeouts() {
            return (int) registry.get("wsgw.registration.timeouts").tag("flow", "connect").tag("site", "registration").counter().count();
        }

        public int registrationAwaitingTermination() {
            return (int) registry.get("wsgw.registration.awaiting_termination").tag("flow", "connect").tag("site", "registration").gauge().value();
        }

        // --- flow=push, site=gw_to_client (asserted on by PushTest) ---

        public Timer sendLockWait() {
            return registry.get("wsgw.send_lock.wait").tag("flow", "push").tag("site", "gw_to_client").timer();
        }

        public int sendLockTimeouts() {
            return (int) registry.get("wsgw.send_lock.timeouts").tag("flow", "push").tag("site", "gw_to_client").counter().count();
        }
    }

    public static ConnectionsUnderTest newConnections() {
        return newConnections(WAIT_FOR_REGISTRATION, WAIT_FOR_SENDMESSAGE_DESATURATION,
                createCircuitBreaker(), WsConnectionsFixture::createCircuitBreaker);
    }

    public static ConnectionsUnderTest newConnections(CircuitBreaker connectCircuitBreaker) {
        return newConnections(WAIT_FOR_REGISTRATION, WAIT_FOR_SENDMESSAGE_DESATURATION,
                connectCircuitBreaker, WsConnectionsFixture::createCircuitBreaker);
    }

    public static ConnectionsUnderTest newConnections(
            Duration registrationWaitTimeout,
            Duration sendLockWaitTimeout,
            CircuitBreaker circuitBreaker,
            Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier) {
        var registry = new SimpleMeterRegistry();
        var timeouts = new Timeouts(registrationWaitTimeout, sendLockWaitTimeout);
        return new ConnectionsUnderTest(new WsConnections(timeouts, circuitBreaker, registry, sendLockTimeoutBreakerSupplier), registry);
    }

    public static CircuitBreaker createCircuitBreaker() {
        CircuitBreaker breakerMock = mock(CircuitBreaker.class);
        when(breakerMock.remaining()).thenReturn(null);
        when(breakerMock.jitteredRemaining()).thenReturn(null);
        return breakerMock;
    }
}
