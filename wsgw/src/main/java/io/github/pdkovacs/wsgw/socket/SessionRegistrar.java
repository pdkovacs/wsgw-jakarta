package io.github.pdkovacs.wsgw.socket;

import jakarta.websocket.Session;

public interface SessionRegistrar {
    boolean register(String connectionId, Session session);
}
