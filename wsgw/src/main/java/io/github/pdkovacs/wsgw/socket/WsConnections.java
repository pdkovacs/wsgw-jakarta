package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
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

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private record Meters(Counter registrationWaits, Counter registrationTimeoutFlagged,
                          AtomicInteger registrationTimeoutAbandoned, Timer pushSendLockWait,
                          Counter pushSendLockTimeouts) {
        static Meters create(MeterRegistry registry) {
            Counter registrationWaits = registry.counter("wsgw.registration.waits", "leg", "push");
            Counter registrationTimeoutFlagged = registry.counter("wsgw.registration.timeout.flagged", "leg", "push");
            AtomicInteger registrationTimeoutAbandoned = new AtomicInteger(0);
            Gauge.builder("wsgw.registration.timeout.abandoned", registrationTimeoutAbandoned, AtomicInteger::get)
                    .tag("leg", "push")
                    .register(registry);
            Timer pushSendLockWait = registry.timer("wsgw.send_lock.wait", "leg", "push");
            Counter pushSendLockTimeouts = registry.counter("wsgw.send_lock.timeouts", "leg", "push");

            return new Meters(registrationWaits, registrationTimeoutFlagged, registrationTimeoutAbandoned,
                    pushSendLockWait, pushSendLockTimeouts);
        }
    }

    private final Timeouts timeouts;
    private final Meters meters;

    private final ConcurrentMap<String, WsConnection> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout, Duration pushWaitForSendMessageDesaturation, MeterRegistry registry) {
        this(new Timeouts(pushToClientWaitTimeout, pushWaitForSendMessageDesaturation), registry);
    }

    public WsConnections(Timeouts timeouts, MeterRegistry registry) {
        this.timeouts = timeouts;
        this.meters = Meters.create(registry);
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

    public void push(String connectionId, String message) throws SendWaitTimedOut, IOException, InterruptedException {
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
                        timeouts.pushWaitForRegistration().toMillis());
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
        } catch (ConnectionGone connectionGone) {
            meters.registrationTimeoutFlagged().increment();
            meters.registrationTimeoutAbandoned().incrementAndGet();
            throw connectionGone;
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
        return new WsConnection(connectionId, new WsConnection.Metrics(meters.pushSendLockWait(), meters.pushSendLockTimeouts()));
    }
}
