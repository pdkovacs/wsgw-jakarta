package io.github.pdkovacs.wsgw.clientward;

import jakarta.websocket.Session;

public interface SessionRegistrar {
    boolean register(String connectionId, Session session);
}
