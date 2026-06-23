package io.github.pdkovacs.wsgw.clientside;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {
    private static final Duration SEND_WAIT_TIMEOUT = Duration.ofSeconds(10);

    private record Conn(Session session, ReentrantLock sendLock) {}

    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);

    private final ConcurrentMap<String, Conn> conns = new ConcurrentHashMap<>();

    public void register(String connectionId, Session session) {
        conns.put(connectionId, new Conn(session, new ReentrantLock()));
    }

    public void push(String connectionId, String message) throws IOException, InterruptedException {
        var conn = conns.get(connectionId);
        if (!conn.sendLock().tryLock(SEND_WAIT_TIMEOUT.toMillis(), MILLISECONDS)) {
            throw new IOException("send backpressure timeout for " + connectionId);
        }
        try {
            conn.session().getBasicRemote().sendText(message);
        } finally {
            conn.sendLock().unlock();
        }
    }

    public void close(String connectionId) throws IOException {
        logger.debug("Closing connection with id {}", connectionId);
        var conn = conns.remove(connectionId);
        conn.session().close();
    }
}
