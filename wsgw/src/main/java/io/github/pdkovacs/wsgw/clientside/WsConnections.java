package io.github.pdkovacs.wsgw.clientside;

import io.github.pdkovacs.wsgw.SendBackpressureException;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {

    private record Conn(Session session, ReentrantLock sendLock) {}

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final Duration pushToClientWaitTimeout;

    private final ConcurrentMap<String, Conn> conns = new ConcurrentHashMap<>();

    public WsConnections(Duration pushToClientWaitTimeout) {
        this.pushToClientWaitTimeout = pushToClientWaitTimeout;
    }

    public void register(String connectionId, Session session) {
        conns.put(connectionId, new Conn(session, new ReentrantLock()));
    }

    public void push(String connectionId, String message) throws SendBackpressureException, IOException, InterruptedException {
        var mLogger = logger.with("method", "push").with("connectionId", connectionId);
        var conn = conns.get(connectionId);
        if (conn == null) {
            mLogger.warn("No connection with id {}", connectionId);
            throw new IllegalArgumentException("No connection with id " + connectionId);
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
        var conn = conns.remove(connectionId);
        if (conn == null) {
            mLogger.warn("No connection with id {}", connectionId);
            throw new IllegalArgumentException("No connection with id " + connectionId);
        }
        conn.session().close();
    }
}
