package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.backpressure.ConnectionGone;
import io.github.pdkovacs.wsgw.backpressure.SendWaitTimedOut;
import io.github.pdkovacs.wsgw.clientward.MessagePusher;
import io.github.pdkovacs.wsgw.clientward.SessionCloser;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final Timeouts timeouts;
    // The leg tag is single-valued today; it exists so this meter shares the backpressure
    // family's query shape, not because other legs wait. Registered eagerly (at 0) so the series is
    // observable before the first race and reads never miss.
    private final Counter registrationWaits;
    private final Counter registrationTimeoutTermination;

    private final ConcurrentMap<String, WsConnection> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout, MeterRegistry registry) {
        this(new Timeouts(pushToClientWaitTimeout, pushToClientWaitTimeout), registry);
    }

    public WsConnections(Timeouts timeouts, MeterRegistry registry) {
        this.timeouts = timeouts;
        this.registrationWaits = registry.counter("wsgw.registration.waits", "leg", "push");
        this.registrationTimeoutTermination = registry.counter("wsgw.registration.timeout.termination", "leg", "push");
    }

    public void register(String connectionId, Session session) {
        var mLogger = logger.with("method", "register").with("connectionId", connectionId);
        mLogger.debug("Registering connection with id " + connectionId);
        try {
            var conn = this.conns.compute(connectionId, (_, existing) -> {
                mLogger.debug("computing connection: exiting={}", existing);
                var connection = existing != null ? existing : new WsConnection(connectionId);
                return connection;
            });
            if (!conn.registerSession(session)) {
                conns.remove(connectionId);
            }
        } catch (Exception e) {
            conns.remove(connectionId);
            throw e;
        }
    }

    public void push(String connectionId, String message) throws SendWaitTimedOut, IOException, InterruptedException {
        var mLogger = logger.with("method", "push").with("connectionId", connectionId);
        var waitedForRegistration = new boolean[] { false };
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
                return new WsConnection(connectionId);
            }
            return existing;
        });

        try {
             conn.sendMessage(message, timeouts);
             if (waitedForRegistration[0]) {
                 // message sent -> wait was benign
                 registrationWaits.increment();
             }
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (ConnectionGone connectionGone) {
            registrationTimeoutTermination.increment();
            throw connectionGone;
        }
    }

    public void close(String connectionId) throws IOException {
        var mLogger = logger.with("method", "close").with("connectionId", connectionId);
        var conn = conns.remove(connectionId);
        if (conn == null) {
            mLogger.warn("No connection with id {}", connectionId);
        }
        conn.close();
    }
}
