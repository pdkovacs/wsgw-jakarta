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

    private record Conn(Session session, ReentrantLock sendLock) {}

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final Duration pushToClientWaitTimeout;

    private final ConcurrentMap<String, CompletableFuture<Conn>> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout) {
        this.pushToClientWaitTimeout = pushToClientWaitTimeout;
    }

    public void register(String connectionId, Session session) {
        var conn = new Conn(session, new ReentrantLock());
        this.conns.compute(connectionId, (_, existing) -> {
            var completable = existing != null ? existing : new CompletableFuture<Conn>();
            completable.complete(conn);
            return completable;
        });
    }

    public void push(String connectionId, String message) throws SendBackpressureException, IOException, InterruptedException {
        var mLogger = logger.with("method", "push").with("connectionId", connectionId);
        var completable = this.conns.compute(connectionId, (_, existing) -> {
            if (existing == null) {
                return new CompletableFuture<Conn>();
            }
            return existing;
        });

        Conn conn = null;
        try {
            conn = completable.get(pushToClientWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

        if (!conn.sendLock().tryLock(pushToClientWaitTimeout.toMillis(), MILLISECONDS)) {
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
