package io.github.pdkovacs.wsgw.clientside;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.websocket.Session;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class WsConnections implements SessionRegistrar, MessagePusher, SessionCloser {
    private static final CtxLogger logger = CtxLogger.of(WsConnections.class);
    
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void register(String connectionId, Session session) {
        sessions.put(connectionId, session);
    }

    public void messageTo(String connectionId, String message) throws IOException {
        var clientSession = sessions.get(connectionId);
        clientSession.getBasicRemote().sendText(message);
    }

    public void close(String connectionId) throws IOException {
        logger.debug("Closing connection with id {}", connectionId);
        var session = sessions.remove(connectionId);
        session.close();
    }
}
