package io.github.pdkovacs.wsgw.clientside;

import io.github.pdkovacs.wsgw.SendBackpressureException;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    public interface Timeouts {
        Duration getPushWaitForRegistration();

        Duration getWaitForSendMessageDesaturation();
    }

    public interface Metrics {
        void incPushWaitsOnRegistrationCount();
    }

    private record Conn(Session session, ReentrantLock sendLock) {}

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final Timeouts timeouts;
    private final Metrics metrics;

    private final ConcurrentMap<String, CompletableFuture<Conn>> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout) {
        this(new Timeouts() {
            @Override
            public Duration getPushWaitForRegistration() {
                return pushToClientWaitTimeout;
            }

            @Override
            public Duration getWaitForSendMessageDesaturation() {
                return pushToClientWaitTimeout;
            }
        }, null);
    }

    public WsConnections(Timeouts timeouts, Metrics metrics) {
        this.timeouts = timeouts;
        this.metrics = metrics;
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
                if (metrics != null) {
                    metrics.incPushWaitsOnRegistrationCount();
                }
                return new CompletableFuture<Conn>();
            }
            return existing;
        });

        Conn conn = null;
        try {
            mLogger.debug("waiting for connection...");
            conn = completable.get(timeouts.getPushWaitForRegistration().toMillis(), MILLISECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            conns.remove(connectionId, completable);
            throw new SendBackpressureException(connectionId);
        } finally {
            if (conn == null) {
                mLogger.warn("No connection with id {}", connectionId);
            }
        }

        if (!conn.sendLock().tryLock(timeouts.getWaitForSendMessageDesaturation().toMillis(), MILLISECONDS)) {
            throw new SendBackpressureException(connectionId);
        }

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
