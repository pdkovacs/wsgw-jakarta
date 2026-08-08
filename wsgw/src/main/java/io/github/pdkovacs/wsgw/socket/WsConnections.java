package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.SendBackpressureException;
import io.github.pdkovacs.wsgw.clientward.MessagePusher;
import io.github.pdkovacs.wsgw.clientward.SessionCloser;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    public interface Timeouts {
        Duration getPushWaitForRegistration();

        Duration getWaitForSendMessageDesaturation();
    }

    private record Conn(Session session, ReentrantLock sendLock) {
    }

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final Timeouts timeouts;
    // The leg tag is single-valued today; it exists so this meter shares the backpressure
    // family's query shape, not because other legs wait. Registered eagerly (at 0) so the series is
    // observable before the first race and reads never miss.
    private final Counter registrationWaits;

    private final ConcurrentMap<String, CompletableFuture<Conn>> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout, MeterRegistry registry) {
        this(new Timeouts() {
            @Override
            public Duration getPushWaitForRegistration() {
                return pushToClientWaitTimeout;
            }

            @Override
            public Duration getWaitForSendMessageDesaturation() {
                return pushToClientWaitTimeout;
            }
        }, registry);
    }

    public WsConnections(Timeouts timeouts, MeterRegistry registry) {
        this.timeouts = timeouts;
        this.registrationWaits = registry.counter("wsgw.registration.waits", "leg", "push");
    }

    public void register(String connectionId, Session session) {
        var mLogger = logger.with("method", "register").with("connectionId", connectionId);
        var conn = new Conn(session, new ReentrantLock());
        mLogger.debug("Registering connection with id " + connectionId);
        this.conns.compute(connectionId, (_, existing) -> {
            mLogger.debug("computing connection: exiting={}", existing);
            var completable = existing != null ? existing : new CompletableFuture<Conn>();
            completable.complete(conn);
            return completable;
        });
    }

    public void push(String connectionId, String message) throws SendBackpressureException, IOException, InterruptedException {
        var mLogger = logger.with("method", "push").with("connectionId", connectionId);
        var completable = this.conns.compute(connectionId, (_, existing) -> {
            if (existing == null) {
                // This push is going to create the holder, so no registration preceded it: the connection hit
                // the push-before-register race and this push must park until registration lands (or
                // the wait times out). Counted once per raced connection -- later pushes that pile
                // onto the same not-yet-registered holder find it already present and are not
                // recounted, so the metric measures race incidence, not parked-wait volume.
                mLogger.debug("push arrived before register; parking until registration");
                registrationWaits.increment();
                return new CompletableFuture<>();
            }
            return existing;
        });

        Conn conn;
        try {
            mLogger.debug("waiting for connection...");
            conn = completable.get(timeouts.getPushWaitForRegistration().toMillis(), MILLISECONDS);
            if (conn == null) {
                mLogger.warn("No connection with id {}", connectionId);
                throw new NoSuchElementException("No connection with id %s".formatted(connectionId));
            }
            mLogger.debug("got connection");
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            conns.remove(connectionId, completable);
            throw new SendBackpressureException(connectionId);
        }

        mLogger.debug("about to wait {} millis for sendLock", timeouts.getWaitForSendMessageDesaturation().toMillis());
        if (!conn.sendLock().tryLock(timeouts.getWaitForSendMessageDesaturation().toMillis(), MILLISECONDS)) {
            mLogger.debug("failed to acquire sendLock", timeouts.getWaitForSendMessageDesaturation().toMillis());
            throw new SendBackpressureException(connectionId);
        }
        mLogger.debug("acquired sendLock", timeouts.getWaitForSendMessageDesaturation().toMillis());

        try {
            conn.session().getBasicRemote().sendText(message);
        } finally {
            conn.sendLock().unlock();
        }
    }

    public void close(String connectionId) throws IOException {
        var mLogger = logger.with("method", "close").with("connectionId", connectionId);
        var completable = conns.remove(connectionId);
        if (completable == null) {
            mLogger.warn("No connection with id {}", connectionId);
            throw new IllegalArgumentException("No connection with id " + connectionId);
        }
        if (completable.isDone() && !completable.isCompletedExceptionally() && !completable.isCancelled()) {
            completable.getNow(null).session().close();
        } else {
            completable.cancel(false); // unblock a push parked on get(...)
        }
    }
}
