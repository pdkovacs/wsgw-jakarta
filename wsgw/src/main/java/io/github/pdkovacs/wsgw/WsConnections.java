package io.github.pdkovacs.wsgw;

import jakarta.websocket.Session;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class WsConnections implements SessionRegistrar, MessagePusher {
    private ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public void register(String connectionId, Session session) {
        sessions.put(connectionId, session);
    }

    @Override
    public void sendTo(String connectionId, String message) throws IOException {
        var clientSession = sessions.get(connectionId);
        clientSession.getBasicRemote().sendText(message);
    }
}
