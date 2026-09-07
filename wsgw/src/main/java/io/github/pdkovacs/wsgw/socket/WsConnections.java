package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.backpressure.SendLockWaitTimedOut;
import io.github.pdkovacs.wsgw.clientward.MessagePusher;
import io.github.pdkovacs.wsgw.clientward.SessionCloser;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.*;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private record Meters(Counter registrationWaits, Counter registrationTimeoutFlagged,
                          AtomicInteger registrationTimeoutAbandoned, Timer sendLockWait,
                          Counter sendLockTimeouts) {
        static Meters create(MeterRegistry registry) {
            // site=registration: the readiness gate is not a hop. A push is merely the
            // caller that happens to be waiting at it, so these are not push-flow meters --
            // they count establishments that did not complete, which is why
            // connectFailurePreemptThreshold consumes them (docs/backpressure.md 2.2).
            Counter registrationWaits =
                    registry.counter("wsgw.registration.waits", "flow", "connect", "site", "registration");
            Counter registrationTimeoutFlagged =
                    registry.counter("wsgw.registration.timeout.flagged", "flow", "connect", "site", "registration");
            AtomicInteger registrationTimeoutAbandoned = new AtomicInteger(0);
            Gauge.builder("wsgw.registration.timeout.abandoned", registrationTimeoutAbandoned, AtomicInteger::get)
                    .tag("flow", "connect")
                    .tag("site", "registration")
                    .register(registry);
            // site=gw_to_client: the PUSH flow's outbound hop, where the congestion actually is.
            // The signals it produces are emitted on the inbound hop, in MessageRequest.
            Timer sendLockWait =
                    registry.timer("wsgw.send_lock.wait", "flow", "push", "site", "gw_to_client");
            Counter sendLockTimeouts =
                    registry.counter("wsgw.send_lock.timeouts", "flow", "push", "site", "gw_to_client");

            return new Meters(registrationWaits, registrationTimeoutFlagged, registrationTimeoutAbandoned,
                    sendLockWait, sendLockTimeouts);
        }
    }

    private final Timeouts timeouts;
    private final CircuitBreaker connectCircuitBreaker;
    private final Meters meters;
    private final Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier;

    private final ConcurrentMap<String, WsConnection> conns = new ConcurrentHashMap<>();

    public WsConnections(
            Duration registrationWaitTimeout,
            Duration sendLockWaitTimeout,
            CircuitBreaker connectCircuitBreaker,
            MeterRegistry registry,
            Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier) {
        this(new Timeouts(registrationWaitTimeout, sendLockWaitTimeout),
                connectCircuitBreaker, registry, sendLockTimeoutBreakerSupplier);
    }

    public WsConnections(Timeouts timeouts, CircuitBreaker connectCircuitBreaker, MeterRegistry registry,
                         Supplier<CircuitBreaker> sendLockTimeoutBreakerSupplier) {
        this.timeouts = timeouts;
        this.connectCircuitBreaker = connectCircuitBreaker;
        this.meters = Meters.create(registry);
        this.sendLockTimeoutBreakerSupplier = sendLockTimeoutBreakerSupplier;
    }

    public boolean register(String connectionId, Session session) {
        var mLogger = logger.with("method", "register").with("connectionId", connectionId);
        mLogger.debug("Registering connection with id " + connectionId);
        try {
            var conn = this.conns.compute(connectionId, (_, existing) -> {
                mLogger.debug("computing connection: exiting={}", existing);
                return existing != null
                        ? existing
                        : createWsConnection(connectionId);
            });
            if (!conn.registerSession(session)) {
                conns.remove(connectionId);
                meters.registrationTimeoutAbandoned().decrementAndGet();
                return false;
            }
            return true;
        } catch (Exception e) {
            conns.remove(connectionId);
            throw e;
        }
    }

    public void push(String connectionId, String message) throws SendLockWaitTimedOut, IOException, InterruptedException {
        var mLogger = logger.with("method", "push").with("connectionId", connectionId);
        var waitedForRegistration = new boolean[]{false};
        var conn = this.conns.compute(connectionId, (_, existing) -> {
            if (existing == null) {
                // This push is going to create the holder, so no registration preceded it: the connection hit
                // the push-before-register race and this push must park until registration lands (or
                // the wait times out). Counted once per raced connection -- later pushes that pile
                // onto the same not-yet-registered holder find it already present and are not
                // recounted, so the metric measures race incidence, not parked-wait volume.
                mLogger.debug(
                        "push arrived before register; parking until registration for {} millis",
                        timeouts.registrationWaitTimeout().toMillis());
                waitedForRegistration[0] = true;
                return createWsConnection(connectionId);
            }
            return existing;
        });

        try {
            conn.sendMessage(message, timeouts);
            if (waitedForRegistration[0]) {
                // message sent -> wait was benign
                meters.registrationWaits().increment();
            }
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void close(String connectionId) throws IOException {
        var mLogger = logger.with("method", "close").with("connectionId", connectionId);
        var conn = conns.remove(connectionId);
        if (conn == null) {
            mLogger.warn("No connection with id {}", connectionId);
            return;
        }
        conn.close();
    }

    private WsConnection createWsConnection(String connectionId) {
        return new WsConnection(
                connectionId,
                new WsConnection.Metrics(meters.sendLockWait(), meters.sendLockTimeouts()),
                sendLockTimeoutBreakerSupplier.get(),
                this::onRegistrationTimeout
        );
    }

    // Reached once per connection, from the flagging itself rather than from a ConnectionGone
    // catch: pushes arriving at an already-flagged connection are refused with ConnectionGone
    // too, so catching those counted one flagged connection N times over -- inflating the gauge
    // and over-feeding the breaker against register()'s single decrement.
    private void onRegistrationTimeout() {
        meters.registrationTimeoutFlagged().increment();
        meters.registrationTimeoutAbandoned().incrementAndGet();
        connectCircuitBreaker.increment();
    }
}
